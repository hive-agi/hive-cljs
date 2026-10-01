(ns hive-cljs.step
  "PROMOTE layer — compile an authored step datum into a port-neutral
   `schema/Op`.

   Extension point: `IStepRule`. `compile-step` folds an ORDERED rule vector and
   the first applicable rule wins, so a new step kind is a new rule appended to
   `default-rules` — never an edit to the folder."
  (:require [hive-cljs.schema :as s]
            [hive-cljs.selector :as sel]
            [hive-dsl.result :as r]
            [malli.core :as m]
            [hive-cljs.dialect.probe :as probe]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defprotocol IStepRule
  "One step kind's compilation rule."
  (rule-id [this]
    "Stable keyword identifying this rule.")
  (applies? [this step]
    "True when this rule compiles `step`.")
  (compile-op [this step]
    "Result of a `schema/Op`, or an :step/malformed error."))

(defprotocol IStepSemantics
  "What a rule's kind MEANS once compiled — optional beside `IStepRule`, so a
   rule written before it existed still compiles."
  (semantics [this]
    "Map of the op flags this rule stamps: `:op/assert?`, `:op/poll?`,
     `:op/read-only?` (see `schema/Op`)."))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- kind [step] (first step))
(defn- args [step] (vec (rest step)))

(def semantic-flags
  "The per-step facts an op carries, each answered by its rule."
  [:op/assert? :op/poll? :op/read-only?])

(defn- semantics-of
  "Op flags for a rule declaring the tags in `sem`, a subset of
   #{:assert :poll :read-only}. Every flag is stated, false included, so an op
   from a built-in rule never falls back to the kind sets."
  [sem]
  (let [sem (set sem)]
    {:op/assert?    (contains? sem :assert)
     :op/poll?      (contains? sem :poll)
     :op/read-only? (contains? sem :read-only)}))

(defn- op
  [step channel & {:keys [expect sem] :as _opts}]
  (cond-> (merge {:op/kind    (kind step)
                  :op/channel channel
                  :op/args    (args step)
                  :op/source  (vec step)}
                 sem)
    expect (assoc :op/expect expect)))

(defn- arity-err
  [step expected]
  (r/err :step/malformed
         {:step step :kind (kind step) :expected-arity expected
          :got-arity (count (args step))}))

(defn- compile-selector-arg
  "Ok of `op` with its leading selector compiled to a string, or the step's
   `:selector/malformed` error. The authored datum stays in :op/source, so a
   report still shows what the scenario wrote."
  [step op dialect]
  (let [res (sel/compile-selector (first (:op/args op)) {:dialect dialect})]
    (if (r/ok? res)
      (r/ok (assoc-in op [:op/args 0] (:ok res)))
      (assoc res :step step :kind (kind step)))))

(defn- fixed-rule
  [step-kind arity channel selector-dialect sem]
  (let [flags (semantics-of sem)]
    (reify
      IStepRule
      (rule-id [_] step-kind)
      (applies? [_ step] (= step-kind (kind step)))
      (compile-op [_ step]
        (let [compiled (op step channel :sem flags)]
          (cond
            (not= arity (count (args step))) (arity-err step arity)
            selector-dialect (compile-selector-arg step compiled selector-dialect)
            :else (r/ok compiled))))
      IStepSemantics
      (semantics [_] flags))))

(defn- browser-rule
  "Rule for a browser-channel step of fixed arity with a leading url or value
   that is NOT a selector. `sem` tags its semantics (see `semantics-of`)."
  [step-kind arity & sem]
  (fixed-rule step-kind arity :browser nil sem))

(defn- selector-rule
  "Rule for a browser-channel step whose first argument is a selector: a
   string, or selector data (`hive-cljs.selector`) compiled here, at the one
   point a step reaches the browser."
  [step-kind arity & sem]
  (fixed-rule step-kind arity :browser :playwright sem))

(defn- runtime-rule
  [step-kind arity & sem]
  (fixed-rule step-kind arity :runtime nil sem))

(defn- css-selector-rule
  "Runtime rule whose first argument is a selector the PAGE evaluates with
   querySelectorAll, so Playwright-only pseudo-classes are refused."
  [step-kind arity & sem]
  (fixed-rule step-kind arity :runtime :css sem))

;; =============================================================================
;; Rules — ordered; first match wins
;; =============================================================================

(def navigation-rules
  [(browser-rule :goto 1)
   (browser-rule :back 0)
   (browser-rule :reload 0)])

(def interaction-rules
  [(selector-rule :click 1)
   (selector-rule :fill 2)
   (selector-rule :select 2)
   (selector-rule :check 1)
   (selector-rule :press 2)
   (selector-rule :hover 1)])

(def synchronisation-rules
  [(selector-rule :wait-for 1 :read-only)
   (browser-rule :wait-ms 1 :read-only)])

(def ^:private no-errors-rule
  "`[:expect-no-errors]` or `[:expect-no-errors {:ignore [\"favicon\"]}]`: the
   page logged no console error and threw no uncaught error since it opened."
  (let [flags (semantics-of [:read-only])]
    (reify
      IStepRule
      (rule-id [_] :expect-no-errors)
      (applies? [_ step] (= :expect-no-errors (kind step)))
      (compile-op [_ step]
        (let [as (args step)]
          (cond
            (> (count as) 1) (arity-err step 1)
            (and (seq as) (not (m/validate s/ErrorsOpts (first as))))
            (r/err :step/malformed
                   {:step step :kind :expect-no-errors
                    :hint "options are a map of :sources #{:console :pageerror} and :ignore [\"substring\" …]"})
            :else (r/ok (op step :browser :sem flags)))))
      IStepSemantics
      (semantics [_] flags))))

(def dom-assertion-rules
  [(selector-rule :expect-text 2 :read-only)
   (selector-rule :expect-value 2 :read-only)
   (selector-rule :expect-visible 1 :read-only)
   (selector-rule :expect-hidden 1 :read-only)
   (selector-rule :expect-count 2 :read-only)
   (selector-rule :expect-attr 3 :read-only)
   (browser-rule :expect-url 1 :read-only)
   no-errors-rule])

(defn- probe-problem
  "Why a JS-vocabulary step's probe FORM cannot render, or nil. A string is
   JavaScript already and is not checked; a bare dotted symbol passes through
   as a global name."
  [x locals]
  (when-not (or (string? x)
                (and (symbol? x) (nil? (namespace x)) (not (contains? locals x))))
    (probe/problem x locals)))

(defn- probe-rule
  "A runtime rule whose argument at `idx` may be a probe form, checked here so
   an unrenderable one fails the PLAN rather than reaching the page."
  [step-kind arity idx locals & sem]
  (let [base (apply runtime-rule step-kind arity sem)]
    (reify
      IStepRule
      (rule-id [_] step-kind)
      (applies? [_ step] (= step-kind (kind step)))
      (compile-op [_ step]
        (let [res (compile-op base step)]
          (if-let [why (and (r/ok? res) (probe-problem (nth (args step) idx) locals))]
            (r/err :step/malformed {:step step :kind step-kind :probe why})
            res)))
      IStepSemantics
      (semantics [_] (semantics base)))))

(def runtime-rules
  "Steps routed to ICljsEval instead of the browser.

   Four vocabularies, one channel. The `-sub`/`-db` kinds are re-frame's and
   only a ClojureScript runtime renders them. The `-js` kinds are every stack's,
   because a page is a page whatever compiled it. The `-state` kinds are every
   stack's too, but read through the injected probe contract instead of a
   per-app expression — which is what lets one scenario vocabulary span stacks.
   `:expect-fits` is the fourth: a NAMED question about layout, so a manifest
   asks it by selector instead of carrying a copy of the measuring JavaScript
   once per viewport.
   A channel that cannot render a kind reports `:incomplete`.

   The `:wait-for-*` kinds are the condition-wait counterpart of the DOM-level
   `:wait-for`: same expression as the matching `:expect-*`, polled until the
   run's timeout instead of asserted once.

   Each rule states its semantics (`:assert` — the returned value is the
   verdict; `:poll` — polled until it holds; `:read-only` — observes only), and
   the compiled op carries them, so the boundary reads the op, not a list."
  [(runtime-rule :eval-cljs 1)
   (runtime-rule :dispatch 1)
   (runtime-rule :expect-sub 2 :assert :read-only)
   (runtime-rule :expect-db 2 :assert :read-only)
   (runtime-rule :wait-for-sub 2 :poll :read-only)
   (runtime-rule :wait-for-db 2 :poll :read-only)
   (probe-rule :eval-js 1 0 {})
   (probe-rule :expect-js 1 0 {} :assert)
   (probe-rule :wait-for-js 1 0 {} :poll)
   (css-selector-rule :expect-fits 1 :assert :read-only)
   (probe-rule :expect-state 2 1 {'v "v"} :assert)
   (probe-rule :wait-for-state 2 1 {'v "v"} :poll)])

;; FALLBACK sets for an op that carries no semantic flag — one built by hand,
;; or by a third-party rule written before ops carried their semantics. An op
;; compiled by a built-in rule states every flag and never reaches these.

(def assertion-kinds
  "Runtime kinds whose returned value IS the assertion — a falsy answer fails
   the step rather than merely being reported."
  #{:expect-sub :expect-db :expect-js :expect-fits :expect-state})

(def poll-kinds
  "Runtime kinds that poll a condition until it holds instead of asserting it
   once."
  #{:wait-for-sub :wait-for-db :wait-for-js :wait-for-state})

(def read-only-kinds
  "Steps that only observe — nothing they do can corrupt app-db."
  #{:expect-text :expect-value :expect-visible :expect-hidden :expect-count
    :expect-attr :expect-url :expect-no-errors :hive-cljs/at-origin :expect-sub :expect-db :expect-fits :wait-for
    :wait-for-sub :wait-for-db :wait-ms :screenshot})

(defn- flag
  "The op's own answer for `k`, else membership of its kind in `fallback`."
  [op k fallback]
  (let [v (get op k)]
    (if (some? v)
      (boolean v)
      (contains? fallback (:op/kind op)))))

(defn assertion-op?
  "True when the runtime value `op` returns IS its verdict."
  [op]
  (flag op :op/assert? assertion-kinds))

(defn poll-op?
  "True when `op` polls a condition until it holds."
  [op]
  (flag op :op/poll? poll-kinds))

(defn read-only-op?
  "True when `op` only observes, so app-db cannot change under it."
  [op]
  (flag op :op/read-only? read-only-kinds))

(def artifact-rules
  [(browser-rule :screenshot 1 :read-only)])

(def default-rules
  (vec (concat navigation-rules
               interaction-rules
               synchronisation-rules
               dom-assertion-rules
               runtime-rules
               artifact-rules)))

(defn known-kinds
  "Step kinds the given rule vector can compile."
  [rules]
  (mapv rule-id rules))

;; =============================================================================
;; Fold
;; =============================================================================

(defn compile-step
  "Compile one step against an ordered rule vector.
   Returns a Result of `schema/Op`."
  ([step] (compile-step default-rules step))
  ([rules step]
   (cond
     (not (vector? step))
     (r/err :step/not-a-vector {:step step})

     (not (keyword? (first step)))
     (r/err :step/no-kind {:step step})

     :else
     (if-let [rule (first (filter #(applies? % step) rules))]
       (compile-op rule step)
       (r/err :step/unknown-kind {:kind (first step)
                                  :known (known-kinds rules)})))))

(defn compile-steps
  "Compile a step vector. Returns a Result of [Op ...], short-circuiting on the
   first malformed step with its :index attached."
  ([steps] (compile-steps default-rules steps))
  ([rules steps]
   (reduce (fn [acc [idx step]]
             (let [res (compile-step rules step)]
               (if (r/ok? res)
                 (r/ok (conj (:ok acc) (:ok res)))
                 (reduced (assoc res :index idx)))))
           (r/ok [])
           (map-indexed vector steps))))

(defn channel-of
  "Channel a step compiles to, or nil when it does not compile."
  ([step] (channel-of default-rules step))
  ([rules step]
   (let [res (compile-step rules step)]
     (when (r/ok? res) (:op/channel (:ok res))))))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> known-kinds [:=> [:cat [:vector :any]] [:vector :keyword]])
(m/=> compile-step [:function
                    [:=> [:cat s/Step] :map]
                    [:=> [:cat [:vector :any] s/Step] :map]])
(m/=> compile-steps [:function
                     [:=> [:cat [:vector s/Step]] :map]
                     [:=> [:cat [:vector :any] [:vector s/Step]] :map]])
