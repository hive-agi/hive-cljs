(ns hive-cljs.condition-wait-test
  "The two runtime-state channels a scenario gets beyond a one-shot assertion:
   a condition-wait that polls, and an app-db invariant asserted between steps.

   Every port is a stub — the test names no vendor."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.plan :as plan]
            [hive-cljs.step :as step]
            [hive-cljs.stub.ports :as stub]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- op
  "Compile one authored step to its Op, optionally frame-scoped."
  ([s] (:ok (step/compile-step s)))
  ([s frame] (assoc (op s) :op/frame frame)))

(def ^:private fast {:timeout-ms 400 :poll-ms 20})

;; =============================================================================
;; Compilation
;; =============================================================================

(deftest wait-steps-compile-to-the-runtime-channel
  (doseq [kind [:wait-for-sub :wait-for-db]]
    (testing (str kind)
      (let [res (step/compile-step [kind [:selected] "some?"])]
        (is (r/ok? res))
        (is (= :runtime (:op/channel (:ok res))))
        (is (= [[:selected] "some?"] (:op/args (:ok res)))))

      (testing "a missing predicate is a malformed step, not a default"
        (is (r/err? (step/compile-step [kind [:selected]])))))))

;; =============================================================================
;; Polling
;; =============================================================================

(deftest a-condition-that-already-holds-passes-on-the-first-probe
  (let [ce  (stub/cljs-eval (constantly [true {:contract/status "active"}]))
        out (boundary/perform-runtime! ce :app (op [:wait-for-sub [:selected] "some?"]) fast)]
    (is (= :pass (:state out)))
    (is (= 1 (count (stub/evals ce))))
    (testing "the probe keeps the value so a pass can report what it saw"
      (is (= (pr-str {:contract/status "active"}) (:detail out))))))

(deftest a-condition-that-arrives-late-still-passes
  (let [n  (atom 0)
        ce (stub/cljs-eval (fn [_ _] (if (< (swap! n inc) 3) [false nil] [true :arrived])))
        out (boundary/perform-runtime! ce :app (op [:wait-for-db [:items] "seq"]) fast)]
    (is (= :pass (:state out)))
    (is (= 3 @n) "polled until the predicate held, no longer")))

