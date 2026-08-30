(ns hive-cljs.shadow.relay
  "IBuildTool adapter over the shadow-cljs remote-relay websocket.

   Transport only: message folding lives in `hive-cljs.shadow.sync-db`, status
   promotion in `hive-cljs.verdict`, wire vocabulary in `hive-cljs.profile`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [gniazdo.core :as ws]
            [hive-cljs.ports :as ports]
            [hive-cljs.profile :as profile]
            [hive-cljs.shadow.sync-db :as sync-db]
            [hive-cljs.verdict :as verdict]
            [hive-dsl.result :as r]
            [taoensso.timbre :as log])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util.concurrent Executor]
           [org.eclipse.jetty.util.thread QueuedThreadPool]
           [org.eclipse.jetty.websocket.client WebSocketClient]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Transit framing
;; =============================================================================

(defn write-transit
  "Encode a message map as a transit-json string."
  [msg]
  (let [out (ByteArrayOutputStream. 4096)]
    (transit/write (transit/writer out :json) msg)
    (.toString out "UTF-8")))

(defn read-transit
  "Decode a transit-json string, or nil when it is not readable."
  [s]
  (try
    (transit/read (transit/reader (ByteArrayInputStream. (.getBytes ^String s "UTF-8")) :json))
    (catch Exception e
      (log/debug "hive-cljs relay: undecodable frame" (.getMessage e))
      nil)))

;; =============================================================================
;; Server token
;; =============================================================================

(defn http-root
  [{:keys [host port]}]
  (str "http://" host ":" port))

(defn fetch-token
  "Scrape the shadow-remote-token meta tag from the server's index page.
   Returns a Result of the token string."
  [prof conn]
  (try
    (let [body (slurp (io/reader (str (http-root conn) "/")))
          [_ token] (re-find (:relay/token-pattern prof) body)]
      (if (str/blank? token)
        (r/err :relay/token-not-found {:url (http-root conn)})
        (r/ok token)))
    (catch Exception e
      (r/err :relay/server-unreachable {:url (http-root conn) :cause (.getMessage e)}))))

(defn relay-url
  [prof conn token]
  (str "ws://" (:host conn) ":" (:port conn) (:relay/path prof)
       "?id=" (:relay/client-id prof) "&server-token=" token))

;; =============================================================================
;; State
;; =============================================================================

(defn- initial-state []
  {:db          (sync-db/empty-db)
   :subs        {}
   :connected?  false
   :client-id   nil
   :socket      nil
   :ws-client   nil
   :last-error  nil})

(def ^:private max-text-message-bytes
  "Cap for an inbound relay text frame, in bytes."
  (* 64 1024 1024))

(defn- daemon-ws-client
  "A websocket client whose threads cannot keep a host JVM from exiting.

   Inbound text frames are accepted up to `max-text-message-bytes`, not
   Jetty's 64 KiB default."
  ^WebSocketClient []
  (let [pool   (doto (QueuedThreadPool.)
                 (.setName "hive-cljs-relay")
                 (.setDaemon true))
        client (WebSocketClient. ^Executor pool)]
    (doto (.getPolicy client)
      (.setMaxTextMessageSize max-text-message-bytes)
      (.setMaxTextMessageBufferSize max-text-message-bytes))
    client))

(defn- notify-subs!
  [state-ref prof build-ids]
  (let [{:keys [db subs]} @state-ref]
    (doseq [bid build-ids
            [k f] subs]
      (let [status (verdict/build-status prof bid (sync-db/raw-status db prof bid))
            event  {:event/build bid
                    :event/status status
                    :event/at (System/currentTimeMillis)}]
        (try (f event)
             (catch Exception e
               (log/warn "hive-cljs relay: subscriber" k "threw" (.getMessage e))))))))

(defn- send!
  [state-ref msg]
  (if-let [sock (:socket @state-ref)]
    (do (ws/send-msg sock (write-transit msg)) (r/ok true))
    (r/err :relay/not-connected {})))

(defn- server-msg
  "A message addressed to the shadow server runtime."
  [prof op extra]
  (merge {:op op :to (:relay/server-runtime-id prof)} extra))

(defn- handle-msg!
  [state-ref prof msg]
  (let [op (:op msg)]
    (cond
      (= op (:relay/welcome-op prof))
      (do (swap! state-ref assoc :client-id (:client-id msg) :connected? true)
          (send! state-ref {:op (:relay/hello-op prof)
                            :client-info (:relay/client-info prof)})
          (send! state-ref (server-msg prof (:relay/db-sync-init-op prof) {})))

      (= op (:relay/ping-op prof))
      (send! state-ref {:op (:relay/pong-op prof)})

      (= op (:relay/db-sync-op prof))
      (swap! state-ref update :db sync-db/apply-snapshot prof msg)

      (= op (:relay/db-update-op prof))
      (let [changed (sync-db/changed-builds prof (:changes msg))]
        (swap! state-ref update :db sync-db/apply-changes prof (:changes msg))
        (when (seq changed) (notify-subs! state-ref prof changed)))

      :else nil)))

