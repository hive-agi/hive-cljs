(ns hive-cljs.shadow.sync-db
  "Pure reducer for shadow-cljs sync-db traffic.

   The relay pushes a full `db-sync` snapshot once, then `db-update` change
   tuples. This namespace folds them into a build table; the socket adapter owns
   only transport."
  (:require [hive-cljs.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn empty-db [] {})

(defn apply-snapshot
  "Fold a `db-sync` reply into the build table, keyed by build id."
  [db prof msg]
  (let [id-key (:relay/build-id-key prof)]
    (reduce (fn [acc entity]
              (if-let [id (get entity id-key)]
                (assoc acc id entity)
                acc))
            db
            (get msg (:relay/builds-key prof)))))

(defn apply-change
  "Fold one sync-db change tuple into the build table.
   Tuples not addressing the build table are ignored."
  [db prof change]
  (let [table (:relay/build-table prof)]
    (if-not (vector? change)
      db
      (let [[kind t entry-id k v] change]
        (if-not (= table t)
          db
          (case kind
            :table-add     (assoc db entry-id k)
            :table-remove  (dissoc db entry-id)
            :entity-add    (assoc-in db [entry-id k] v)
            :entity-update (assoc-in db [entry-id k] v)
            :entity-remove (update db entry-id dissoc k)
            db))))))

(defn apply-changes
  [db prof changes]
  (reduce #(apply-change %1 prof %2) db (or changes [])))

(defn build-ids
  [db]
  (vec (keys db)))

(defn raw-status
  "Raw build-status payload for a build, or nil."
  [db prof build-id]
  (get-in db [build-id (:relay/build-status-key prof)]))

(defn worker-active?
  [db prof build-id]
  (boolean (get-in db [build-id (:relay/worker-active-key prof)])))

(defn changed-builds
  "Build ids whose status attribute appears in a change batch."
  [prof changes]
  (let [table  (:relay/build-table prof)
        status (:relay/build-status-key prof)]
    (into []
          (comp (filter vector?)
                (filter (fn [[kind t _ k]]
                          (and (contains? #{:entity-add :entity-update} kind)
                               (= table t)
                               (= status k))))
                (map (fn [[_ _ entry-id]] entry-id))
                (distinct))
          (or changes []))))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> apply-change [:=> [:cat :map [:map-of :keyword :any] :any] :map])
(m/=> apply-changes [:=> [:cat :map [:map-of :keyword :any] [:maybe [:sequential :any]]] :map])
(m/=> build-ids [:=> [:cat :map] [:vector :any]])
(m/=> changed-builds [:=> [:cat [:map-of :keyword :any] [:maybe [:sequential :any]]]
                      [:vector s/BuildId]])
