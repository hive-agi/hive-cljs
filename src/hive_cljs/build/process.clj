(ns hive-cljs.build.process
  "`IBuildTool` over a shell command — the build channel for a toolchain with no
   long-lived server to ask.

   `elm make`, `vite build`, `tsc`, an npm script: anything with an exit code is
   a build verdict. What this cannot do is NOTICE a build it did not run, so its
   events fire for compiles driven through hive-cljs and not for an external
   `vite --watch`. That is a real limit and is reported as one rather than
   papered over with a poller that would invent a verdict between file writes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-cljs.ports :as ports]
            [hive-dsl.result :as r])
  (:import [java.util List]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private max-diagnostic-lines 200)

(defn exec!
  "Run `argv` with `cwd`. Result of {:exit :out :err}.

   A non-zero exit is DATA, not an error: a failed compile is exactly the
   verdict this tool exists to report."
  [argv cwd]
  (try
    (let [pb   (doto (ProcessBuilder. ^List (vec argv))
                 (.directory (io/file cwd)))
          proc (.start pb)
          out  (slurp (.getInputStream proc))
          err  (slurp (.getErrorStream proc))
          exit (.waitFor proc)]
      (r/ok {:exit exit :out out :err err}))
    (catch Exception e
      (r/err :build/exec-failed
             {:argv (vec argv) :cwd (str cwd) :cause (.getMessage e)}))))

(defn unknown-status
  "A build nobody has compiled yet. :unknown, never an error — not having asked
   is not the same as having asked and failed."
  [id]
  {:build/id id :build/state :unknown
   :build/warnings [] :build/errors [] :build/files []})

(defn diagnostics
  "Compiler output as report lines, newest-relevant first and bounded.

   stderr then stdout: `elm make` writes its errors to stderr, `tsc` to stdout,
   and a tool that reported only one of them would call half the failures silent."
  [{:keys [out err]}]
  (->> [err out]
       (mapcat str/split-lines)
       (remove str/blank?)
       (take max-diagnostic-lines)
       vec))

(defn status-of
  [id result elapsed-ms]
  (let [failed? (not (zero? (:exit result)))]
    {:build/id          id
     :build/state       (if failed? :failed :completed)
     :build/warnings    []
     :build/errors      (if failed? (diagnostics result) [])
     :build/files       []
     :build/duration-ms elapsed-ms}))

(defn- notify!
  [state-ref id status]
  (let [event {:event/build id :event/status status
               :event/at (System/currentTimeMillis)}]
    (doseq [[_ f] (:subs @state-ref)]
      (try (f event) (catch Throwable _ nil)))
    event))

(defrecord ProcessBuildTool [root commands state-ref exec-fn]
  ports/IBuildTool

  (builds [_] (r/ok (vec (keys commands))))

  (build-status [_ id]
    (r/ok (or (get-in @state-ref [:statuses id]) (unknown-status id))))

  (compile-once! [_ id]
    (if-let [argv (get commands id)]
      (let [started (System/currentTimeMillis)]
        (r/bind (exec-fn argv root)
                (fn [result]
                  (let [status (status-of id result (- (System/currentTimeMillis) started))]
                    (swap! state-ref assoc-in [:statuses id] status)
                    (notify! state-ref id status)
                    (r/ok status)))))
      (r/err :build/no-command
             {:build id
              :declared (vec (keys commands))
              :hint "give the build a :command under :hive.cljs/builds"})))

  (subscribe! [_ k f]
    (swap! state-ref assoc-in [:subs k] f)
    (r/ok k))

  (unsubscribe! [_ k]
    (swap! state-ref update :subs dissoc k)
    (r/ok k)))

(defn build-tool
  "A build tool over `commands` — `{build-id argv}` — run with `root` as cwd.

   `exec-fn` is injectable so the orchestration is testable without spawning a
   process."
  ([root commands] (build-tool root commands exec!))
  ([root commands exec-fn]
   (->ProcessBuildTool root commands (atom {:statuses {} :subs {}}) exec-fn)))
