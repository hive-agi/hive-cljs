(ns hive-cljs.watch.supervisor
  "BOUNDARY layer — subscribe to build events, ask `hive-cljs.watch` what to do,
   and run the resulting scenarios.

   Decisions are pure and live in `hive-cljs.watch`; this namespace owns only
   subscription, debounce state, execution and the run log."
  (:require [hive-cljs.boundary :as boundary]
            [hive-cljs.ports :as ports]
            [hive-cljs.verdict :as verdict]
            [hive-cljs.watch :as watch]
            [hive-dsl.result :as r]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private sub-key ::hive-cljs-watch)
(def ^:private log-limit 50)

(defn- now [] (System/currentTimeMillis))

(defn- record!
  [state-ref entry]
  (swap! state-ref update :log
         (fn [l] (vec (take-last log-limit (conj (or l []) entry))))))

(defn handle-event!
  "Fold one build event: decide, run what the decisions ask for, log the outcome.
   Returns the decisions taken."
  [{:keys [manifest deps state-ref]} event]
  (let [last-at   (get-in @state-ref [:last-run (:event/build event)])
        decisions (watch/decide manifest event last-at)
        to-run    (watch/runnable decisions)]
    (record! state-ref {:at (:event/at event)
                        :build (:event/build event)
                        :state (get-in event [:event/status :build/state])
                        :decisions (mapv :decision/kind decisions)})
    (when (seq to-run)
      (swap! state-ref assoc-in [:last-run (:event/build event)] (:event/at event))
      (let [res (boundary/run-scenarios! deps manifest to-run)]
        (doseq [rep (if (r/ok? res) (:ok res) [])]
          (log/info "hive-cljs watch:" (verdict/summarize rep))
          (record! state-ref {:at (now) :report rep}))
        (swap! state-ref assoc :last-reports (if (r/ok? res) (:ok res) []))))
    decisions))

(defrecord Supervisor [manifest deps state-ref])

(defn start!
  "Begin watching. `deps` must carry :build-tool, plus :driver / :cljs-eval for
   whatever channels the scenarios use. Returns a Result of a Supervisor."
  [manifest deps]
  (if-not (ports/build-tool? (:build-tool deps))
    (r/err :watch/no-build-tool {})
    (let [state-ref (atom {:last-run {} :log [] :running? true})
          sup       (->Supervisor manifest deps state-ref)
          sub-res   (ports/subscribe! (:build-tool deps) sub-key
                                      (fn [event]
                                        (when (:running? @state-ref)
                                          (try (handle-event! sup event)
                                               (catch Exception e
                                                 (log/warn "hive-cljs watch: handler threw"
                                                           (.getMessage e)))))))]
      (if (r/err? sub-res)
        sub-res
        (r/ok sup)))))

(defn stop!
  "Stop watching. Idempotent."
  [{:keys [deps state-ref] :as _sup}]
  (swap! state-ref assoc :running? false)
  (ports/unsubscribe! (:build-tool deps) sub-key)
  (r/ok :stopped))

(defn status
  "Current watcher state — running flag, per-build last-run stamps, recent log."
  [{:keys [manifest state-ref] :as _sup}]
  (let [s @state-ref]
    (r/ok {:running?     (:running? s)
           :debounce-ms  (get-in manifest [:manifest/watch :debounce-ms])
           :watched      (or (get-in manifest [:manifest/watch :builds]) :all)
           :last-run     (:last-run s)
           :last-reports (mapv verdict/summarize (:last-reports s))
           :log          (:log s)})))
