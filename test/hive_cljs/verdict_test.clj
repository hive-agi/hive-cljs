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

(deftest a-compile-settles-only-on-a-cycle-newer-than-the-one-it-found
  (let [done    (verdict/build-status P :app {:status :completed :compiled "1"})
        failed  (verdict/build-status P :app {:status :failed})
        going   (verdict/build-status P :app {:status :compiling})
        unknown (verdict/unknown-status :app)
        raw-a   {:status :completed :duration-ms 100}
        raw-b   {:status :completed :duration-ms 250}]
    (testing "terminal states are exactly the settled ones"
      (is (verdict/terminal-state? :completed))
      (is (verdict/terminal-state? :failed))
      (is (not (verdict/terminal-state? :compiling)))
      (is (not (verdict/terminal-state? :pending)))
      (is (not (verdict/terminal-state? :unknown)))
      (is (verdict/settled? done))
      (is (verdict/settled? failed))
      (is (not (verdict/settled? going)))
      (is (not (verdict/settled? unknown))))

    (testing "a settled status identical to the pre-request one is NOT this cycle"
      (is (not (verdict/compile-settled? done raw-a raw-a false))))

    (testing "a changed payload is a witness on its own"
      (is (verdict/compile-settled? done raw-b raw-a false)))

    (testing "having passed through a non-terminal state is the other witness"
      (is (verdict/compile-settled? done raw-a raw-a true)))

    (testing "a witness alone does not settle an unfinished build"
      (is (not (verdict/compile-settled? going raw-b raw-a true)))
      (is (not (verdict/compile-settled? unknown nil nil true))))

    (testing "a failed compile settles too — it is an answer, not a wait"
      (is (verdict/compile-settled? failed raw-b raw-a false)))

    (testing "a build never seen before settles on its first terminal status"
      (is (not (verdict/compile-settled? unknown nil nil false)))
      (is (verdict/compile-settled? done raw-a nil false)))))
