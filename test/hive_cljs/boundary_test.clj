(ns hive-cljs.boundary-test
  "The integration the subsystem exists for: a plan carrying BOTH browser and
   runtime steps executes across two ports and produces one report.

   Both ports are stubs — the test names no vendor."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.dialect.source :as source]
            [hive-cljs.fixtures :as fix]
            [hive-cljs.plan :as plan]
            [hive-cljs.ports :as ports]
            [hive-cljs.stub.ports :as stub]
            [hive-cljs.verdict :as verdict]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- deps
  ([] (deps stub/always-pass (constantly true)))
  ([outcome-fn value-fn]
   {:driver    (stub/driver outcome-fn)
    :cljs-eval (stub/cljs-eval value-fn)
    :build-tool (stub/build-tool)}))

(deftest runs-both-channels-in-one-scenario
  (let [d   (deps)
        res (boundary/run-scenario! d fix/manifest :login)]
    (is (r/ok? res))
    (is (verdict/run-ok? (:ok res)))
    (testing "browser ops reached the driver, runtime ops did not"
      (let [kinds (mapv :op/kind (stub/performed-ops (:driver d)))]
        (is (= [:goto :fill :click :expect-text] kinds))
        (is (not (contains? (set kinds) :expect-sub)))))
    (testing "the runtime assertion reached ICljsEval as a subscription deref"
      (let [[[build form]] (stub/evals (:cljs-eval d))]
        (is (= :app build))
        (is (= "(some? (deref (re-frame.core/subscribe [:current-user])))" form))))
    (testing "the session was opened and closed exactly once"
      (is (= 1 (stub/sessions-opened (:driver d))))
      (is (= 1 (stub/sessions-closed (:driver d)))))))

(deftest urls-are-absolutized-against-base-url
  (let [d (deps)]
    (boundary/run-scenario! d fix/manifest :login)
    (is (= "http://localhost:8280/login"
           (-> (stub/performed-ops (:driver d)) first :op/args first)))))

(deftest failure-halts-and-marks-the-rest-skipped
  (let [d   (deps (stub/fail-on :click) (constantly true))
        res (boundary/run-scenario! d fix/manifest :login)
        rep (:ok res)]
    (is (= :fail (:run/state rep)))
    (is (= [:pass :pass :fail :skipped :skipped]
           (mapv :step/state (:run/steps rep))))
    (testing "the session is still closed after a failure"
      (is (= 1 (stub/sessions-closed (:driver d)))))))

(deftest runtime-predicate-decides-pass-or-fail
  (testing "a truthy subscription value passes"
    (let [d (deps stub/always-pass (constantly true))]
      (is (verdict/run-ok? (:ok (boundary/run-scenario! d fix/manifest :login))))))
  (testing "a false subscription value fails the run"
    (let [d   (deps stub/always-pass (constantly false))
          rep (:ok (boundary/run-scenario! d fix/manifest :login))]
      (is (= :fail (:run/state rep)))
      (is (= :expect-sub (:step/kind (last (:run/steps rep))))))))

(deftest missing-runtime-port-is-incomplete-never-green
  (let [d   {:driver (stub/driver) :cljs-eval nil}
        rep (:ok (boundary/run-scenario! d fix/manifest :login))]
    (testing "the run still produces a report rather than exploding"
      (is (some? rep))
      (is (= [:goto :fill :click :expect-text :expect-sub]
             (mapv :step/kind (:run/steps rep)))))
    (testing "the unrunnable assertion is :incomplete, and it decides the run"
      (is (= :incomplete (:step/state (last (:run/steps rep)))))
      (is (= :incomplete (:run/state rep)))
      (is (not (verdict/run-ok? rep))))
    (testing "browser steps still report their own truth"
      (is (= [:pass :pass :pass :pass]
             (mapv :step/state (butlast (:run/steps rep))))))))

(deftest runtime-channel-is-pinned-to-the-driven-page
  (let [d   (deps)
        rep (:ok (boundary/run-scenario! d fix/manifest :login))]
    (testing "the session is stamped once, and the runtime binds to that stamp"
      (let [[token] (stub/marks (:driver d))]
        (is (some? token))
        (is (= [[:app token]] (stub/binds (:cljs-eval d))))))
    (is (verdict/run-ok? rep))
    (testing "the pin is released when the run ends"
      (is (nil? (stub/bound-runtime (:cljs-eval d)))))))

