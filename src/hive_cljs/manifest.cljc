(ns hive-cljs.manifest
  "COLLECT layer — turn a project-authored `hive-cljs.edn` into a normalized
   `schema/Manifest` with every default resolved.

   Pure: file reading lives in the boundary."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [hive-cljs.schema :as s]
            [hive-dsl.result :as r]
            [malli.core :as m]
            [malli.error :as me]
            [hive-cljs.mutation :as mutation]))

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

(def default-toolchain
  "Toolchain assumed when a project declares none — the historical behaviour,
   so an existing config keeps working unchanged."
  :shadow-cljs)
(def default-e2e     {:browser :chromium :headless true :timeout-ms 15000 :poll-ms 250})
(def default-watch   {:on-build-success [] :on-build-failure [] :debounce-ms 500})
(def default-artifacts-dir ".hive-cljs/artifacts")

;; =============================================================================
;; Promoters — one decision each
;; =============================================================================

(defn normalize-shadow
  "Resolve shadow connectivity defaults."
  [raw]
  (merge default-shadow (select-keys (or raw {}) [:host :port :nrepl-port])))

(defn normalize-toolchain
  "Which toolchain opens this project's build and runtime channels."
  [raw]
  (or raw default-toolchain))

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
    (:frame raw) (assoc :frame (:frame raw))
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
            :scenarios     (mapv normalize-scenario (:scenarios raw))
            :faults        (mutation/normalize-faults (:faults raw))}
           (select-keys raw [:browser :headless :timeout-ms :poll-ms :frame
                             :app-db-schema :app-db-check]))))

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

(def default-coverage
  {:profile     :coverage/c8
   :runner      ["node"]
   :report-dir  "target/coverage"
   :exclude     ["*-test"]
   :thresholds  {}})

(defn normalize-thresholds
  "Author-written percentages (ints are natural) → the schema's doubles."
  [raw]
  (into {} (map (fn [[k v]] [k (double v)])) (or raw {})))

(defn normalize-coverage
  "Resolve the coverage section. Returns nil when the project authors none —
   coverage is opt-in, and an absent section must not fabricate a build id.

   Paths stay relative: the boundary runs the plan with the project root as cwd.
   `:compile false` (or an empty vector) measures the bundle already on disk."
  [raw]
  (when (map? raw)
    (let [build   (or (:build raw) :unit-node)
          compile (:compile raw)]
      (cond-> {:coverage/build           build
               :coverage/bundle          (or (:bundle raw)
                                             (str "target/" (name build) ".js"))
               :coverage/profile         (or (:profile raw) (:profile default-coverage))
               :coverage/compile         (cond
                                           (false? compile) []
                                           (seq compile)    (vec compile)
                                           :else ["npx" "shadow-cljs" "compile" (name build)])
               :coverage/source-prefixes (vec (or (:source-prefixes raw) []))
               :coverage/exclude         (vec (or (:exclude raw) (:exclude default-coverage)))
               :coverage/report-dir      (or (:report-dir raw) (:report-dir default-coverage))
               :coverage/thresholds      (normalize-thresholds (:thresholds raw))
               :coverage/runner          (vec (or (:runner raw) (:runner default-coverage)))}
        (:baseline raw) (assoc :coverage/baseline (:baseline raw))))))

;; =============================================================================
;; Pipeline
;; =============================================================================

(defn normalize
  "Raw `hive-cljs.edn` map + project root → normalized manifest map."
  [raw root]
  (let [builds   (normalize-builds (:hive.cljs/builds raw))
        coverage (normalize-coverage (:hive.cljs/coverage raw))]
    (cond-> {:manifest/root      root
             :manifest/toolchain (normalize-toolchain (:hive.cljs/toolchain raw))
             :manifest/shadow    (normalize-shadow (:hive.cljs/shadow raw))
             :manifest/builds    builds
             :manifest/e2e       (normalize-e2e (:hive.cljs/e2e raw) builds root)
             :manifest/watch     (normalize-watch (:hive.cljs/watch raw))}
      coverage (assoc :manifest/coverage coverage))))

(defn validate
  "Result of a normalized manifest, or an :manifest/invalid error with
   humanized malli explanation."
  [manifest]
  (if (m/validate s/Manifest manifest)
    (r/ok manifest)
    (r/err :manifest/invalid
           {:explain (me/humanize (m/explain s/Manifest manifest))})))

(defn duplicate-scenario-ids
  "Scenario ids declared more than once, sorted.

   Scenarios may arrive from several files once `:scenario-paths` is in play,
   so a collision is a config error rather than a last-one-wins merge."
  [scenarios]
  (->> (map :id scenarios)
       frequencies
       (keep (fn [[id n]] (when (< 1 n) id)))
       sort
       vec))

(defn parse
  "Raw config + root → Result of a validated normalized manifest.
   `sources` records which files the config came from."
  ([raw root] (parse raw root nil))
  ([raw root sources]
   (if-not (map? raw)
     (r/err :manifest/not-a-map {:got (type raw)})
     (let [m    (cond-> (normalize raw root)
                  (seq sources) (assoc :manifest/sources (vec sources)))
           dups (duplicate-scenario-ids (get-in m [:manifest/e2e :scenarios]))]
       (if (seq dups)
         (r/err :manifest/duplicate-scenario {:ids dups})
         (validate m))))))

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

(defn coverage
  "Normalized coverage config, or nil when the project authors none."
  [manifest]
  (:manifest/coverage manifest))

(m/=> normalize-coverage [:=> [:cat :any] [:maybe s/CoverageConfig]])
(m/=> normalize-thresholds [:=> [:cat :any] s/CoverageThresholds])
(m/=> coverage [:=> [:cat s/Manifest] [:maybe s/CoverageConfig]])

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> normalize-shadow [:=> [:cat [:maybe [:map-of :keyword :any]]] s/ShadowConfig])

(m/=> normalize-toolchain [:=> [:cat :any] s/ToolchainId])
(m/=> normalize-builds [:=> [:cat [:maybe [:map-of :keyword :any]]]
                        [:map-of s/BuildId s/BuildSpec]])
(m/=> normalize-action [:=> [:cat :any] s/WatchAction])
(m/=> normalize [:=> [:cat [:map-of :keyword :any] s/NonBlankString] :map])
(m/=> scenarios [:=> [:cat s/Manifest] [:vector s/Scenario]])
(m/=> build-ids [:=> [:cat s/Manifest] [:vector s/BuildId]])
