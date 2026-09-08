(ns hive-cljs.fit.test
  "Generate the `clojure.test` vars that gate one document's fit.

   `deffit` is the whole surface. A consumer supplies a `ports/IFitSource` and,
   when it can answer one honestly, a `ports/IFitUniverse`; the macro decides
   what is asserted about them. Putting the assertions HERE rather than in each
   consumer's suite is what makes two of them enforceable at all:

   - a gate must not read green when its source measured nothing, and
   - a coverage check must draw its universe from the document model rather
     than from the source it is checking.

   A hand-written suite can satisfy both and cannot be held to either."
  (:require [clojure.string :as str]
            [clojure.test]
            [hive-cljs.fit :as fit]
            [hive-cljs.ports :as ports]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; One run, shared by every generated var
;; =============================================================================

(defn run
  "Drive `source` once and return everything the generated tests assert on.

   `universe` may be nil, in which case no coverage is claimed -- which is
   different from claiming coverage over nothing."
  [source universe policy]
  (when-not (ports/fit-source? source)
    (throw (ex-info "hive-cljs.fit: :source does not satisfy ports/IFitSource"
                    {:error :fit/not-a-source :source (type source)})))
  (when (and universe (not (ports/fit-universe? universe)))
    (throw (ex-info "hive-cljs.fit: :universe does not satisfy ports/IFitUniverse"
                    {:error :fit/not-a-universe :universe (type universe)})))
  (let [measurements (vec (ports/fit-measurements source))]
    {:fit/measurements measurements
     :fit/source-rung (ports/fit-rung source)
     :fit/policy policy
     :fit/report (fit/report measurements policy)
     :fit/coverage (when universe
                     (fit/coverage (ports/fit-universe universe)
                                   (fit/reached measurements)
                                   (:fit/exempt policy)))}))

;; =============================================================================
;; What each generated var asserts
;; =============================================================================

(defn measured-anything?
  "Whether the run has a subject to judge at all."
  [run]
  (seq (:fit/measurements run)))

(defn vacuity-message
  [run]
  (str "the fit source produced NO measurement, so this gate asserts nothing. "
       "An empty source is the failure mode that looks exactly like success: "
       "check that the document has subjects and that the source reaches them. "
       "Source rung: " (name (:fit/source-rung run))))

(defn unmodelled
  "Ids the source admitted it could not model."
  [run]
  (->> (:fit/measurements run)
       (filterv #(false? (:fit/modelled? %)))
       (mapv :fit/id)))

(defn rung-message
  "Why a run's rungs do not hold together, or nil when they do."
  [{:fit/keys [measurements source-rung report]}]
  (cond
    (fit/mixed-rung? measurements)
    (str "measurements were taken at more than one rung "
         (pr-str (sort (into #{} (map :fit/rung) measurements)))
         ", so the report's single rung label is true of only part of it")

    (not= source-rung (:fit/rung report))
    (str "the source claims the " (name source-rung) " rung but its "
         "measurements are labelled " (name (:fit/rung report))
         "; a source may not report above the rung it can stand behind")))

(defn fit-message
  "Why a run's report is a failure, or nil when it is not."
  [{:fit/keys [report policy]}]
  (when (= :fail (:fit/state report))
    (fit/explain report policy)))

(defn warnings-note
  "What the run WARNED about, or nil. Printed rather than asserted: a warning
   is a finding the rung cannot settle, and printing it is how it reaches an
   author without gating a build on evidence that does not carry one."
  [{:fit/keys [report policy]}]
  (when (seq (:fit/warnings report))
    (str "fit warnings (" (name (:fit/rung report)) " rung, not gating):\n"
         (str/join "\n" (keep #(fit/describe % policy) (:fit/warnings report))))))

;; =============================================================================
;; Generation
;; =============================================================================

(def ^:private unsafe-in-symbol #"[^A-Za-z0-9*+!_?<>=-]+")

(defn test-sym
  "Symbol naming one generated var."
  [prefix suffix]
  (symbol (str (str/replace (str prefix) unsafe-in-symbol "-") "-" suffix)))

(def known-opts
  #{:source :universe :policy :doc})

(defmacro deffit
  "Emit the `clojure.test` vars that gate `prefix`'s fit.

   `opts` keys, each a FORM evaluated once when the tests run:
     :source    a `ports/IFitSource`; required
     :universe  a `ports/IFitUniverse`; when given, coverage is asserted
     :policy    overrides for `fit/default-policy`; `:fit/exempt` there is the
                {kind reason} map of content the gate deliberately does not
                measure
     :doc       prose attached to every generated var

   Generated vars:
     <prefix>-was-measured   the source produced at least one subject
     <prefix>-rung-holds     the rungs are consistent and not overclaimed
     <prefix>-fits           nothing failed at the rung it was measured at
     <prefix>-covers-model   every kind the document model admits was reached
                             (only when :universe is given)

   A subject the source could not model is a WARNING, printed by
   `<prefix>-fits` and not asserted separately: one severity model decides
   what gates, and `:fit/strict?` is the single lever that promotes every
   warning to a failure. A second, harder rule for unmodelled subjects would
   be a policy this macro applies behind the caller's back.

   The run happens once, in a delay the vars share, so N assertions cost one
   traversal of the document."
  [prefix opts]
  (let [unknown (remove known-opts (keys opts))]
    (when (seq unknown)
      (throw (ex-info (str "hive-cljs.fit/deffit: unknown option(s) "
                           (str/join ", " unknown))
                      {:error :fit/unknown-option :keys (vec unknown)})))
    (when-not (contains? opts :source)
      (throw (ex-info "hive-cljs.fit/deffit: :source is required"
                      {:error :fit/no-source}))))
  (let [{:keys [source universe policy doc]} opts
        run-sym (test-sym prefix "fit-run")
        var-meta (cond-> {} doc (assoc :doc doc))]
    `(do
       (def ~(with-meta run-sym {:private true})
         (delay (run ~source ~universe ~policy)))

       (clojure.test/deftest ~(with-meta (test-sym prefix "was-measured") var-meta)
         (let [run# @~run-sym]
           (clojure.test/is (measured-anything? run#) (vacuity-message run#))))

       (clojure.test/deftest ~(with-meta (test-sym prefix "rung-holds") var-meta)
         (let [msg# (rung-message @~run-sym)]
           (clojure.test/is (nil? msg#) msg#)))

       (clojure.test/deftest ~(with-meta (test-sym prefix "fits") var-meta)
         (let [run# @~run-sym
               msg# (fit-message run#)]
           (when-let [note# (warnings-note run#)] (println note#))
           (clojure.test/is (nil? msg#) msg#)))

       ~@(when universe
           [`(clojure.test/deftest ~(with-meta (test-sym prefix "covers-model") var-meta)
               (let [cov# (:fit/coverage @~run-sym)
                     msg# (fit/explain-coverage cov#)]
                 (clojure.test/is (nil? msg#) msg#)))]))))
