(ns hive-cljs.probe-dsl-test
  "Runtime probes authored as DATA rather than as JavaScript string blobs.

   The claims: a probe form renders to one JS expression the existing
   `:*-js` / `:*-state` kinds already evaluate; a string stays the verbatim
   escape hatch; and a form outside the language fails the PLAN, before a
   browser opens, instead of reaching the page as a ReferenceError."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.dialect.js :as js]
            [hive-cljs.dialect.probe :as probe]
            [hive-cljs.dialect.re-frame :as re-frame]
            [hive-cljs.step :as step]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- op [kind & args]
  {:op/kind kind :op/channel :runtime :op/args (vec args)})

(deftest a-dom-count-probe-reads-as-the-question-it-asks
  (is (= "(1 === ((document)?.querySelectorAll(\".fragment.visible\").length ?? 0))"
         (probe/->js '(= 1 (dom/count ".fragment.visible"))))))

(deftest core-forms-render-with-clojure-meaning
  (testing "a two-operand comparison is infix, = is strict"
    (is (= "(1 < 2)" (probe/->js '(< 1 2))))
    (is (= "(\"a\" === \"b\")" (probe/->js '(= "a" "b")))))
  (testing "an n-ary comparison is a chain, not JS's left fold"
    (is (str/includes? (probe/->js '(< 1 2 3)) "xs[i - 1] < x")))
  (testing "get-in is nil-safe at every segment"
    (is (= "(window)?.[\"app\"]?.[0]" (probe/->js '(get-in js/window [:app 0])))))
  (testing "interop reaches page objects"
    (is (= "(window).__timelines" (probe/->js '(.-__timelines js/window))))
    (is (= "(window).scrollTo(0, 0)" (probe/->js '(.scrollTo js/window 0 0)))))
  (testing "let binds fresh locals, so shadowing stays Clojure's"
    (let [src (probe/->js '(let [x 1 x (inc x)] x))]
      (is (re-find #"const x_1 = 1; const x_2 = \(x_1 \+ 1\); return x_2;" src)))))

(deftest a-known-function-can-be-passed-by-name
  (is (str/includes? (probe/->js '(every? dom/visible? (dom/all ".slide")))
                     ".every((x_1) => (((x_2) => "))
  (testing "and the callback sees the element alone, never JS's (index, array)"
    (is (= "Array.from(([\"1\"]) ?? []).map((x_1) => (parseInt)(x_1))"
           (probe/->js '(map js/parseInt ["1"]))))))

(deftest a-form-outside-the-language-is-named-not-guessed
  (is (str/includes? (probe/problem '(frobnicate 1)) "unknown function frobnicate"))
  (is (str/includes? (probe/problem 'app-state) "unknown symbol app-state"))
  (is (str/includes? (probe/problem '(.-bad-prop js/window)) "bad property"))
  (is (str/includes? (probe/problem '(get-in js/window path)) "literal path"))
  (is (nil? (probe/problem '(dom/count "li")))))

(deftest a-string-literal-cannot-smuggle-source-into-the-page
  (is (= "(document)?.querySelector(\"a\\\"); evil(); (\\\"\")"
         (probe/->js '(dom/one "a\"); evil(); (\"")))))

;; =============================================================================
;; Through the JS dialect
;; =============================================================================

(deftest the-js-kinds-take-a-form-and-a-string-alike
  (testing "a string is JavaScript already and passes through verbatim"
    (is (= "window.app.reset()" (js/assertion-source (op :eval-js "window.app.reset()")))))
  (testing "a bare dotted symbol is still its own name"
    (is (= "window.app.ready" (js/assertion-source (op :eval-js 'window.app.ready)))))
  (testing "a form is rendered by the probe language"
    (is (= (js/truthy-value "(2 === ((document)?.querySelectorAll(\"li\").length ?? 0))")
           (js/assertion-source (op :expect-js '(= 2 (dom/count "li"))))))
    (is (= (js/truthy-probe "(2 === ((document)?.querySelectorAll(\"li\").length ?? 0))")
           (js/probe-source (op :wait-for-js '(= 2 (dom/count "li"))))))))

(deftest a-state-predicate-form-sees-the-read-value-as-v
  (let [src (js/assertion-source (op :expect-state ["model" "loading"] '(= v false)))]
    (is (str/includes? src "return ((v === false)) ? v : false;")))
  (testing "the string spelling it replaces renders the same check"
    (is (str/includes? (js/probe-source (op :wait-for-state ["m"] "v === false"))
                       "[!!(v === false), v]"))))

(deftest a-clojurescript-runtime-evaluates-the-same-rendered-form
  (is (= (str "(js->clj (js/eval "
              (pr-str (js/truthy-value "(1 === ((document)?.querySelectorAll(\"li\").length ?? 0))"))
              "))")
         (re-frame/assertion-source (op :expect-js '(= 1 (dom/count "li")))))))

;; =============================================================================
;; Plan time
;; =============================================================================

(deftest an-unrenderable-probe-fails-the-plan-not-the-run
  (let [res (step/compile-step [:expect-js '(= 1 (dom/cuont "li"))])]
    (is (r/err? res))
    (is (= :step/malformed (:error res)))
    (is (str/includes? (:probe res) "dom/cuont")))
  (testing "a state predicate may name v, and nothing else unbound"
    (is (r/ok? (step/compile-step [:wait-for-state ["model"] '(some? v)])))
    (is (r/err? (step/compile-step [:wait-for-state ["model"] '(some? w)]))))
  (testing "strings and bare names are never second-guessed"
    (is (r/ok? (step/compile-step [:expect-js "anything goes ((("])))
    (is (r/ok? (step/compile-step [:eval-js 'window.app.reset])))
    (is (r/ok? (step/compile-step [:expect-state ["m"] "v !== null"])))))

(deftest a-state-predicate-given-as-a-function-is-applied-not-tested
  ;; A function value is always truthy: rendering `some?` as one would pass
  ;; every assertion. It is applied to the read value instead.
  (is (str/includes? (js/assertion-source (op :expect-state ["m"] 'some?))
                     "return ((v != null)) ? v : false;"))
  (is (str/includes? (js/probe-source (op :wait-for-state ["m"] '(fn [x] (> x 2))))
                     "[!!((((x_1) => (x_1 > 2)))(v)), v]")))
