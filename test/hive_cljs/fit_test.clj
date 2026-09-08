(ns hive-cljs.fit-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.fit :as fit]
            [hive-cljs.fit.test :as fit-test]
            [hive-cljs.ports :as ports]
            [hive-cljs.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def box {:w 960.0 :h 700.0})

(defn measurement
  [id extent & {:as opts}]
  (merge #:fit{:id id :rung :measured :box box :extent extent} opts))

;; =============================================================================
;; Findings
;; =============================================================================

(deftest a-subject-inside-its-box-has-nothing-to-report
  (is (= [] (fit/findings (measurement "a" {:w 900.0 :h 600.0})))))

(deftest a-half-pixel-of-overflow-is-rounding-not-a-finding
  (testing "tolerance absorbs what integer scroll sizes round up"
    (is (= [] (fit/findings (measurement "a" {:w 961.0 :h 701.0}))))
    (is (seq (fit/findings (measurement "a" {:w 960.0 :h 740.0}))))))

(deftest a-finding-names-the-axis-and-the-amount
  (let [fs (fit/findings (measurement "a" {:w 1000.0 :h 800.0}))]
    (is (= #{:taller-than-box :wider-than-box} (set (map :fit/kind fs))))
    (is (= #{40.0 100.0} (set (map :fit/by fs))))))

(deftest a-clipped-descendant-is-its-own-finding
  (let [fs (fit/findings (measurement "a" {:w 900.0 :h 600.0}
                                      :fit/clipped [#:fit{:tag "pre" :class "plato-code"
                                                          :over-w 0.0 :over-h 120.0}]))]
    (is (= [:clipped] (map :fit/kind fs)))
    (is (= 120.0 (:fit/by (first fs))))))

;; =============================================================================
;; Scale
;; =============================================================================

(deftest the-scale-is-the-tightest-of-the-two-axes
  (is (= 0.5 (fit/fit-scale (measurement "a" {:w 1000.0 :h 1400.0}))))
  (is (= 1.0 (fit/fit-scale (measurement "a" {:w 100.0 :h 100.0})))))

;; =============================================================================
;; States
;; =============================================================================

(deftest a-subject-that-fits-and-claims-nothing-is-ok
  (is (= :ok (:fit/state (fit/evaluate (measurement "a" {:w 900.0 :h 600.0}))))))

(deftest a-declared-overflow-is-waived
  (is (= :waived (:fit/state (fit/evaluate (measurement "a" {:w 900.0 :h 900.0}
                                                        :fit/policy :allow))))))

(deftest a-shrinkable-overflow-carries-the-scale-that-repairs-it
  (let [v (fit/evaluate (measurement "a" {:w 960.0 :h 875.0} :fit/policy :shrink))]
    (is (= :shrunk (:fit/state v)))
    (is (= 0.8 (:fit/scale v)))))

(deftest shrinking-past-the-readable-floor-is-not-a-remedy
  (let [v (fit/evaluate (measurement "a" {:w 960.0 :h 2000.0} :fit/policy :shrink))]
    (is (= :too-small (:fit/state v)))
    (is (= :fail (:fit/severity v)))))

(deftest a-clipped-descendant-cannot-be-shrunk-away
  (testing "scaling a subject scales the clip with it, so the ratio survives"
    (is (= :overflows
           (:fit/state (fit/evaluate (measurement "a" {:w 900.0 :h 600.0}
                                                  :fit/policy :shrink
                                                  :fit/clipped
                                                  [#:fit{:tag "pre" :class ""
                                                         :over-w 0.0 :over-h 90.0}])))))))

(deftest a-policy-on-a-subject-that-now-fits-is-itself-a-finding
  (let [v (fit/evaluate (measurement "a" {:w 900.0 :h 600.0} :fit/policy :allow))]
    (is (= :stale-waiver (:fit/state v)))
    (is (= :warn (:fit/severity v)) "a stale waiver hides the next overflow, it is not one")))

;; =============================================================================
;; The rung decides what a finding is worth
;; =============================================================================

(deftest a-measured-overflow-fails
  (is (= :fail (:fit/severity (fit/evaluate (measurement "a" {:w 960.0 :h 800.0}))))))

(deftest an-estimated-overflow-inside-the-margin-only-warns
  (let [m #:fit{:id "a" :rung :estimated :box box
                :extent {:w 960.0 :h 760.0} :margin 100.0}]
    (is (= :overflows (:fit/state (fit/evaluate m))))
    (is (= :warn (:fit/severity (fit/evaluate m)))
        "60px of overflow inside a 100px error bar is not evidence of anything")))

(deftest an-estimated-overflow-outside-the-margin-fails
  (let [m #:fit{:id "a" :rung :estimated :box box
                :extent {:w 960.0 :h 900.0} :margin 100.0}]
    (is (= :fail (:fit/severity (fit/evaluate m))))))

(deftest strict-promotes-every-warning-to-a-failure
  (let [m #:fit{:id "a" :rung :estimated :box box
                :extent {:w 960.0 :h 760.0} :margin 100.0}]
    (is (= :fail (:fit/severity (fit/evaluate m #:fit{:strict? true}))))))

(deftest a-subject-the-source-could-not-model-is-unknown-not-ok
  (let [m #:fit{:id "a" :rung :estimated :box box :modelled? false}]
    (is (= :unknown (:fit/state (fit/evaluate m))))
    (is (= :warn (:fit/severity (fit/evaluate m))))))

;; =============================================================================
;; Report
;; =============================================================================

(deftest a-report-over-nothing-is-unavailable-never-a-pass
  (let [r (fit/report [])]
    (is (= :unavailable (:fit/state r)))
    (is (str/includes? (fit/explain r) "nothing was proven"))))

(deftest a-report-takes-the-worst-severity-any-verdict-carries
  (is (= :fail (:fit/state (fit/report [(measurement "a" {:w 900.0 :h 600.0})
                                        (measurement "b" {:w 960.0 :h 900.0})]))))
  (is (= :pass (:fit/state (fit/report [(measurement "a" {:w 900.0 :h 600.0})])))))

(deftest a-report-tallies-the-states-it-saw
  (is (= {:ok 1 :waived 1}
         (:fit/tally (fit/report [(measurement "a" {:w 900.0 :h 600.0})
                                  (measurement "b" {:w 900.0 :h 900.0}
                                               :fit/policy :allow)])))))

(deftest explain-names-the-subject-the-axis-and-the-amount
  (let [msg (fit/explain (fit/report [(measurement "welcome" {:w 960.0 :h 820.0})]))]
    (is (str/includes? msg "welcome"))
    (is (str/includes? msg "120px taller"))
    (is (str/includes? msg "measured rung"))))

(deftest mixed-rungs-are-not-reportable-under-one-label
  (is (fit/mixed-rung? [(measurement "a" {:w 1.0 :h 1.0})
                        #:fit{:id "b" :rung :estimated :box box
                              :extent {:w 1.0 :h 1.0}}]))
  (is (not (fit/mixed-rung? [(measurement "a" {:w 1.0 :h 1.0})]))))

;; =============================================================================
;; Coverage
;; =============================================================================

(deftest coverage-over-an-empty-universe-is-unavailable
  (let [c (fit/coverage #{} #{:image})]
    (is (= :unavailable (:fit/state c)))
    (is (str/includes? (fit/explain-coverage c) "EMPTY"))))

(deftest coverage-names-what-the-model-admits-but-nothing-reached
  (let [c (fit/coverage #{:image :code :table} #{:image})]
    (is (= :fail (:fit/state c)))
    (is (= [:code :table] (:fit/missing c)))
    (is (str/includes? (fit/explain-coverage c) "code, table"))))

(deftest coverage-passes-when-every-admitted-kind-was-reached
  (is (= :pass (:fit/state (fit/coverage #{:image :code} #{:image :code :extra})))))

(deftest an-exemption-excuses-a-kind-and-has-to-carry-its-reason
  (let [c (fit/coverage #{:image :code} #{:image} {:code "no deck ships one yet"})]
    (is (= :pass (:fit/state c)))
    (is (= [] (:fit/missing c)))
    (is (= {:code "no deck ships one yet"} (:fit/exempt c)))))

(deftest an-exemption-for-a-kind-that-is-now-reached-is-itself-a-finding
  (testing "an exemption outlives whatever justified it unless something says so"
    (let [c (fit/coverage #{:image} #{:image} {:image "was never exercised"})]
      (is (= :fail (:fit/state c)))
      (is (= [:image] (:fit/stale-exemptions c)))
      (is (str/includes? (fit/explain-coverage c) "should be dropped")))))

(deftest reached-is-the-union-of-what-the-subjects-drew-on
  (is (= #{:image :code}
         (fit/reached [(measurement "a" {:w 1.0 :h 1.0} :fit/kinds #{:image})
                       (measurement "b" {:w 1.0 :h 1.0} :fit/kinds #{:code :image})]))))

;; =============================================================================
;; Schema conformance
;; =============================================================================

(deftest verdicts-and-reports-conform-to-their-schemas
  (let [ms [(measurement "a" {:w 900.0 :h 600.0})
            (measurement "b" {:w 960.0 :h 900.0} :fit/policy :shrink)]]
    (is (every? #(m/validate s/FitMeasurement %) ms))
    (is (every? #(m/validate s/FitVerdict %) (map fit/evaluate ms)))
    (is (m/validate s/FitReport (fit/report ms)))
    (is (m/validate s/FitCoverage (fit/coverage #{:a} #{:a})))
    (is (m/validate s/FitCoverage (fit/coverage #{:a :b} #{:a} {:b "reason"})))))

;; =============================================================================
;; The generated suite
;; =============================================================================

(defn stub-source [rung measurements]
  (reify ports/IFitSource
    (fit-rung [_] rung)
    (fit-measurements [_] measurements)))

(defn stub-universe [kinds]
  (reify ports/IFitUniverse
    (fit-universe [_] kinds)))

(deftest run-drives-a-source-once-and-answers-every-question
  (let [r (fit-test/run (stub-source :measured [(measurement "a" {:w 900.0 :h 600.0}
                                                             :fit/kinds #{:image})])
                        (stub-universe #{:image :code})
                        nil)]
    (is (= :measured (:fit/source-rung r)))
    (is (= :pass (:fit/state (:fit/report r))))
    (is (= [:code] (:fit/missing (:fit/coverage r))))))

(deftest run-claims-no-coverage-when-no-universe-is-given
  (testing "which is not the same as claiming coverage over nothing"
    (is (nil? (:fit/coverage (fit-test/run (stub-source :measured []) nil nil))))))

(deftest run-refuses-a-source-that-is-not-one
  (is (thrown? clojure.lang.ExceptionInfo (fit-test/run {} nil nil)))
  (is (thrown? clojure.lang.ExceptionInfo
               (fit-test/run (stub-source :measured []) {} nil))))

(deftest an-empty-source-is-a-failure-not-a-pass
  (let [r (fit-test/run (stub-source :estimated []) nil nil)]
    (is (not (fit-test/measured-anything? r)))
    (is (str/includes? (fit-test/vacuity-message r) "asserts nothing"))))

(deftest overclaiming-the-rung-is-caught
  (let [r (fit-test/run (stub-source :measured
                                     [#:fit{:id "a" :rung :estimated :box box
                                            :extent {:w 1.0 :h 1.0}}])
                        nil nil)]
    (is (str/includes? (fit-test/rung-message r) "may not report above"))))

(deftest unmodelled-subjects-are-named
  (let [r (fit-test/run (stub-source :estimated
                                     [#:fit{:id "mystery" :rung :estimated :box box
                                            :modelled? false}])
                        nil nil)]
    (is (= ["mystery"] (fit-test/unmodelled r)))))

;; The macro under its own gate: a green expansion over a green source.
(fit-test/deffit stub
  {:source (stub-source :measured [(measurement "a" {:w 900.0 :h 600.0}
                                                :fit/kinds #{:image :code})])
   :universe (stub-universe #{:image :code})
   :doc "hive-cljs's own smoke case for deffit."})

(deftest deffit-generates-the-vars-it-documents
  (doseq [suffix ["was-measured" "rung-holds" "fits" "covers-model"]]
    (is (some? (ns-resolve 'hive-cljs.fit-test (symbol (str "stub-" suffix))))
        (str "deffit did not emit stub-" suffix))))

(defn- expansion-error
  "The ex-info a bad `deffit` throws, unwrapped from the compiler's wrapper."
  [form]
  (try (macroexpand-1 form) nil
       (catch Exception e (ex-data (or (ex-cause e) e)))))

(deftest deffit-refuses-an-unknown-option
  (is (= :fit/unknown-option
         (:error (expansion-error '(hive-cljs.fit.test/deffit x {:source nil :typo 1})))))
  (is (= :fit/no-source
         (:error (expansion-error '(hive-cljs.fit.test/deffit x {:universe nil}))))))
