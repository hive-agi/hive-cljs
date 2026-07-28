(ns hive-cljs.step
  "PROMOTE layer — compile an authored step datum into a port-neutral
   `schema/Op`.

   Extension point: `IStepRule`. `compile-step` folds an ORDERED rule vector and
   the first applicable rule wins, so a new step kind is a new rule appended to
   `default-rules` — never an edit to the folder."
  (:require [hive-cljs.schema :as s]
            [hive-dsl.result :as r]
            [malli.core :as m]))

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

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- kind [step] (first step))
(defn- args [step] (vec (rest step)))

(defn- op
  [step channel & {:keys [expect] :as _opts}]
  (cond-> {:op/kind    (kind step)
           :op/channel channel
           :op/args    (args step)
           :op/source  (vec step)}
    expect (assoc :op/expect expect)))

(defn- arity-err
  [step expected]
  (r/err :step/malformed
         {:step step :kind (kind step) :expected-arity expected
          :got-arity (count (args step))}))

(defn- browser-rule
  "Rule for a browser-channel step of fixed arity with a leading selector/url."
  [step-kind arity]
  (reify IStepRule
    (rule-id [_] step-kind)
    (applies? [_ step] (= step-kind (kind step)))
    (compile-op [_ step]
      (if (= arity (count (args step)))
        (r/ok (op step :browser))
        (arity-err step arity)))))

(defn- runtime-rule
  [step-kind arity]
  (reify IStepRule
    (rule-id [_] step-kind)
    (applies? [_ step] (= step-kind (kind step)))
    (compile-op [_ step]
      (if (= arity (count (args step)))
        (r/ok (op step :runtime))
        (arity-err step arity)))))

;; =============================================================================
;; Rules — ordered; first match wins
;; =============================================================================

(def navigation-rules
  [(browser-rule :goto 1)
   (browser-rule :back 0)
   (browser-rule :reload 0)])

(def interaction-rules
  [(browser-rule :click 1)
   (browser-rule :fill 2)
   (browser-rule :select 2)
   (browser-rule :check 1)
   (browser-rule :press 2)
   (browser-rule :hover 1)])

(def synchronisation-rules
  [(browser-rule :wait-for 1)
   (browser-rule :wait-ms 1)])

(def dom-assertion-rules
  [(browser-rule :expect-text 2)
   (browser-rule :expect-value 2)
   (browser-rule :expect-visible 1)
   (browser-rule :expect-hidden 1)
   (browser-rule :expect-count 2)
   (browser-rule :expect-url 1)])

(def runtime-rules
  "Steps routed to ICljsEval instead of the browser."
  [(runtime-rule :eval-cljs 1)
   (runtime-rule :dispatch 1)
   (runtime-rule :expect-sub 2)
   (runtime-rule :expect-db 2)])

(def artifact-rules
  [(browser-rule :screenshot 1)])

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
