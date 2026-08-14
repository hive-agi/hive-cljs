(ns hive-cljs.schema-property-test
  "Property + mutation coverage synthesized from the malli value objects.

   Nothing here writes a generator or an oracle by hand: tighten a schema in
   `hive-cljs.schema` and these tighten with it."
  (:require [hive-cljs.manifest :as manifest]
            [hive-cljs.profile :as profile]
            [hive-cljs.schema :as s]
            [hive-cljs.verdict :as verdict]
            [hive-cljs.watch :as watch]
            [hive-schemas.test :as ht]
            [hive-cljs.coverage :as cov]
            [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(ht/deftrifecta-from-schema unknown-status
  hive-cljs.verdict/unknown-status
  {:in  s/BuildId
   :out s/BuildStatus
   :rel (fn [in out] (and (= in (:build/id out))
                          (= :unknown (:build/state out))))
   :num-tests 100})

(ht/deftrifecta-from-schema normalize-action
  hive-cljs.manifest/normalize-action
  {:in  :keyword
   :out s/WatchAction
   :rel (fn [in out] (= in (first out)))
   :mutation false
   :num-tests 100})

(ht/deftrifecta-from-schema run-state
  hive-cljs.verdict/run-state
  {:in  [:vector s/StepResult]
   :out s/RunState
   :rel (fn [in out]
          (let [states (set (map :step/state in))]
            (= out (cond (contains? states :error)      :error
                         (contains? states :fail)       :fail
                         (contains? states :incomplete) :incomplete
                         :else                          :pass))))
   :mutation false
   :num-tests 200})

(ht/deftrifecta-from-schema report
  hive-cljs.verdict/report
  {:in  [:cat s/ScenarioId [:vector s/StepResult]]
   :out s/RunReport
   :rel (fn [[id steps] out]
          (and (= id (:run/scenario out))
               (= (count steps) (count (:run/steps out)))))
   :num-tests 100})

(ht/deftrifecta-from-schema build-ok?
  hive-cljs.verdict/build-ok?
  {:in  s/BuildStatus
   :out :boolean
   :rel (fn [in out] (= out (= :completed (:build/state in))))
   :mutation false
   :num-tests 200})

(ht/deftrifecta-from-schema coverage-pct
  hive-cljs.coverage/pct
  {:in  [:cat [:int {:min 0 :max 100000}] [:int {:min 0 :max 100000}]]
   :out s/Percentage
   :rel (fn [[_ total] out] (if (zero? total) (zero? out) (<= 0.0 out 100.0)))
   :mutation false
   :num-tests 200})

(ht/deftrifecta-from-schema coverage-metric
  hive-cljs.coverage/metric
  {:in  [:map
         [:covered [:int {:min 0 :max 100000}]]
         [:total [:int {:min 0 :max 100000}]]]
   :out s/CoverageMetric
   :rel (fn [in out] (and (= (:covered in) (:metric/covered out))
                          (= (:total in) (:metric/total out))))
   :num-tests 200})

(ht/deftrifecta-from-schema coverage-totals
  hive-cljs.coverage/totals
  {:in  [:vector s/CoverageRow]
   :out s/CoverageTotals
   :rel (fn [in out]
          (and (= (count in) (:coverage/namespaces out))
               (= (reduce + 0 (map #(get-in % [:coverage/lines :metric/covered]) in))
                  (get-in out [:coverage/lines :metric/covered]))))
   :num-tests 100})

(ht/deftrifecta-from-schema coverage-deltas
  hive-cljs.coverage/deltas
  {:in  [:cat [:vector s/CoverageRow] [:vector s/CoverageRow]]
   :out [:vector s/CoverageDelta]
   :rel (fn [[now _] out] (= (count now) (count out)))
   :mutation false
   :num-tests 100})

(ht/deftrifecta-from-schema coverage-verdict
  hive-cljs.coverage/verdict
  {:in  [:cat s/CoverageTotals s/CoverageThresholds]
   :out s/CoverageVerdict
   :rel (fn [[totals _] out]
          (= (zero? (:coverage/namespaces totals))
             (= :unavailable (:coverage/state out))))
   :num-tests 100})

(ht/deftrifecta-from-schema coverage-worst-first
  hive-cljs.coverage/worst-first
  {:in  [:vector s/CoverageRow]
   :out [:vector s/CoverageRow]
   :rel (fn [in out]
          (let [pcts (map #(get-in % [:coverage/lines :metric/pct]) out)]
            (and (= (count in) (count out))
                 (= (frequencies in) (frequencies out))
                 (= pcts (sort pcts)))))
   :mutation false
   :num-tests 100})

(ht/deftrifecta-from-schema coverage-demunge-ns
  hive-cljs.coverage/demunge-ns
  {:in  :string
   :out :string
   :rel (fn [_ out] (and (not (str/includes? out "/"))
                         (not (str/includes? out "_"))))
   :mutation false
   :num-tests 200})

(ht/deftrifecta-from-schema normalize-coverage
  hive-cljs.manifest/normalize-coverage
  {:in  [:map [:source-prefixes [:vector {:min 1} [:string {:min 1}]]]]
   :out s/CoverageConfig
   :rel (fn [in out] (= (:source-prefixes in) (:coverage/source-prefixes out)))
   :num-tests 100})
