(ns hive-cljs.plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.fixtures :as fix]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.plan :as plan]
            [hive-cljs.schema :as s]
            [hive-cljs.stub.ports :as stub]
            [hive-dsl.result :as r]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(deftest plans-conform-to-the-schema
  (let [p (:ok (plan/plan-for-id fix/manifest :login))]
    (is (m/validate s/RunPlan p) (pr-str (m/explain s/RunPlan p)))))

(deftest relative-urls-resolve-absolute-ones-pass-through
  (is (= "http://x/a" (plan/absolutize "http://x" "/a")))
  (is (= "http://x/a" (plan/absolutize "http://x/" "/a")))
  (is (= "http://x/a" (plan/absolutize "http://x" "a")))
  (is (= "http://other/z" (plan/absolutize "http://x" "http://other/z"))))

(deftest a-scenario-without-a-build-inherits-the-sole-build
  (testing "one build declared → inherited"
    (is (= :app (plan/default-build fix/manifest)))
    (let [p (:ok (plan/build-plan fix/manifest {:id :adhoc :steps [[:goto "/"]]}))]
      (is (= :app (:plan/build p)))))

  (testing "two builds declared → ambiguous, left unset"
    (let [m (:ok (manifest/parse (assoc-in fix/raw [:hive.cljs/builds :other] {:http-port 9000})
                                 "/tmp/x"))]
      (is (nil? (plan/default-build m)))
      (is (nil? (:plan/build (:ok (plan/build-plan m {:id :adhoc :steps [[:goto "/"]]})))))))

  (testing "an explicit :build always wins"
    (let [m (:ok (manifest/parse (assoc-in fix/raw [:hive.cljs/builds :other] {:http-port 9000})
                                 "/tmp/x"))
          p (:ok (plan/build-plan m {:id :adhoc :build :other :steps [[:goto "/"]]}))]
      (is (= :other (:plan/build p))))))

(deftest a-scenario-resolves-urls-against-the-port-of-the-build-it-names
  (let [m (:ok (manifest/parse (-> fix/raw
                                   (assoc-in [:hive.cljs/builds :scene] {:http-port 8087})
                                   (assoc-in [:hive.cljs/e2e :scenarios]
                                             [{:id :on-app :build :app :steps [[:goto "/index.html"]]}
                                              {:id :on-scene :build :scene :steps [[:goto "/index.html"]]}
                                              {:id :no-build :steps [[:goto "/index.html"]]}]))
                               "/tmp/x"))
        goto-url (fn [id] (first (:op/args (first (:plan/ops (:ok (plan/plan-for-id m id)))))))]
    (testing "a second build on a second port is reachable, not just the first"
      (is (= "http://localhost:8280/index.html" (goto-url :on-app)))
      (is (= "http://localhost:8087/index.html" (goto-url :on-scene))))

    (testing "a scenario naming no build keeps the manifest-wide base-url"
      (is (= (get-in m [:manifest/e2e :base-url])
             (:plan/base-url (:ok (plan/plan-for-id m :no-build))))))

    (testing "the session opts carry the base-url the ops were resolved against"
      (is (= "http://localhost:8087"
             (get-in (:ok (plan/plan-for-id m :on-scene)) [:plan/session :base-url]))))))

(deftest the-named-builds-port-wins-and-falls-back-when-there-is-none
  (let [m (:ok (manifest/parse (-> fix/raw
                                   (assoc-in [:hive.cljs/builds :scene] {:http-port 8087})
                                   (assoc-in [:hive.cljs/builds :static] {:entry "/index.html"})
                                   (assoc-in [:hive.cljs/e2e :base-url] "http://127.0.0.1:9999")
                                   (assoc-in [:hive.cljs/e2e :scenarios]
                                             [{:id :on-scene :build :scene :steps [[:goto "/a"]]}
                                              {:id :on-static :build :static :steps [[:goto "/a"]]}]))
                               "/tmp/x"))]
    (testing "a declared :http-port outranks the manifest-wide :base-url"
      (is (= "http://localhost:8087" (:plan/base-url (:ok (plan/plan-for-id m :on-scene))))))

    (testing "a build with no :http-port falls back to the explicit :base-url"
      (is (= "http://127.0.0.1:9999" (:plan/base-url (:ok (plan/plan-for-id m :on-static))))))))

(deftest a-runtime-step-without-a-resolvable-build-is-a-typed-step-error
  (let [m    (:ok (manifest/parse (-> fix/raw
                                      (assoc-in [:hive.cljs/builds :other] {:http-port 9000})
                                      (assoc-in [:hive.cljs/e2e :scenarios]
                                                [{:id :adhoc
                                                  :steps [[:expect-sub [:q] "some?"]]}]))
                                  "/tmp/x"))
        rep  (:ok (boundary/run-scenario! {:driver (stub/driver)
                                           :cljs-eval (stub/cljs-eval)}
                                          m :adhoc))]
    (is (= :error (:run/state rep)))
    (is (re-find #"needs a build" (:step/detail (first (:run/steps rep)))))))

(deftest channels-report-what-a-plan-needs
  (let [login (:ok (plan/plan-for-id fix/manifest :login))
        dash  (:ok (plan/plan-for-id fix/manifest :dashboard))]
    (is (= #{:browser :runtime} (plan/channels-used login)))
    (is (plan/needs-runtime? login))
    (is (= #{:browser} (plan/channels-used dash)))
    (is (not (plan/needs-runtime? dash)))))

(deftest a-malformed-step-fails-the-plan-not-the-run
  (let [m (:ok (manifest/parse (assoc-in fix/raw [:hive.cljs/e2e :scenarios]
                                         [{:id :bad :steps [[:goto "/"] [:fill "#a"]]}])
                               "/tmp/x"))]
    (is (= :step/malformed (:error (plan/plan-for-id m :bad))))))

(deftest tag-selection-builds-one-plan-per-scenario
  (let [res (plan/plans-for-tags fix/manifest #{:smoke})]
    (is (r/ok? res))
    (is (= [:login] (mapv :plan/scenario (:ok res)))))
  (is (= :scenario/none-matched (:error (plan/plans-for-tags fix/manifest #{:nope})))))
