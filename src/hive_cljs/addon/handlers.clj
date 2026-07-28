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
            [hive-dsl.result :as r]))

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
