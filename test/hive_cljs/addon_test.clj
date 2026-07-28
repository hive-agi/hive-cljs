(ns hive-cljs.addon-test
  "The IAddon contract and the `cljs` tool's dispatch surface."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as proto]
            [hive-cljs.addon :as addon]
            [hive-cljs.addon.handlers :as h]
            [hive-cljs.addon.registry :as registry]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(deftest satisfies-the-iaddon-contract
  (let [a (addon/addon-ctor {})]
    (is (proto/addon? a))
    (is (= "hive.cljs" (proto/addon-id a)))
    (is (proto/valid-addon-type? (proto/addon-type a)))
    (is (contains? (proto/capabilities a) :tools))
    (is (= #{} (proto/excluded-tools a)))
    (is (= {} (proto/hooks a)))
    (is (= [] (proto/schema-extensions a)))))

(deftest initialize-is-idempotent-and-reports-tools
  (let [a (addon/addon-ctor {})
        r1 (proto/initialize! a {:addon/config {}})
        r2 (proto/initialize! a {:addon/config {}})]
    (is (:success? r1))
    (is (:success? r2))
    (is (= ["cljs"] (get-in r1 [:metadata :tools])))))

(deftest health-reports-without-any-project
  (let [a (addon/addon-ctor {})]
    (is (= :ok (:status (proto/health a))))))

(deftest tool-def-is-well-formed
  (is (= "cljs" (:name addon/tool-def)))
  (is (fn? (:handler addon/tool-def)))
  (is (= ["command"] (get-in addon/tool-def [:inputSchema :required])))
  (testing "every advertised subcommand is documented and callable"
    (doseq [[cmd {:keys [fn doc]}] registry/subcommands]
      (is (fn? fn) cmd)
      (is (string? doc) cmd)
      (is (re-find (re-pattern cmd) (:description addon/tool-def)) cmd))))

(deftest dispatch-routing
  (testing "help lists every subcommand"
    (let [res (addon/dispatch {:command "help"})]
      (is (r/ok? res))
      (is (= (count registry/subcommands) (count (get-in res [:ok :subcommands]))))))
  (testing "commands are whitespace- and case-normalized"
    (is (= :manifest/not-found
           (:error (addon/dispatch {:command "  E2E   LIST "
                                    :directory "/tmp/hive-cljs-absent"})))))
  (testing "an unknown command is a typed error listing what exists"
    (let [res (addon/dispatch {:command "frobnicate"})]
      (is (= :cljs/unknown-command (:error res)))
      (is (contains? (set (:known res)) "doctor"))))
  (testing "a missing command defaults to help"
    (is (r/ok? (addon/dispatch {})))))

(deftest doctor-reports-an-absent-manifest-without-throwing
  (let [res (addon/dispatch {:command "doctor" :directory "/tmp/hive-cljs-absent"})]
    (is (r/ok? res))
    (is (= :invalid (get-in res [:ok :manifest])))))

(deftest param-coercion-tolerates-mcp-strings
  (is (= :app (h/->keyword "app")))
  (is (= :app (h/->keyword ":app")))
  (is (= :app (h/->keyword :app)))
  (is (= #{:smoke :slow} (h/->tags "smoke, slow")))
  (is (= #{:smoke} (h/->tags [:smoke])))
  (is (= #{} (h/->tags nil))))

(deftest missing-params-are-typed-errors
  (testing "compile without a build"
    (let [res (addon/dispatch {:command "compile" :directory "/tmp/hive-cljs-absent"})]
      (is (r/err? res))))
  (testing "watch stop with no watcher is not an error"
    (let [res (addon/dispatch {:command "watch stop" :directory "/tmp/hive-cljs-absent"})]
      (is (r/ok? res))
      (is (false? (get-in res [:ok :stopped]))))))
