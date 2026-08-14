(ns hive-cljs.coverage
  "PURE layer for ClojureScript coverage.

   Turns a normalized `schema/CoverageConfig` plus a coverage profile into a
   `schema/CoveragePlan` (the argv to run), and an istanbul summary map into a
   `schema/CoverageReport` (rows, totals, baseline delta, threshold verdict).

   Authors write NAMESPACE patterns; this layer translates them to the compiled
   module globs the coverage tool actually filters on. Process execution and
   file reading live in the boundary."
  (:require [clojure.string :as str]
            [hive-cljs.profile :as profile]
            [hive-cljs.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Namespace ⇄ module name
;; =============================================================================

(defn munge-ns
  "ClojureScript namespace spelling → the compiler's module spelling.
   `*` is preserved so a pattern stays a pattern."
  [ns-pattern]
  (str/replace (str ns-pattern) "-" "_"))

(defn demunge-ns
  "A source-mapped coverage key → the ClojureScript namespace it belongs to.

   The key is the path the source map records (`…/cljs-runtime/a/b_c.cljs`),
   which is the NESTED spelling even when the emitted module is flat."
  [k]
  (let [path (or (last (str/split (str k) #"cljs-runtime/")) (str k))]
    (-> path
        (str/replace #"\.clj[sc]$" "")
        (str/replace "/" ".")
        (str/replace "_" "-"))))

(defn glob-root
  "Directory prefix the module globs are anchored at.

   The final segment of the profile's compiled-root, made position-independent
   with `**/` so the glob survives a different build or output directory."
  [prof]
  (let [segs (remove str/blank? (str/split (:coverage/compiled-root prof) #"/"))]
    (str "**/" (last segs))))

;; =============================================================================
;; Layout — OCP: a new emitted-module layout is a new defmethod
;; =============================================================================

(defmulti module-globs
  "Compiled-module globs matching one namespace pattern, for the profile's
   emitted layout. Dispatches on `:coverage/compiled-layout`."
  (fn [prof _ns-pattern] (:coverage/compiled-layout prof)))

(defmethod module-globs :flat-dotted
  [prof ns-pattern]
  (let [root   (glob-root prof)
        suffix (:coverage/module-suffix prof)
        m      (munge-ns ns-pattern)]
    (if (str/includes? m "*")
      [(str root "/" m suffix)]
      ;; A bare pattern is a PREFIX: the namespace itself and everything under it.
      [(str root "/" m suffix)
       (str root "/" m ".*" suffix)])))

(defmethod module-globs :nested
  [prof ns-pattern]
  (let [root   (glob-root prof)
        suffix (:coverage/module-suffix prof)
        m      (str/replace (munge-ns ns-pattern) "." "/")]
    (if (str/includes? m "*")
      [(str root "/" m suffix)]
      [(str root "/" m suffix)
       (str root "/" m "/**/*" suffix)])))

(defmethod module-globs :default
  [prof ns-pattern]
  (module-globs (assoc prof :coverage/compiled-layout :flat-dotted) ns-pattern))

;; =============================================================================
;; Plan
;; =============================================================================

(defn- flag-pairs [flag values]
  (into [] (mapcat (fn [v] [flag v])) values))

(defn summary-path
  "Where the profile's machine-readable summary lands for this report dir."
  [prof report-dir]
  (str (str/replace report-dir #"/$" "") "/" (:coverage/summary-file prof)))

(defn argv
  "The full command line for one coverage run."
  [prof config]
  (let [includes (mapcat #(module-globs prof %) (:coverage/source-prefixes config))
        excludes (mapcat #(module-globs prof %) (:coverage/exclude config))]
    (vec (concat (:coverage/command prof)
                 (flag-pairs (:coverage/reporter-flag prof) (:coverage/reporters prof))
                 [(:coverage/report-dir-flag prof) (:coverage/report-dir config)]
                 (flag-pairs (:coverage/include-flag prof) includes)
                 (flag-pairs (:coverage/exclude-flag prof) excludes)
                 (:coverage/runner config)
                 [(:coverage/bundle config)]))))

(defn plan
  "Config + project root → a `schema/CoveragePlan`."
  [config root]
  (let [prof (profile/coverage-profile (:coverage/profile config))]
    {:plan/compile-argv (vec (:coverage/compile config))
     :plan/argv         (argv prof config)
     :plan/cwd          root
     :plan/summary-path (summary-path prof (:coverage/report-dir config))}))

;; =============================================================================
;; Summary → rows
;; =============================================================================

(defn pct
  "Percentage covered, always within [0, 100].

   A dimension with nothing to instrument is 0.0 — never a division by zero and
   never istanbul's \"Unknown\" string. The upper clamp keeps the function total
   for inputs a malformed report could carry."
  [covered total]
  (if (pos? total)
    (min 100.0 (* 100.0 (/ (double covered) (double total))))
    0.0))

(defn metric
  [raw]
  (let [covered (or (:covered raw) 0)
        total   (or (:total raw) 0)]
    {:metric/covered covered
     :metric/total   total
     :metric/pct     (pct covered total)}))

(defn source-key?
  "True for an istanbul entry that is a ClojureScript source file.

   Drops istanbul's synthetic `total` row. What counts as PROJECT source is the
   config's include/exclude patterns, applied by the coverage tool — never a
   second, hidden policy here."
  [[k _]]
  (and (not= "total" (name k))
       (some? (re-find #"\.clj[sc]$" (str (name k))))))

(defn row
  [[k v]]
  {:coverage/ns        (demunge-ns (name k))
   :coverage/file      (str (name k))
   :coverage/lines     (metric (:lines v))
   :coverage/branches  (metric (:branches v))
   :coverage/functions (metric (:functions v))})

(defn rows
  "Istanbul summary map → coverage rows, sorted by namespace."
  [summary]
  (->> (or summary {})
       (filter source-key?)
       (map row)
       (sort-by :coverage/ns)
       vec))

(defn- sum-metric [rows k]
  (let [covered (reduce + 0 (map #(get-in % [k :metric/covered]) rows))
        total   (reduce + 0 (map #(get-in % [k :metric/total]) rows))]
    {:metric/covered covered
     :metric/total   total
     :metric/pct     (pct covered total)}))

(defn totals
  [rows]
  {:coverage/namespaces (count rows)
   :coverage/lines      (sum-metric rows :coverage/lines)
   :coverage/branches   (sum-metric rows :coverage/branches)
   :coverage/functions  (sum-metric rows :coverage/functions)})

;; =============================================================================
;; Delta
;; =============================================================================

(defn- covered [row k] (get-in row [k :metric/covered] 0))

(defn deltas
  "Per-namespace change in COVERED COUNTS against baseline rows.

   Counts, not percentages: a compiled-JS coverage tool only enumerates ranges
   inside functions it actually executed, so new tests enlarge the denominator
   and a percentage can fall while coverage genuinely rose."
  [now baseline]
  (let [by-ns (into {} (map (juxt :coverage/ns identity)) baseline)]
    (vec (for [r now
               :let [b (get by-ns (:coverage/ns r))]]
           {:delta/ns        (:coverage/ns r)
            :delta/lines     (- (covered r :coverage/lines) (covered b :coverage/lines))
            :delta/branches  (- (covered r :coverage/branches) (covered b :coverage/branches))
            :delta/functions (- (covered r :coverage/functions) (covered b :coverage/functions))
            :delta/new?      (nil? b)}))))

(defn regressions
  "Namespaces that lost covered lines, branches or functions."
  [deltas]
  (vec (filter #(or (neg? (:delta/lines %))
                    (neg? (:delta/branches %))
                    (neg? (:delta/functions %)))
               deltas)))

;; =============================================================================
;; Verdict
;; =============================================================================

(def dimension->key
  {:lines :coverage/lines :branches :coverage/branches :functions :coverage/functions})

(defn verdict
  "Threshold verdict over the run totals. No rows at all is `:unavailable` —
   a suite that measured nothing must never read as a pass."
  [totals thresholds]
  (if (zero? (:coverage/namespaces totals 0))
    {:coverage/state :unavailable :coverage/breaches []}
    (let [breaches (vec (for [[dim required] (sort-by key thresholds)
                              :let [actual (get-in totals [(dimension->key dim) :metric/pct] 0.0)]
                              :when (< actual (double required))]
                          {:breach/dimension dim
                           :breach/required  (double required)
                           :breach/actual    actual}))]
      {:coverage/state    (if (seq breaches) :below-threshold :pass)
       :coverage/breaches breaches})))

(defn report
  "Summary map + baseline summary map → a `schema/CoverageReport`."
  [config summary baseline-summary]
  (let [rs (rows summary)
        ts (totals rs)]
    (cond-> {:coverage/build      (:coverage/build config)
             :coverage/rows       rs
             :coverage/totals     ts
             :coverage/verdict    (verdict ts (:coverage/thresholds config))
             :coverage/report-dir (:coverage/report-dir config)}
      (seq baseline-summary) (assoc :coverage/deltas (deltas rs (rows baseline-summary))))))

;; =============================================================================
;; Presentation
;; =============================================================================

(defn worst-first
  "Rows ordered by line coverage ascending — what to work on next, first."
  [rows]
  (vec (sort-by (fn [r] [(get-in r [:coverage/lines :metric/pct])
                         (:coverage/ns r)])
                rows)))

(defn matching
  "Rows whose namespace contains `q`. A blank query keeps every row."
  [rows q]
  (if (str/blank? (str q))
    (vec rows)
    (vec (filter #(str/includes? (:coverage/ns %) (str q)) rows))))

(defn brief
  "One row reduced to a report line: percentage plus the covered/total counts
   the percentage is derived from, because only the counts compare across runs."
  [row]
  {:ns        (:coverage/ns row)
   :lines     [(get-in row [:coverage/lines :metric/pct])
               (get-in row [:coverage/lines :metric/covered])
               (get-in row [:coverage/lines :metric/total])]
   :branches  [(get-in row [:coverage/branches :metric/pct])
               (get-in row [:coverage/branches :metric/covered])
               (get-in row [:coverage/branches :metric/total])]
   :functions [(get-in row [:coverage/functions :metric/pct])
               (get-in row [:coverage/functions :metric/covered])
               (get-in row [:coverage/functions :metric/total])]})

(m/=> worst-first [:=> [:cat [:vector s/CoverageRow]] [:vector s/CoverageRow]])
(m/=> matching [:=> [:cat [:vector s/CoverageRow] :any] [:vector s/CoverageRow]])
(m/=> brief [:=> [:cat s/CoverageRow] [:map-of :keyword :any]])

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> munge-ns [:=> [:cat :any] :string])
(m/=> demunge-ns [:=> [:cat :any] :string])
(m/=> glob-root [:=> [:cat [:map-of :keyword :any]] s/NonBlankString])
(m/=> module-globs [:=> [:cat [:map-of :keyword :any] :any] [:sequential s/NonBlankString]])
(m/=> summary-path [:=> [:cat [:map-of :keyword :any] s/NonBlankString] s/NonBlankString])
(m/=> argv [:=> [:cat [:map-of :keyword :any] s/CoverageConfig] [:vector {:min 1} s/NonBlankString]])
(m/=> plan [:=> [:cat s/CoverageConfig s/NonBlankString] s/CoveragePlan])
(m/=> pct [:=> [:cat :int :int] s/Percentage])
(m/=> metric [:=> [:cat [:maybe [:map-of :keyword :any]]] s/CoverageMetric])
(m/=> rows [:=> [:cat [:maybe [:map-of :any :any]]] [:vector s/CoverageRow]])
(m/=> totals [:=> [:cat [:vector s/CoverageRow]] s/CoverageTotals])
(m/=> deltas [:=> [:cat [:vector s/CoverageRow] [:vector s/CoverageRow]] [:vector s/CoverageDelta]])
(m/=> regressions [:=> [:cat [:vector s/CoverageDelta]] [:vector s/CoverageDelta]])
(m/=> verdict [:=> [:cat s/CoverageTotals s/CoverageThresholds] s/CoverageVerdict])
(m/=> report [:=> [:cat s/CoverageConfig [:maybe [:map-of :any :any]] [:maybe [:map-of :any :any]]]
              s/CoverageReport])
