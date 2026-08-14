(ns hive-cljs.coverage-test
  "What the schemas cannot state: the namespace⇄module translation table, the
   DIP profile swap, the OCP layout extension, and the boundary orchestration
   driven by injected effects."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.coverage :as cov]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.profile :as profile]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def raw-config
  {:hive.cljs/builds   {:app {:http-port 8280}}
   :hive.cljs/e2e      {:scenarios [{:id :smoke :steps [[:goto "/"]]}]}
   :hive.cljs/coverage {:build           :unit-node
                        :source-prefixes ["payment-flow"]
                        :exclude         ["*-test" "payment-flow.dev.*"]
                        :thresholds      {:lines 80}
                        :baseline        "target/coverage/baseline.json"}})

(defn- parsed [raw] (:ok (manifest/parse raw "/proj")))

(def manifest-with-coverage (parsed raw-config))

(def config (manifest/coverage manifest-with-coverage))

(defn- summary-entry [covered total]
  {:lines     {:covered covered :total total}
   :branches  {:covered covered :total total}
   :functions {:covered covered :total total}})

(def summary
  {"/proj/.shadow-cljs/builds/unit-node/dev/out/cljs-runtime/payment_flow/views/editar.cljs"
   (summary-entry 10 10)
   "/proj/.shadow-cljs/builds/unit-node/dev/out/cljs-runtime/payment_flow/views/calculos.cljs"
   (summary-entry 3 10)
   "total" (summary-entry 13 20)})

;; =============================================================================
;; Namespace ⇄ module
;; =============================================================================

(deftest a-source-mapped-path-reads-back-as-the-namespace-it-came-from
  (testing "the source map records the NESTED spelling, munged"
    (is (= "payment-flow.views.editar"
           (cov/demunge-ns
            "/x/.shadow-cljs/builds/u/dev/out/cljs-runtime/payment_flow/views/editar.cljs"))))
  (testing "cljc sources map back the same way"
    (is (= "a.b-c" (cov/demunge-ns "…/cljs-runtime/a/b_c.cljc"))))
  (testing "a path with no runtime root is still read, not dropped"
    (is (= "a.b" (cov/demunge-ns "a/b.cljs")))))

(deftest the-module-glob-follows-the-emitted-layout-not-the-namespace-tree
  (let [flat (profile/coverage-profile :coverage/c8)]
    (testing "shadow-cljs writes ONE flat directory of dotted module names, so a
              path-shaped glob would match nothing"
      (is (= ["**/cljs-runtime/payment_flow.js"
              "**/cljs-runtime/payment_flow.*.js"]
             (cov/module-globs flat "payment-flow"))))
    (testing "a pattern that already carries a wildcard is used as authored"
      (is (= ["**/cljs-runtime/*_test.js"] (cov/module-globs flat "*-test")))
      (is (= ["**/cljs-runtime/payment_flow.dev.*.js"]
             (cov/module-globs flat "payment-flow.dev.*"))))
    (testing "a nested-layout toolchain gets directory globs from the same input"
      (let [nested (assoc flat :coverage/compiled-layout :nested)]
        (is (= ["**/cljs-runtime/payment_flow.js"
                "**/cljs-runtime/payment_flow/**/*.js"]
               (cov/module-globs nested "payment-flow")))))))

(deftest an-unknown-layout-degrades-to-the-shipped-one-rather-than-throwing
  (is (= (cov/module-globs (profile/coverage-profile) "payment-flow")
         (cov/module-globs (assoc (profile/coverage-profile)
                                  :coverage/compiled-layout :not-a-layout)
                           "payment-flow"))))

;; =============================================================================
;; DIP — the provider is data
;; =============================================================================

