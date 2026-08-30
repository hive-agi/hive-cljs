(ns hive-cljs.page-eval-test
  "The stack-agnostic runtime channel.

   A scenario against an Elm, React or Svelte application uses the same step
   vector as one against ClojureScript; only the runtime vocabulary differs.
   The claim that matters here is that this channel needs NO page pinning — it
   evaluates inside the session it was handed, so there is no other runtime that
   could answer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.browser.page-eval :as page-eval]
            [hive-cljs.dialect.js :as js]
            [hive-cljs.fixtures :as fix]
            [hive-cljs.plan :as plan]
            [hive-cljs.ports :as ports]
            [hive-cljs.stub.ports :as stub]
            [hive-cljs.toolchain :as toolchain]
            [hive-cljs.verdict :as verdict]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- op [kind & args]
  {:op/kind kind :op/channel :runtime :op/args (vec args)})

(defn- run
  "Run `steps` with a page answering `page-fn`. Returns [report driver]."
  [steps page-fn]
  (let [driver (stub/answering-page (stub/driver) page-fn)
        deps   {:driver driver :cljs-eval (page-eval/channel driver)}
        p      (plan/build-plan fix/manifest {:id :js-smoke :steps steps})]
    [(boundary/run-plan! deps (:ok p)) driver]))

;; =============================================================================
;; The dialect
;; =============================================================================

(deftest the-js-dialect-renders-the-stack-agnostic-vocabulary
  (is (= "window.app.ready" (js/assertion-source (op :eval-js "window.app.ready"))))
  (testing "an assertion reports the value it saw, not a bare boolean"
    (let [src (js/assertion-source (op :expect-js "document.title"))]
      (is (str/includes? src "document.title"))
      (is (str/includes? src "v ? v : false"))))
  (testing "a poll carries the value alongside the verdict"
    (let [src (js/probe-source (op :wait-for-js "window.ready"))]
      (is (str/includes? src "[!!v, v]")))))

(deftest the-js-dialect-declines-the-re-frame-vocabulary
  ;; It has no idea what a subscription is, and must say so rather than guess.
  (is (nil? (js/assertion-source (op :expect-sub [:user] "some?"))))
  (is (nil? (js/probe-source (op :wait-for-sub [:user] "some?")))))

(deftest the-probe-vocabulary-reads-through-one-contract
  (let [src (js/assertion-source (op :expect-state ["model" "user" "name"] "v !== null"))]
    (testing "the path is a JSON array whose first segment names the source"
      (is (str/includes? src "read([\"model\",\"user\",\"name\"])")))
    (testing "the predicate sees the read value as v"
      (is (str/includes? src "v !== null")))
    (testing "and a pass reports the value rather than a bare true"
      (is (str/includes? src "? v : false")))))

(deftest the-probe-is-installed-before-the-first-step-not-imported-by-the-app
  ;; The contract the application calls into at startup has to be waiting when
  ;; the first :goto loads it — installed after startup, the app's own
  ;; expose(...) call would already have run against nothing.
  (let [[res driver] (run [[:goto "/"] [:expect-state ["model"] "v"]] (constantly true))]
    (is (r/ok? res))
    (let [[src] (stub/bootstraps driver)]
      (is (some? src) "the run installs the probe itself; the app imports nothing")
      (is (str/includes? src "window.__hive__"))
      (is (str/includes? src "expose:")))))

(deftest a-driver-that-cannot-bootstrap-explains-the-absent-probe
  ;; Not "the value is missing" — the contract was never installed, and only one
  ;; of those is about the application.
  (let [src (js/assertion-source (op :expect-state ["model"] "v"))]
    (is (str/includes? src "if (!window.__hive__) throw new Error"))
    (is (str/includes? src "never installed"))
    (is (str/includes? src ":expect-js")
        "and points at the vocabulary that needs no probe")))

(deftest the-installed-probe-is-the-shipped-resource
  ;; The JS and the expressions that read it are one contract in one namespace;
  ;; a channel inventing its own would drift from the reader silently.
  (let [src (ports/bootstrap-source (page-eval/channel (stub/driver)))]
    (is (= @js/installer src))
    (is (str/includes? src "pushed:") "the Elm push path ships with it")))

(deftest a-driver-that-cannot-bootstrap-does-not-fail-a-run-that-never-reads-state
  ;; driver-without-marking carries no IPageBootstrap. A scenario using only
  ;; browser steps must not be failed by a channel it does not use.
  (let [driver (stub/driver-without-marking)
        deps   {:driver driver :cljs-eval (page-eval/channel driver)}
        p      (plan/build-plan fix/manifest {:id :dom-only :steps [[:goto "/"]]})
        res    (boundary/run-plan! deps (:ok p))]
    (is (r/ok? res))
    (is (verdict/run-ok? (:ok res)))))

(deftest a-path-segment-may-be-a-keyword-or-an-index
  (is (= "[\"model\",\"items\",0,\"id\"]"
         (js/json-path [:model "items" 0 :id]))))