(defn- status-of
  [state-ref prof build-id]
  (if-let [raw (sync-db/raw-status (:db @state-ref) prof build-id)]
    (verdict/build-status prof build-id raw)
    (verdict/unknown-status build-id)))

(defn- await-compile!
  "Block until `build-id` settles on a compile cycle newer than `prev-raw`.

   Returns a Result of the settled `schema/BuildStatus`, or
   `:relay/compile-timeout` when `:relay/compile-timeout-ms` elapses first."
  [state-ref prof build-id prev-raw]
  (let [timeout-ms (:relay/compile-timeout-ms prof)
        poll-ms    (:relay/poll-ms prof)
        deadline   (+ (System/currentTimeMillis) timeout-ms)]
    (loop [progressed? false]
      (let [raw    (sync-db/raw-status (:db @state-ref) prof build-id)
            status (status-of state-ref prof build-id)]
        (cond
          (verdict/compile-settled? status raw prev-raw progressed?)
          (r/ok status)

          (> (System/currentTimeMillis) deadline)
          (r/err :relay/compile-timeout {:build build-id
                                         :timeout-ms timeout-ms
                                         :last-state (:build/state status)})

          :else
          (do (Thread/sleep ^long poll-ms)
              (recur (or progressed?
                         (not (verdict/terminal-state? (:build/state status)))))))))))

;; =============================================================================
;; Adapter
;; =============================================================================

(defrecord ShadowRelay [prof conn state-ref]
  ports/IBuildTool
  (builds [_]
    (r/ok (sync-db/build-ids (:db @state-ref))))

  (build-status [_ build-id]
    (r/ok (status-of state-ref prof build-id)))

  (compile-once! [_ build-id]
    (let [op       (if (sync-db/worker-active? (:db @state-ref) prof build-id)
                     (:relay/watch-compile-op prof)
                     (:relay/compile-op prof))
          prev-raw (sync-db/raw-status (:db @state-ref) prof build-id)
          sent     (send! state-ref
                          (server-msg prof op {(:relay/build-id-arg prof) build-id}))]
      (if (r/err? sent)
        sent
        (await-compile! state-ref prof build-id prev-raw))))

  (subscribe! [_ k f]
    (swap! state-ref assoc-in [:subs k] f)
    (r/ok k))

  (unsubscribe! [_ k]
    (swap! state-ref update :subs dissoc k)
    (r/ok k)))

(defn connected?
  [^ShadowRelay relay]
  (boolean (:connected? @(:state-ref relay))))

(defn snapshot
  "Current sync-db view — diagnostics."
  [^ShadowRelay relay]
  (:db @(:state-ref relay)))

(defn connect!
  "Open a relay connection. Returns a Result of a `ShadowRelay`.

   conn: {:host str :port int}"
  ([conn] (connect! (profile/relay-profile) conn))
  ([prof conn]
   (r/bind
    (fetch-token prof conn)
    (fn [token]
      (let [state-ref (atom (initial-state))
            client    (daemon-ws-client)]
        (try
          (.start client)
          (let [sock (ws/connect
                      (relay-url prof conn token)
                      :client     client
                      :on-receive (fn [s]
                                    (when-let [msg (read-transit s)]
                                      (try (handle-msg! state-ref prof msg)
                                           (catch Exception e
                                             (log/warn "hive-cljs relay: handler threw"
                                                       (.getMessage e))))))
                      :on-error   (fn [e]
                                    (swap! state-ref assoc :last-error (str e)))
                      :on-close   (fn [_ _]
                                    (swap! state-ref assoc :connected? false)))]
            (swap! state-ref assoc :socket sock :ws-client client)
            (r/ok (->ShadowRelay prof conn state-ref)))
          (catch Exception e
            (try (.stop client) (catch Exception _ nil))
            (r/err :relay/connect-failed {:cause (.getMessage e)
                                          :url (relay-url prof conn "<token>")}))))))))

(defn await-ready!
  "Block until the welcome handshake lands or `timeout-ms` elapses.
   Returns a Result of the relay."
  [relay timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (connected? relay) (r/ok relay)
        (> (System/currentTimeMillis) deadline)
        (r/err :relay/handshake-timeout {:timeout-ms timeout-ms})
        :else (do (Thread/sleep 50) (recur))))))

(defn disconnect!
  "Close the relay socket and release its client. Idempotent."
  [^ShadowRelay relay]
  (let [{:keys [socket ws-client]} @(:state-ref relay)]
    (when socket (try (ws/close socket) (catch Exception _ nil)))
    (when ws-client
      (try (.stop ^WebSocketClient ws-client) (catch Exception _ nil))))
  (swap! (:state-ref relay) assoc :socket nil :ws-client nil :connected? false)
  (r/ok nil))
