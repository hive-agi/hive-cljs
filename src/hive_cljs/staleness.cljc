(ns hive-cljs.staleness
  "PROMOTE layer — decide whether cached state still describes the world.

   Two axes:
   - manifest vs disk, from source stamps collected at the boundary
   - declared builds vs the builds the connected toolchain reports"
  (:require [clojure.set :as set]
            [hive-cljs.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Manifest vs disk
;; =============================================================================

(defn sources-changed?
  "True when `current` stamps differ from the `cached` ones.

   Either side empty is not a witness of change: nothing was recorded, so no
   difference can be attributed to an edit."
  [cached current]
  (boolean (and (seq cached) (seq current) (not= (vec cached) (vec current)))))

(defn freshness
  [cached current]
  (if (sources-changed? cached current) :stale :fresh))

;; =============================================================================
;; Declared builds vs served builds
;; =============================================================================

(defn server-match
  "Compare the builds a manifest declares with the builds a toolchain reports.

   `:unknown` when either side is empty — a disjointness claim needs both."
  [declared reported]
  (let [d (set declared)
        r (set reported)]
    (cond
      (or (empty? d) (empty? r))   :unknown
      (seq (set/intersection d r)) :ok
      :else                        :mismatch)))

(defn wrong-server?
  [declared reported]
  (= :mismatch (server-match declared reported)))

;; =============================================================================
;; Emitted bundle vs the sources it was built from
;; =============================================================================

(defn bundle-freshness
  "Compare a build's newest emitted output with the newest source under it.

   `:unknown` when either side is missing — an absent output directory and an
   up-to-the-second bundle are not the same claim, and only one of them is
   worth reporting."
  [compiled newest-source]
  (if (or (nil? compiled) (nil? newest-source)
          (zero? compiled) (zero? newest-source))
    :unknown
    (if (< compiled newest-source) :stale :fresh)))

(defn bundle-stamp
  "Build id + filesystem facts → `schema/BundleStamp`."
  [build-id {:keys [output-dir compiled newest-source]}]
  (cond-> {:bundle/build         build-id
           :bundle/state         (bundle-freshness compiled newest-source)
           :bundle/compiled      (or compiled 0)
           :bundle/newest-source (or newest-source 0)}
    output-dir (assoc :bundle/output-dir (str output-dir))))

(defn bundle-stamps
  "Facts keyed by build id → a stamp vector, sorted by build id."
  [facts]
  (mapv (fn [[bid f]] (bundle-stamp bid f)) (sort-by key (or facts {}))))

(defn stale-bundles
  "Build ids whose emitted output predates their own sources."
  [stamps]
  (->> stamps (filter #(= :stale (:bundle/state %))) (mapv :bundle/build)))

;; =============================================================================
;; Report
;; =============================================================================

(defn report
  "Cached + current stamps, declared + reported builds and per-build bundle
   facts → `schema/StalenessReport`."
  [{:keys [cached-sources current-sources declared-builds reported-builds bundles]}]
  {:staleness/manifest        (freshness cached-sources current-sources)
   :staleness/sources         (vec current-sources)
   :staleness/server          (server-match declared-builds reported-builds)
   :staleness/declared-builds (vec declared-builds)
   :staleness/reported-builds (vec reported-builds)
   :staleness/bundles         (bundle-stamps bundles)})

(m/=> bundle-freshness
      [:=> [:cat [:maybe s/Millis] [:maybe s/Millis]] s/BundleFreshness])
(m/=> bundle-stamp [:=> [:cat s/BuildId [:map-of :keyword :any]] s/BundleStamp])
(m/=> bundle-stamps [:=> [:cat [:maybe [:map-of s/BuildId :any]]] [:vector s/BundleStamp]])
(m/=> stale-bundles [:=> [:cat [:sequential s/BundleStamp]] [:vector s/BuildId]])

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> sources-changed?
      [:=> [:cat [:maybe [:sequential s/SourceStamp]] [:maybe [:sequential s/SourceStamp]]]
       :boolean])
(m/=> freshness
      [:=> [:cat [:maybe [:sequential s/SourceStamp]] [:maybe [:sequential s/SourceStamp]]]
       s/ManifestFreshness])
(m/=> server-match
      [:=> [:cat [:maybe [:sequential s/BuildId]] [:maybe [:sequential s/BuildId]]]
       s/ServerMatch])
(m/=> wrong-server?
      [:=> [:cat [:maybe [:sequential s/BuildId]] [:maybe [:sequential s/BuildId]]] :boolean])
(m/=> report [:=> [:cat [:map-of :keyword :any]] s/StalenessReport])