(deftest probe-state-steps-run-end-to-end
  (let [[res driver] (run [[:goto "/"] [:expect-state ["model" "ready"] "v === true"]]
                          (constantly true))]
    (is (r/ok? res))
    (is (verdict/run-ok? (:ok res)))
    (let [[[_ src]] (stub/page-evals driver)]
      (is (str/includes? src "window.__hive__.read")))))

;; =============================================================================
;; The channel's shape is the argument
;; =============================================================================

(deftest the-page-channel-needs-no-pinning-apparatus
  (let [ch (page-eval/channel (stub/driver))]
    (is (ports/cljs-eval? ch))
    (is (ports/session-bound? ch))
    (is (ports/runtime-dialect? ch))
    (testing "and deliberately carries none of the out-of-band capabilities"
      (is (not (ports/runtime-affinity? ch))
          "there is no runtime to choose, so nothing to pin")
      (is (not (ports/runtime-inventory? ch))
          "one session, one page — nothing to enumerate")
      (is (not (ports/runtime-introspection? ch))
          "reading a page is not the power to rewrite an app's handler registry"))))

(deftest binding-a-session-leaves-the-channel-it-was-given-alone
  ;; One channel value serves every run, so binding must not mutate it.
  (let [ch    (page-eval/channel (stub/driver))
        bound (ports/with-session ch {:stub-session 7})]
    (is (not (identical? ch bound)))
    (is (nil? (:session ch)))
    (is (= 7 (get-in bound [:session :stub-session])))))

(deftest an-unbound-channel-says-it-has-no-page
  (let [res (ports/eval-cljs (page-eval/channel (stub/driver)) :app "1")]
    (is (r/err? res))
    (is (= :page-eval/no-session (:error res)))
    (is (str/includes? (:hint res) ":goto")
        "the fix is a navigation step, and the error should say so")))

(deftest a-driver-that-cannot-evaluate-is-a-typed-error
  (let [ch  (ports/with-session (page-eval/channel (stub/driver-without-marking))
                                {:stub-session 1})
        res (ports/eval-cljs ch :app "1")]
    (is (r/err? res))
    (is (= :page-eval/driver-cannot-evaluate (:error res)))))

;; =============================================================================
;; End to end
;; =============================================================================

(deftest a-js-assertion-runs-in-the-page-the-scenario-drives
  (let [[res driver] (run [[:goto "/"] [:expect-js "document.title"]]
                          (constantly "Hello"))]
    (is (r/ok? res))
    (is (verdict/run-ok? (:ok res)))
    (testing "the evaluation reached the page opened by this run"
      (let [[[session src]] (stub/page-evals driver)]
        (is (= 1 session))
        (is (str/includes? src "document.title"))))
    (testing "and no page was stamped: an in-band channel has nothing to pin"
      (is (empty? (stub/marks driver))))))

(deftest a-falsy-javascript-value-fails-the-step
  ;; 0 and "" are failures in JavaScript and must not survive as a pass just
  ;; because they are truthy in Clojure.
  (let [[res _] (run [[:goto "/"] [:expect-js "window.count"]] (constantly false))]
    (is (r/ok? res))
    (is (not (verdict/run-ok? (:ok res))))
    (is (= :fail (:step/state (last (:run/steps (:ok res))))))))

(deftest a-js-condition-wait-polls-until-it-holds
  (let [answers (atom [[false nil] [false nil] [true "ready"]])
        [res _] (run [[:goto "/"] [:wait-for-js "window.ready"]]
                     (fn [_] (let [[a & more] @answers]
                               (when (seq more) (reset! answers (vec more)))
                               a)))]
    (is (r/ok? res))
    (is (verdict/run-ok? (:ok res)))))

(deftest the-re-frame-vocabulary-is-incomplete-on-a-javascript-page
  ;; The whole point of :incomplete: this says nothing about the application,
  ;; and must not read as either a pass or a failure of the app.
  (let [[res _] (run [[:goto "/"] [:expect-sub [:user] "some?"]] (constantly true))
        step    (last (:run/steps (:ok res)))]
    (is (= :incomplete (:step/state step)))
    (is (str/includes? (:step/detail step) ":expect-sub"))))

;; =============================================================================
;; Mounting it
;; =============================================================================

(deftest the-browser-toolchain-is-registered-and-resolves
  (is (contains? (toolchain/registered) :browser))
  (let [res (toolchain/resolve-toolchain :browser)]
    (is (r/ok? res))
    (is (ports/toolchain? (:ok res)))))

(deftest the-browser-toolchain-supervises-no-build-and-says-so
  ;; An Elm or Vite project has no long-lived build server to ask, and a build
  ;; verdict nobody can produce must be an explained absence.
  (let [tc  (:ok (toolchain/resolve-toolchain :browser))
        res (ports/open-build-tool tc {})]
    (is (r/err? res))
    (is (= :build-tool/not-supervised (:error res)))))
