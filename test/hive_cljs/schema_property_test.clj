(ns hive-cljs.schema-property-test
  "Property + mutation coverage synthesized from the malli value objects.

   Nothing here writes a generator or an oracle by hand: tighten a schema in
   `hive-cljs.schema` and these tighten with it."
  (:require [hive-cljs.manifest :as manifest]
            [hive-cljs.profile :as profile]
            [hive-cljs.schema :as s]
            [hive-cljs.verdict :as verdict]
            [hive-cljs.watch :as watch]
            [hive-schemas.test :as ht]))

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
            (= out (cond (contains? states :error) :error
                         (contains? states :fail)  :fail
                         :else                     :pass))))
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
