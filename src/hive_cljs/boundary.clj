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
            [hive-cljs.verdict :as verdict]
            [hive-dsl.result :as r]
            [hive-cljs.mutation :as mutation]
            [clojure.data.json :as json]
            [hive-cljs.coverage :as coverage]
            [clojure.walk :as walk]
            [hive-cljs.step :as step])
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

(def scenario-file-suffix ".edn")

(defn scenario-files
  "Scenario EDN files declared by `:scenario-paths`, resolved against `dir`.

   A path may name a directory (every *.edn below it, sorted for a stable
   scenario order) or a single file."
  [dir paths]
  (->> paths
       (mapcat (fn [p]
                 (let [f (io/file (str dir) (str p))]
                   (cond
                     (.isDirectory f)
                     (filter (fn [^java.io.File c]
                               (and (.isFile c)
                                    (str/ends-with? (.getName c) scenario-file-suffix)))
                             (file-seq f))

                     (.isFile f) [f]
                     :else       []))))
       (map (fn [^java.io.File f] (.getPath f)))
       sort
       vec))

(defn- read-scenario-file
  "Scenarios declared in one file: a bare vector of scenario maps, a map with
   :scenarios, or a single scenario map."
  [path]
  (let [v (read-edn-file (io/file (str path)))]
    (cond
      (vector? v)                   v
      (and (map? v) (:scenarios v)) (vec (:scenarios v))
      (map? v)                      [v]
      :else                         [])))

(defn load-scenario-paths
  "Fold `:scenario-paths` files into a raw config's `:scenarios`.

   Returns `[raw' files]`; the files join `:manifest/sources`, so editing a
   scenario file invalidates the cached session exactly like editing the
   manifest itself."
  [dir raw]
  (let [files (some->> (get-in raw [:hive.cljs/e2e :scenario-paths])
                       seq
                       (scenario-files dir))]
    (if-not (seq files)
      [raw []]
      [(update-in raw [:hive.cljs/e2e :scenarios]
                  (fn [declared]
                    (into (vec declared) (mapcat read-scenario-file) files)))
       (vec files)])))

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
      (let [[raw scenario-srcs] (load-scenario-paths
                                 dir (manifest/merge-config from-project from-file))]
        {:dir dir
         :raw raw
         :sources (into sources scenario-srcs)}))))

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
;; Bundle facts — what the toolchain last emitted, vs what it was built from
;; =============================================================================

(def shadow-filename "shadow-cljs.edn")

