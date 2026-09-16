(ns hive-cljs.playwright-test
  "The Playwright adapter's scoping decision, which needs no browser to pin.

   Driving a real page belongs to a run; deciding WHAT a step addresses is a
   pure choice over the session map, and it is the choice with a silent failure
   mode. An iframe that cannot be resolved must never degrade into the top
   page: the steps would then measure the composition host instead of the
   application, and report a pass for doing it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.browser.playwright :as pw]
            [hive-dsl.result :as r])
  (:import [com.microsoft.playwright Page]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- page-matching-nothing
  "A Page whose querySelector finds no element. Every other method throws, so a
   test that drifts into driving the browser fails loudly instead of passing."
  []
  (proxy [Page] []
    (querySelector [_sel] nil)))

(deftest without-an-iframe-a-step-addresses-the-page-itself
  (let [page (page-matching-nothing)]
    (is (identical? page (pw/dom-root {:page page})))
    (is (identical? page (:ok (pw/eval-target {:page page}))))))

(deftest an-unresolvable-iframe-is-an-error-never-a-fallback-to-the-page
  ;; The whole point of the option. Falling back would answer every selector
  ;; against the HOST's document while reporting a pass, which is the exact
  ;; confusion :iframe exists to remove.
  (let [session {:page (page-matching-nothing) :iframe "#missing"}]
    (testing "the selector channel throws, and `perform!` turns that into :error"
      (let [thrown (is (thrown? clojure.lang.ExceptionInfo (pw/dom-root session)))]
        (is (str/includes? (ex-message thrown) "#missing")
            "the message names the selector, because that is what has to change")
        (is (= "#missing" (:selector (ex-data thrown))))))

    (testing "the eval channel reports it as a typed error instead"
      (let [res (pw/eval-target session)]
        (is (r/err? res))
        (is (= :browser/iframe-unresolved (:error res)))
        (is (= "#missing" (:selector res)))))))
