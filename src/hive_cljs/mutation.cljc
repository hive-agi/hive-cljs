(ns hive-cljs.mutation
  "PROMOTE + PIPELINE layer — behavioural mutation testing for a running app.

   `hive-schemas.test` mutates VALUES against schemas on the JVM; this mutates
   BEHAVIOUR against scenarios in the browser. A fault is data: source text the
   runtime channel evaluates, spliced into an ordinary `schema/RunPlan` as one
   more op, so nothing about execution changes.

   The verdict on a fault is inverted from an ordinary run — a suite that stays
   GREEN under a fault has a hole, and the fault survived."
  (:require [clojure.string :as str]
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

(defn normalize-fault
  "Authored fault → `schema/Fault`, or nil when it names nothing to break.

   Two spellings: `{:target sym :with \"(constantly nil)\"}` neutralizes a var,
   `{:form \"…\"}` evaluates arbitrary source — which is what re-registering a
   re-frame handler needs, since those live in a registry rather than a var."
  [raw]
  (let [{:keys [target with form doc]} raw
        id   (fault-id raw)
        src  (or form (when (and target with) (str "(set! " target " " with ")")))]
    (when (and id (not (str/blank? (str src))))
      (cond-> {:fault/id id :fault/form (str src)}
        target (assoc :fault/target target)
        doc    (assoc :fault/doc doc)))))

(defn normalize-faults
  "Authored fault vector → `schema/Fault` vector, dropping the unusable ones."
  [raws]
  (vec (keep normalize-fault raws)))

(defn registry-fault
  "One registered re-frame handler + the source that neutralizes it → a Fault.

   The id is flattened (`:app/items` → `:sub-app.items`) so it stays a simple
   keyword: a fault id names a fault, not a namespace in the app."
  [kind id form]
  {:fault/id   (keyword (str (name kind) "-"
                             (str/replace (str/replace (str id) #"^:" "") "/" ".")))
   :fault/form form
   :fault/doc  (str "neutralized re-frame " (name kind) " " id)})

(defn fault-op
  "The op that applies a fault: plain source on the runtime channel."
  [fault]
  (let [src (:fault/form fault)]
    {:op/kind    :eval-cljs
     :op/channel :runtime
     :op/args    [src]
     :op/source  [:eval-cljs src]}))

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

(defn inject
  "Splice a fault's op into a plan at [[injection-point]]."
  [plan fault]
  (let [ops (vec (:plan/ops plan))
        at  (injection-point ops)]
    (assoc plan :plan/ops
           (into (subvec ops 0 at) cat [[(fault-op fault)] (subvec ops at)]))))

;; =============================================================================
;; Verdicts
;; =============================================================================

(defn killed-by
  "Scenarios that went red under a fault — the ones that killed it."
  [reports]
  (vec (keep (fn [r] (when-not (= :pass (:run/state r)) (:run/scenario r))) reports)))

(defn verdict
  "One fault + the reports it produced → `schema/FaultVerdict`."
  [fault reports]
  (let [by (killed-by reports)]
    (cond-> {:fault/id      (:fault/id fault)
             :fault/killed? (boolean (seq by))}
      (seq by) (assoc :fault/by by)
      (empty? by)
      (assoc :fault/detail
             (str "no scenario noticed " (:fault/form fault)
                  " — the suite is blind to this behaviour")))))

(defn score
  "Fraction of faults the suite killed. An empty catalog scores 0.0, never 1.0 —
   a suite that was never challenged has proved nothing."
  [verdicts]
  (if (empty? verdicts)
    0.0
    (double (/ (count (filter :fault/killed? verdicts)) (count verdicts)))))

(defn report
  "Scenario ids + fault verdicts → `schema/MutationReport`."
  [scenario-ids verdicts]
  {:mutation/scenarios (vec scenario-ids)
   :mutation/verdicts  (vec verdicts)
   :mutation/killed    (mapv :fault/id (filter :fault/killed? verdicts))
   :mutation/survived  (mapv :fault/id (remove :fault/killed? verdicts))
   :mutation/score     (score verdicts)})

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> normalize-faults [:=> [:cat [:maybe [:sequential :any]]] [:vector s/Fault]])
(m/=> fault-op [:=> [:cat s/Fault] s/Op])
(m/=> injection-point [:=> [:cat [:sequential :map]] :int])
(m/=> inject [:=> [:cat s/RunPlan s/Fault] s/RunPlan])
(m/=> killed-by [:=> [:cat [:sequential :any]] [:vector s/ScenarioId]])
(m/=> verdict [:=> [:cat s/Fault [:sequential :any]] s/FaultVerdict])
(m/=> score [:=> [:cat [:sequential s/FaultVerdict]] :double])
(m/=> report [:=> [:cat [:sequential s/ScenarioId] [:sequential s/FaultVerdict]]
              s/MutationReport])
