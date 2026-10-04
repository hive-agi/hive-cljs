(ns hive-cljs.build.process
  "`IBuildTool` over a shell command — the build channel for a toolchain with no
   long-lived server to ask.

   `elm make`, `vite build`, `tsc`, an npm script: anything with an exit code is
   a build verdict, and a compile run through `compile-once!` emits an event.

   A compile it did NOT run — an external `vite --watch`, `elm-live` — is seen
   only through the output it leaves: a build declaring `:artifacts` is
   observed by `hive-cljs.build.observe` while anyone is subscribed, and a
   change to that output emits the same `BuildEvent`. A build declaring none is
   still blind to external compiles, and that is reported rather than papered
   over with a poller that would invent a verdict between file writes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-cljs.build.observe :as observe]
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

(defn- publish!
  "Remember `event`'s status and hand the event to every subscriber."
  [state-ref event]
  (swap! state-ref assoc-in [:statuses (:event/build event)] (:event/status event))
  (doseq [[_ f] (:subs @state-ref)]
    (try (f event) (catch Throwable _ nil)))
  event)

(defn- notify!
  [state-ref id status]
  (publish! state-ref {:event/build id :event/status status
                       :event/at (System/currentTimeMillis)}))

(defrecord ProcessBuildTool [root commands state-ref exec-fn observer]
  ports/IBuildTool

  (builds [_]
    (r/ok (vec (distinct (concat (keys commands) (keys (:specs observer)))))))

  (build-status [_ id]
    (r/ok (or (get-in @state-ref [:statuses id]) (unknown-status id))))

  (compile-once! [_ id]
    (if-let [argv (get commands id)]
      (let [started (System/currentTimeMillis)]
        ;; the output this compile writes is reported by its exit code, so the
        ;; observer must not report it a second time
        (when observer (observe/suspend! observer id))
        (try
          (r/bind (exec-fn argv root)
                  (fn [result]
                    (let [status (status-of id result (- (System/currentTimeMillis) started))]
                      (notify! state-ref id status)
                      (r/ok status))))
          (finally
            (when observer (observe/resume! observer id)))))
      (r/err :build/no-command
             {:build id
              :declared (vec (keys commands))
              :hint "give the build a :command under :hive.cljs/builds"})))

  (subscribe! [_ k f]
    (swap! state-ref assoc-in [:subs k] f)
    ;; output is sampled only while somebody listens for what it would say
    (when observer (observe/start! observer))
    (r/ok k))

  (unsubscribe! [_ k]
    (let [subs (:subs (swap! state-ref update :subs dissoc k))]
      (when (and observer (empty? subs)) (observe/stop! observer)))
    (r/ok k)))

(defn close!
  "Stop observing output. Idempotent; never throws."
  [^ProcessBuildTool bt]
  (when-let [obs (:observer bt)]
    (try (observe/stop! obs) (catch Throwable _ nil)))
  nil)

(defn build-tool
  "A build tool over `commands` — `{build-id argv}` — run with `root` as cwd.

   `exec-fn` is injectable so the orchestration is testable without spawning a
   process. `opts` may carry `:artifacts` — `{build-id artifact-spec}` — to
   observe compiles run outside hive-cljs, sampled through the `:stamps`
   (`IFileStamps`) and `:clock` (`IClock`) ports, both defaulting to the real
   ones."
  ([root commands] (build-tool root commands exec!))
  ([root commands exec-fn] (build-tool root commands exec-fn {}))
  ([root commands exec-fn {:keys [artifacts stamps clock]}]
   (let [state-ref (atom {:statuses {} :subs {}})
         obs       (when (seq artifacts)
                     (observe/observer root artifacts
                                       (fn [event] (publish! state-ref event))
                                       {:stamps stamps :clock clock}))]
     (->ProcessBuildTool root commands state-ref exec-fn obs))))
