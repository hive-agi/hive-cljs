(ns hive-cljs.verdict
  "PROMOTE layer — raw provider payloads and raw step outcomes become verdicts.

   Two promotions:
   - relay build payload → `schema/BuildStatus`
   - step outcomes       → `schema/RunReport`"
  (:require [clojure.string :as str]
            [hive-cljs.profile :as profile]
            [hive-cljs.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Build status
;; =============================================================================

(def ^:private compile-log-prefix "Compile CLJS:")

(defn- as-int [x]
  (cond
    (integer? x) x
    (number? x)  (long x)
    (string? x)  (try #?(:clj (Long/parseLong (str/trim x))
                         :cljs (js/parseInt x 10))
                      (catch #?(:clj Exception :cljs :default) _ nil))
    :else nil))

(defn parse-log-file
  "A `Compile CLJS: path (123 ms)` log line → `schema/CompiledFile`, or nil."
  [line]
  (when (and (string? line) (str/starts-with? line compile-log-prefix))
    (let [body (str/trim (subs line (count compile-log-prefix)))
          [_ path ms] (re-matches #"(.*?)\s*\((\d+)\s*ms\)\s*" body)]
      (cond-> {:file/path (or (some-> path str/trim not-empty) body)}
        ms (assoc :file/elapsed-ms (as-int ms))))))

(defn compiled-files
  "Extract compiled files from a relay build log."
  [log]
  (vec (keep parse-log-file log)))

(defn build-status
  "Raw relay build payload → `schema/BuildStatus`."
  [prof build-id raw]
  (let [raw (or raw {})]
    (cond-> {:build/id       build-id
             :build/state    (profile/normalize-state prof (:status raw))
             :build/warnings (vec (:warnings raw))
             :build/errors   (vec (or (:errors raw)
                                      (when-let [rep (:report raw)] [rep])))
             :build/files    (compiled-files (:log raw))}
      (as-int (:resources raw)) (assoc :build/resources (as-int (:resources raw)))
      (as-int (:compiled raw))  (assoc :build/compiled (as-int (:compiled raw)))
      (as-int (:duration-ms raw)) (assoc :build/duration-ms (as-int (:duration-ms raw)))
      (:at raw)                 (assoc :build/at (:at raw)))))

(defn unknown-status
  "BuildStatus for a build the toolchain has not reported on."
  [build-id]
  {:build/id build-id :build/state :unknown
   :build/warnings [] :build/errors [] :build/files []})

(defn build-ok?
  [status]
  (= :completed (:build/state status)))

;; =============================================================================
;; Run report
;; =============================================================================

(defn step-result
  "Index + op + raw outcome → `schema/StepResult`."
  [idx op outcome]
  (cond-> {:step/index idx
           :step/kind  (:op/kind op)
           :step/state (or (:state outcome) :error)}
    (:detail outcome)     (assoc :step/detail (str (:detail outcome)))
    (:elapsed-ms outcome) (assoc :step/elapsed-ms (:elapsed-ms outcome))))

(defn skipped-result
  [idx op reason]
  {:step/index idx :step/kind (:op/kind op)
   :step/state :skipped :step/detail reason})

(defn run-state
  "Worst state across step results decides the run."
  [results]
  (let [states (set (map :step/state results))]
    (cond
      (contains? states :error) :error
      (contains? states :fail)  :fail
      :else                     :pass)))

(defn report
  "Scenario id + step results → `schema/RunReport`."
  ([scenario-id results] (report scenario-id results {}))
  ([scenario-id results {:keys [elapsed-ms artifacts]}]
   (cond-> {:run/scenario scenario-id
            :run/state    (run-state results)
            :run/steps    (vec results)}
     elapsed-ms      (assoc :run/elapsed-ms elapsed-ms)
     (seq artifacts) (assoc :run/artifacts (vec artifacts)))))

(defn run-ok?
  [rep]
  (= :pass (:run/state rep)))

(defn summarize
  "One-line human summary of a run report."
  [rep]
  (let [steps (:run/steps rep)
        by    (frequencies (map :step/state steps))]
    (str (name (:run/scenario rep)) ": " (name (:run/state rep))
         " (" (get by :pass 0) " pass, " (get by :fail 0) " fail, "
         (get by :error 0) " error, " (get by :skipped 0) " skipped)")))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> compiled-files [:=> [:cat [:maybe [:sequential :any]]] [:vector s/CompiledFile]])
(m/=> unknown-status [:=> [:cat s/BuildId] s/BuildStatus])
(m/=> build-ok? [:=> [:cat s/BuildStatus] :boolean])
(m/=> step-result [:=> [:cat [:int {:min 0}] s/Op [:maybe [:map-of :keyword :any]]] s/StepResult])
(m/=> run-state [:=> [:cat [:vector s/StepResult]] s/RunState])
(m/=> run-ok? [:=> [:cat s/RunReport] :boolean])
(m/=> summarize [:=> [:cat s/RunReport] s/NonBlankString])
