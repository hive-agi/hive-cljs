(ns hive-cljs.build.observe
  "BOUNDARY layer — notice compiles hive-cljs did not run by sampling the
   output they leave.

   An external `vite --watch` or `elm-live` never tells anyone it compiled.
   This samples each build's declared `:artifacts` through an `IFileStamps`
   port, reads the time through an `IClock` port, asks the pure
   `hive-cljs.build.artifacts/step` whether a compile finished, and hands every
   compile it observed to `emit` as an ordinary `BuildEvent`.

   Sampling is not inventing: an event fires only when the artifact under test
   actually changed and has held still, never on a timer tick by itself.

   `tick!` is the whole behaviour and is synchronous, so a test drives it with a
   fake filesystem and a fake clock; `start!` only schedules it."
  (:require [clojure.java.io :as io]
            [hive-cljs.build.artifacts :as artifacts]
            [hive-cljs.ports :as ports])
  (:import [java.io File]
           [java.nio.file Files]
           [java.security MessageDigest]
           [java.util.concurrent Executors ScheduledExecutorService ThreadFactory
            TimeUnit]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def default-poll-ms
  "How often the output is sampled when a build declares no `:poll-ms`."
  250)

;; =============================================================================
;; Real adapters
;; =============================================================================

(defn- sha-256
  [^File f]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (.update d (Files/readAllBytes (.toPath f)))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn- file-stamp
  [^File f hash?]
  (cond-> {:modified (max 0 (.lastModified f)) :size (max 0 (.length f))}
    hash? (assoc :hash (sha-256 f))))

(defrecord FsStamps []
  ports/IFileStamps
  (file-stamps [_ root paths opts]
    (let [base (.toPath (io/file (str root)))]
      (into {}
            (comp (map #(io/file (str root) (str %)))
                  (filter #(.exists ^File %))
                  (mapcat file-seq)
                  (filter #(.isFile ^File %))
                  (keep (fn [^File f]
                          ;; a file deleted between listing and reading is a
                          ;; write in progress, not an error
                          (try [(str (.relativize base (.toPath f)))
                                (file-stamp f (:hash? opts))]
                               (catch Exception _ nil)))))
            paths))))

(defrecord SystemClock []
  ports/IClock
  (now-ms [_] (System/currentTimeMillis)))

(defn fs-stamps [] (->FsStamps))
(defn system-clock [] (->SystemClock))

;; =============================================================================
;; The observer
;; =============================================================================

(defn observer
  "An observer of `specs` — `{build-id artifact-spec}` — under `root`.

   `emit` receives each observed `BuildEvent`. `:stamps` and `:clock` are the
   ports it samples through; both default to the real ones."
  ([root specs emit] (observer root specs emit {}))
  ([root specs emit {:keys [stamps clock]}]
   {:root      root
    :specs     specs
    :emit      emit
    :stamps    (or stamps (fs-stamps))
    :clock     (or clock (system-clock))
    :state-ref (atom {:builds (zipmap (keys specs) (repeat artifacts/initial-state))
                      :suspended #{}
                      :executor nil})}))

(defn- sample
  [{:keys [root stamps]} spec]
  (ports/file-stamps stamps root (artifacts/watched-paths spec)
                     {:hash? (boolean (:hash? spec))}))

(defn tick!
  "Sample every build once and emit what was observed. Returns the events
   emitted, in build order. A build hive-cljs is compiling itself is skipped —
   that compile reports its own verdict."
  [{:keys [specs emit clock state-ref] :as obs}]
  (let [now    (ports/now-ms clock)
        ;; fold under the lock so a concurrent suspend!/resume! is never
        ;; overwritten by a stale read; emit outside it, since a subscriber
        ;; may run a whole e2e suite
        events (into []
                     (keep (fn [[id spec]]
                             (locking state-ref
                               (when-not (contains? (:suspended @state-ref) id)
                                 (let [st (get-in @state-ref [:builds id] artifacts/initial-state)
                                       {:keys [state changed]}
                                       (artifacts/step st spec now (sample obs spec))]
                                   (swap! state-ref assoc-in [:builds id] state)
                                   (when changed (artifacts/event-of id changed now)))))))
                     (sort-by (comp str key) specs))]
    (doseq [e events] (try (emit e) (catch Throwable _ nil)))
    events))

(defn observes?
  [{:keys [specs]} build-id]
  (contains? specs build-id))

(defn suspend!
  "Stop observing `build-id` while hive-cljs compiles it itself."
  [{:keys [state-ref] :as obs} build-id]
  (when (observes? obs build-id)
    (locking state-ref
      (swap! state-ref update :suspended conj build-id)))
  nil)

(defn resume!
  "Observe `build-id` again, taking the output as it now stands as the baseline,
   so a compile hive-cljs ran and already reported is not reported twice."
  [{:keys [specs state-ref] :as obs} build-id]
  (when-let [spec (get specs build-id)]
    (locking state-ref
      (let [stamps (sample obs spec)]
        (swap! state-ref (fn [s]
                           (-> s
                               (update :suspended disj build-id)
                               (update-in [:builds build-id]
                                          #(artifacts/rebase (or % artifacts/initial-state)
                                                             stamps))))))))
  nil)

(defn poll-ms
  "Sampling period — the shortest any observed build asks for."
  [{:keys [specs]}]
  (or (some->> (vals specs) (keep :poll-ms) seq (apply min))
      default-poll-ms))

(defn running? [{:keys [state-ref]}] (some? (:executor @state-ref)))

(defn- daemon-factory []
  (reify ThreadFactory
    (newThread [_ r]
      (doto (Thread. ^Runnable r "hive-cljs-artifact-observer")
        (.setDaemon true)))))

(defn start!
  "Begin sampling on a daemon thread. Idempotent. The first sample is a
   baseline, so output already on disk is never reported."
  [{:keys [state-ref] :as obs}]
  (locking state-ref
    (when-not (running? obs)
      (let [^ScheduledExecutorService ex (Executors/newSingleThreadScheduledExecutor
                                          (daemon-factory))
            period (long (poll-ms obs))]
        (swap! state-ref assoc :executor ex)
        (.scheduleWithFixedDelay ex
                                 ^Runnable (fn [] (try (tick! obs) (catch Throwable _ nil)))
                                 0 period TimeUnit/MILLISECONDS))))
  obs)

(defn stop!
  "Stop sampling and forget what was seen, so a restart baselines afresh.
   Idempotent."
  [{:keys [specs state-ref] :as obs}]
  (locking state-ref
    (when-let [^ScheduledExecutorService ex (:executor @state-ref)]
      (.shutdownNow ex))
    (swap! state-ref assoc :executor nil
           :builds (zipmap (keys specs) (repeat artifacts/initial-state))))
  obs)
