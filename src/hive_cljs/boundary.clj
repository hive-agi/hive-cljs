(ns hive-cljs.boundary
  "BOUNDARY layer — the only place a plan meets a port.

   Every collaborator arrives as an argument: `{:build-tool … :driver …
   :cljs-eval …}`. Nothing here names a vendor."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.plan :as plan]
            [hive-cljs.ports :as ports]
            [hive-cljs.shadow.nrepl :as nrepl-forms]
            [hive-cljs.verdict :as verdict]
            [hive-dsl.result :as r])
  (:import [java.io PushbackReader]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Manifest loading
;; =============================================================================

(defn manifest-path
  [root]
  (str (str/replace (str root) #"/$" "") "/" manifest/manifest-filename))

(defn project-path
  "Path to the project descriptor that may carry `:hive.cljs` config."
  [root]
  (str (str/replace (str root) #"/$" "") "/" manifest/project-filename))

(defn- env-value
  "`#hive/env VAR` or `#hive/env [VAR fallback]` → the environment value,
   coerced to a long when it reads as an integer."
  [spec]
  (let [[var-name fallback] (if (sequential? spec) spec [spec nil])
        raw (System/getenv (str var-name))]
    (cond
      (str/blank? raw)             fallback
      (re-matches #"[-+]?\d+" raw) (parse-long raw)
      :else                        raw)))

(def ^:private edn-readers {'hive/env env-value})

(defn- read-edn-file
  [f]
  (with-open [rdr (PushbackReader. (io/reader f))]
    (edn/read {:readers edn-readers} rdr)))

(defn ancestor-dirs
  "`root` and every ancestor directory, nearest first."
  [root]
  (loop [d   (.getAbsoluteFile (io/file (str root)))
         acc []]
    (if (nil? d)
      acc
      (recur (.getParentFile d) (conj acc (.getPath d))))))

(defn file-stamp
  "Filesystem facts about `path` → `schema/SourceStamp`."
  [path]
  (let [f       (io/file (str path))
        exists? (.exists f)]
    {:source/path     (str path)
     :source/exists?  exists?
     :source/modified (if exists? (.lastModified f) 0)
     :source/size     (if exists? (.length f) 0)}))

(defn source-stamps
  "Stamps for every path, in the order given."
  [paths]
  (mapv file-stamp paths))

(defn- level-config
  "Config authored at one directory, or nil when that directory authors none.
   `:sources` is ordered highest precedence first."
  [dir]
  (let [pp (project-path dir)
        mp (manifest-path dir)
        pf (io/file pp)
        mf (io/file mp)
        from-project (when (.exists pf) (manifest/project-config (read-edn-file pf)))
        from-file    (when (.exists mf) (read-edn-file mf))
        sources      (cond-> []
                       (some? from-file)  (conj mp)
                       (seq from-project) (conj pp))]
    (when (seq sources)
      {:dir dir
       :raw (manifest/merge-config from-project from-file)
       :sources sources})))

(defn resolve-config
  "Walk up from `root` and merge the config levels that apply.

   The nearest directory that authors config decides the project root. Ancestor
   levels merge underneath it only when that nearest level sets
   `:hive.cljs/inherit true`. Returns {:root dir :raw merged :sources paths}
   with sources highest precedence first, or nil when no level authors config."
  [root]
  (let [levels (keep level-config (ancestor-dirs root))]
    (when-let [nearest (first levels)]
      (let [chain (if (manifest/inherit? (:raw nearest)) (vec levels) [nearest])]
        {:root    (:dir nearest)
         :raw     (apply manifest/merge-config (map :raw (reverse chain)))
         :sources (vec (mapcat :sources chain))}))))

(def ^:private uninteresting-dirs
  #{"node_modules" "target" "out" ".git" ".shadow-cljs" ".hive-cljs" ".cpcache"})

(defn descendant-candidates
  "Directories below `root` that author hive-cljs config, bounded by `max-depth`."
  ([root] (descendant-candidates root 3))
  ([root max-depth]
   (letfn [(children [dir]
             (->> (or (.listFiles (io/file dir)) [])
                  (filter #(.isDirectory ^java.io.File %))
                  (remove #(str/starts-with? (.getName ^java.io.File %) "."))
                  (remove #(contains? uninteresting-dirs (.getName ^java.io.File %)))))
           (walk [dir depth]
             (if (neg? depth)
               []
               (into []
                     (mapcat (fn [^java.io.File k]
                               (let [p (.getPath k)]
                                 (cond-> (walk p (dec depth))
                                   (some? (level-config p)) (conj p)))))
                     (children dir))))]
     (try
       (vec (sort (walk (str root) max-depth)))
       (catch Exception _ [])))))

(defn load-manifest
  "Resolve hive-cljs config for `root` and return a Result of a normalized manifest.

   Two files author config at each directory level:
   - `.hive-project.edn` — a `:hive.cljs` submap, or flat `:hive.cljs/*` keys
   - `hive-cljs.edn`     — the dedicated file, which WINS on collision

   Resolution walks up from `root` and stops at the nearest directory that
   authors config; that directory becomes `:manifest/root`. Ancestor levels are
   merged underneath it only when the nearest level sets
   `:hive.cljs/inherit true`. `:manifest/sources` lists every contributing file,
   highest precedence first."
  [root]
  (try
    (if-let [{:keys [raw sources] resolved :root} (resolve-config root)]
      (manifest/parse raw resolved sources)
      (r/err :manifest/not-found
             {:searched   (vec (mapcat (juxt manifest-path project-path)
                                       (ancestor-dirs root)))
              :candidates (descendant-candidates root)}))
    (catch Exception e
      (r/err :manifest/unreadable {:root (str root) :cause (.getMessage e)}))))

;; =============================================================================
;; Runtime-channel execution
;; =============================================================================

(defn- runtime-expr
  "Source text a runtime op evaluates."
  [op]
  (let [[a b] (:op/args op)]
    (case (:op/kind op)
      :eval-cljs  (nrepl-forms/form->string a)
      :dispatch   (nrepl-forms/dispatch-form a)
      :expect-sub (nrepl-forms/predicate-call b (nrepl-forms/sub-form a))
      :expect-db  (nrepl-forms/predicate-call b (nrepl-forms/db-form a))
      (nrepl-forms/form->string a))))

(defn- assertion-op?
  [op]
  (contains? #{:expect-sub :expect-db} (:op/kind op)))

(defn perform-runtime!
  "Execute a :runtime op through ICljsEval. Returns an outcome map."
  [cljs-eval build-id op]
  (cond
    (nil? cljs-eval)
    {:state :incomplete
     :detail "no CLJS eval port connected — set :nrepl-port and check the shadow nREPL is reachable (`cljs close` re-probes a cached dead connection)"}

    (nil? build-id)
    {:state :error
     :detail "runtime step needs a build: set :build on the scenario, or declare exactly one build in the manifest"}

    :else
    (let [started (System/currentTimeMillis)
          res     (ports/eval-cljs cljs-eval build-id (runtime-expr op))
          elapsed (- (System/currentTimeMillis) started)]
      (if (r/err? res)
        {:state :error :detail (pr-str res) :elapsed-ms elapsed}
        (let [v (get-in res [:ok :value])]
          (if (assertion-op? op)
            (if (and (some? v) (not (false? v)))
              {:state :pass :detail (pr-str v) :elapsed-ms elapsed}
              {:state :fail :detail (str "predicate returned " (pr-str v))
               :elapsed-ms elapsed})
            {:state :pass :detail (pr-str v) :elapsed-ms elapsed}))))))

;; =============================================================================
;; Plan execution
;; =============================================================================

(defn- affinity-possible?
  "True when both ports can identify the driven page to each other."
  [{:keys [driver cljs-eval]}]
  (and (ports/page-marker? driver) (ports/runtime-affinity? cljs-eval)))

(defn- runtime-binding
  "Bind the runtime channel to the driven page, at most once per run.
   Returns nil when bound, or an outcome map when binding failed."
  [{:keys [cljs-eval]} state build-id token]
  (if (contains? @state :binding)
    (:binding @state)
    (let [res     (ports/bind-runtime! cljs-eval build-id token)
          outcome (when (r/err? res)
                    {:state :incomplete
                     :detail (str "runtime not bound to the driven page: " (pr-str res))})]
      (swap! state assoc :binding outcome)
      outcome)))

(defn- outcome-of
  [{:keys [driver cljs-eval] :as deps} state session build-id token op]
  (if (= :runtime (:op/channel op))
    (or (when (and token cljs-eval) (runtime-binding deps state build-id token))
        (perform-runtime! cljs-eval build-id op))
    (let [res (ports/perform! driver session op)]
      (if (r/err? res) {:state :error :detail (pr-str res)} (:ok res)))))

(defn run-plan!
  "Execute a RunPlan against injected ports. Returns a Result of a RunReport.

   deps: {:driver IBrowserDriver (required when the plan has browser ops)
          :cljs-eval ICljsEval   (required when the plan has runtime ops)}

   When both ports support it, the session is stamped and the runtime channel is
   pinned to that stamp, so state assertions read the browser this run drives.
   Steps after the first failure are reported as :skipped."
  [deps plan]
  (let [channels       (plan/channels-used plan)
        needs-browser? (contains? channels :browser)
        needs-runtime? (contains? channels :runtime)
        started        (System/currentTimeMillis)]
    (if (and needs-browser? (nil? (:driver deps)))
      (r/err :run/no-driver {:scenario (:plan/scenario plan)})
      (let [session-res (if needs-browser?
                          (ports/open-session! (:driver deps) (:plan/session plan))
                          (r/ok nil))]
        (if (r/err? session-res)
          session-res
          (let [session (:ok session-res)
                token   (when (and session needs-runtime? (affinity-possible? deps))
                          (let [t (str (random-uuid))]
                            (when (r/ok? (ports/mark-session! (:driver deps) session t)) t)))
                state   (atom {})]
            (try
              (let [{:keys [results artifacts]}
                    (reduce
                     (fn [{:keys [halted] :as acc} [idx op]]
                       (if halted
                         (update acc :results conj
                                 (verdict/skipped-result idx op "earlier step failed"))
                         (let [outcome (outcome-of deps state session (:plan/build plan) token op)
                               result  (verdict/step-result idx op outcome)]
                           (-> acc
                               (assoc :halted (contains? #{:fail :error} (:step/state result)))
                               (update :results conj result)
                               (update :artifacts into (:artifacts outcome))))))
                     {:halted false :results [] :artifacts []}
                     (map-indexed vector (:plan/ops plan)))]
                (r/ok (verdict/report (:plan/scenario plan) results
                                      {:elapsed-ms (- (System/currentTimeMillis) started)
                                       :artifacts artifacts})))
              (finally
                (when token (ports/unbind-runtime! (:cljs-eval deps)))
                (when session (ports/close-session! (:driver deps) session))))))))))

(defn run-scenario!
  "Manifest + scenario id → Result of a RunReport."
  [deps manifest scenario-id]
  (r/bind (plan/plan-for-id manifest scenario-id)
          #(run-plan! deps %)))

(defn run-scenarios!
  "Run several scenarios, collecting every report (no short-circuit)."
  [deps manifest ids]
  (r/ok (mapv (fn [id]
                (let [res (run-scenario! deps manifest id)]
                  (if (r/ok? res)
                    (:ok res)
                    {:run/scenario id :run/state :error
                     :run/steps [] :run/error res})))
              ids)))

(defn run-tagged!
  "Run every scenario carrying any of `tags`."
  [deps manifest tags]
  (run-scenarios! deps manifest (mapv :id (manifest/scenarios-by-tag manifest tags))))
