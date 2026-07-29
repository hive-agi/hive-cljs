(ns hive-cljs.manifest
  "COLLECT layer — turn a project-authored `hive-cljs.edn` into a normalized
   `schema/Manifest` with every default resolved.

   Pure: file reading lives in the boundary."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hive-cljs.schema :as s]
            [hive-dsl.result :as r]
            [malli.core :as m]
            [malli.error :as me]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def manifest-filename "hive-cljs.edn")

(def project-filename ".hive-project.edn")

(defn project-config
  "Extract hive-cljs config from a `.hive-project.edn` map.

   Accepts both spellings: a nested `:hive.cljs` submap with short keys, and
   flat `:hive.cljs/*` keys. Flat wins on collision. Returns {} when neither is
   present."
  [project-edn]
  (if-not (map? project-edn)
    {}
    (let [flat   (into {} (filter (fn [[k _]] (and (keyword? k)
                                                   (= "hive.cljs" (namespace k)))))
                       project-edn)
          nested (into {} (map (fn [[k v]] [(keyword "hive.cljs" (name k)) v]))
                       (let [n (:hive.cljs project-edn)] (when (map? n) n)))]
      (merge nested flat))))

(defn merge-config
  "Merge raw config maps left-to-right; later wins. Top-level sections are
   merged one level deep, so a base section may be split across sources."
  [& configs]
  (or (apply merge-with
             (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))
             (remove nil? configs))
      {}))

(def inherit-key
  "Config key by which a project opts in to ancestor config."
  :hive.cljs/inherit)

(defn inherit?
  "True when a raw config asks for ancestor descriptors to be merged under it."
  [raw]
  (true? (get raw inherit-key)))

(defn config-authored?
  "True when a raw config carries anything beyond the inheritance switch."
  [raw]
  (boolean (seq (dissoc raw inherit-key))))

(def default-shadow  {:host "localhost" :port 9630})
(def default-e2e     {:browser :chromium :headless true :timeout-ms 15000})
(def default-watch   {:on-build-success [] :on-build-failure [] :debounce-ms 500})
(def default-artifacts-dir ".hive-cljs/artifacts")

;; =============================================================================
;; Promoters — one decision each
;; =============================================================================

(defn normalize-shadow
  "Resolve shadow connectivity defaults."
  [raw]
  (merge default-shadow (select-keys (or raw {}) [:host :port :nrepl-port])))

(defn normalize-build
  "Resolve one build spec; `id` supplies :shadow/id when absent."
  [id raw]
  (let [raw (or raw {})]
    (cond-> {:shadow/id (or (:shadow/id raw) id)}
      (:http-port raw) (assoc :http-port (:http-port raw))
      (:entry raw)     (assoc :entry (:entry raw)))))

(defn normalize-builds
  [raw]
  (reduce-kv (fn [acc id spec] (assoc acc id (normalize-build id spec)))
             {}
             (or raw {})))

(defn normalize-scenario
  "Resolve one scenario; steps are kept as authored."
  [raw]
  (cond-> {:id    (:id raw)
           :steps (vec (:steps raw))}
    (:build raw) (assoc :build (:build raw))
    (:doc raw)   (assoc :doc (:doc raw))
    (seq (:tags raw)) (assoc :tags (set (:tags raw)))))

(defn infer-base-url
  "Base URL for the e2e run: explicit, else derived from a build's :http-port."
  [raw-e2e builds]
  (or (:base-url raw-e2e)
      (when-let [port (some :http-port (vals builds))]
        (str "http://localhost:" port))
      "http://localhost:8080"))

(defn normalize-e2e
  [raw builds root]
  (let [raw (or raw {})]
    (merge default-e2e
           {:base-url      (infer-base-url raw builds)
            :artifacts-dir (or (:artifacts-dir raw)
                               (str (str/replace root #"/$" "") "/" default-artifacts-dir))
            :scenarios     (mapv normalize-scenario (:scenarios raw))}
           (select-keys raw [:browser :headless :timeout-ms]))))

(defn normalize-action
  "Coerce a watch action to the `[kind opts]` tuple shape."
  [a]
  (cond
    (keyword? a)                    [a {}]
    (and (vector? a) (= 1 (count a))) [(first a) {}]
    (vector? a)                     [(first a) (or (second a) {})]
    :else                           [:ignore {}]))

(defn normalize-watch
  [raw]
  (let [raw (or raw {})]
    (cond-> (merge default-watch
                   {:on-build-success (mapv normalize-action (:on-build-success raw))
                    :on-build-failure (mapv normalize-action (:on-build-failure raw))}
                   (select-keys raw [:debounce-ms]))
      (seq (:builds raw)) (assoc :builds (set (:builds raw))))))

;; =============================================================================
;; Pipeline
;; =============================================================================

(defn normalize
  "Raw `hive-cljs.edn` map + project root → normalized manifest map."
  [raw root]
  (let [builds (normalize-builds (:hive.cljs/builds raw))]
    {:manifest/root   root
     :manifest/shadow (normalize-shadow (:hive.cljs/shadow raw))
     :manifest/builds builds
     :manifest/e2e    (normalize-e2e (:hive.cljs/e2e raw) builds root)
     :manifest/watch  (normalize-watch (:hive.cljs/watch raw))}))

(defn validate
  "Result of a normalized manifest, or an :manifest/invalid error with
   humanized malli explanation."
  [manifest]
  (if (m/validate s/Manifest manifest)
    (r/ok manifest)
    (r/err :manifest/invalid
           {:explain (me/humanize (m/explain s/Manifest manifest))})))

(defn parse
  "Raw config + root → Result of a validated normalized manifest.
   `sources` records which files the config came from."
  ([raw root] (parse raw root nil))
  ([raw root sources]
   (if-not (map? raw)
     (r/err :manifest/not-a-map {:got (type raw)})
     (validate (cond-> (normalize raw root)
                 (seq sources) (assoc :manifest/sources (vec sources)))))))

;; =============================================================================
;; Queries
;; =============================================================================

(defn scenarios
  "All scenarios in the manifest."
  [manifest]
  (get-in manifest [:manifest/e2e :scenarios]))

(defn scenario
  "Scenario by id, or nil."
  [manifest id]
  (first (filter #(= id (:id %)) (scenarios manifest))))

(defn scenarios-by-tag
  "Scenarios carrying any of `tags`. Empty `tags` selects all."
  [manifest tags]
  (let [tags (set tags)]
    (if (empty? tags)
      (vec (scenarios manifest))
      (vec (filter #(seq (set/intersection tags (set (:tags %))))
                   (scenarios manifest))))))

(defn build-ids
  [manifest]
  (vec (keys (:manifest/builds manifest))))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> normalize-shadow [:=> [:cat [:maybe [:map-of :keyword :any]]] s/ShadowConfig])
(m/=> normalize-builds [:=> [:cat [:maybe [:map-of :keyword :any]]]
                        [:map-of s/BuildId s/BuildSpec]])
(m/=> normalize-action [:=> [:cat :any] s/WatchAction])
(m/=> normalize [:=> [:cat [:map-of :keyword :any] s/NonBlankString] :map])
(m/=> scenarios [:=> [:cat s/Manifest] [:vector s/Scenario]])
(m/=> build-ids [:=> [:cat s/Manifest] [:vector s/BuildId]])
