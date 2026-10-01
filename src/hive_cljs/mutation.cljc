(ns hive-cljs.mutation
  "PROMOTE + PIPELINE layer — behavioural mutation testing for a running app.

   `hive-schemas.test` mutates VALUES against schemas on the JVM; this mutates
   BEHAVIOUR against scenarios in the browser. A fault is data: a form the
   runtime channel evaluates, spliced into an ordinary `schema/RunPlan` as one
   more op, so nothing about execution changes.

   The verdict on a fault is inverted from an ordinary run — a suite that stays
   GREEN under a fault has a hole, and the fault survived."
  (:require [clojure.string :as str]
            [hive-cljs.dialect.source :as source]
            [hive-cljs.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Promoters — one decision each
;; =============================================================================

(defn- fault-id
  [{:keys [id target]}]
  (cond
    (keyword? id) id
    (some? id)    (keyword (str id))
    target        (keyword (str/replace (str target) "/" "."))))

(defn- blank-source?
  [x]
  (or (nil? x) (and (string? x) (str/blank? x))))

(defn normalize-fault
  "Authored fault → `schema/Fault`, or nil when it names nothing to break.

   Two spellings: `{:target sym :with (constantly nil)}` neutralizes a var,
   `{:form (…)}` evaluates arbitrary source — which is what re-registering a
   re-frame handler needs, since those live in a registry rather than a var.

   `:with` and `:form` are FORMS; a string is still accepted as the escape
   hatch for reader macros EDN cannot carry (`#(…)`, `#\"…\"`). Nothing is
   printed here — the fault stays a form until the runtime channel evaluates it."
  [raw]
  (let [{:keys [target with form doc]} raw
        id   (fault-id raw)
        src  (cond
               (not (blank-source? form)) form
               ;; `:with nil` is a real replacement — break the var to nil.
               (and target (contains? raw :with)
                    (not (and (string? with) (str/blank? with))))
               (list 'set! target (source/arg with)))]
    (when (and id (some? src))
      (cond-> {:fault/id id :fault/form src}
        target (assoc :fault/target target)
        doc    (assoc :fault/doc doc)))))

(defn normalize-faults
  "Authored fault vector → `schema/Fault` vector, dropping the unusable ones."
  [raws]
  (vec (keep normalize-fault raws)))

(defn registry-fault
  "One registered re-frame handler + the form that neutralizes it → a Fault.

   The id is flattened (`:app/items` → `:sub-app.items`) so it stays a simple
   keyword: a fault id names a fault, not a namespace in the app."
  [kind id form]
  {:fault/id   (keyword (str (name kind) "-"
                             (str/replace (str/replace (str id) #"^:" "") "/" ".")))
   :fault/form form
   :fault/doc  (str "neutralized re-frame " (name kind) " " id)})

(defn fault-op
  "The op that applies a fault: its form, evaluated on the runtime channel.
   Built here rather than compiled, so it states its own semantics: it
   MUTATES, and its value is reported, not asserted."
  [fault]
  (let [src (:fault/form fault)]
    {:op/kind       :eval-cljs
     :op/channel    :runtime
     :op/args       [src]
     :op/assert?    false
     :op/poll?      false
     :op/read-only? false
     :op/source     [:eval-cljs src]}))

(def boot-barrier-kinds
  "Ops a scenario opens with to let the page come up. A fault is spliced AFTER
   these, never between them and the navigation."
  #{:wait-for :wait-ms :wait-for-sub :wait-for-db :expect-url})

(defn injection-point
  "Index at which a fault op belongs in `ops`.

   After the first `:goto`, because navigation replaces the runtime wholesale
   and would wipe a fault applied before it — and then after the waits that
   follow it, because a runtime the browser has not finished registering
   answers no eval at all. Splicing between the two is how a fault ERRORS
   instead of taking effect, which scores as a kill and makes every fault look
   caught. A plan that never navigates takes the fault at the head."
  [ops]
  (if-let [i (first (keep-indexed #(when (= :goto (:op/kind %2)) %1) ops))]
    (loop [at (inc i)]
      (if (and (< at (count ops))
               (contains? boot-barrier-kinds (:op/kind (nth ops at))))
        (recur (inc at))
        at))
    0))

(defn app-origin
  "The app's origin (`scheme://host[:port]`) from a plan's base-url, or nil
   when the base-url names none — a relative or malformed base-url leaves the
   app page undeterminable."
  [plan]
  (when-let [[_ origin] (re-find #"^(https?://[^/?#\s]+)" (str (:plan/base-url plan)))]
    origin))

(defn guard-op
  "The browser op that refuses to go on unless the page is on `origin`.

   A fault applied on whatever page happens to be current — an IdP login page
   the app redirected to, say — breaks nothing in the app, yet the eval errors
   on a page that does not carry the app and the run goes red. The guard turns
   that into a fault that was never applied instead of a fault that was killed.

   It only observes, so it states `:op/read-only?` itself."
  [origin]
  {:op/kind       :hive-cljs/at-origin
   :op/channel    :browser
   :op/args       [origin]
   :op/assert?    false
   :op/poll?      false
   :op/read-only? true
   :op/source     [:hive-cljs/at-origin origin]})

(defn- app-arrival?
  "True when `op` asserts the page arrived on the app: an `:expect-url` naming
   the app origin, or a path on it."
  [origin op]
  (and (= :expect-url (:op/kind op))
       (let [u (str (first (:op/args op)))]
         (or (str/starts-with? u origin) (str/starts-with? u "/")))))

(defn- after-barriers
  [ops at]
  (loop [at at]
    (if (and (< at (count ops))
             (contains? boot-barrier-kinds (:op/kind (nth ops at))))
      (recur (inc at))
      at)))

(defn app-injection-point
  "Index at which a fault belongs once the page is known to be the APP page.

   A scenario that signs in through an identity provider opens on the IdP, not
   on the app; its first `:goto` lands on a login form. When the scenario
   asserts arrival on the app (`[:expect-url <origin or /path>]`), the fault
   goes after the last such assertion and its waits. Otherwise it falls back to
   [[injection-point]], guarded by [[guard-op]]."
  [ops origin]
  (if-let [i (when origin
               (last (keep-indexed #(when (app-arrival? origin %2) %1) ops)))]
    (after-barriers ops (inc i))
    (injection-point ops)))

(defn inject
  "Splice a fault's op into a plan on the app page.

   A plan that navigates gets a [[guard-op]] on the app origin right before the
   fault, so a fault that would land on a foreign page is never applied. A plan
   that never navigates takes the fault at the head, unguarded."
  [plan fault]
  (let [ops    (vec (:plan/ops plan))
        origin (app-origin plan)
        nav?   (some #(= :goto (:op/kind %)) ops)
        at     (app-injection-point ops origin)
        added  (if (and nav? origin)
                 [(guard-op origin) (fault-op fault)]
                 [(fault-op fault)])]
    (assoc plan :plan/ops
           (into (subvec ops 0 at) cat [added (subvec ops at)]))))

(defn injectable?
  "True when a fault can be placed on the app page of `plan`: either the plan
   never navigates (the runtime IS the app), or its app origin is known."
  [plan]
  (or (not-any? #(= :goto (:op/kind %)) (:plan/ops plan))
      (some? (app-origin plan))))

(defn fault-index
  "Index of the fault op in an injected plan, or nil."
  [plan fault]
  (let [fop (fault-op fault)]
    (first (keep-indexed #(when (= fop %2) %1) (:plan/ops plan)))))

(defn applied?
  "True when the step at `idx` of `report` ran green — the fault took effect.
   A nil index means the fault was never placed at all."
  [report idx]
  (boolean
   (and idx
        (some #(and (= idx (:step/index %)) (= :pass (:step/state %)))
              (:run/steps report)))))

;; =============================================================================
;; Verdicts
;; =============================================================================

(defn- applied-report?
  "A report counts toward a verdict only when its fault was applied. A report
   carrying no `:fault/applied?` mark is taken as applied."
  [r]
  (not (false? (:fault/applied? r))))

(defn killed-by
  "Scenarios that went red under an APPLIED fault — the ones that killed it."
  [reports]
  (vec (keep (fn [r] (when (and (applied-report? r) (not= :pass (:run/state r)))
                       (:run/scenario r)))
             reports)))

(defn verdict
  "One fault + the reports it produced → `schema/FaultVerdict`.

   A report marked `:fault/applied? false` is one where the fault never took
   effect: it neither kills nor spares the fault. When no report applied it,
   the verdict is `:unapplied` — never `:killed`, because a run that went red
   before the fault existed noticed nothing about it."
  [fault reports]
  (let [by      (killed-by reports)
        applied (filter applied-report? reports)]
    (cond
      (seq by)
      {:fault/id (:fault/id fault) :fault/killed? true
       :fault/status :killed :fault/by by}

      (and (seq reports) (empty? applied))
      {:fault/id (:fault/id fault) :fault/killed? false
       :fault/status :unapplied
       :fault/detail (str "the fault was never applied on the app page in any "
                          "scenario, so the suite was not challenged by "
                          (source/form->string (:fault/form fault)))}

      :else
      {:fault/id (:fault/id fault) :fault/killed? false
       :fault/status :survived
       :fault/detail (str "no scenario noticed " (source/form->string (:fault/form fault))
                          " — the suite is blind to this behaviour")})))

(defn- unapplied? [v] (= :unapplied (:fault/status v)))

(defn score
  "Fraction of APPLIED faults the suite killed. Unapplied faults are left out
   of both sides — they challenged nothing. An empty catalog, or one where no
   fault was applied, scores 0.0, never 1.0 — a suite that was never
   challenged has proved nothing."
  [verdicts]
  (let [counted (remove unapplied? verdicts)]
    (if (empty? counted)
      0.0
      (double (/ (count (filter :fault/killed? counted)) (count counted))))))

(defn report
  "Scenario ids + fault verdicts → `schema/MutationReport`."
  [scenario-ids verdicts]
  {:mutation/scenarios (vec scenario-ids)
   :mutation/verdicts  (vec verdicts)
   :mutation/killed    (mapv :fault/id (filter :fault/killed? verdicts))
   :mutation/survived  (mapv :fault/id (remove #(or (:fault/killed? %) (unapplied? %))
                                               verdicts))
   :mutation/unapplied (mapv :fault/id (filter unapplied? verdicts))
   :mutation/score     (score verdicts)})

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> normalize-faults [:=> [:cat [:maybe [:sequential :any]]] [:vector s/Fault]])
(m/=> fault-op [:=> [:cat s/Fault] s/Op])
(m/=> injection-point [:=> [:cat [:sequential :map]] :int])
(m/=> inject [:=> [:cat s/RunPlan s/Fault] s/RunPlan])
(m/=> app-origin [:=> [:cat :map] [:maybe :string]])
(m/=> applied? [:=> [:cat :map [:maybe :int]] :boolean])
(m/=> killed-by [:=> [:cat [:sequential :any]] [:vector s/ScenarioId]])
(m/=> verdict [:=> [:cat s/Fault [:sequential :any]] s/FaultVerdict])
(m/=> score [:=> [:cat [:sequential s/FaultVerdict]] :double])
(m/=> report [:=> [:cat [:sequential s/ScenarioId] [:sequential s/FaultVerdict]]
              s/MutationReport])
