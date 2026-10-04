(ns hive-cljs.walk-test
  "Generative walks against stub runners — no browser."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-cljs.fixtures :as fix]
            [hive-cljs.stub.ports :as stub]
            [hive-cljs.verdict :as verdict]
            [hive-cljs.walk :as walk]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def cart
  "A page that must be visited before adding, and items that must exist
   before deleting."
  {:init       {:page nil :items 0}
   :max-length 12
   :alphabet
   [{:id :visit :gen (gen/elements [[:goto "/a"] [:goto "/b"]])
     :next (fn [m [_ url]] (assoc m :page url))}
    {:id :add :step [:click "#add"]
     :pre  #(= "/a" (:page %))
     :next (fn [m _] (update m :items inc))}
    {:id :del :step [:click "#del"]
     :pre  #(pos? (:items %))
     :next (fn [m _] (update m :items dec))}]})

(defn- legal?
  "Independent replay of `cart`'s rules over a plain step vector."
  [steps]
  (boolean
   (reduce (fn [{:keys [page items] :as m} step]
             (case step
               [:goto "/a"]    (assoc m :page "/a")
               [:goto "/b"]    (assoc m :page "/b")
               [:click "#add"] (if (= "/a" page) (update m :items inc) (reduced false))
               [:click "#del"] (if (pos? items) (update m :items dec) (reduced false))
               (reduced false)))
           {:page nil :items 0}
           steps)))

(defn- buggy-runner
  "Fails when an item is deleted while two exist."
  [steps]
  (not (:bad (reduce (fn [{:keys [n] :as m} step]
                       (case step
                         [:click "#add"] (update m :n inc)
                         [:click "#del"] (cond-> (update m :n dec) (= 2 n) (assoc :bad true))
                         m))
                     {:n 0}
                     steps))))

;; =============================================================================
;; Generation
;; =============================================================================

(defspec every-generated-walk-respects-preconditions 200
  (prop/for-all [steps (walk/walk-gen cart)]
    (and (legal? steps)
         (<= 1 (count steps) 12))))

(deftest a-seed-reproduces-the-same-walk
  (is (= (walk/sample-walk cart 20 42) (walk/sample-walk cart 20 42))))

(deftest a-template-without-an-enabled-predecessor-is-never-chosen
  (testing "deletion is impossible until something was added, so a walk never opens with it"
    (doseq [seed (range 50)]
      (let [steps (walk/sample-walk cart 15 seed)]
        (is (not= [:click "#del"] (first steps)))
        (is (not= [:click "#add"] (first steps)))))))

(deftest a-walk-stops-early-when-nothing-is-enabled
  (let [once {:init 0 :max-length 10
              :alphabet [{:id :only :step [:click "#x"] :pre zero? :next (fn [m _] (inc m))}]}]
    (is (= [[:click "#x"]] (walk/sample-walk once 30 1)))))

(deftest a-gen-may-depend-on-the-model
  (let [spec {:init 0 :max-length 6
              :alphabet [{:id :n :gen (fn [m] (gen/return [:wait-ms (inc m)]))
                          :next (fn [m _] (inc m))}]}]
    (is (= [[:wait-ms 1] [:wait-ms 2] [:wait-ms 3]]
           (take 3 (walk/sample-walk spec 6 3))))))

(deftest valid-pins-a-generated-argument-to-the-model
  (let [spec {:init #{} :max-length 8
              :alphabet [{:id :open :gen (gen/elements [[:goto "/a"] [:goto "/b"]])
                          :valid? (fn [seen [_ u]] (not (seen u)))
                          :next (fn [seen [_ u]] (conj seen u))}]}]
    (doseq [seed (range 30)]
      (let [steps (walk/sample-walk spec 10 seed)]
        (is (apply distinct? steps))))))

(deftest a-malformed-spec-is-refused
  (is (= :walk/malformed (:error (walk/validate-spec {:alphabet []}))))
  (is (= :walk/malformed (:error (walk/validate-spec
                                  {:alphabet [{:id :a :step [:click "#a"]}
                                              {:id :a :step [:click "#b"]}]}))))
  (is (= :walk/malformed (:error (walk/validate-spec
                                  {:alphabet [{:id :a}]}))))
  (is (= :walk/malformed (:error (walk/validate-spec
                                  {:min-length 5 :max-length 2
                                   :alphabet [{:id :a :step [:click "#a"]}]}))))
  (is (thrown? clojure.lang.ExceptionInfo (walk/walk-gen {:alphabet []}))))

