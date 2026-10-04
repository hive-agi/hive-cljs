(ns hive-cljs.walk
  "PROPERTY layer — generative walks: test.check-driven step sequences that
   shrink to the shortest walk still breaking the run.

   A walk spec declares a step ALPHABET instead of a step vector:

     {:init       {:page nil}                        ; abstract model
      :min-length 1                                  ; default 1
      :max-length 20                                 ; default 20
      :alphabet
      [{:id :visit  :gen (gen/elements [[:goto \"/a\"] [:goto \"/b\"]])
        :next (fn [m [_ url]] (assoc m :page url))}
       {:id :delete :step [:click \"#delete\"]
        :pre  (fn [m] (= \"/a\" (:page m)))}]}

   Each template carries:
     :id     keyword, unique within the alphabet
     :step   a fixed step vector, or
     :gen    a test.check generator of step vectors, or (fn [model] generator)
     :pre    (fn [model]) → truthy when the template may be chosen (default: always)
     :valid? (fn [model step]) → truthy when this exact step is still legal
             at `model` (default: always). Checked again while shrinking,
             when earlier steps have been removed under it.
     :next   (fn [model step]) → model after the step (default: unchanged)

   `walk-gen` is a test.check generator of step vectors. Every walk it
   produces, and every walk it shrinks to, satisfies the preconditions when
   replayed from :init — a shrunk walk is never one the app could not have
   been put through.

   `check` runs walks through an injected runner — any fn of a step vector to
   a RunReport (or a Result of one). `plan-runner` is that fn for the real
   scenario boundary; a headless runner (a pure reduce over event handlers) is
   equally valid. Nothing here touches a port directly."
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.random :as random]
            [clojure.test.check.results :as results]
            [clojure.test.check.rose-tree :as rose]
            [hive-cljs.schema :as s]
            [hive-cljs.verdict :as verdict]
            [hive-dsl.result :as r]
            [malli.core :as m]
            #?(:clj [hive-cljs.boundary :as boundary])
            #?(:clj [hive-cljs.plan :as plan])))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Spec
;; =============================================================================

(def StepTemplate
  [:and
   [:map
    [:id :keyword]
    [:step {:optional true} s/Step]
    [:gen {:optional true} :any]
    [:pre {:optional true} fn?]
    [:valid? {:optional true} fn?]
    [:next {:optional true} fn?]]
   [:fn {:error/message "a template needs exactly one of :step or :gen"}
    #(= 1 (count (select-keys % [:step :gen])))]])

(def WalkSpec
  [:and
   [:map
    [:init {:optional true} :any]
    [:alphabet [:vector {:min 1} StepTemplate]]
    [:min-length {:optional true} [:int {:min 0}]]
    [:max-length {:optional true} [:int {:min 1}]]]
   [:fn {:error/message "template ids must be unique"}
    #(apply distinct? (map :id (:alphabet %)))]
   [:fn {:error/message ":min-length must not exceed :max-length"}
    #(<= (:min-length % 1) (:max-length % 20))]])

(defn validate-spec
  "Result of the spec with defaults filled, or a :walk/malformed error."
  [spec]
  (if (m/validate WalkSpec spec)
    (r/ok (merge {:init nil :min-length 1 :max-length 20} spec))
    (r/err :walk/malformed {:explain (pr-str (:errors (m/explain WalkSpec spec)))})))

;; =============================================================================
;; Model replay
;; =============================================================================

(defn- by-id [spec] (into {} (map (juxt :id identity)) (:alphabet spec)))

(defn- enabled? [tpl model] ((or (:pre tpl) (constantly true)) model))

(defn- legal? [tpl model step]
  (and (enabled? tpl model)
       ((or (:valid? tpl) (constantly true)) model step)))

(defn- advance [tpl model step] ((or (:next tpl) (fn [m _] m)) model step))

(defn- template-gen [tpl model]
  (cond
    (contains? tpl :step) (gen/return (:step tpl))
    (gen/generator? (:gen tpl)) (:gen tpl)
    :else ((:gen tpl) model)))

(defn valid-walk?
  "True when `entries` ([{:walk/template id :walk/step step} ...]) replays from
   the spec's :init with every template enabled and every step legal, and its
   length lies within the spec's bounds."
  [spec entries]
  (let [tpls (by-id spec)]
    (and (<= (:min-length spec 1) (count entries) (:max-length spec 20))
         (boolean
          (reduce (fn [model {:walk/keys [template step]}]
                    (let [tpl (tpls template)]
                      (if (and tpl (legal? tpl model step))
                        (advance tpl model step)
                        (reduced false))))
                  (:init spec)
                  entries)))))

(defn models
  "The model before each entry and after the last: (count entries)+1 models,
   for reporting what state a shrunk walk drove the model through."
  [spec entries]
  (let [tpls (by-id spec)]
    (reductions (fn [model {:walk/keys [template step]}]
                  (advance (tpls template) model step))
                (:init spec)
                entries)))

;; =============================================================================
;; Generator
;; =============================================================================

(defn- pick-rose
  "Rose tree of one step entry chosen among templates enabled at `model`, or
   nil when none is."
  [alphabet model rnd size]
  (let [enabled (filterv #(enabled? % model) alphabet)]
    (when (seq enabled)
      (let [[r-pick r-arg] (random/split rnd)
            idx (rose/root (gen/call-gen (gen/choose 0 (dec (count enabled))) r-pick size))
            tpl (nth enabled idx)
            arg (gen/call-gen (template-gen tpl model) r-arg size)]
        (rose/fmap (fn [step] {:walk/template (:id tpl) :walk/step step}) arg)))))

(defn- entries-rose
  [spec rnd size]
  (let [{:keys [alphabet init min-length max-length]} spec
        [r-len r-walk] (random/split rnd)
        hi  (max min-length (min max-length size))
        len (rose/root (gen/call-gen (gen/choose min-length hi) r-len size))]
    (loop [i 0 model init rnd r-walk acc []]
      (if (= i len)
        acc
        (let [[r-step r-rest] (random/split rnd)
              entry (pick-rose alphabet model r-step size)]
          (if (nil? entry)
            acc
            (let [{:walk/keys [template step]} (rose/root entry)
                  tpl (first (filter #(= template (:id %)) alphabet))]
              ;; a drawn step its template's :valid? refuses is dropped, so
              ;; the generated walk is legal from the root down
              (if (legal? tpl model step)
                (recur (inc i) (advance tpl model step) r-rest (conj acc entry))
                (recur (inc i) model r-rest acc)))))))))

(defn- chunk-sizes
  "The chunk lengths a walk of n steps is shrunk by: the halvings n/2, n/4, …
   first (fast progress on long walks), then every other length down to 1, so
   a shrunk walk is 1-minimal for contiguous removal."
  [n]
  (distinct (concat (take-while pos? (iterate #(quot % 2) (quot n 2)))
                    (range (dec n) 0 -1))))

(defn- shrink-walk
  "Rose tree over a vector of entry roses.

   Children, most aggressive first: every contiguous chunk removed, for each
   chunk size from half the walk down to one step (delta debugging — a
   dependent pair such as add-then-delete only disappears TOGETHER, which
   one-at-a-time removal never reaches), then every single step replaced by
   one of its own shrinks."
  [roses]
  (let [n (count roses)]
    (rose/make-rose
     (mapv rose/root roses)
     (concat
      (for [k (chunk-sizes (max n 1))
            i (range 0 (inc (- n k)))
            :when (pos? n)]
        (shrink-walk (into (subvec roses 0 i) (subvec roses (+ i k)))))
      (when (= 1 n) [(shrink-walk [])])
      (for [i (range n)
            child (rose/children (nth roses i))]
        (shrink-walk (assoc roses i child)))))))

(defn entry-gen
  "Generator of walk ENTRIES ([{:walk/template id :walk/step step} ...]).

   Shrinks by dropping contiguous chunks of steps (halves first, down to one
   step) and by shrinking each step's own generator, keeping only candidates
   that still replay legally from :init. Throws on a malformed spec."
  [spec]
  (let [res (validate-spec spec)]
    (when (r/err? res)
      (throw (ex-info "hive-cljs.walk: malformed walk spec" res)))
    (let [spec (:ok res)]
      (gen/->Generator
       (fn [rnd size]
         (->> (entries-rose spec rnd size)
              (rose/shrink-vector vector)
              (rose/filter #(valid-walk? spec %))))))))

(defn walk-gen
  "Generator of step vectors drawn from the spec's alphabet. See `entry-gen`."
  [spec]
  (gen/fmap #(mapv :walk/step %) (entry-gen spec)))

(defn sample-walk
  "One walk from the spec at `size`; with `seed` the same walk every time."
  ([spec size] (gen/generate (walk-gen spec) size))
  ([spec size seed] (gen/generate (walk-gen spec) size seed)))

;; =============================================================================
;; Property runner
;; =============================================================================

(defn- outcome
  "Normalise a runner's answer — a RunReport, a Result of one, or a boolean —
   into [pass? data]."
  [ans]
  (cond
    (boolean? ans)                    [ans {}]
    (and (map? ans) (r/err? ans))     [false {:walk/error ans}]
    (and (map? ans) (r/ok? ans))      (let [rep (:ok ans)] [(verdict/run-ok? rep) {:walk/report rep}])
    (and (map? ans) (:run/state ans)) [(verdict/run-ok? ans) {:walk/report ans}]
    :else                             [false {:walk/error {:error :walk/bad-runner-answer
                                                           :got (pr-str ans)}}]))

(defn walk-result
  "A test.check Result of running `steps` through `run-fn`. Its result-data
   carries the RunReport (or the run error), so the shrunk failure explains
   itself."
  [run-fn steps]
  (let [[pass? data] (outcome (run-fn steps))]
    (reify results/Result
      (pass? [_] pass?)
      (result-data [_] (assoc data :walk/steps steps)))))

(defn property
  "test.check property: every walk the spec generates runs green under `run-fn`.
   Its argument is the walk's ENTRIES, so a shrunk failure keeps the template
   each step came from."
  [spec run-fn]
  (prop/for-all* [(entry-gen spec)] #(walk-result run-fn (mapv :walk/step %))))

(defn- fails? [run-fn entries]
  (not (first (outcome (run-fn (mapv :walk/step entries))))))

(defn minimize
  "Deterministic final pass over a failing walk's entries: remove contiguous
   chunks (longest first) while the walk stays legal and still fails, until no
   single chunk can go. test.check's shrink search is greedy and may stop at a
   local minimum; this makes the reported walk 1-minimal. Returns
   [entries runs]."
  [spec run-fn entries]
  (let [spec (:ok (validate-spec spec))]
    (loop [es (vec entries) runs 0]
      (let [n     (count es)
            cands (for [k (range (dec n) 0 -1)
                        i (range 0 (inc (- n k)))
                        :let [c (into (subvec es 0 i) (subvec es (+ i k)))]
                        :when (valid-walk? spec c)]
                    c)
            [hit tried] (reduce (fn [[_ t] c]
                                  (if (fails? run-fn c) (reduced [c (inc t)]) [nil (inc t)]))
                                [nil 0] cands)]
        (if hit
          (recur hit (+ runs tried))
          [es (+ runs tried)])))))

(defn check
  "Run up to `:num-tests` (default 30) walks through `run-fn`, shrinking the
   first failure. Returns

     {:walk/pass? bool :walk/seed long :walk/num-tests n}

   and on failure additionally

     :walk/failing   the walk as first found
     :walk/smallest  the shrunk walk — the minimal repro
     :walk/shrinks   runs spent shrinking
     :walk/report    the smallest walk's RunReport (or :walk/error)

   Pass `:seed` from a failing check to replay it exactly. `:max-size` bounds
   the size parameter, which in turn bounds walk length below :max-length.
   `:minimize? false` skips the final 1-minimal pass (it re-runs the walk up to
   O(n^2) times per removed chunk, which matters for a browser runner)."
  ([spec run-fn] (check spec run-fn {}))
  ([spec run-fn {:keys [num-tests seed max-size minimize?]
                 :or {num-tests 30 max-size 50 minimize? true}}]
   (let [res (apply tc/quick-check num-tests (property spec run-fn)
                    :max-size max-size (when seed [:seed seed]))]
     (if (:pass? res)
       {:walk/pass?     true
        :walk/seed      (:seed res)
        :walk/num-tests (:num-tests res)}
       (let [shrunk      (first (get-in res [:shrunk :smallest]))
             [small runs] (if minimize? (minimize spec run-fn shrunk) [shrunk 0])
             steps       (mapv :walk/step small)
             data        (if (= small shrunk)
                           (get-in res [:shrunk :result-data])
                           (second (outcome (run-fn steps))))]
         (merge (select-keys data [:walk/report :walk/error])
                {:walk/pass?     false
                 :walk/seed      (:seed res)
                 :walk/num-tests (:num-tests res)
                 :walk/failing   (mapv :walk/step (first (:fail res)))
                 :walk/smallest  steps
                 :walk/shrinks   (+ runs (get-in res [:shrunk :total-nodes-visited] 0))}))))))

(defn explain
  "One-line human summary of a `check` result."
  [{:walk/keys [pass? seed num-tests smallest failing report error]}]
  (if pass?
    (str "walk: " num-tests " walks green (seed " seed ")")
    (str "walk: failed (seed " seed ") — shrunk " (count failing) " steps to "
         (count smallest) ": " (pr-str smallest)
         (cond report (str " — " (verdict/summarize report))
               error  (str " — " (pr-str error))))))

#?(:clj
   (defn plan-runner
     "A runner executing each walk through the real scenario boundary:
      `scenario` (without :steps) is planned against `manifest` with the walk as
      its steps, then run with `deps` (the injected ports). Returns a Result of
      a RunReport, as `check` expects."
     ([deps manifest] (plan-runner deps manifest {:id :walk}))
     ([deps manifest scenario]
      (fn [steps]
        (r/bind (plan/build-plan manifest (assoc scenario :steps (vec steps)))
                #(boundary/run-plan! deps %))))))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> validate-spec [:=> [:cat :any] :map])
(m/=> valid-walk? [:=> [:cat :map [:sequential :map]] :boolean])
