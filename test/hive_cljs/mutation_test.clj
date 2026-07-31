(ns hive-cljs.mutation-test
  "Behavioural mutation: break the live app on purpose and check the suite
   notices. The verdict is inverted — a green run under a fault is the failure."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.mutation :as mutation]
            [hive-cljs.plan :as plan]
            [hive-cljs.schema :as s]
            [hive-cljs.stub.ports :as stub]
            [hive-dsl.result :as r]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Catalog
;; =============================================================================

(deftest a-target-and-a-replacement-become-a-set-form
  (let [f (mutation/normalize-fault {:id :status-hole
                                     :target 'app.view-model/derive-status
                                     :with "(constantly nil)"})]
    (is (m/validate s/Fault f) (pr-str (m/explain s/Fault f)))
    (is (= "(set! app.view-model/derive-status (constantly nil))" (:fault/form f)))
    (is (= 'app.view-model/derive-status (:fault/target f)))))

(deftest raw-source-is-taken-as-authored
  (let [f (mutation/normalize-fault {:id :sub-hole :form "(reg-sub :x (fn [_ _] nil))"})]
    (is (= "(reg-sub :x (fn [_ _] nil))" (:fault/form f)))
    (is (nil? (:fault/target f)))))

(deftest an-id-is-derived-from-the-target-when-none-is-given
  (is (= :app.view-model.derive-status
         (:fault/id (mutation/normalize-fault {:target 'app.view-model/derive-status
                                               :with "(constantly nil)"})))))

(deftest a-fault-that-names-nothing-to-break-is-dropped
  (is (nil? (mutation/normalize-fault {:id :empty})))
  (is (nil? (mutation/normalize-fault {:target 'a/b})))
  (is (nil? (mutation/normalize-fault {:with "(constantly nil)"})))
  (testing "and the drop does not take the usable ones with it"
    (is (= [:good]
           (mapv :fault/id
                 (mutation/normalize-faults [{:id :bad}
                                             {:id :good :form "(set! a/b nil)"}]))))))

(deftest declared-faults-reach-the-normalized-manifest
  (let [m (:ok (manifest/parse
                {:hive.cljs/builds {:app {:http-port 8280}}
                 :hive.cljs/e2e    {:faults [{:id :hole :target 'a/b :with "nil"}]
                                    :scenarios [{:id :s :steps [[:goto "/"]]}]}}
                "/tmp/hive-cljs-faults"))]
    (is (= [:hole] (mapv :fault/id (get-in m [:manifest/e2e :faults]))))))

;; =============================================================================
;; Injection
;; =============================================================================

(def ^:private fault
  {:fault/id :hole :fault/form "(set! a/b (constantly nil))"})

(defn- manifest-for
  [steps]
  (:ok (manifest/parse
        {:hive.cljs/shadow {:port 9630 :nrepl-port 7889}
         :hive.cljs/builds {:app {:http-port 8280}}
         :hive.cljs/e2e    {:scenarios [{:id :s :build :app :steps steps}]}}
        "/tmp/hive-cljs-mutation")))

(defn- plan-for [steps]
  (let [m (manifest-for steps)]
    (:ok (plan/build-plan m (first (manifest/scenarios m))))))

(deftest the-fault-lands-after-the-navigation-that-would-wipe-it
  (let [p (mutation/inject (plan-for [[:goto "/"] [:click "#go"] [:expect-visible "#p"]])
                           fault)]
    (is (= [:goto :eval-cljs :click :expect-visible] (mapv :op/kind (:plan/ops p))))
    (is (m/validate s/RunPlan p) (pr-str (m/explain s/RunPlan p)))
    (testing "the injected op rides the runtime channel"
      (is (= :runtime (:op/channel (second (:plan/ops p))))))))

(deftest a-plan-that-never-navigates-takes-the-fault-at-the-head
  (let [p (mutation/inject (plan-for [[:expect-sub [:x] "some?"]]) fault)]
    (is (= [:eval-cljs :expect-sub] (mapv :op/kind (:plan/ops p))))))

(deftest injection-leaves-the-original-plan-alone
  (let [p (plan-for [[:goto "/"] [:click "#go"]])]
    (mutation/inject p fault)
    (is (= 2 (count (:plan/ops p))))))

;; =============================================================================
;; Verdicts
;; =============================================================================

(deftest a-red-scenario-kills-the-fault
  (let [v (mutation/verdict fault [{:run/scenario :a :run/state :pass}
                                   {:run/scenario :b :run/state :fail}])]
    (is (m/validate s/FaultVerdict v) (pr-str (m/explain s/FaultVerdict v)))
    (is (true? (:fault/killed? v)))
    (is (= [:b] (:fault/by v)))))

(deftest an-all-green-run-means-the-fault-survived
  (let [v (mutation/verdict fault [{:run/scenario :a :run/state :pass}])]
    (is (false? (:fault/killed? v)))
    (is (re-find #"blind to this behaviour" (:fault/detail v)))))

(deftest an-error-counts-as-a-kill-only-because-it-is-not-green
  (is (true? (:fault/killed? (mutation/verdict fault [{:run/scenario :a :run/state :error}]))))
  (is (true? (:fault/killed? (mutation/verdict fault [{:run/scenario :a :run/state :incomplete}])))))

(deftest an-unchallenged-suite-scores-zero-never-one
  (is (= 0.0 (mutation/score [])))
  (is (= 0.0 (mutation/score [{:fault/id :a :fault/killed? false}])))
  (is (= 0.5 (mutation/score [{:fault/id :a :fault/killed? true}
                              {:fault/id :b :fault/killed? false}])))
  (is (= 1.0 (mutation/score [{:fault/id :a :fault/killed? true}]))))

(deftest the-report-separates-killed-from-survived
  (let [rep (mutation/report [:s]
                             [{:fault/id :a :fault/killed? true}
                              {:fault/id :b :fault/killed? false}])]
    (is (m/validate s/MutationReport rep) (pr-str (m/explain s/MutationReport rep)))
    (is (= [:a] (:mutation/killed rep)))
    (is (= [:b] (:mutation/survived rep)))
    (is (= 0.5 (:mutation/score rep)))))

;; =============================================================================
;; Running
;; =============================================================================

(def ^:private walk [[:goto "/"] [:expect-visible "#panel"]])

(defn- noticing-deps
  "Ports where the DOM assertion starts failing once the fault form has been
   evaluated — the stub equivalent of a scenario seeing broken behaviour."
  [broken?]
  {:driver    (stub/driver (fn [_ op]
                             (if (and @broken? (= :expect-visible (:op/kind op)))
                               {:state :fail :detail "the panel went blank" :elapsed-ms 1}
                               {:state :pass :elapsed-ms 1})))
   :cljs-eval (stub/cljs-eval (fn [_ form]
                                (when (= (:fault/form fault) form) (reset! broken? true))
                                true))})

(deftest a-fault-the-suite-notices-is-killed
  (let [res (boundary/run-mutations! (noticing-deps (atom false))
                                     [(plan-for walk)] [fault])]
    (is (r/ok? res) (pr-str res))
    (is (= [:hole] (:mutation/killed (:ok res))))
    (is (= 1.0 (:mutation/score (:ok res))))))

(deftest a-fault-nothing-notices-survives-and-is-named
  (let [res (boundary/run-mutations! {:driver    (stub/driver)
                                      :cljs-eval (stub/cljs-eval (constantly true))}
                                     [(plan-for walk)] [fault])]
    (is (r/ok? res) (pr-str res))
    (is (= [:hole] (:mutation/survived (:ok res))))
    (is (= 0.0 (:mutation/score (:ok res))))))

(deftest a-red-baseline-is-refused-rather-than-scored
  (let [res (boundary/run-mutations! {:driver    (stub/driver (stub/fail-on :expect-visible))
                                      :cljs-eval (stub/cljs-eval (constantly true))}
                                     [(plan-for walk)] [fault])]
    (is (r/err? res))
    (is (= :mutation/baseline-red (:error res)))
    (testing "the refusal says what was already failing"
      (is (seq (:summary res))))))

(deftest an-empty-catalog-is-an-error-not-a-perfect-score
  (let [res (boundary/run-mutations! {:driver (stub/driver)} [(plan-for walk)] [])]
    (is (r/err? res))
    (is (= :mutation/no-faults (:error res)))))

(deftest auto-derivation-turns-every-registered-handler-into-a-fault
  (let [ce   (stub/cljs-eval (fn [_ form]
                               (when (re-find #"kind->id->handler" form)
                                 {:sub [:app/items :app/selected] :event [:app/load]})))
        deps {:driver (stub/driver) :cljs-eval ce}
        res  (boundary/derive-faults! deps (plan-for walk) [:sub :event])]
    (is (r/ok? res) (pr-str res))
    (is (= [:sub-app.items :sub-app.selected :event-app.load]
           (mapv :fault/id (:ok res))))

    (testing "one page, one round trip — both registries read in a single probe"
      (is (= 1 (count (filter (fn [[_ form]] (re-find #"kind->id->handler" form))
                              (stub/evals ce))))))

    (testing "a neutralized sub clears the reaction cache on both sides"
      (is (re-find #"clear-subscription-cache!.*reg-sub :app/items.*clear-subscription-cache!"
                   (:fault/form (first (:ok res))))))

    (testing "every derived fault conforms"
      (is (every? #(m/validate s/Fault %) (:ok res))))))

(deftest auto-derivation-without-a-runtime-is-a-typed-absence
  (testing "no runtime channel — the probe cannot answer, so nothing is claimed"
    (let [res (boundary/derive-faults! {:driver (stub/driver)} (plan-for walk) [:sub])]
      (is (r/err? res))
      (is (= :probe/failed (:error res)))))

  (testing "asking for no registries is not an error, it is an empty catalog"
    (is (= [] (:ok (boundary/derive-faults! {} (plan-for walk) []))))))
