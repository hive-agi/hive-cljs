(ns hive-cljs.step-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.step :as step]
            [hive-cljs.ports :as ports]
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
