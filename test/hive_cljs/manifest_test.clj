(ns hive-cljs.manifest-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.fixtures :as fix]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.schema :as s]
            [hive-dsl.result :as r]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(deftest empty-manifest-normalizes-to-a-valid-default
  (let [res (manifest/parse {} "/tmp/x")]
    (is (r/ok? res))
    (is (m/validate s/Manifest (:ok res)))
    (is (= "localhost" (get-in res [:ok :manifest/shadow :host])))
    (is (= 9630 (get-in res [:ok :manifest/shadow :port])))))

(deftest base-url-is-inferred-from-a-build-http-port
  (is (= "http://localhost:8280" (get-in fix/manifest [:manifest/e2e :base-url])))
  (testing "an explicit base-url wins"
    (let [m (:ok (manifest/parse (assoc-in fix/raw [:hive.cljs/e2e :base-url]
                                           "http://127.0.0.1:9999")
                                 "/tmp/x"))]
      (is (= "http://127.0.0.1:9999" (get-in m [:manifest/e2e :base-url]))))))

(deftest build-id-defaults-to-its-map-key
  (is (= :app (get-in fix/manifest [:manifest/builds :app :shadow/id]))))

(deftest watch-actions-normalize-to-tuples
  (is (= [:run-e2e {}] (manifest/normalize-action :run-e2e)))
  (is (= [:run-e2e {}] (manifest/normalize-action [:run-e2e])))
  (is (= [:run-e2e {:tags #{:smoke}}] (manifest/normalize-action [:run-e2e {:tags #{:smoke}}]))))

(deftest non-map-input-is-a-typed-error
  (is (= :manifest/not-a-map (:error (manifest/parse [1 2 3] "/tmp/x")))))

(deftest invalid-shape-is-explained-not-thrown
  (let [res (manifest/parse {:hive.cljs/shadow {:port "not-a-port"}} "/tmp/x")]
    (is (r/err? res))
    (is (= :manifest/invalid (:error res)))
    (is (some? (:explain res)))))

(deftest scenario-queries
  (is (= [:login :dashboard] (mapv :id (manifest/scenarios fix/manifest))))
  (is (= :login (:id (manifest/scenario fix/manifest :login))))
  (is (nil? (manifest/scenario fix/manifest :nope)))
  (is (= [:login] (mapv :id (manifest/scenarios-by-tag fix/manifest #{:smoke}))))
  (testing "no tags selects everything"
    (is (= 2 (count (manifest/scenarios-by-tag fix/manifest #{}))))))
