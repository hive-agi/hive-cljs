(ns hive-cljs.step-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.step :as step]
            [hive-cljs.ports :as ports]
            [hive-cljs.stub.ports :as stub]
            [hive-dsl.result :as r]
            [malli.core :as m]
            [hive-cljs.schema :as s]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(deftest compiles-to-conforming-ops
  (doseq [step [[:goto "/x"] [:click "#a"] [:fill "#a" "v"] [:expect-text "#a" "t"]
                [:expect-count "#a" 3] [:screenshot "shot"] [:wait-ms 10]
                [:eval-cljs "(+ 1 2)"] [:dispatch [:evt]] [:expect-sub [:q] 'some?]]]
    (testing (str step)
      (let [res (step/compile-step step)]
        (is (r/ok? res))
        (is (m/validate s/Op (:ok res)) (pr-str (m/explain s/Op (:ok res))))))))

(deftest channel-routing
  (testing "DOM steps go to the browser"
    (is (= :browser (step/channel-of [:click "#a"])))
    (is (= :browser (step/channel-of [:expect-text "#a" "t"]))))
  (testing "runtime assertions bypass the browser"
    (is (= :runtime (step/channel-of [:expect-sub [:q] 'some?])))
    (is (= :runtime (step/channel-of [:expect-db [:path] 'map?])))
    (is (= :runtime (step/channel-of [:dispatch [:evt]])))
    (is (= :runtime (step/channel-of [:eval-cljs "(+ 1 2)"])))))

(deftest a-fit-question-is-a-runtime-assertion-carrying-only-a-selector
  (testing "it compiles, conforms, and is evaluated in the page"
    (let [res (step/compile-step [:expect-fits ".memorial-message-choice"])]
      (is (r/ok? res))
      (is (m/validate s/Op (:ok res)) (pr-str (m/explain s/Op (:ok res))))
      (is (= :runtime (:op/channel (:ok res))))))

  (testing "the selector is the whole argument"
    (is (= :step/malformed (:error (step/compile-step [:expect-fits]))))
    (is (= :step/malformed (:error (step/compile-step [:expect-fits ".a" ".b"])))))

  (testing "a falsy answer fails the step rather than being merely reported"
    (is (contains? step/assertion-kinds :expect-fits))
    (is (not (contains? step/poll-kinds :expect-fits))
        "it asserts once; there is no :wait-for-fits")))

(deftest an-attribute-question-is-a-browser-step-not-a-javascript-blob
  ;; The last common DOM question with no step of its own, so every manifest
  ;; that asked it reached for :expect-js and a querySelector expression.
  (testing "it compiles onto the DOM channel"
    (let [res (step/compile-step [:expect-attr "#menu" "aria-expanded" "true"])]
      (is (r/ok? res))
      (is (m/validate s/Op (:ok res)) (pr-str (m/explain s/Op (:ok res))))
      (is (= :browser (:op/channel (:ok res))))))

  (testing "selector, attribute, value: all three are required"
    (is (= :step/malformed (:error (step/compile-step [:expect-attr "#a" "href"]))))
    (is (= :step/malformed (:error (step/compile-step [:expect-attr "#a"])))))

  (testing "it only observes, so an app-db invariant need not be re-asserted after it"
    (is (contains? boundary/read-only-kinds :expect-attr))))

(deftest malformed-steps-are-typed-errors
  (is (= :step/not-a-vector (:error (step/compile-step {:kind :goto}))))
  (is (= :step/no-kind (:error (step/compile-step ["goto" "/x"]))))
  (is (= :step/unknown-kind (:error (step/compile-step [:teleport "/x"]))))
  (let [e (step/compile-step [:fill "#a"])]
    (is (= :step/malformed (:error e)))
    (is (= 2 (:expected-arity e)))
    (is (= 1 (:got-arity e)))))

(deftest compile-steps-short-circuits-with-index
  (let [res (step/compile-steps [[:goto "/x"] [:click "#a"] [:fill "#a"]])]
    (is (r/err? res))
    (is (= 2 (:index res)))))

(deftest rule-order-first-match-wins
  (testing "an earlier rule shadows a later one for the same kind"
    (let [override (reify step/IStepRule
                     (rule-id [_] :goto)
                     (applies? [_ st] (= :goto (first st)))
                     (compile-op [_ st] (r/ok {:op/kind :goto :op/channel :runtime
                                               :op/args (vec (rest st)) :op/source (vec st)})))
          rules    (into [override] step/default-rules)]
      (is (= :runtime (:op/channel (:ok (step/compile-step rules [:goto "/x"])))))
      (is (= :browser (:op/channel (:ok (step/compile-step step/default-rules [:goto "/x"]))))))))

(deftest ocp-new-kind-needs-no-edit-to-the-folder
  (let [swipe (reify step/IStepRule
                (rule-id [_] :swipe)
                (applies? [_ st] (= :swipe (first st)))
                (compile-op [_ st] (r/ok {:op/kind :swipe :op/channel :browser
                                          :op/args (vec (rest st)) :op/source (vec st)})))
        rules (conj step/default-rules swipe)]
    (is (= :step/unknown-kind (:error (step/compile-step [:swipe "#a" :left]))))
    (is (r/ok? (step/compile-step rules [:swipe "#a" :left])))
    (is (contains? (set (step/known-kinds rules)) :swipe))))

(deftest a-form-valued-runtime-step-reaches-the-channel-as-pinned-source
  ;; The manifest is EDN, so :eval-cljs and the :expect-* predicates take a
  ;; FORM as readily as a string. The form is printed where it crosses into
  ;; the runtime, under pinned printer vars, so the source sent does not
  ;; depend on the bindings of whichever thread runs the scenario.
  (let [steps [[:eval-cljs '(when-not (= :ok (:state (plato.fit/verdict-for "welcome")))
                              (throw (ex-info "nope" {:app/why [1 2 3 4]})))]
               [:expect-sub [:user/current] '(fn [v] (= v "pedro"))]
               [:expect-db [:user :name] 'string?]
               [:wait-for-db [:items] 'seq]]
        sent  (fn []
                (let [ce (stub/cljs-eval (fn [_ src]
                                           (if (.startsWith ^String src "(let [v ")
                                             [true 1]
                                             true)))]
                  (doseq [st steps]
                    (let [res (step/compile-step st)]
                      (is (r/ok? res) (pr-str st))
                      (is (m/validate s/Op (:ok res)))
                      (is (= :pass (:state (boundary/perform-runtime!
                                            ce :app (:ok res)
                                            {:timeout-ms 100 :poll-ms 50}))))))
                  (mapv second (stub/evals ce))))
        base  (sent)]
    (is (= (str "(when-not (= :ok (:state (plato.fit/verdict-for \"welcome\")))"
                " (throw (ex-info \"nope\" {:app/why [1 2 3 4]})))")
           (first base)))
    (is (= "((fn [v] (= v \"pedro\")) (deref (re-frame.core/subscribe [:user/current])))"
           (second base)))
    (doseq [ns-maps [true false] length [nil 1] level [nil 1]
            meta? [true false] readably [true false]]
      (binding [*print-namespace-maps* ns-maps *print-length* length
                *print-level* level *print-meta* meta? *print-readably* readably]
        (is (= base (sent)))))
    (testing "a string step still passes through verbatim"
      (let [ce (stub/cljs-eval)]
        (boundary/perform-runtime! ce :app (:ok (step/compile-step [:eval-cljs "#(inc %)"])))
        (is (= ["#(inc %)"] (mapv second (stub/evals ce))))))))

(deftest expect-no-errors-is-authored-as-data
  (testing "bare, and with data options, it compiles to a browser assertion"
    (is (= :browser (:op/channel (:ok (step/compile-step [:expect-no-errors])))))
    (is (r/ok? (step/compile-step [:expect-no-errors {:ignore ["favicon"]
                                                      :sources #{:pageerror}}]))))
  (testing "JS text is not an option map, and unknown keys are refused"
    (is (r/err? (step/compile-step [:expect-no-errors "console.error.length === 0"])))
    (is (r/err? (step/compile-step [:expect-no-errors {:js "x"}])))
    (is (r/err? (step/compile-step [:expect-no-errors {} {}])))))
