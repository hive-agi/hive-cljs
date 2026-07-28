(ns hive-cljs.boundary
  "BOUNDARY layer — the only place a plan meets a port.

   Every collaborator arrives as an argument: `{:build-tool … :driver …
   :cljs-eval …}`. Nothing here names a vendor."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.plan :as plan]
            [hive-cljs.ports :as ports]
            [hive-cljs.shadow.nrepl :as nrepl-forms]
            [hive-cljs.verdict :as verdict]
            [hive-dsl.result :as r])
  (:import [java.io PushbackReader]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Manifest loading
;; =============================================================================

(defn manifest-path
  [root]
  (str (str/replace (str root) #"/$" "") "/" manifest/manifest-filename))

(defn load-manifest
  "Read and validate `hive-cljs.edn` under `root`. Returns a Result of a
   normalized manifest."
  [root]
  (let [f (io/file (manifest-path root))]
    (if-not (.exists f)
      (r/err :manifest/not-found {:path (manifest-path root)})
      (try
        (with-open [rdr (PushbackReader. (io/reader f))]
          (manifest/parse (edn/read rdr) (str root)))
        (catch Exception e
          (r/err :manifest/unreadable {:path (manifest-path root)
                                       :cause (.getMessage e)}))))))

;; =============================================================================
;; Runtime-channel execution
;; =============================================================================

(defn- runtime-expr
  "Source text a runtime op evaluates."
  [op]
  (let [[a b] (:op/args op)]
    (case (:op/kind op)
      :eval-cljs  (nrepl-forms/form->string a)
      :dispatch   (nrepl-forms/dispatch-form a)
      :expect-sub (nrepl-forms/predicate-call b (nrepl-forms/sub-form a))
      :expect-db  (nrepl-forms/predicate-call b (nrepl-forms/db-form a))
      (nrepl-forms/form->string a))))

(defn- assertion-op?
  [op]
  (contains? #{:expect-sub :expect-db} (:op/kind op)))

(defn perform-runtime!
  "Execute a :runtime op through ICljsEval. Returns an outcome map."
  [cljs-eval build-id op]
  (cond
    (nil? cljs-eval)
    {:state :skipped :detail "no cljs runtime configured (:nrepl-port missing)"}

    (nil? build-id)
    {:state :error
     :detail "runtime step needs a build: set :build on the scenario, or declare exactly one build in the manifest"}

    :else
    (let [started (System/currentTimeMillis)
          res     (ports/eval-cljs cljs-eval build-id (runtime-expr op))
          elapsed (- (System/currentTimeMillis) started)]
      (if (r/err? res)
        {:state :error :detail (pr-str res) :elapsed-ms elapsed}
        (let [v (get-in res [:ok :value])]
          (if (assertion-op? op)
            (if (and (some? v) (not (false? v)))
              {:state :pass :detail (pr-str v) :elapsed-ms elapsed}
              {:state :fail :detail (str "predicate returned " (pr-str v))
               :elapsed-ms elapsed})
            {:state :pass :detail (pr-str v) :elapsed-ms elapsed}))))))

;; =============================================================================
;; Plan execution
;; =============================================================================

(defn- outcome-of
  [{:keys [driver cljs-eval]} session build-id op]
  (if (= :runtime (:op/channel op))
    (perform-runtime! cljs-eval build-id op)
    (let [res (ports/perform! driver session op)]
      (if (r/err? res) {:state :error :detail (pr-str res)} (:ok res)))))

(defn run-plan!
  "Execute a RunPlan against injected ports. Returns a Result of a RunReport.

   deps: {:driver IBrowserDriver (required when the plan has browser ops)
          :cljs-eval ICljsEval   (required when the plan has runtime ops)}
   Steps after the first failure are reported as :skipped."
  [deps plan]
  (let [needs-browser? (contains? (plan/channels-used plan) :browser)
        started        (System/currentTimeMillis)]
    (if (and needs-browser? (nil? (:driver deps)))
      (r/err :run/no-driver {:scenario (:plan/scenario plan)})
      (let [session-res (if needs-browser?
                          (ports/open-session! (:driver deps) (:plan/session plan))
                          (r/ok nil))]
        (if (r/err? session-res)
          session-res
          (let [session (:ok session-res)]
            (try
              (let [{:keys [results artifacts]}
                    (reduce
                     (fn [{:keys [halted] :as acc} [idx op]]
                       (if halted
                         (update acc :results conj
                                 (verdict/skipped-result idx op "earlier step failed"))
                         (let [outcome (outcome-of deps session (:plan/build plan) op)
                               result  (verdict/step-result idx op outcome)]
                           (-> acc
                               (assoc :halted (contains? #{:fail :error} (:step/state result)))
                               (update :results conj result)
                               (update :artifacts into (:artifacts outcome))))))
                     {:halted false :results [] :artifacts []}
                     (map-indexed vector (:plan/ops plan)))]
                (r/ok (verdict/report (:plan/scenario plan) results
                                      {:elapsed-ms (- (System/currentTimeMillis) started)
                                       :artifacts artifacts})))
              (finally
                (when session (ports/close-session! (:driver deps) session))))))))))

(defn run-scenario!
  "Manifest + scenario id → Result of a RunReport."
  [deps manifest scenario-id]
  (r/bind (plan/plan-for-id manifest scenario-id)
          #(run-plan! deps %)))

(defn run-scenarios!
  "Run several scenarios, collecting every report (no short-circuit)."
  [deps manifest ids]
  (r/ok (mapv (fn [id]
                (let [res (run-scenario! deps manifest id)]
                  (if (r/ok? res)
                    (:ok res)
                    {:run/scenario id :run/state :error
                     :run/steps [] :run/error res})))
              ids)))

(defn run-tagged!
  "Run every scenario carrying any of `tags`."
  [deps manifest tags]
  (run-scenarios! deps manifest (mapv :id (manifest/scenarios-by-tag manifest tags))))
