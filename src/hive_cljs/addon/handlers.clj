(ns hive-cljs.addon.handlers
  "Subcommand bodies for the `cljs` tool. Each takes the parsed params map and
   returns a Result."
  (:require [clojure.string :as str]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.ports :as ports]
            [hive-cljs.system :as system]
            [hive-cljs.verdict :as verdict]
            [hive-cljs.watch.supervisor :as supervisor]
            [hive-dsl.result :as r]
            [hive-cljs.plan :as plan]
            [hive-cljs.coverage :as cov]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defonce ^:private watchers (atom {}))

;; =============================================================================
;; Param coercion — MCP delivers strings
;; =============================================================================

(defn ->keyword
  [x]
  (cond
    (keyword? x) x
    (string? x)  (keyword (str/replace x #"^:" ""))
    :else        nil))

(defn ->tags
  [x]
  (cond
    (nil? x)     #{}
    (coll? x)    (set (keep ->keyword x))
    (string? x)  (set (keep ->keyword (str/split x #"[,\s]+")))
    :else        #{}))

(defn ->fault-kinds
  "Which re-frame registries to derive a fault catalog from.
   `true` means both; a tag list narrows it."
  [x]
  (cond
    (nil? x)                            []
    (or (true? x) (= "true" (str x)))   [:sub :event]
    :else                               (vec (keep #{:sub :event} (->tags x)))))

(defn root-of
  [{:keys [directory root]}]
  (or root directory (System/getProperty "user.dir")))

(defn- with-session
  [params f]
  (r/bind (system/session (root-of params)) f))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn doctor
  [params]
  (system/doctor (root-of params)))

(defn staleness
  [params]
  (system/staleness (root-of params)))

(defn status
  "Build verdicts — one build or all known."
  [params]
  (with-session
    params
    (fn [s]
      (r/bind
       (system/ensure-port s :build-tool)
       (fn [bt]
         (if-let [bid (->keyword (:build params))]
           (ports/build-status bt bid)
           (r/bind (ports/builds bt)
                   (fn [ids]
                     (r/ok (mapv #(get (ports/build-status bt %) :ok) ids))))))))))

(defn compile-build
  [params]
  (with-session
    params
    (fn [s]
      (if-let [bid (->keyword (:build params))]
        (r/bind (system/ensure-port s :build-tool)
                #(ports/compile-once! % bid))
        (r/err :params/missing {:param :build})))))

(defn eval-cljs
  [params]
  (with-session
    params
    (fn [s]
      (let [bid  (->keyword (:build params))
            code (:code params)]
        (cond
          (nil? bid)        (r/err :params/missing {:param :build})
          (str/blank? code) (r/err :params/missing {:param :code})
          :else (r/bind (system/ensure-port s :cljs-eval)
                        #(ports/eval-cljs % bid code)))))))

(defn e2e-list
  [params]
  (with-session
    params
    (fn [s]
      (r/ok (mapv (fn [sc] {:id (:id sc)
                            :build (:build sc)
                            :tags (vec (:tags sc))
                            :steps (count (:steps sc))})
                  (manifest/scenarios (:manifest s)))))))

(defn e2e-run
  "Run one scenario by id, or every scenario matching :tags."
  [params]
  (with-session
    params
    (fn [s]
      (let [deps (system/run-deps s)
            m    (:manifest s)
            id   (->keyword (:scenario params))
            tags (->tags (:tags params))]
        (cond
          id   (r/bind (boundary/run-scenario! deps m id)
                       (fn [rep] (r/ok {:summary (verdict/summarize rep) :report rep})))
          (seq tags)
          (r/bind (boundary/run-tagged! deps m tags)
                  (fn [reps] (r/ok {:summary (mapv verdict/summarize reps)
                                    :reports reps})))
          :else (r/err :params/missing {:param :scenario :alt :tags}))))))

(defn e2e-run-all
  "Fan out a run across every descendant project that authors hive-cljs config.

   Opt-in, because `e2e run` from a workspace root deliberately refuses to guess
   which project was meant. Every candidate is reported, failures included — a
   dropped project reads as a project with nothing to run."
  [params]
  (let [root  (root-of params)
        depth (or (some-> (:depth params) str parse-long) 3)
        roots (boundary/descendant-candidates root depth)]
    (if (empty? roots)
      (r/err :workspace/no-candidates
             {:root root
              :hint "no directory below this root authors hive-cljs.edn or :hive.cljs config"})
      (r/ok {:root     root
             :projects (mapv (fn [d]
                               (let [res (e2e-run (assoc params :directory d :root d))]
                                 (if (r/ok? res)
                                   {:project d :ok true :summary (:summary (:ok res))}
                                   {:project d :ok false :error res})))
                             roots)}))))

(defn e2e-mutate
  "Score the suite against a fault catalog: declared `:faults`, plus the
   handlers `:auto` derives from the running app.

   Inverted verdict — a fault the suite does not turn red is a hole in the
   suite, not a passing test."
  [params]
  (with-session
    params
    (fn [s]
      (let [m        (:manifest s)
            deps     (system/run-deps s)
            id       (->keyword (:scenario params))
            kinds    (->fault-kinds (:auto params))
            declared (vec (get-in m [:manifest/e2e :faults]))]
        (r/bind
         (if id
           (r/bind (plan/plan-for-id m id) (fn [p] (r/ok [p])))
           (plan/plans-for-tags m (->tags (:tags params))))
         (fn [plans]
           (r/bind
            (boundary/derive-faults! deps (first plans) kinds)
            (fn [auto]
              (boundary/run-mutations! deps plans (into declared auto))))))))))

(defn coverage
  "Coverage over the project's own ClojureScript, worst-covered namespace first."
  [params]
  (with-session
    params
    (fn [s]
      (r/bind
       (boundary/run-coverage! (:manifest s))
       (fn [{:keys [report process]}]
         (let [rows (-> (:coverage/rows report)
                        (cov/matching (:filter params))
                        cov/worst-first)]
           (r/ok (cond-> {:build      (:coverage/build report)
                          :tests      (:tests process)
                          :verdict    (:coverage/verdict report)
                          :totals     (:coverage/totals report)
                          :report-dir (:coverage/report-dir report)
                          :namespaces (mapv cov/brief rows)}
                   (:coverage/deltas report)
                   (assoc :deltas (:coverage/deltas report)
                          :regressions (cov/regressions (:coverage/deltas report)))))))))))

(defn coverage-baseline
  "Freeze the current summary as the baseline the next run reports against."
  [params]
  (with-session params (fn [s] (boundary/save-baseline! (:manifest s)))))

(defn watch-start
  [params]
  (with-session
    params
    (fn [s]
      (let [root (root-of params)]
        (if (get @watchers root)
          (r/ok {:already-running true :root root})
          (r/bind (supervisor/start! (:manifest s) (system/run-deps s))
                  (fn [sup]
                    (swap! watchers assoc root sup)
                    (r/ok {:started true :root root}))))))))

(defn watch-stop
  [params]
  (let [root (root-of params)]
    (if-let [sup (get @watchers root)]
      (do (supervisor/stop! sup)
          (swap! watchers dissoc root)
          (r/ok {:stopped true :root root}))
      (r/ok {:stopped false :reason "no watcher running" :root root}))))

(defn watch-status
  [params]
  (let [root (root-of params)]
    (if-let [sup (get @watchers root)]
      (supervisor/status sup)
      (r/ok {:running? false :root root}))))

(defn close
  [params]
  (let [root (root-of params)]
    (when-let [sup (get @watchers root)]
      (supervisor/stop! sup)
      (swap! watchers dissoc root))
    (system/close! root)))

(defn running-watchers [] (vec (keys @watchers)))
