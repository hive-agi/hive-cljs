(ns hive-cljs.derive-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.derive :as d]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Unification
;; =============================================================================

(deftest a-variable-binds-and-a-literal-must-agree
  (is (= '{?x :bo} (d/unify '[:parent :ana ?x] [:parent :ana :bo] {})))
  (is (nil? (d/unify '[:parent :ana ?x] [:parent :cy :bo] {}))))

(deftest a-variable-bound-twice-must-see-the-same-value
  (testing "which is what makes a conjunction a join"
    (is (= '{?x :bo} (d/unify '[:parent :ana ?x] [:parent :ana :bo] '{?x :bo})))
    (is (nil? (d/unify '[:parent :ana ?x] [:parent :ana :bo] '{?x :cy})))))

(deftest a-wildcard-matches-and-binds-nothing
  (is (= {} (d/unify '[:parent _ _] [:parent :ana :bo] {}))))

(deftest arity-has-to-agree
  (is (nil? (d/unify '[:parent ?a ?b] [:parent :ana] {})))
  (is (nil? (d/unify '[:parent ?a] [:parent :ana :bo] {}))))

;; =============================================================================
;; Solving a conjunction
;; =============================================================================

(def family
  #{[:parent :ana :bo]
    [:parent :bo :cy]
    [:parent :dee :cy]})

(deftest a-conjunction-joins-on-its-shared-variables
  (let [sols (d/solve '[[:parent ?a ?b] [:parent ?b ?c]] family)]
    (is (= 1 (count sols)))
    (is (= '{?a :ana ?b :bo ?c :cy} (:bindings (first sols))))
    (is (= 2 (count (:premises (first sols)))))))

(deftest a-conjunction-that-matches-nothing-yields-nothing
  (is (empty? (d/solve '[[:parent ?a :nobody]] family))))

(deftest an-empty-conjunction-is-satisfied-once
  (testing "so a rule with no premises fires exactly once, not never"
    (is (= 1 (count (d/solve [] family))))))

;; =============================================================================
;; Rules and the fixpoint
;; =============================================================================

(def ancestor-base
  (d/rule :ancestor/base "A parent is an ancestor."
          '[[:parent ?a ?b]]
          (fn [{:syms [?a ?b]}] [:ancestor ?a ?b])))

(def ancestor-step
  (d/rule :ancestor/step "Ancestry is transitive."
          '[[:ancestor ?a ?b] [:ancestor ?b ?c]]
          (fn [{:syms [?a ?c]}] [:ancestor ?a ?c])))

(deftest a-fixpoint-closes-a-transitive-relation
  (let [fx (d/run [ancestor-base ancestor-step] family)]
    (is (contains? (:facts fx) [:ancestor :ana :cy]))
    (is (contains? (:facts fx) [:ancestor :ana :bo]))
    (is (contains? (:facts fx) [:ancestor :dee :cy]))))

(deftest a-fixpoint-separates-what-was-asserted-from-what-was-derived
  (let [fx (d/run [ancestor-base ancestor-step] family)]
    (is (= family (into #{} (remove (:derived fx)) (:facts fx))))
    (is (not-any? #(= :parent (first %)) (:derived fx)))))

(deftest a-rule-may-derive-several-facts-at-once
  (let [fx (d/run [(d/rule :both "" '[[:parent ?a ?b]]
                           (fn [{:syms [?a ?b]}]
                             [[:child ?b ?a] [:has-child ?a]]))]
                  family)]
    (is (contains? (:facts fx) [:child :bo :ana]))
    (is (contains? (:facts fx) [:has-child :ana]))))

(deftest a-rule-that-derives-nil-derives-nothing
  (testing "which is how a rule declines a match its guard could not express"
    (let [fx (d/run [(d/rule :none "" '[[:parent ?a ?b]] (fn [_] nil))] family)]
      (is (empty? (:derived fx))))))

(deftest a-guard-is-the-arithmetic-a-pattern-cannot-state
  (let [facts #{[:size :small 10] [:size :big 100]}
        fx (d/run [(d/rule :too-big "" '[[:size ?id ?px]]
                           (fn [{:syms [?id]}] [:oversized ?id])
                           (fn [{:syms [?px]}] (> ?px 50)))]
                  facts)]
    (is (= #{[:oversized :big]} (:derived fx)))))

(deftest a-fixpoint-is-idempotent
  (let [once (d/run [ancestor-base ancestor-step] family)]
    (is (= (:facts once) (:facts (d/run [ancestor-base ancestor-step] (:facts once)))))))

(deftest a-non-terminating-rule-set-stops-loudly
  (testing "an unbounded arithmetic derivation is the usual culprit"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no fixpoint"
         (d/run [(d/rule :forever "" '[[:n ?x]]
                         (fn [{:syms [?x]}] [:n (inc ?x)]))]
                #{[:n 0]}
                {:max-rounds 5})))))

;; =============================================================================
;; Provenance
;; =============================================================================

(deftest a-derived-fact-names-the-rule-that-produced-it
  (let [fx (d/run [ancestor-base ancestor-step] family)
        {:rule/keys [id] :keys [premises]} (d/why fx [:ancestor :ana :cy])]
    (is (= :ancestor/step id))
    (is (= #{[:ancestor :ana :bo] [:ancestor :bo :cy]} (set premises)))))

(deftest an-asserted-fact-has-no-derivation-and-says-so
  (testing "which is not the same as failing to find one"
    (let [fx (d/run [ancestor-base ancestor-step] family)]
      (is (nil? (d/why fx [:parent :ana :bo])))
      (is (str/includes? (d/explain fx [:parent :ana :bo]) "asserted")))))

(deftest a-trace-walks-back-to-the-asserted-facts
  (let [fx (d/run [ancestor-base ancestor-step] family)
        steps (d/trace fx [:ancestor :ana :cy])]
    (is (= 3 (count steps)) "the conclusion and the two base facts under it")
    (is (= #{:ancestor/step :ancestor/base} (set (map :rule/id steps))))))

(deftest a-trace-terminates-on-a-cyclic-derivation
  (let [fx (d/run [ancestor-base ancestor-step] #{[:parent :a :b] [:parent :b :a]})]
    (is (seq (d/trace fx [:ancestor :a :a])))))

;; =============================================================================
;; Querying
;; =============================================================================

(deftest a-query-reads-bindings-off-the-fixpoint
  (let [fx (d/run [ancestor-base ancestor-step] family)]
    (is (= #{:bo :cy}
           (into #{} (map '?x) (d/query fx '[[:ancestor :ana ?x]]))))))

(deftest facts-matching-reads-whole-facts
  (let [fx (d/run [ancestor-base ancestor-step] family)]
    (is (contains? (d/facts-matching fx '[:ancestor :ana _]) [:ancestor :ana :cy]))
    (is (not-any? #(= :parent (first %)) (d/facts-matching fx '[:ancestor _ _])))))

;; =============================================================================
;; Strata
;; =============================================================================

(deftest a-later-stratum-sees-what-an-earlier-one-derived
  (let [fx (d/run-strata
            [{:rules [ancestor-base]
              :assert (fn [fx] [[:generation (count (d/facts-matching fx '[:ancestor _ _]))]])}
             {:rules [(d/rule :big "" '[[:generation ?n]]
                              (fn [{:syms [?n]}] [:many ?n])
                              (fn [{:syms [?n]}] (> ?n 2)))]}]
            family)]
    (is (contains? (:facts fx) [:generation 3]))
    (is (contains? (:facts fx) [:many 3]))))

(deftest an-aggregate-can-carry-the-premises-it-was-computed-from
  (testing "otherwise the aggregation is where a provenance chain goes dead"
    (let [fx (d/run-strata
              [{:rules [ancestor-base]
                :assert (fn [fx]
                          (let [as (d/facts-matching fx '[:ancestor _ _])]
                            {[:count (count as)] (vec as)}))}]
              family)
          {:rule/keys [id] :keys [premises]} (d/why fx [:count 3])]
      (is (= :stratum/aggregate id))
      (is (= 3 (count premises)))
      (testing "so a trace walks straight through it to the asserted facts"
        (is (contains? (set (map :rule/id (d/trace fx [:count 3])))
                       :ancestor/base))))))

(deftest a-plain-collection-of-asserted-facts-still-works
  (let [fx (d/run-strata [{:rules [] :assert (fn [_] [[:extra 1]])}] #{})]
    (is (contains? (:facts fx) [:extra 1]))
    (is (nil? (d/why fx [:extra 1])))))