(def source-extensions #{".cljs" ".cljc" ".clj" ".js" ".edn"})

(defn shadow-config
  "The project's `shadow-cljs.edn`, or nil when it is absent or unreadable."
  [root]
  (let [f (io/file (str root) shadow-filename)]
    (when (.exists f)
      (try (read-edn-file f) (catch Exception _ nil)))))

(defn newest-modified
  "Last-modified millis of the newest file under `paths` carrying one of `exts`,
   or 0 when nothing matches."
  [root paths exts]
  (->> paths
       (map (fn [p] (io/file (str root) (str p))))
       (filter (fn [^java.io.File f] (.exists f)))
       (mapcat file-seq)
       (filter (fn [^java.io.File f] (.isFile f)))
       (filter (fn [^java.io.File f]
                 (some (fn [e] (str/ends-with? (.getName f) e)) exts)))
       (map (fn [^java.io.File f] (.lastModified f)))
       (reduce max 0)))

(defn- build-output-paths
  "Where a shadow build writes — `:output-dir` (browser) or `:output-to` (node)."
  [build-cfg]
  (keep identity [(:output-dir build-cfg) (:output-to build-cfg)]))

(defn bundle-facts
  "Per-build `{:output-dir :compiled :newest-source}` read off the filesystem.

   Answers the third staleness axis without any toolchain support: a build whose
   emitted output predates its own sources was served from a stale bundle,
   which is what a green run against a broken page looks like."
  [root build-ids]
  (if-let [cfg (shadow-config root)]
    (let [newest-src (newest-modified root (or (:source-paths cfg) ["src"])
                                      source-extensions)]
      (into {}
            (map (fn [bid]
                   (let [outs (build-output-paths (get-in cfg [:builds bid]))]
                     [bid {:output-dir    (first outs)
                           :compiled      (if (seq outs)
                                            (newest-modified root outs #{".js"})
                                            0)
                           :newest-source newest-src}])))
            build-ids))
    {}))

;; =============================================================================
;; Runtime-channel execution
;; =============================================================================

(def wait-kinds
  "Runtime steps that poll a condition instead of asserting it once.

   Defined by the step vocabulary, not here: a new stack's kinds must not need
   an edit to the boundary."
  step/poll-kinds)

(defn wait-op? [op] (contains? wait-kinds (:op/kind op)))

(def default-wait-timeout-ms 15000)
(def default-poll-ms 250)

(defn- probe-result
  "Split a probe's return into `[held? value]`, tolerating a runtime that
   printed something EDN could not read back."
  [v]
  (if (vector? v) [(first v) (second v)] [nil v]))

(defn- unrendered-outcome
  "A step the connected runtime channel has no rendering for.

   `:incomplete` rather than `:fail`: the assertion was never attempted, and a
   vocabulary the channel does not speak says nothing about the application."
  [op]
  {:state  :incomplete
   :detail (str "the runtime channel has no rendering for " (:op/kind op)
                " — that step vocabulary belongs to another stack")})

(defn- poll-runtime!
  "Poll a condition-wait op until its predicate holds or the budget expires.

   The failure detail carries the LAST OBSERVED value, not just a false —
   'never happened' and 'not yet' need different fixes."
  [cljs-eval build-id op {:keys [timeout-ms poll-ms]}]
  (if-let [expr (ports/probe-source cljs-eval op)]
    (let [budget   (or timeout-ms default-wait-timeout-ms)
          interval (max 50 (or poll-ms default-poll-ms))
          started  (System/currentTimeMillis)
          deadline (+ started budget)]
      (loop []
        (let [res     (ports/eval-cljs cljs-eval build-id expr)
              elapsed (- (System/currentTimeMillis) started)]
          (if (r/err? res)
            {:state :error :detail (pr-str res) :elapsed-ms elapsed}
            (let [[held? v] (probe-result (get-in res [:ok :value]))]
              (cond
                (and (some? held?) (not (false? held?)))
                {:state :pass :detail (pr-str v) :elapsed-ms elapsed}

                (< (System/currentTimeMillis) deadline)
                (do (Thread/sleep interval) (recur))

                :else
                {:state :fail
                 :detail (str "condition never held within " budget "ms — last value "
                              (pr-str v))
                 :elapsed-ms elapsed}))))))
    (unrendered-outcome op)))

(defn- assertion-op?
  [op]
  (contains? step/assertion-kinds (:op/kind op)))

(defn perform-runtime!
  "Execute a :runtime op through ICljsEval. Returns an outcome map.

   The channel renders the op into its own source language through
   `IRuntimeDialect`; this layer only decides what the returned value means.

   `opts` is the plan's `:plan/runtime` — the polling budget for condition-wait
   steps. Absent, those fall back to the module defaults."
  ([cljs-eval build-id op] (perform-runtime! cljs-eval build-id op {}))
  ([cljs-eval build-id op opts]
   (cond
     (nil? cljs-eval)
     {:state :incomplete
      :detail "no runtime eval port connected — set :nrepl-port and check the shadow nREPL is reachable (`cljs close` re-probes a cached dead connection)"}

     (nil? build-id)
     {:state :error
      :detail "runtime step needs a build: set :build on the scenario, or declare exactly one build in the manifest"}

     (not (ports/runtime-dialect? cljs-eval))
     {:state :incomplete
      :detail "the connected runtime channel renders no step vocabulary at all"}

     (wait-op? op)
     (poll-runtime! cljs-eval build-id op opts)

     :else
     (if-let [expr (ports/assertion-source cljs-eval op)]
       (let [started (System/currentTimeMillis)
             res     (ports/eval-cljs cljs-eval build-id expr)
             elapsed (- (System/currentTimeMillis) started)]
         (if (r/err? res)
           {:state :error :detail (pr-str res) :elapsed-ms elapsed}
           (let [v (get-in res [:ok :value])]
             (if (assertion-op? op)
               (if (and (some? v) (not (false? v)))
                 {:state :pass :detail (pr-str v) :elapsed-ms elapsed}
                 {:state :fail :detail (str "predicate returned " (pr-str v))
                  :elapsed-ms elapsed})
               {:state :pass :detail (pr-str v) :elapsed-ms elapsed}))))
       (unrendered-outcome op)))))

;; =============================================================================
;; app-db invariant channel
;; =============================================================================

(def read-only-kinds
  "Steps that only observe — nothing they do can corrupt app-db."
  #{:expect-text :expect-value :expect-visible :expect-hidden :expect-count
    :expect-url :expect-sub :expect-db :wait-for :wait-for-sub :wait-for-db
    :wait-ms :screenshot})

(defn invariant-applies?
  "True when the configured app-db invariant should be asserted after `op`."
  [{:keys [app-db-schema app-db-check]} op last?]
  (boolean
   (and app-db-schema
        (case (or app-db-check :every-step)
          :final     last?
          :mutations (not (contains? read-only-kinds (:op/kind op)))
          true))))

(defn check-invariant!
  "Assert the configured app-db schema in the runtime.

   nil when the state conforms; a failing outcome otherwise. An eval that blows
   up is reported rather than swallowed — an invariant that cannot run is not an
   invariant that held. A channel that cannot read application state at all is
   the same story, so it reports rather than passing quietly."
  [cljs-eval build-id {:keys [app-db-schema frame]}]
  (when (and cljs-eval build-id app-db-schema)
    (if-not (ports/runtime-introspection? cljs-eval)
      {:state  :incomplete
       :detail (str "the runtime channel cannot read application state, so "
                    app-db-schema " was never asserted")}
      (let [expr (ports/invariant-source cljs-eval app-db-schema frame)
            res  (ports/eval-cljs cljs-eval build-id expr)]
        (cond
          (r/err? res)
          {:state  :error
           :detail (str "app-db invariant could not be evaluated — does the build "
                        "carry " app-db-schema " and malli? " (pr-str res))}

          (some? (get-in res [:ok :value]))
          {:state  :fail
           :detail (str "app-db violates " app-db-schema ": "
                        (pr-str (get-in res [:ok :value])))})))))

;; =============================================================================
;; Plan execution
;; =============================================================================

(defn- affinity-possible?
  "True when both ports can identify the driven page to each other."
  [{:keys [driver cljs-eval]}]
  (and (ports/page-marker? driver) (ports/runtime-affinity? cljs-eval)))

(defn- bootstrap-channel!
  "Install the runtime channel's document bootstrap, before the first op.

   Before, because a contract the application calls into at STARTUP is useless
   installed after startup — the call would already have run against nothing.

   A failure here is not fatal to the run: only the steps that read through the
   contract depend on it, and those report the absence themselves. A scenario
   that never uses them should not be failed by a channel it does not use."
  [{:keys [driver cljs-eval]} session]
  (when (and session
             (ports/session-bound? cljs-eval)
             (ports/page-bootstrap? driver))
    (when-let [source (ports/bootstrap-source cljs-eval)]
      (ports/bootstrap! driver session source))))

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
  [{:keys [driver cljs-eval] :as deps} state session build-id token rt op]
  (if (= :runtime (:op/channel op))
    (or (when (and token cljs-eval) (runtime-binding deps state build-id token))
        (perform-runtime! cljs-eval build-id op rt))
    (let [res (ports/perform! driver session op)]
      (if (r/err? res) {:state :error :detail (pr-str res)} (:ok res)))))