(deftest an-unidentifiable-page-is-incomplete-not-a-silent-pass
  (let [d   {:driver     (stub/driver)
             :cljs-eval  (stub/cljs-eval (constantly true)
                                         {:accept-any-token? false :runtimes {}})
             :build-tool (stub/build-tool)}
        rep (:ok (boundary/run-scenario! d fix/manifest :login))]
    (testing "the assertion never ran against an arbitrary runtime"
      (is (= :incomplete (:step/state (last (:run/steps rep)))))
      (is (= :incomplete (:run/state rep)))
      (is (not (verdict/run-ok? rep))))
    (testing "no runtime eval was attempted once binding failed"
      (is (empty? (stub/evals (:cljs-eval d)))))
    (testing "binding is attempted once per run, not once per runtime step"
      (is (= 1 (count (stub/binds (:cljs-eval d))))))))

(deftest ports-without-affinity-still-run
  (testing "a driver that cannot stamp falls back to the unpinned channel"
    (let [d   {:driver (stub/driver-without-marking) :cljs-eval (stub/cljs-eval)
               :build-tool (stub/build-tool)}
          rep (:ok (boundary/run-scenario! d fix/manifest :login))]
      (is (verdict/run-ok? rep))
      (is (empty? (stub/binds (:cljs-eval d))))))
  (testing "an eval channel that cannot pin falls back too"
    (let [d   {:driver (stub/driver) :cljs-eval (stub/cljs-eval-without-affinity)
               :build-tool (stub/build-tool)}
          rep (:ok (boundary/run-scenario! d fix/manifest :login))]
      (is (verdict/run-ok? rep))
      (is (empty? (stub/marks (:driver d)))))))

(deftest missing-driver-is-a-typed-error
  (let [res (boundary/run-scenario! {:cljs-eval (stub/cljs-eval)} fix/manifest :login)]
    (is (= :run/no-driver (:error res)))))

(deftest unknown-scenario-is-a-typed-error
  (let [res (boundary/run-scenario! (deps) fix/manifest :nope)]
    (is (= :scenario/not-found (:error res)))
    (is (= [:login :dashboard] (:known res)))))

(deftest tagged-runs-select-by-tag
  (let [reps (:ok (boundary/run-tagged! (deps) fix/manifest #{:smoke}))]
    (is (= [:login] (mapv :run/scenario reps))))
  (let [reps (:ok (boundary/run-tagged! (deps) fix/manifest #{:slow}))]
    (is (= [:dashboard] (mapv :run/scenario reps)))))

(deftest browser-only-plan-needs-no-runtime
  (let [p (:ok (plan/plan-for-id fix/manifest :dashboard))]
    (is (not (plan/needs-runtime? p)))
    (is (verdict/run-ok? (:ok (boundary/run-plan! (deps) p))))))

;; =============================================================================
;; Forms cross into source only inside the channel adapter
;; =============================================================================

(deftest a-probe-carries-a-FORM-to-the-channel-which-prints-it
  (let [ce   (stub/cljs-eval (fn [_ form] (when (re-find #"kind->id->handler" form)
                                            {:sub [:a/b]})))
        d    {:driver (stub/driver) :cljs-eval ce}
        plan {:plan/scenario :s :plan/build :app :plan/base-url "http://localhost:8280"
              :plan/ops [{:op/kind :goto :op/channel :browser :op/args ["http://localhost:8280/"]
                          :op/source [:goto "/"]}]}
        form (ports/registry-source ce [:sub])
        res  (boundary/probe-runtime! d plan form)]
    (testing "the introspection port hands out a form, not text"
      (is (map? form))
      (is (= '(vec (keys (get (deref re-frame.registrar/kind->id->handler) :sub)))
             (get form :sub))))
    (is (r/ok? res) (pr-str res))
    (is (= {:sub [:a/b]} (:ok res)))
    (testing "the channel received pinned source, the same under any printer vars"
      (let [[[_ sent]] (stub/evals ce)]
        (is (= "{:sub (vec (keys (get (deref re-frame.registrar/kind->id->handler) :sub)))}" sent))
        (binding [*print-length* 1 *print-level* 1 *print-readably* false]
          (is (= sent (source/pr-source form))))))))

(deftest derived-faults-stay-forms-until-the-channel
  (let [ce  (stub/cljs-eval (fn [_ form] (when (re-find #"kind->id->handler" form)
                                           {:sub [:a/b] :event [:a/go]})))
        d   {:driver (stub/driver) :cljs-eval ce}
        res (boundary/derive-faults!
             d {:plan/scenario :s :plan/build :app :plan/base-url "http://localhost:8280"
                :plan/ops []}
             [:sub :event])]
    (is (r/ok? res) (pr-str res))
    (is (every? (comp seq? :fault/form) (:ok res)))
    (is (= (ports/neutralize-source ce :event :a/go) (:fault/form (last (:ok res)))))))
