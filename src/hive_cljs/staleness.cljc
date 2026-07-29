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
;; Report
;; =============================================================================

(defn report
  "Cached + current stamps and declared + reported builds → `schema/StalenessReport`."
  [{:keys [cached-sources current-sources declared-builds reported-builds]}]
  {:staleness/manifest        (freshness cached-sources current-sources)
   :staleness/sources         (vec current-sources)
   :staleness/server          (server-match declared-builds reported-builds)
   :staleness/declared-builds (vec declared-builds)
   :staleness/reported-builds (vec reported-builds)})

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
