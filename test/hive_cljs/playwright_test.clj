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
  (:import [com.microsoft.playwright Frame Page ElementHandle]))

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

(deftest launch-args-reach-the-browser-process-options
  (is (= ["--use-fake-device-for-media-stream"]
         (vec (.-args (pw/launch-options :chromium true nil ["--use-fake-device-for-media-stream"])))))
  (is (true? (.-headless (pw/launch-options :chromium true nil nil))))
  (is (nil? (.-args (pw/launch-options :chromium false nil [])))
      "no switches leaves Playwright's own defaults untouched")
  (is (= ["--use-fake-device-for-media-stream" "--class=tests"]
         (vec (.-args (pw/launch-options :chromium false "tests" ["--use-fake-device-for-media-stream"])))))
  (is (= ["--use-fake-device-for-media-stream"]
         (vec (.-args (pw/launch-options :webkit false "tests" ["--use-fake-device-for-media-stream"]))))))

;; =============================================================================
;; Selector data never reaches Playwright as data
;; =============================================================================

(defn- recording-page
  "A Page that records every selector string it is handed. Its querySelector
   answers an element whose content frame is `frame`."
  [seen frame]
  (proxy [Page] []
    (querySelector [sel]
      (swap! seen conj sel)
      (proxy [ElementHandle] []
        (contentFrame [] frame)))
    (click [sel & _] (swap! seen conj sel) nil)
    (textContent [sel & _] (swap! seen conj sel) "Hello there")))

(deftest an-iframe-datum-is-compiled-before-the-page-sees-it
  (let [seen  (atom [])
        frame (proxy [Frame] [])
        page  (recording-page seen frame)]
    (is (identical? frame (pw/dom-root {:page page :iframe [:hyperframes-player [:iframe#stage]]})))
    (is (= ["hyperframes-player iframe#stage"] @seen))
    (testing "a string, which a plan always hands over, passes through"
      (reset! seen [])
      (pw/dom-root {:page page :iframe "#player"})
      (is (= ["#player"] @seen)))
    (testing "a malformed datum is a selector error naming the problem"
      (let [res (pw/eval-target {:page page :iframe [:li :a :b]})]
        (is (= :browser/iframe-unresolved (:error res)))
        (is (str/includes? (:cause res) "malformed selector"))))))

(deftest every-dom-op-hands-playwright-a-string
  (let [seen (atom [])
        page (recording-page seen nil)]
    (testing "a compiled op (what a plan carries) is unchanged"
      (is (= :pass (:state (pw/perform-op {:page page} {:op/kind :click :op/args ["#go"]}))))
      (is (= ["#go"] @seen)))
    (testing "an op built by hand with selector data is compiled, not passed raw"
      (reset! seen [])
      (let [out (pw/perform-op {:page page} {:op/kind :expect-text
                                            :op/args [[:li {:data-testid "hi"}] "Hello"]})]
        (is (= :pass (:state out)))
        (is (= ["li[data-testid=\"hi\"]"] @seen))
        (is (every? string? @seen))))
    (testing "the selector-string seam itself"
      (is (= "#go" (pw/selector-string "#go")))
      (is (= "#go" (pw/selector-string :#go)))
      (is (thrown? clojure.lang.ExceptionInfo (pw/selector-string [:li :a :b]))))))

(defn- page-at [url] (proxy [Page] [] (url [] url)))

(deftest the-origin-guard-refuses-a-foreign-page
  (is (= :pass (:state (pw/perform-op {:page (page-at "http://app.test:8080/home")}
                                      {:op/kind :hive-cljs/at-origin
                                       :op/args ["http://app.test:8080"]}))))
  (is (= :fail (:state (pw/perform-op {:page (page-at "https://idp.test/login")}
                                      {:op/kind :hive-cljs/at-origin
                                       :op/args ["http://app.test:8080"]})))))

(deftest expect-no-errors-reads-the-recorded-console-and-pageerrors
  (let [errs    (atom [{:error/source :console :error/text "favicon.ico 404"}
                       {:error/source :pageerror :error/text "TypeError: x is null"}])
        session {:errors errs}
        run     #(:state (pw/perform-op session {:op/kind :expect-no-errors :op/args %}))]
    (is (= :fail (run [])))
    (is (= :fail (run [{:ignore ["favicon"]}])))
    (is (= :pass (run [{:ignore ["favicon" "TypeError"]}])))
    (is (= :pass (run [{:sources #{:console} :ignore ["favicon"]}])))
    (testing "a clean page passes"
      (is (= :pass (:state (pw/perform-op {:errors (atom [])}
                                          {:op/kind :expect-no-errors :op/args []})))))))