;; =============================================================================
;; Shrinking
;; =============================================================================

(deftest a-failing-walk-shrinks-to-the-minimal-legal-repro
  (let [res (walk/check cart buggy-runner {:num-tests 300 :seed 7})]
    (is (false? (:walk/pass? res)))
    (is (= 7 (:walk/seed res)))
    (is (= [[:goto "/a"] [:click "#add"] [:click "#add"] [:click "#del"]]
           (:walk/smallest res)))
    (is (legal? (:walk/smallest res)) "the shrunk walk still satisfies every precondition")
    (is (<= (count (:walk/smallest res)) (count (:walk/failing res))))
    (is (re-find #"shrunk \d+ steps to 4" (walk/explain res)))))

(deftest the-same-seed-shrinks-to-the-same-walk
  (is (= (select-keys (walk/check cart buggy-runner {:num-tests 300 :seed 11})
                      [:walk/failing :walk/smallest])
         (select-keys (walk/check cart buggy-runner {:num-tests 300 :seed 11})
                      [:walk/failing :walk/smallest]))))

(defspec shrinking-never-leaves-the-legal-walks 20
  (prop/for-all [seed gen/nat]
    (let [res (walk/check cart buggy-runner {:num-tests 100 :seed seed})]
      (or (:walk/pass? res)
          (and (legal? (:walk/smallest res))
               (= 4 (count (:walk/smallest res))))))))

(deftest a-green-runner-passes
  (let [res (walk/check cart (constantly true) {:num-tests 25 :seed 3})]
    (is (true? (:walk/pass? res)))
    (is (= 25 (:walk/num-tests res)))
    (is (re-find #"25 walks green" (walk/explain res)))))

(deftest a-run-error-is-a-failure-carrying-the-error
  (let [res (walk/check cart (fn [_] (r/err :run/no-driver {})) {:num-tests 5 :seed 1})]
    (is (false? (:walk/pass? res)))
    (is (= :run/no-driver (get-in res [:walk/error :error])))
    (is (= 1 (count (:walk/smallest res))))))

;; =============================================================================
;; Through the scenario boundary
;; =============================================================================

(defn- fail-del-with-two
  "Driver outcome fn: the page breaks when #del is clicked while two items exist."
  []
  (let [n (atom 0)]
    (fn [_ op]
      (let [sel (first (:op/args op))]
        (cond
          (and (= :click (:op/kind op)) (= "#add" sel)) (do (swap! n inc) {:state :pass})
          (and (= :click (:op/kind op)) (= "#del" sel))
          (let [before @n]
            (swap! n dec)
            (if (= 2 before) {:state :fail :detail "corrupted"} {:state :pass}))
          :else {:state :pass})))))

(deftest plan-runner-shrinks-a-walk-through-the-real-boundary
  (let [run   (fn [steps]
                ;; a fresh page per walk, as a fresh session would be
                (let [deps {:driver (stub/driver (fail-del-with-two))
                            :cljs-eval (stub/cljs-eval)}]
                  ((walk/plan-runner deps fix/manifest {:id :walk :build :app}) steps)))
        res   (walk/check cart run {:num-tests 300 :seed 7})
        rep   (:walk/report res)]
    (is (false? (:walk/pass? res)))
    (is (= [[:goto "/a"] [:click "#add"] [:click "#add"] [:click "#del"]]
           (:walk/smallest res)))
    (is (= :walk (:run/scenario rep)))
    (is (= :fail (:run/state rep)))
    (is (= "corrupted" (:step/detail (last (:run/steps rep)))))
    (is (re-find #"walk: fail" (walk/explain res)))))

(deftest plan-runner-passes-a-green-walk
  (let [deps {:driver (stub/driver) :cljs-eval (stub/cljs-eval)}
        res  ((walk/plan-runner deps fix/manifest) [[:goto "/a"] [:click "#add"]])]
    (is (r/ok? res))
    (is (verdict/run-ok? (:ok res)))))
