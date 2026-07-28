(ns hive-cljs.system
  "Composition root — resolve a project root into a live set of ports.

   Every collaborator is optional and degrades to a typed absence, so `doctor`
   can report exactly which channel is missing without anything throwing."
  (:require [hive-cljs.boundary :as boundary]
            [hive-cljs.browser.factory :as browser]
            [hive-cljs.ports :as ports]
            [hive-cljs.shadow.nrepl :as shadow-nrepl]
            [hive-cljs.shadow.relay :as relay]
            [hive-dsl.result :as r]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defonce ^:private sessions (atom {}))

(def ^:private handshake-timeout-ms 5000)

(defn- connect-build-tool
  [manifest]
  (let [conn (:manifest/shadow manifest)]
    (r/bind (relay/connect! conn)
            #(relay/await-ready! % handshake-timeout-ms))))

(defn- connect-cljs-eval
  [manifest]
  (let [conn (:manifest/shadow manifest)]
    (if (shadow-nrepl/blank-port? conn)
      (r/err :cljs-eval/no-nrepl-port {})
      (shadow-nrepl/connect! conn))))

(defn open!
  "Build a session for `root`: manifest + whichever ports connect.

   Returns a Result of
   {:manifest … :build-tool … :cljs-eval … :driver … :errors {port err}}"
  [root]
  (r/bind
   (boundary/load-manifest root)
   (fn [manifest]
     (let [bt      (connect-build-tool manifest)
           ce      (connect-cljs-eval manifest)
           drv     (browser/driver)
           session {:root       (str root)
                    :manifest   manifest
                    :build-tool (when (r/ok? bt) (:ok bt))
                    :cljs-eval  (when (r/ok? ce) (:ok ce))
                    :driver     (when (r/ok? drv) (:ok drv))
                    :errors     (cond-> {}
                                  (r/err? bt)  (assoc :build-tool bt)
                                  (r/err? ce)  (assoc :cljs-eval ce)
                                  (r/err? drv) (assoc :driver drv))}]
       (swap! sessions assoc (str root) session)
       (r/ok session)))))

(defn session
  "Existing session for root, opening one when absent."
  [root]
  (if-let [s (get @sessions (str root))]
    (r/ok s)
    (open! root)))

(defn close!
  "Release a session's ports. Idempotent."
  [root]
  (when-let [s (get @sessions (str root))]
    (when-let [bt (:build-tool s)] (try (relay/disconnect! bt) (catch Exception _ nil)))
    (when-let [ce (:cljs-eval s)] (try (shadow-nrepl/disconnect! ce) (catch Exception _ nil)))
    (swap! sessions dissoc (str root)))
  (r/ok :closed))

(defn close-all!
  []
  (doseq [root (keys @sessions)] (close! root))
  (r/ok :closed))

(defn run-deps
  "The `deps` map the boundary and supervisor expect."
  [session]
  (select-keys session [:build-tool :driver :cljs-eval]))

(defn health
  "Per-port availability for a session."
  [session]
  {:build-tool (if (:build-tool session) :ok :down)
   :cljs-eval  (if (:cljs-eval session) :ok :down)
   :browser    (if (:driver session) :ok :down)
   :errors     (into {} (map (fn [[k v]] [k (:error v)])) (:errors session))})

(defn doctor
  "Manifest + connectivity diagnosis for a project root."
  [root]
  (let [res (session root)]
    (if (r/err? res)
      (r/ok {:manifest :invalid :detail res})
      (let [s (:ok res)
            m (:manifest s)]
        (r/ok {:manifest      :ok
               :root          (:root s)
               :shadow        (:manifest/shadow m)
               :builds        (vec (keys (:manifest/builds m)))
               :scenarios     (mapv :id (get-in m [:manifest/e2e :scenarios]))
               :base-url      (get-in m [:manifest/e2e :base-url])
               :watch         (:manifest/watch m)
               :ports         (health s)
               :browser-adapter (if (browser/available?) :present :absent)})))))

(defn ensure-port
  "Return a Result of the named port from a session, or the recorded error."
  [session port]
  (or (some-> (get session port) r/ok)
      (get-in session [:errors port])
      (r/err :port/unavailable {:port port})))

(defn log-session
  [session]
  (log/debug "hive-cljs session" (:root session) (health session))
  session)

(defn build-tool? [session] (ports/build-tool? (:build-tool session)))