(defn run-plan!
  "Execute a RunPlan against injected ports. Returns a Result of a RunReport.

   deps: {:driver IBrowserDriver (required when the plan has browser ops)
          :cljs-eval ICljsEval   (required when the plan has runtime ops)}

   A runtime channel reaches the driven page one of two ways. An `ISessionBound`
   channel evaluates INSIDE the session, so it is bound to it and its document
   bootstrap installed before the first op. An out-of-band channel cannot be
   bound, so instead the session is stamped and the channel pinned to that stamp
   — without which the REPL answers from whichever runtime the toolchain happens
   to pick, and any other open tab silently decides every state assertion.

   With `:app-db-schema` configured, a passing step is re-checked against that
   schema before it counts as green. Steps after the first failure are reported
   as :skipped."
  [deps plan]
  (let [channels       (plan/channels-used plan)
        needs-browser? (contains? channels :browser)
        needs-runtime? (contains? channels :runtime)
        rt             (:plan/runtime plan)
        build-id       (:plan/build plan)
        ops            (:plan/ops plan)
        last-index     (dec (count ops))
        started        (System/currentTimeMillis)]
    (if (and needs-browser? (nil? (:driver deps)))
      (r/err :run/no-driver {:scenario (:plan/scenario plan)})
      (let [session-res (if needs-browser?
                          (ports/open-session! (:driver deps) (:plan/session plan))
                          (r/ok nil))]
        (if (r/err? session-res)
          session-res
          (let [session (:ok session-res)
                deps    (cond-> deps
                          (and session (ports/session-bound? (:cljs-eval deps)))
                          (update :cljs-eval ports/with-session session))
                _       (bootstrap-channel! deps session)
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
                         (let [raw     (outcome-of deps state session build-id token rt op)
                               outcome (or (when (and (= :pass (:state raw))
                                                      (invariant-applies? rt op (= idx last-index)))
                                             (some-> (check-invariant! (:cljs-eval deps) build-id rt)
                                                     (assoc :elapsed-ms (:elapsed-ms raw))))
                                           raw)
                               result  (verdict/step-result idx op outcome)]
                           (-> acc
                               (assoc :halted (contains? #{:fail :error} (:step/state result)))
                               (update :results conj result)
                               (update :artifacts into (:artifacts raw))))))
                     {:halted false :results [] :artifacts []}
                     (map-indexed vector ops))]
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

(defn- goto-op
  "The plan's own first navigation, else one synthesized from its base-url."
  [plan]
  (or (first (filter #(= :goto (:op/kind %)) (:plan/ops plan)))
      {:op/kind :goto :op/channel :browser
       :op/args [(:plan/base-url plan)]
       :op/source [:goto (:plan/base-url plan)]}))

(defn probe-runtime!
  "Navigate to the app and evaluate `form-str` in the page that opens.

   Returns a Result of the value. There is no running app to interrogate until
   something has navigated to it, so a probe cannot be a bare eval — it needs a
   page, and it needs the runtime pinned to that page like any other run."
  [deps plan form-str]
  (let [probe (assoc plan
                     :plan/scenario :hive-cljs/probe
                     :plan/ops [(goto-op plan)
                                {:op/kind :eval-cljs :op/channel :runtime
                                 :op/args [form-str]
                                 :op/source [:eval-cljs form-str]}])
        res   (run-plan! deps probe)]
    (if (r/err? res)
      res
      (let [final (last (:run/steps (:ok res)))]
        (if (= :pass (:step/state final))
          (r/ok (edn/read-string (str (:step/detail final))))
          (r/err :probe/failed {:step final}))))))

;; =============================================================================
;; Mutation runs
;; =============================================================================

(defn derive-faults!
  "Ask the running app which handlers it registered, one fault per handler.

   The zero-config half of the catalog: no manifest entry, no knowledge of the
   app's internals — a suite that survives every neutralized subscription is a
   suite that never looked at the screen.

   Needs `IRuntimeIntrospection`: deriving a catalog means rewriting the app's
   own registry, which not every runtime channel can do. Declared faults stay
   available to the ones that cannot."
  [deps plan kinds]
  (let [ce (:cljs-eval deps)]
    (cond
      (empty? kinds) (r/ok [])

      ;; Only when a channel is actually CONNECTED. With none at all, the probe
      ;; below reports the missing port, and 'no runtime channel' is a better
      ;; diagnosis than 'this channel lacks a capability' — the reader would
      ;; otherwise go looking for a capability when nothing was connected.
      (and (some? ce) (not (ports/runtime-introspection? ce)))
      (r/err :mutation/no-introspection
             {:hint (str "this runtime channel cannot enumerate the application's "
                         "handlers — declare :faults explicitly under :hive.cljs/e2e")})

      :else
      (r/bind
       (probe-runtime! deps plan (when ce (ports/registry-source ce kinds)))
       (fn [registries]
         (r/ok (vec (mapcat (fn [k]
                              (map #(mutation/registry-fault
                                     k % (ports/neutralize-source ce k %))
                                   (get registries k)))
                            kinds))))))))

(defn- run-plans!
  "Run several plans, degrading a port failure into a red report rather than
   losing the scenario — a dropped plan would read as a fault nobody killed."
  [deps plans]
  (mapv (fn [p]
          (let [res (run-plan! deps p)]
            (if (r/ok? res)
              (:ok res)
              {:run/scenario (:plan/scenario p) :run/state :error
               :run/steps [] :run/error res})))
        plans))

(defn run-mutations!
  "Score `plans` against a fault catalog: each fault is injected, the suite is
   re-run, and a suite that stays GREEN failed to notice it.

   The unmutated baseline runs first and a red one is refused — a failing suite
   kills every mutant for the wrong reason and would score a perfect 1.0."
  [deps plans faults]
  (cond
    (empty? plans)
    (r/err :mutation/no-scenarios {})

    (empty? faults)
    (r/err :mutation/no-faults
           {:hint "declare :faults under :hive.cljs/e2e, or pass :auto to derive them"})

    :else
    (let [baseline (run-plans! deps plans)]
      (if-not (every? #(= :pass (:run/state %)) baseline)
        (r/err :mutation/baseline-red
               {:hint    "the suite must be green before a mutation score means anything"
                :summary (mapv verdict/summarize baseline)})
        (r/ok (mutation/report
               (mapv :plan/scenario plans)
               (mapv (fn [f]
                       (mutation/verdict
                        f (run-plans! deps (mapv #(mutation/inject % f) plans))))
                     faults)))))))

;; =============================================================================
;; Coverage
;; =============================================================================

(defn exec-argv!
  "Run `argv` with `cwd`. Result of {:exit :out :err}.

   A non-zero exit is DATA, not an error: a coverage tool exits non-zero when
   the suite fails, and that run still wrote a summary worth reading."
  [argv cwd]
  (try
    (let [pb   (doto (ProcessBuilder. ^java.util.List (vec argv))
                 (.directory (io/file cwd)))
          proc (.start pb)
          out  (slurp (.getInputStream proc))
          err  (slurp (.getErrorStream proc))
          exit (.waitFor proc)]
      (r/ok {:exit exit :out out :err err}))
    (catch Exception e
      (r/err :coverage/exec-failed
             {:argv (vec argv) :cwd cwd :cause (.getMessage e)}))))

(defn read-json-file
  "Parse an istanbul summary file. Outer keys stay STRINGS — they are filesystem
   paths, and `keyword` would split one on its last slash into a namespace.
   Returns nil when the file is absent."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (with-open [rdr (io/reader f)]
        (update-vals (json/read rdr) walk/keywordize-keys)))))

(defn- tail
  [s]
  (let [s (str s)]
    (if (<= (count s) 2000) s (subs s (- (count s) 2000)))))

(defn- resolve-path [root rel]
  (if (str/starts-with? (str rel) "/")
    (str rel)
    (str (str/replace root #"/$" "") "/" rel)))

(defn run-coverage!
  "Execute the coverage plan for `m` and build the report.

   Effects arrive as arguments so the orchestration is testable without a
   process or a filesystem: {:exec! (fn [argv cwd]) :read-json (fn [path])}."
  ([m] (run-coverage! m {}))
  ([m {:keys [exec! read-json]
       :or   {exec! exec-argv! read-json read-json-file}}]
   (if-let [cfg (manifest/coverage m)]
     (let [root (:manifest/root m)
           p    (coverage/plan cfg root)]
       (r/bind
        (if (seq (:plan/compile-argv p))
          (exec! (:plan/compile-argv p) root)
          (r/ok {:exit 0 :out "" :err "" :skipped true}))
        (fn [compiled]
          (if-not (zero? (:exit compiled))
            (r/err :coverage/compile-failed
                   {:argv (:plan/compile-argv p)
                    :exit (:exit compiled)
                    :err  (tail (:err compiled))})
            (r/bind
             (exec! (:plan/argv p) root)
             (fn [ran]
               (if-let [summary (read-json (resolve-path root (:plan/summary-path p)))]
                 (let [baseline (some->> (:coverage/baseline cfg)
                                         (resolve-path root)
                                         read-json)]
                   (r/ok {:report  (coverage/report cfg summary baseline)
                          :process {:exit  (:exit ran)
                                    :tests (if (zero? (:exit ran)) :pass :fail)}}))
                 (r/err :coverage/no-summary
                        {:expected (:plan/summary-path p)
                         :exit     (:exit ran)
                         :err      (tail (:err ran))
                         :hint     (str "no machine-readable summary was written — check that the "
                                        "profile's summary reporter is enabled and that its include "
                                        "globs match the emitted module names")}))))))))
     (r/err :coverage/not-configured
            {:root (:manifest/root m)
             :hint "add a :hive.cljs/coverage section declaring :source-prefixes"}))))

(defn save-baseline!
  "Copy the current summary to the configured baseline path, so the next run
   reports a delta against this one."
  ([m] (save-baseline! m {}))
  ([m {:keys [read-json write-json]
       :or   {read-json read-json-file
              write-json (fn [path data]
                           (io/make-parents (io/file path))
                           (spit path (json/write-str data)))}}]
   (if-let [cfg (manifest/coverage m)]
     (if-let [dest (:coverage/baseline cfg)]
       (let [root (:manifest/root m)
             p    (coverage/plan cfg root)
             src  (resolve-path root (:plan/summary-path p))]
         (if-let [summary (read-json src)]
           (do (write-json (resolve-path root dest) summary)
               (r/ok {:baseline dest :from (:plan/summary-path p)
                      :namespaces (count (coverage/rows summary))}))
           (r/err :coverage/no-summary {:expected (:plan/summary-path p)})))
       (r/err :coverage/no-baseline-configured
              {:hint "set :baseline in the :hive.cljs/coverage section"}))
     (r/err :coverage/not-configured {:root (:manifest/root m)}))))
