(ns hive-cljs.locator-step-test
  "The data step vocabulary that replaces :eval-js / :wait-for-js: input steps,
   locator predicates and the conditional `:when`. Compiled purely, judged
   purely, and run through the stub driver port."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.locator :as locator]
            [hive-cljs.schema :as s]
            [hive-cljs.step :as step]
            [hive-cljs.stub.ports :as stub]
            [hive-dsl.result :as r]
            [hive-schemas.test :as ht]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def owner-scenario
  "The consumer scenario from the card, rewritten with no JavaScript."
  [[:goto "/"]
   [:wait-for ".member-bar form.owner, .member-bar select" {:state :attached}]
   [:when [:exists ".member-bar form.owner input"]
    [:fill ".member-bar form.owner input" "Dono E2E"]
    [:wait-for ".member-bar form.owner button[type=submit]" {:state :enabled}]
    [:submit ".member-bar form.owner"]]
   [:select ".member-bar select" :first-non-empty]
   [:wait-for ".member-bar select" {:value :non-empty}]
   [:expect ".member-bar form.owner" {:absent true}]])

(deftest the-card-scenario-compiles-without-js
  (let [res (step/compile-steps owner-scenario)]
    (is (r/ok? res) (pr-str res))
    (is (every? #(m/validate s/Op %) (:ok res)))
    (is (every? #(= :browser (:op/channel %)) (:ok res)))
    (is (empty? (step/lint-steps owner-scenario)))))

(deftest new-steps-compile-to-browser-ops
  (testing "a predicate travels on the op as data"
    (is (= ["#a" {:state :enabled}]
           (:op/args (:ok (step/compile-step [:wait-for "#a" {:state :enabled}]))))))
  (testing "a bare :wait-for keeps its old shape"
    (is (= ["#a"] (:op/args (:ok (step/compile-step [:wait-for "#a"]))))))
  (testing "selector data compiles inside :when, test and body alike"
    (let [op (:ok (step/compile-step [:when [:exists :form.owner] [:click :button#go]]))]
      (is (= [:exists "form.owner"] (first (:op/args op))))
      (is (= [:click] (mapv :op/kind (second (:op/args op)))))
      (is (= ["button#go"] (:op/args (first (second (:op/args op))))))))
  (testing "malformed steps fail the plan"
    (doseq [bad [[:wait-for "#a" {:state :shiny}]
                 [:wait-for "#a" {}]
                 [:expect "#a"]
                 [:submit]
                 [:when [:maybe "#a"] [:click "#a"]]
                 [:when [:exists "#a"]]
                 [:when [:exists "#a"] [:eval-js "1"]]
                 [:when [:exists "#a"] [:nope]]]]
      (is (#{:step/malformed :step/unknown-kind} (:error (step/compile-step bad)))
          (pr-str bad)))))

(deftest a-third-party-kind-works-inside-when
  (let [swipe (reify step/IStepRule
                (rule-id [_] :swipe)
                (applies? [_ s] (= :swipe (first s)))
                (compile-op [_ s] (r/ok {:op/kind :swipe :op/channel :browser
                                         :op/args (vec (rest s)) :op/source s})))
        rules (conj step/default-rules swipe)]
    (is (r/ok? (step/compile-step rules [:when [:exists "#a"] [:swipe :left]])))
    (is (r/err? (step/compile-step [:when [:exists "#a"] [:swipe :left]])))))

(deftest judge-reads-facts
  (is (:held? (locator/judge {:state :enabled} {:count 1 :enabled true})))
  (is (not (:held? (locator/judge {:state :enabled} {:count 0}))))
  (is (:held? (locator/judge {:state :hidden} {:count 0})))
  (is (:held? (locator/judge {:value :non-empty} {:count 1 :value "x"})))
  (is (not (:held? (locator/judge {:value :non-empty} {:count 1 :value ""}))))
  (is (:held? (locator/judge {:count 0 :absent true} {:count 0})))
  (is (:held? (locator/judge {:text #"Do+no"} {:count 1 :text "Dooono"})))
  (is (:held? (locator/judge {:text "ola"} {:count 1 :text "hola"})))
  (let [v (locator/judge {:state :visible :value "a"} {:count 1 :visible true :value "b"})]
    (is (not (:held? v)))
    (is (= "value \"a\", saw \"b\"" (:detail v)))))

(deftest branch-and-option-choice
  (let [op (:ok (step/compile-step [:when [:absent "#x"] [:click "#y"]]))]
    (is (= [:click] (mapv :op/kind (locator/branch-ops op 0))))
    (is (= [] (locator/branch-ops op 2))))
  (is (= "2" (locator/pick-option :first-non-empty ["" " " "2" "3"])))
  (is (nil? (locator/pick-option :first-non-empty ["" ""])))
  (is (= "BR" (locator/pick-option "BR" ["" "BR"]))))

(deftest lint-points-js-to-data-steps
  (let [ws (step/lint-steps [[:goto "/"] [:eval-js "1"]
                             [:when [:exists "#a"] [:wait-for-js "true"]]])]
    (is (= [[1 :eval-js] [2 :wait-for-js]] (mapv (juxt :index :kind) ws)))
    (is (every? #(= :step/js-escape-hatch (:warning %)) ws))))

(deftest the-stub-driver-receives-data-not-js
  (let [driver (stub/driver)
        plan   {:plan/scenario :owner :plan/base-url "http://app.test"
                :plan/session {} :plan/runtime {}
                :plan/ops (:ok (step/compile-steps owner-scenario))}
        rep    (boundary/run-plan! {:driver driver} plan)]
    (is (= :pass (get-in rep [:ok :run/state])) (pr-str rep))
    (is (= [:goto :wait-for :when :select :wait-for :expect]
           (mapv :op/kind (stub/performed-ops driver))))
    (is (= [".member-bar select" {:value :non-empty}]
           (:op/args (nth (stub/performed-ops driver) 4))))
    (is (not-any? #(some (fn [w] (str/includes? (pr-str %) w)) ["document" "dispatchEvent"])
                  (stub/performed-ops driver)))))

(ht/deftrifecta-from-schema judge
  hive-cljs.locator/judge
  {:in  [:cat s/LocatorPredicateData locator/Facts]
   :out locator/Verdict
   :rel (fn [[pred facts] out]
          (if (contains? pred :absent)
            (or (not (:held? out)) (= (:absent pred) (zero? (:count facts))))
            true))
   :num-tests 200})

(ht/deftrifecta-from-schema facts-needed
  hive-cljs.locator/facts-needed
  {:in  s/LocatorPredicateData
   :out [:set :keyword]
   :rel (fn [pred out] (and (contains? out :count)
                            (= (contains? pred :value) (contains? out :value))
                            (= (contains? pred :text) (contains? out :text))))
   :mutation false
   :num-tests 200})
