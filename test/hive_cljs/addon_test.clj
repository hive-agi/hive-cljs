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
    (is (= {} (proto/hooks a)))))

(deftest initialize-is-idempotent
  (let [a (addon/addon-ctor {})
        r1 (proto/initialize! a {:addon/config {}})
        r2 (proto/initialize! a {:addon/config {}})]
    (is (:success? r1))
    (is (:success? r2))
    (is (= "code cljs" (get-in r1 [:metadata :subdomain])))))

;; =============================================================================
;; `code cljs …` subdomain
;; =============================================================================

(deftest contributes-a-subdomain-not-a-standalone-root
  (let [a (addon/addon-ctor {})]
    (testing "no root tool is advertised — the surface is the code subdomain"
      (is (= [] (proto/tools a))))
    (testing "exactly one command is contributed, keyed by the subdomain name"
      (is (= ["cljs"] (vec (keys registry/code-contributions))))
      (is (fn? (get-in registry/code-contributions ["cljs" :handler])))
      (is (string? (get-in registry/code-contributions ["cljs" :description]))))))

(deftest schema-extension-targets-the-code-tool-without-clobbering-its-params
  (let [a   (addon/addon-ctor {})
        ext (proto/schema-extensions a)]
    (is (= ["code"] (vec (keys ext))))
    (testing "novel params are contributed"
      (is (contains? (get ext "code") "build"))
      (is (contains? (get ext "code") "scenario"))
      (is (contains? (get ext "code") "tags")))
    (testing "params the code tool already owns are NOT re-contributed"
      (doseq [owned ["command" "code" "path" "file" "namespace" "directory" "line"]]
        (is (not (contains? (get ext "code") owned)) owned)))))

(deftest subdomain-prefix-is-stripped-before-dispatch
  (is (= "status" (registry/strip-subdomain-prefix "cljs status")))
  (is (= "e2e run" (registry/strip-subdomain-prefix "cljs e2e run")))
  (is (= "watch start" (registry/strip-subdomain-prefix "cljs watch start")))
  (is (= "help" (registry/strip-subdomain-prefix "cljs help")))
  (testing "an already-bare command passes through"
    (is (= "status" (registry/strip-subdomain-prefix "status"))))
  (testing "a command merely starting with the letters cljs is not truncated"
    (is (= "cljsomething" (registry/strip-subdomain-prefix "cljsomething")))))

(deftest subdomain-dispatch-renders-the-host-tool-result-shape
  (testing "a success renders as the host's {:type \"text\"} content, not a raw Result"
    (let [res (registry/dispatch-subdomain {:command "cljs help"})]
      (is (= "text" (:type res)))
      (is (string? (:text res)))
      (is (not (:isError res)))
      (is (re-find #"doctor" (:text res)))))
  (testing "a failure renders as an error result carrying the category"
    (let [res (registry/dispatch-subdomain {:command "cljs frobnicate"})]
      (is (= "text" (:type res)))
      (is (true? (:isError res)))
      (is (re-find #"unknown-command" (:text res)))))
  (testing "a multi-word subcommand survives the strip"
    (let [res (registry/dispatch-subdomain {:command "cljs e2e list"
                                            :directory "/tmp/hive-cljs-absent"})]
      (is (true? (:isError res)))
      (is (re-find #"manifest/not-found" (:text res)))))
  (testing "the bare dispatch still returns a raw Result for in-process callers"
    (is (r/ok? (registry/dispatch {:command "help"})))
    (is (= :cljs/unknown-command (:error (registry/dispatch {:command "frobnicate"}))))))

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
