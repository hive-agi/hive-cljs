(ns hive-cljs.boundary-test
  "The integration the subsystem exists for: a plan carrying BOTH browser and
   runtime steps executes across two ports and produces one report.

   Both ports are stubs — the test names no vendor."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.fixtures :as fix]
            [hive-cljs.plan :as plan]
            [hive-cljs.stub.ports :as stub]
            [hive-cljs.verdict :as verdict]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- deps
  ([] (deps stub/always-pass (constantly true)))
  ([outcome-fn value-fn]
   {:driver    (stub/driver outcome-fn)
    :cljs-eval (stub/cljs-eval value-fn)
    :build-tool (stub/build-tool)}))

(deftest runs-both-channels-in-one-scenario
  (let [d   (deps)
        res (boundary/run-scenario! d fix/manifest :login)]
    (is (r/ok? res))
    (is (verdict/run-ok? (:ok res)))
    (testing "browser ops reached the driver, runtime ops did not"
      (let [kinds (mapv :op/kind (stub/performed-ops (:driver d)))]
        (is (= [:goto :fill :click :expect-text] kinds))
        (is (not (contains? (set kinds) :expect-sub)))))
    (testing "the runtime assertion reached ICljsEval as a subscription deref"
      (let [[[build form]] (stub/evals (:cljs-eval d))]
        (is (= :app build))
        (is (= "(some? @(re-frame.core/subscribe [:current-user]))" form))))
    (testing "the session was opened and closed exactly once"
      (is (= 1 (stub/sessions-opened (:driver d))))
      (is (= 1 (stub/sessions-closed (:driver d)))))))

(deftest urls-are-absolutized-against-base-url
  (let [d (deps)]
    (boundary/run-scenario! d fix/manifest :login)
    (is (= "http://localhost:8280/login"
           (-> (stub/performed-ops (:driver d)) first :op/args first)))))

(deftest failure-halts-and-marks-the-rest-skipped
  (let [d   (deps (stub/fail-on :click) (constantly true))
        res (boundary/run-scenario! d fix/manifest :login)
        rep (:ok res)]
    (is (= :fail (:run/state rep)))
    (is (= [:pass :pass :fail :skipped :skipped]
           (mapv :step/state (:run/steps rep))))
    (testing "the session is still closed after a failure"
      (is (= 1 (stub/sessions-closed (:driver d)))))))

(deftest runtime-predicate-decides-pass-or-fail
  (testing "a truthy subscription value passes"
    (let [d (deps stub/always-pass (constantly true))]
      (is (verdict/run-ok? (:ok (boundary/run-scenario! d fix/manifest :login))))))
  (testing "a false subscription value fails the run"
    (let [d   (deps stub/always-pass (constantly false))
          rep (:ok (boundary/run-scenario! d fix/manifest :login))]
      (is (= :fail (:run/state rep)))
      (is (= :expect-sub (:step/kind (last (:run/steps rep))))))))

(deftest missing-runtime-port-skips-rather-than-explodes
  (let [d   {:driver (stub/driver) :cljs-eval nil}
        rep (:ok (boundary/run-scenario! d fix/manifest :login))]
    (is (= :skipped (:step/state (last (:run/steps rep)))))
    (is (= :pass (:run/state rep)))))

(deftest missing-driver-is-a-typed-error
  (let [res (boundary/run-scenario! {:cljs-eval (stub/cljs-eval)} fix/manifest :login)]
    (is (= :run/no-driver (:error res)))))

(deftest unknown-scenario-is-a-typed-error
  (let [res (boundary/run-scenario! (deps) fix/manifest :nope)]
    (is (= :scenario/not-found (:error res)))
    (is (= [:login :dashboard] (:known res)))))

(deftest tagged-runs-select-by-tag
  (let [reps (:ok (boundary/run-tagged! (deps) fix/manifest #{:smoke}))]
    (is (= [:login] (mapv :run/scenario reps))))
  (let [reps (:ok (boundary/run-tagged! (deps) fix/manifest #{:slow}))]
    (is (= [:dashboard] (mapv :run/scenario reps)))))

(deftest browser-only-plan-needs-no-runtime
  (let [p (:ok (plan/plan-for-id fix/manifest :dashboard))]
    (is (not (plan/needs-runtime? p)))
    (is (verdict/run-ok? (:ok (boundary/run-plan! (deps) p))))))
