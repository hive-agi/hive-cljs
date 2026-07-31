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
            [taoensso.timbre :as log]
            [hive-cljs.staleness :as staleness]))

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
   {:manifest … :sources … :build-tool … :cljs-eval … :driver … :errors {port err}}"
  [root]
  (r/bind
   (boundary/load-manifest root)
   (fn [manifest]
     (let [bt      (connect-build-tool manifest)
           ce      (connect-cljs-eval manifest)
           drv     (browser/driver)
           session {:root       (str root)
                    :manifest   manifest
                    :sources    (boundary/source-stamps (:manifest/sources manifest))
                    :build-tool (when (r/ok? bt) (:ok bt))
                    :cljs-eval  (when (r/ok? ce) (:ok ce))
                    :driver     (when (r/ok? drv) (:ok drv))
                    :errors     (cond-> {}
                                  (r/err? bt)  (assoc :build-tool bt)
                                  (r/err? ce)  (assoc :cljs-eval ce)
                                  (r/err? drv) (assoc :driver drv))}]
       (swap! sessions assoc (str root) session)
       (r/ok session)))))

(defn current-stamps
  "Stamps read now for the files a session's manifest was built from."
  [s]
  (boundary/source-stamps (mapv :source/path (:sources s))))

(defn stale?
  "True when a session's contributing files changed since it was opened."
  [s]
  (staleness/sources-changed? (:sources s) (current-stamps s)))

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

(defn session
  "Existing session for root, reopening it when its config changed on disk."
  [root]
  (if-let [s (get @sessions (str root))]
    (if (stale? s)
      (do (close! (str root)) (open! root))
      (r/ok s))
    (open! root)))

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

(defn reported-builds
  "Build ids the connected toolchain actually serves, or nil when it is down."
  [s]
  (when-let [bt (:build-tool s)]
    (let [res (ports/builds bt)]
      (when (r/ok? res) (vec (:ok res))))))

(defn- wrong-server-warning
  [shadow declared reported]
  {:warning :shadow/wrong-server
   :detail  (str "shadow at " (:host shadow) ":" (:port shadow) " serves "
                 (pr-str reported) " but this project declares " (pr-str declared)
                 " — shadow takes the next free port when its own is busy, so the"
                 " relay is talking to a different project's server")})

(defn runtimes
  "Runtimes the session's eval channel can see, per declared build.

   {:status :down}        the channel is not connected
   {:status :unsupported} the adapter cannot enumerate runtimes
   {:status :ok :pinned id-or-nil :by-build {build-id entry}}, where entry is
   {:connected [{:client-id … :user-agent … :host …} …]} or {:error err}."
  [s builds]
  (let [ce (:cljs-eval s)]
    (cond
      (nil? ce)                           {:status :down}
      (not (ports/runtime-inventory? ce)) {:status :unsupported}
      :else
      {:status   :ok
       :pinned   (ports/pinned-runtime ce)
       :by-build (into {}
                       (map (fn [b]
                              (let [res (ports/connected-runtimes ce b)]
                                [b (if (r/ok? res)
                                     {:connected (:ok res)}
                                     {:error (:error res)})])))
                       builds)})))

(defn- crowded-builds
  "Declared builds carrying more than one connected runtime."
  [rts]
  (->> (:by-build rts)
       (filter (fn [[_ entry]] (< 1 (count (:connected entry)))))
       (mapv key)))

(defn- ambiguous-runtime-warning
  [builds]
  {:warning :runtime/ambiguous
   :detail  (str "more than one runtime is connected to " (pr-str builds)
                 " — a scenario pins the page it drives, but `cljs eval` has no"
                 " page to pin to and answers from whichever runtime the"
                 " toolchain picks")})

(defn- stale-bundle-warning
  [builds]
  {:warning :bundle/stale
   :detail  (str "the emitted output for " (pr-str builds) " is older than the"
                 " sources it was built from — the page under test is a previous"
                 " compile, so a green run proves nothing about the current code")})

(defn doctor
  "Manifest + connectivity diagnosis for a project root."
  [root]
  (let [res (session root)]
    (if (r/err? res)
      (r/ok {:manifest :invalid :detail res})
      (let [s        (:ok res)
            m        (:manifest s)
            shadow   (:manifest/shadow m)
            declared (vec (keys (:manifest/builds m)))
            reported (vec (reported-builds s))
            match    (staleness/server-match declared reported)
            rts      (runtimes s declared)
            crowded  (crowded-builds rts)
            bundles  (staleness/bundle-stamps
                      (boundary/bundle-facts (:manifest/root m) declared))
            stale-bs (staleness/stale-bundles bundles)]
        (r/ok {:manifest        :ok
               :root            (:manifest/root m)
               :invoked-from    (:root s)
               :sources         (vec (:manifest/sources m))
               :shadow          shadow
               :builds          declared
               :served-builds   reported
               :server          match
               :bundles         bundles
               :scenarios       (mapv :id (get-in m [:manifest/e2e :scenarios]))
               :base-url        (get-in m [:manifest/e2e :base-url])
               :app-db-schema   (get-in m [:manifest/e2e :app-db-schema])
               :watch           (:manifest/watch m)
               :ports           (health s)
               :runtimes        rts
               :browser-adapter (if (browser/available?) :present :absent)
               :warnings        (cond-> []
                                  (= :mismatch match)
                                  (conj (wrong-server-warning shadow declared reported))

                                  (seq crowded)
                                  (conj (ambiguous-runtime-warning crowded))

                                  (seq stale-bs)
                                  (conj (stale-bundle-warning stale-bs)))})))))

(defn staleness
  "Freshness of the cached view for `root` — three axes: manifest-vs-disk,
   declared-vs-served builds, and emitted-bundle-vs-source.

   Reported against the view as it was BEFORE this call, so an edit is still
   visible in the report that refreshes it."
  [root]
  (let [cached (get @sessions (str root))]
    (r/bind (session root)
            (fn [s]
              (let [declared (vec (keys (get-in s [:manifest :manifest/builds])))]
                (r/ok (staleness/report
                       {:cached-sources  (:sources (or cached s))
                        :current-sources (current-stamps s)
                        :declared-builds declared
                        :reported-builds (vec (reported-builds s))
                        :bundles         (boundary/bundle-facts
                                          (get-in s [:manifest :manifest/root])
                                          declared)})))))))

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
