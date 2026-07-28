(ns hive-cljs.verdict-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.profile :as profile]
            [hive-cljs.schema :as s]
            [hive-cljs.shadow.sync-db :as sync-db]
            [hive-cljs.verdict :as verdict]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def P (profile/relay-profile))

(deftest promotes-a-raw-payload-to-a-conforming-status
  (let [st (verdict/build-status P :app
                                 {:status :completed :resources "317" :compiled "1"
                                  :log ["Compile CLJS: src/my/app.cljs (505 ms)"
                                        "Some other line"]
                                  :warnings []})]
    (is (m/validate s/BuildStatus st) (pr-str (m/explain s/BuildStatus st)))
    (is (= :completed (:build/state st)))
    (is (= 317 (:build/resources st)))
    (is (= [{:file/path "src/my/app.cljs" :file/elapsed-ms 505}] (:build/files st)))
    (is (verdict/build-ok? st))))

(deftest status-tokens-normalize-through-the-profile
  (is (= :completed (profile/normalize-state P :completed)))
  (is (= :completed (profile/normalize-state P ":completed")))
  (is (= :completed (profile/normalize-state P :compile-warnings)))
  (is (= :pending (profile/normalize-state P :configure)))
  (testing "an unmapped token degrades, never throws"
    (is (= :unknown (profile/normalize-state P :something-new)))
    (is (= :unknown (profile/normalize-state P nil)))))

(deftest a-report-is-decided-by-its-worst-step
  (is (= :pass (verdict/run-state [{:step/state :pass} {:step/state :pass}])))
  (is (= :fail (verdict/run-state [{:step/state :pass} {:step/state :fail}])))
  (is (= :error (verdict/run-state [{:step/state :fail} {:step/state :error}])))
  (testing "skipped alone does not fail a run"
    (is (= :pass (verdict/run-state [{:step/state :pass} {:step/state :skipped}])))))

(deftest reports-conform-and-summarize
  (let [rep (verdict/report :login [{:step/index 0 :step/kind :goto :step/state :pass}
                                    {:step/index 1 :step/kind :click :step/state :fail}]
                            {:elapsed-ms 42})]
    (is (m/validate s/RunReport rep) (pr-str (m/explain s/RunReport rep)))
    (is (= "login: fail (1 pass, 1 fail, 0 error, 0 skipped)" (verdict/summarize rep)))))

(deftest sync-db-folds-relay-changes
  (let [snap (sync-db/apply-snapshot
              {} P {:shadow.cljs/builds
                    [{:shadow.cljs/build-id :app
                      :shadow.cljs/build-worker-active true
                      :shadow.cljs/build-status {:status :pending}}]})]
    (is (= [:app] (sync-db/build-ids snap)))
    (is (sync-db/worker-active? snap P :app))

    (testing "only build-status changes surface as changed builds"
      (let [changes [[:entity-update :shadow.cljs/build :app
                      :shadow.cljs/build-status {:status :completed}]
                     [:entity-update :shadow.cljs/repl-history 7 :x 1]]]
        (is (= [:app] (sync-db/changed-builds P changes)))
        (is (= :completed
               (:status (sync-db/raw-status (sync-db/apply-changes snap P changes) P :app))))))

    (testing "a removed table entry disappears"
      (is (empty? (sync-db/build-ids
                   (sync-db/apply-changes snap P [[:table-remove :shadow.cljs/build :app]])))))))

(deftest unknown-build-degrades-to-a-conforming-status
  (let [st (verdict/unknown-status :ghost)]
    (is (m/validate s/BuildStatus st))
    (is (= :unknown (:build/state st)))
    (is (not (verdict/build-ok? st)))))