(deftest swapping-the-coverage-provider-is-a-registration-not-a-code-edit
  (profile/register!
   :coverage
   (assoc (profile/coverage-profile)
          :profile/id :coverage/other
          :coverage/command ["other-tool"]
          :coverage/compiled-root "build/out/modules"
          :coverage/module-suffix ".mjs"))
  (let [other (profile/coverage-profile :coverage/other)]
    (is (= "**/modules" (cov/glob-root other)))
    (is (= ["**/modules/payment_flow.mjs" "**/modules/payment_flow.*.mjs"]
           (cov/module-globs other "payment-flow")))
    (is (= "other-tool" (first (cov/argv other config)))))
  (testing "an unregistered id falls back to the shipped profile"
    (is (= :coverage/c8 (:profile/id (profile/coverage-profile :coverage/nope))))))

;; =============================================================================
;; Plan
;; =============================================================================

(deftest the-plan-is-a-complete-command-line-and-nothing-is-run-to-build-it
  (let [p (cov/plan config "/proj")
        argv (:plan/argv p)]
    (is (= ["npx" "shadow-cljs" "compile" "unit-node"] (:plan/compile-argv p)))
    (is (= "/proj" (:plan/cwd p)))
    (is (= "target/coverage/coverage-summary.json" (:plan/summary-path p)))
    (testing "the machine-readable reporter is present, or nothing can be parsed"
      (is (some #{"json-summary"} argv)))
    (testing "every source prefix is included and every exclusion excluded"
      (let [pairs (partition 2 1 argv)]
        (is (some #{["--include" "**/cljs-runtime/payment_flow.*.js"]} pairs))
        (is (some #{["--exclude" "**/cljs-runtime/*_test.js"]} pairs))
        (is (some #{["--exclude" "**/cljs-runtime/payment_flow.dev.*.js"]} pairs))))
    (testing "the runner and its bundle come last, after every flag"
      (is (= ["node" "target/unit-node.js"] (take-last 2 argv))))))

(deftest a-project-may-measure-a-bundle-it-did-not-just-build
  (let [cfg (manifest/normalize-coverage
             {:source-prefixes ["a"] :compile false})]
    (is (= [] (:coverage/compile cfg)))
    (is (= [] (:plan/compile-argv (cov/plan cfg "/proj"))))))

;; =============================================================================
;; Summary → rows
;; =============================================================================

(deftest istanbuls-synthetic-total-row-is-never-mistaken-for-a-namespace
  (let [rs (cov/rows summary)]
    (is (= ["payment-flow.views.calculos" "payment-flow.views.editar"]
           (mapv :coverage/ns rs)))
    (is (= 2 (:coverage/namespaces (cov/totals rs))))))

(deftest a-dimension-with-nothing-to-instrument-is-zero-not-a-crash
  (is (= 0.0 (cov/pct 0 0)))
  (is (= {:metric/covered 0 :metric/total 0 :metric/pct 0.0} (cov/metric nil)))
  (is (= 100.0 (get-in (cov/metric {:covered 7 :total 7}) [:metric/pct]))))

(deftest totals-sum-the-counts-and-derive-the-percentage-from-them
  (let [t (cov/totals (cov/rows summary))]
    (is (= 13 (get-in t [:coverage/lines :metric/covered])))
    (is (= 20 (get-in t [:coverage/lines :metric/total])))
    (is (= 65.0 (get-in t [:coverage/lines :metric/pct])))))

;; =============================================================================
;; Delta — counts, not percentages
;; =============================================================================

(deftest a-delta-is-read-in-covered-counts-because-percentages-move-with-the-denominator
  (let [before (cov/rows {"…/cljs-runtime/a/b.cljs"
                          {:lines {:covered 5 :total 5}
                           :branches {:covered 0 :total 0}
                           :functions {:covered 1 :total 1}}})
        after  (cov/rows {"…/cljs-runtime/a/b.cljs"
                          {:lines {:covered 9 :total 10}
                           :branches {:covered 6 :total 17}
                           :functions {:covered 2 :total 2}}})
        [d] (cov/deltas after before)]
    (testing "branch coverage fell from 100% to 35% while genuinely rising"
      (is (= 0.0 (get-in (first before) [:coverage/branches :metric/pct])))
      (is (< (get-in (first after) [:coverage/branches :metric/pct]) 36.0))
      (is (= 6 (:delta/branches d))))
    (is (= 4 (:delta/lines d)))
    (is (false? (:delta/new? d)))
    (is (empty? (cov/regressions [d])))))

(deftest a-namespace-the-baseline-never-saw-is-marked-new-not-improved
  (let [[d] (cov/deltas (cov/rows summary) [])]
    (is (true? (:delta/new? d)))))

(deftest losing-covered-lines-is-reported-as-a-regression
  (let [before (cov/rows {"…/cljs-runtime/a/b.cljs" (summary-entry 10 10)})
        after  (cov/rows {"…/cljs-runtime/a/b.cljs" (summary-entry 4 10)})
        ds     (cov/deltas after before)]
    (is (= [-6] (mapv :delta/lines ds)))
    (is (= 1 (count (cov/regressions ds))))))

;; =============================================================================
;; Verdict
;; =============================================================================

(deftest a-run-that-measured-nothing-is-unavailable-never-a-pass
  (is (= :unavailable (:coverage/state (cov/verdict (cov/totals []) {:lines 80.0})))))

(deftest thresholds-gate-only-the-dimensions-they-name
  (let [t (cov/totals (cov/rows summary))]
    (is (= :pass (:coverage/state (cov/verdict t {}))))
    (is (= :pass (:coverage/state (cov/verdict t {:lines 60.0}))))
    (let [v (cov/verdict t {:lines 80.0 :branches 90.0})]
      (is (= :below-threshold (:coverage/state v)))
      (is (= [:branches :lines] (mapv :breach/dimension (:coverage/breaches v))))
      (is (= 65.0 (:breach/actual (first (filter #(= :lines (:breach/dimension %))
                                                 (:coverage/breaches v)))))))))

;; =============================================================================
;; Presentation
;; =============================================================================

(deftest the-worst-covered-namespace-is-reported-first
  (is (= ["payment-flow.views.calculos" "payment-flow.views.editar"]
         (mapv :coverage/ns (cov/worst-first (cov/rows summary)))))
  (testing "a filter narrows by namespace, and a blank one keeps everything"
    (is (= 1 (count (cov/matching (cov/rows summary) "calculos"))))
    (is (= 2 (count (cov/matching (cov/rows summary) ""))))
    (is (= 2 (count (cov/matching (cov/rows summary) nil)))))
  (testing "a report line carries the counts the percentage came from"
    (is (= [30.0 3 10] (:lines (cov/brief (first (cov/worst-first (cov/rows summary)))))))))

;; =============================================================================
;; Manifest
;; =============================================================================

(deftest coverage-is-opt-in-and-an-absent-section-invents-no-build
  (is (nil? (manifest/normalize-coverage nil)))
  (is (nil? (manifest/coverage (parsed (dissoc raw-config :hive.cljs/coverage))))))

(deftest a-coverage-section-with-nothing-to-measure-is-a-config-error
  (let [res (manifest/parse (assoc-in raw-config
                                      [:hive.cljs/coverage :source-prefixes] [])
                            "/proj")]
    (is (r/err? res))))

(deftest author-written-percentages-are-accepted-as-plain-numbers
  (is (= {:lines 80.0} (:coverage/thresholds
                        (manifest/normalize-coverage
                         {:source-prefixes ["a"] :thresholds {:lines 80}})))))

;; =============================================================================
;; Boundary — injected effects
;; =============================================================================

(defn- stub-exec [calls]
  (fn [argv cwd] (swap! calls conj [argv cwd]) (r/ok {:exit 0 :out "" :err ""})))

(defn- stub-read [m]
  (fn [path] (some (fn [[suffix v]] (when (str/ends-with? path suffix) v)) m)))

(deftest a-coverage-run-is-orchestration-over-injected-effects
  (let [calls (atom [])
        res   (boundary/run-coverage!
               manifest-with-coverage
               {:exec!     (stub-exec calls)
                :read-json (stub-read {"coverage-summary.json" summary})})]
    (is (r/ok? res))
    (testing "the build is compiled before it is measured, both under the root"
      (is (= 2 (count @calls)))
      (is (= ["npx" "shadow-cljs" "compile" "unit-node"] (first (first @calls))))
      (is (= "/proj" (second (first @calls))))
      (is (= "npx" (ffirst (second @calls)))))
    (let [{:keys [report process]} (:ok res)]
      (is (= :unit-node (:coverage/build report)))
      (is (= :pass (:tests process)))
      (is (= 2 (count (:coverage/rows report))))
      (testing "no baseline on disk means no delta claimed"
        (is (nil? (:coverage/deltas report))))
      (testing "65% lines is under the configured 80"
        (is (= :below-threshold (get-in report [:coverage/verdict :coverage/state])))))))

(deftest a-baseline-on-disk-turns-the-run-into-a-delta
  (let [before {"…/cljs-runtime/payment_flow/views/editar.cljs" (summary-entry 2 10)}
        res (boundary/run-coverage!
             manifest-with-coverage
             {:exec!     (stub-exec (atom []))
              :read-json (stub-read {"coverage-summary.json" summary
                                     "baseline.json" before})})
        report (:report (:ok res))
        by-ns (into {} (map (juxt :delta/ns identity)) (:coverage/deltas report))]
    (is (= 8 (:delta/lines (get by-ns "payment-flow.views.editar"))))
    (is (true? (:delta/new? (get by-ns "payment-flow.views.calculos"))))))

(deftest a-failing-suite-still-yields-the-numbers-it-managed-to-measure
  (let [compile-argv? (fn [argv] (some #{"shadow-cljs"} argv))
        res (boundary/run-coverage!
             manifest-with-coverage
             {:exec!     (fn [argv _] (r/ok {:exit (if (compile-argv? argv) 0 1)
                                             :out "" :err ""}))
              :read-json (stub-read {"coverage-summary.json" summary})})]
    (is (r/ok? res))
    (is (= :fail (get-in (:ok res) [:process :tests])))
    (is (= 2 (count (get-in (:ok res) [:report :coverage/rows]))))))

(deftest a-failed-compile-is-not-reported-as-zero-coverage
  (let [ran (atom 0)
        res (boundary/run-coverage!
             manifest-with-coverage
             {:exec!     (fn [_ _] (swap! ran inc) (r/ok {:exit 2 :out "" :err "boom"}))
              :read-json (constantly summary)})]
    (is (r/err? res))
    (is (= 1 @ran) "the coverage tool must not run over a bundle that failed to build")))

(deftest a-run-that-wrote-no-summary-is-an-error-with-the-reason-attached
  (let [res (boundary/run-coverage!
             manifest-with-coverage
             {:exec! (stub-exec (atom [])) :read-json (constantly nil)})]
    (is (r/err? res))
    (is (str/includes? (pr-str res) "include globs"))))

(deftest coverage-refuses-to-guess-when-the-project-authors-no-section
  (is (r/err? (boundary/run-coverage!
               (parsed (dissoc raw-config :hive.cljs/coverage))
               {:exec! (stub-exec (atom [])) :read-json (constantly summary)}))))

(deftest a-baseline-is-frozen-from-the-summary-the-last-run-wrote
  (let [written (atom nil)
        res (boundary/save-baseline!
             manifest-with-coverage
             {:read-json  (stub-read {"coverage-summary.json" summary})
              :write-json (fn [path data] (reset! written [path data]))})]
    (is (r/ok? res))
    (is (= "/proj/target/coverage/baseline.json" (first @written)))
    (is (= summary (second @written)))
    (is (= 2 (:namespaces (:ok res))))))

(deftest freezing-a-baseline-nobody-configured-says-so
  (let [m (parsed (update raw-config :hive.cljs/coverage dissoc :baseline))]
    (is (r/err? (boundary/save-baseline! m {:read-json (constantly summary)
                                            :write-json (fn [_ _])})))))
