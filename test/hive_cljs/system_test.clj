(ns hive-cljs.system-test
  "Composition-root invariants that only surface across TWO calls, or across a
   whole session lifetime.

   No vendor is contacted: shadow is pointed at a port nothing listens on, so
   every port degrades to a typed absence — which is precisely the state in
   which `session` must still notice that config changed on disk."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.shadow.relay :as relay]
            [hive-cljs.system :as system]
            [hive-dsl.result :as r])
  (:import [java.io File]
           [java.net ServerSocket]
           [org.eclipse.jetty.websocket.client WebSocketClient]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(use-fixtures :once
  (fn [f]
    (try (f)
         (finally (system/close-all!)))))

;; =============================================================================
;; Temp projects
;; =============================================================================

(defn- free-port
  "A port bound only long enough to learn its number; nothing listens on it after."
  []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(def ^:private dead-shadow-port
  "Shadow endpoint for these tests: `relay/connect!` must fail at token fetch."
  (free-port))

(defn- tmp-root
  "A fresh temp project dir. Tests never touch a real project path."
  [label]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "hive-cljs-system-" label "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getAbsolutePath d)))

(defn- manifest-file
  ^File [root]
  (io/file root manifest/manifest-filename))

(defn- write-manifest!
  "Write `raw` as the project's manifest with `mtime`, and return its stamp.

   mtime is set explicitly rather than inherited from the write: `file-stamp`
   keys on :source/modified and :source/size, and two writes can land inside one
   filesystem timestamp tick."
  [root raw mtime]
  (let [^File f (manifest-file root)]
    (spit f (pr-str raw))
    (.setLastModified f mtime)
    (boundary/file-stamp (.getPath f))))

(defn- raw-manifest
  [scenarios]
  {:hive.cljs/shadow {:host "localhost" :port dead-shadow-port}
   :hive.cljs/builds {:app {:http-port 8280}}
   :hive.cljs/e2e    {:scenarios scenarios}})

(def ^:private login-scenarios
  [{:id :login :build :app :steps [[:goto "/login"]]}])

(def ^:private checkout-scenarios
  [{:id :checkout :build :app
    :steps [[:goto "/checkout"] [:click "#pay"] [:expect-text "#status" "Paid"]]}])

;; =============================================================================
;; A manifest edited between sessions
;; =============================================================================

(deftest session-reopens-when-the-manifest-changed-on-disk
  (let [root    (tmp-root "reopen")
        t0      (- (System/currentTimeMillis) 10000)
        stamp-1 (write-manifest! root (raw-manifest login-scenarios) t0)]
    (try
      (let [res-1 (system/session root)
            s1    (:ok res-1)]
        (is (r/ok? res-1))
        (testing "the first session sees what was on disk when it opened"
          (is (= [:login] (mapv :id (manifest/scenarios (:manifest s1)))))
          (is (= [[:goto "/login"]]
                 (:steps (manifest/scenario (:manifest s1) :login)))))

        (testing "an untouched project is served from cache — the same session object"
          (is (false? (system/stale? s1)))
          (is (identical? s1 (:ok (system/session root)))))

        (let [stamp-2 (write-manifest! root (raw-manifest checkout-scenarios) (+ t0 5000))]
          (testing "premise: the edit really moved the stamp `stale?` reads"
            (is (not= stamp-1 stamp-2))
            (is (not= (:source/size stamp-1) (:source/size stamp-2)))
            (is (not= (:source/modified stamp-1) (:source/modified stamp-2)))
            (is (true? (system/stale? s1))))

          (let [s2 (:ok (system/session root))]
            (testing "the second call yields the NEW scenario, not the cached one"
              (is (not (identical? s1 s2)))
              (is (= [:checkout] (mapv :id (manifest/scenarios (:manifest s2)))))
              (is (= [[:goto "/checkout"] [:click "#pay"] [:expect-text "#status" "Paid"]]
                     (:steps (manifest/scenario (:manifest s2) :checkout))))
              (is (nil? (manifest/scenario (:manifest s2) :login))))

            (testing "the reopened session re-stamps, so it reads fresh again"
              (is (false? (system/stale? s2)))
              (is (= [(.getPath (manifest-file root))]
                     (mapv :source/path (:sources s2))))))))
      (finally (system/close! root)))))

(deftest staleness-reports-the-edit-that-refreshed-the-view
  (let [root (tmp-root "staleness")
        t0   (- (System/currentTimeMillis) 10000)]
    (try
      (write-manifest! root (raw-manifest login-scenarios) t0)
      (is (r/ok? (system/session root)))
      (testing "an untouched project reads :fresh"
        (is (= :fresh (get-in (system/staleness root) [:ok :staleness/manifest]))))

      (write-manifest! root (raw-manifest checkout-scenarios) (+ t0 5000))
      (testing "the report that refreshes the view still shows the edit"
        (let [res (system/staleness root)]
          (is (= :stale (get-in res [:ok :staleness/manifest])))
          (is (= [:app] (get-in res [:ok :staleness/declared-builds])))))

      (testing "and the next report is fresh again"
        (is (= :fresh (get-in (system/staleness root) [:ok :staleness/manifest]))))
      (finally (system/close! root)))))

;; =============================================================================
;; Relay threads cannot keep a host JVM from exiting
;; =============================================================================

(def ^:private relay-thread-prefix "hive-cljs-relay")

(defn- relay-threads
  "Live threads named by the relay's own pool."
  []
  (->> (keys (Thread/getAllStackTraces))
       (filter (fn [^Thread t] (str/starts-with? (.getName t) relay-thread-prefix)))
       (filter (fn [^Thread t] (.isAlive t)))
       vec))

(defn- non-daemon-relay-thread-names
  []
  (->> (relay-threads)
       (remove (fn [^Thread t] (.isDaemon t)))
       (mapv (fn [^Thread t] (.getName t)))))

(defn- await-no-relay-threads
  "Poll up to `ms` for the pool to reap its threads; returns the survivors' names."
  [ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [ts (relay-threads)]
        (if (or (empty? ts) (> (System/currentTimeMillis) deadline))
          (mapv (fn [^Thread t] (.getName t)) ts)
          (do (Thread/sleep 50) (recur)))))))

(deftest relay-pool-threads-are-daemons-and-disconnect-reaps-them
  (testing (str "COVERED offline: the client `relay/daemon-ws-client` builds names its "
                "threads hive-cljs-relay-*, marks every one a daemon, and `disconnect!` "
                "stops the client — the lifecycle ownership gniazdo hands to the caller "
                "when :client is supplied. NOT COVERED: no shadow-cljs server runs here, "
                "so `connect!` fails at token fetch and never reaches gniazdo; nothing is "
                "asserted about threads a live websocket session would create.")
    (let [^WebSocketClient client (#'relay/daemon-ws-client)]
      (try
        (.start client)
        (let [ts (relay-threads)]
          (is (pos? (count ts))
              "the pool must really start threads, else the daemon claim is vacuous")
          (is (every? (fn [^Thread t] (.isDaemon t)) ts))
          (is (= [] (non-daemon-relay-thread-names))))

        (let [relay (relay/->ShadowRelay nil
                                         {:host "localhost" :port dead-shadow-port}
                                         (atom {:socket nil :ws-client client
                                                :connected? true}))]
          (is (r/ok? (relay/disconnect! relay)))
          (is (nil? (:ws-client @(:state-ref relay))))
          (is (false? (:connected? @(:state-ref relay))))
          (is (= [] (await-no-relay-threads 10000))
              "disconnect! owns .stop — gniazdo registers no cleanup for a supplied :client"))
        (finally
          (try (.stop client) (catch Exception _ nil)))))))

(deftest close-all-leaves-no-non-daemon-relay-thread
  (testing (str "COVERED: with relay pool threads demonstrably alive, `system/close-all!` "
                "leaves no NON-daemon thread named hive-cljs-relay* — the invariant that "
                "lets an embedded host JVM decide to exit. NOT COVERED: a connected relay, "
                "which needs a live shadow-cljs server; here open! records :build-tool as "
                "a typed absence.")
    (let [root (tmp-root "close-all")
          ^WebSocketClient client (#'relay/daemon-ws-client)]
      (try
        (write-manifest! root (raw-manifest login-scenarios) (System/currentTimeMillis))
        (.start client)
        (let [s (:ok (system/session root))]
          (is (= (str root) (:root s)))
          (is (contains? (:errors s) :build-tool))
          (is (pos? (count (relay-threads)))
              "relay threads must be alive, else the invariant below is vacuous"))

        (is (r/ok? (system/close-all!)))
        (is (= [] (non-daemon-relay-thread-names)))
        (finally
          (try (.stop client) (catch Exception _ nil)))))))