(deftest a-condition-that-never-holds-fails-with-the-last-value-seen
  (let [ce  (stub/cljs-eval (constantly [false {:contract/status "pending"}]))
        out (boundary/perform-runtime! ce :app (op [:wait-for-sub [:selected] "some?"]) fast)]
    (is (= :fail (:state out)))
    (testing "the detail distinguishes 'not yet' from 'never happened'"
      (is (re-find #"condition never held within 400ms" (:detail out)))
      (is (re-find #"pending" (:detail out))))
    (testing "it polled rather than asserting once"
      (is (< 1 (count (stub/evals ce)))))))

(deftest a-runtime-that-blows-up-mid-poll-is-an-error-not-a-timeout
  (let [ce  (stub/cljs-eval (constantly (r/err :cljs-eval/runtime-error {:detail "boom"})))
        out (boundary/perform-runtime! ce :app (op [:wait-for-db [:items] "seq"]) fast)]
    (is (= :error (:state out)))
    (is (re-find #"runtime-error" (:detail out)))))

(deftest a-probe-that-printed-something-unreadable-is-not-mistaken-for-a-hit
  (testing "a non-vector return means the runtime did not answer the probe"
    (let [ce  (stub/cljs-eval (constantly "#object[Foo]"))
          out (boundary/perform-runtime! ce :app (op [:wait-for-sub [:x] "some?"]) fast)]
      (is (= :fail (:state out)))
      (is (re-find #"#object" (:detail out))))))

(deftest the-probe-is-frame-scoped-when-the-scenario-is
  (let [seen (atom nil)
        ce   (stub/cljs-eval (fn [_ form] (reset! seen form) [true 1]))]
    (boundary/perform-runtime! ce :app (op [:wait-for-sub [:selected] "some?"] :main) fast)
    (is (re-find #"re-frame\.core/with-frame :main" @seen))))

;; =============================================================================
;; app-db invariant channel
;; =============================================================================

(defn- manifest-with
  "A one-build manifest whose e2e section carries `e2e-extra`."
  [e2e-extra steps]
  (:ok (manifest/parse
        {:hive.cljs/shadow {:port 9630 :nrepl-port 7889}
         :hive.cljs/builds {:app {:http-port 8280}}
         :hive.cljs/e2e    (merge {:timeout-ms 400 :poll-ms 20
                                   :scenarios [{:id :s :build :app :steps steps}]}
                                  e2e-extra)}
        "/tmp/hive-cljs-invariant")))

(def ^:private walk
  [[:goto "/"] [:click "#go"] [:expect-visible "#panel"]])

(defn- run
  "Run the manifest's only scenario, returning the report."
  [m value-fn]
  (let [d {:driver (stub/driver) :cljs-eval (stub/cljs-eval value-fn)}]
    [(:ok (boundary/run-scenario! d m :s)) (:cljs-eval d)]))

(deftest without-a-schema-nothing-extra-is-evaluated
  (let [[rep ce] (run (manifest-with {} walk) (constantly nil))]
    (is (= :pass (:run/state rep)))
    (is (empty? (stub/evals ce)) "a browser-only scenario stays browser-only")))

(deftest a-conforming-app-db-is-checked-after-every-step-and-stays-green
  (let [[rep ce] (run (manifest-with {:app-db-schema 'app.schema/db} walk)
                      (constantly nil))]
    (is (= :pass (:run/state rep)))
    (is (= 3 (count (stub/evals ce))) "one invariant read per passing step")
    (testing "the check validates the WHOLE app-db, not a path"
      (let [[[_ form]] (stub/evals ce)]
        (is (re-find #"malli\.core/explain app\.schema/db @re-frame\.db/app-db" form))))))

(deftest a-violating-app-db-fails-the-step-that-produced-it
  (let [[rep _] (run (manifest-with {:app-db-schema 'app.schema/db} walk)
                     (fn [_ _] [{:path [:items 0] :value nil}]))]
    (is (= :fail (:run/state rep)))
    (testing "the FIRST step is the one blamed, and the rest are skipped"
      (is (= [:fail :skipped :skipped] (mapv :step/state (:run/steps rep))))
      (is (re-find #"app-db violates app\.schema/db"
                   (:step/detail (first (:run/steps rep))))))))

(deftest an-invariant-that-cannot-run-is-reported-not-assumed-to-hold
  (let [[rep _] (run (manifest-with {:app-db-schema 'app.schema/db} walk)
                     (constantly (r/err :cljs-eval/runtime-error {:detail "no such var"})))]
    (is (= :error (:run/state rep)))
    (is (re-find #"could not be evaluated" (:step/detail (first (:run/steps rep)))))))

(deftest the-check-cadence-is-configurable
  (testing ":final asserts once, after the last step"
    (let [[rep ce] (run (manifest-with {:app-db-schema 'app.schema/db
                                        :app-db-check  :final}
                                       walk)
                        (constantly nil))]
      (is (= :pass (:run/state rep)))
      (is (= 1 (count (stub/evals ce))))))

  (testing ":mutations skips the steps that cannot corrupt state"
    (let [[rep ce] (run (manifest-with {:app-db-schema 'app.schema/db
                                        :app-db-check  :mutations}
                                       walk)
                        (constantly nil))]
      (is (= :pass (:run/state rep)))
      (is (= 2 (count (stub/evals ce))) ":expect-visible observes, it does not mutate"))))

(deftest a-failed-step-is-not-re-blamed-on-the-invariant
  (let [d   {:driver    (stub/driver (stub/fail-on :click))
             :cljs-eval (stub/cljs-eval (constantly nil))}
        rep (:ok (boundary/run-scenario!
                  d (manifest-with {:app-db-schema 'app.schema/db} walk) :s))]
    (is (= [:pass :fail :skipped] (mapv :step/state (:run/steps rep))))
    (testing "only the step that passed was checked"
      (is (= 1 (count (stub/evals (:cljs-eval d))))))))

;; =============================================================================
;; Plan wiring
;; =============================================================================

(deftest the-plan-carries-the-runtime-options-the-boundary-needs
  (let [m    (manifest-with {:app-db-schema 'app.schema/db :app-db-check :mutations
                             :frame :main}
                            walk)
        plan (:ok (plan/build-plan m (first (manifest/scenarios m))))]
    (is (= {:timeout-ms    400
            :poll-ms       20
            :frame         :main
            :app-db-schema 'app.schema/db
            :app-db-check  :mutations}
           (:plan/runtime plan)))))

(deftest poll-ms-has-a-default-so-a-manifest-need-not-name-it
  (let [m (:ok (manifest/parse
                {:hive.cljs/builds {:app {:http-port 8280}}
                 :hive.cljs/e2e    {:scenarios [{:id :s :steps walk}]}}
                "/tmp/hive-cljs-defaults"))]
    (is (= 250 (get-in m [:manifest/e2e :poll-ms])))))
