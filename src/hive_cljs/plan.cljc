(ns hive-cljs.plan
  "PIPELINE layer — compose a manifest and a scenario into a `schema/RunPlan`.

   Pure orchestration: no port is touched here, the plan is data the boundary
   executes."
  (:require [clojure.string :as str]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.schema :as s]
            [hive-cljs.step :as step]
            [hive-dsl.result :as r]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn absolutize
  "Resolve a step's relative URL against the run's base-url."
  [base-url url]
  (cond
    (str/starts-with? url "http") url
    (str/starts-with? url "/")    (str (str/replace base-url #"/$" "") url)
    :else                         (str (str/replace base-url #"/$" "") "/" url)))

(defn resolve-urls
  "Rewrite :goto ops so their first arg is an absolute URL."
  [base-url ops]
  (mapv (fn [op]
          (if (= :goto (:op/kind op))
            (update-in op [:op/args 0] #(absolutize base-url %))
            op))
        ops))

(defn session-opts
  "Browser session options for a run."
  [manifest]
  (let [e2e (:manifest/e2e manifest)]
    {:browser    (:browser e2e)
     :headless   (:headless e2e)
     :base-url   (:base-url e2e)
     :timeout-ms (:timeout-ms e2e)
     :artifacts-dir (:artifacts-dir e2e)}))

(defn runtime-opts
  "Runtime-channel options for a run — what the boundary needs that the browser
   session does not: how long to poll a condition-wait, and which app-db
   invariant to assert between steps."
  [manifest frame]
  (let [e2e (:manifest/e2e manifest)]
    (cond-> {:timeout-ms (:timeout-ms e2e)
             :poll-ms    (:poll-ms e2e)}
      frame                 (assoc :frame frame)
      (:app-db-schema e2e)  (assoc :app-db-schema (:app-db-schema e2e))
      (:app-db-check e2e)   (assoc :app-db-check (:app-db-check e2e)))))

(defn default-build
  "Build a scenario runs against when it names none: the project's sole build,
   or nil when the choice is ambiguous."
  [manifest]
  (let [ids (manifest/build-ids manifest)]
    (when (= 1 (count ids)) (first ids))))

(defn build-plan
  "Manifest + scenario → Result of `schema/RunPlan`.

   A scenario without :build inherits the project's sole build id. A frame id
   (scenario :frame, else e2e :frame) is stamped onto runtime ops so re-frame2
   frame-scoped apps get frame-pinned subscribe/dispatch/db reads."
  ([manifest scenario] (build-plan step/default-rules manifest scenario))
  ([rules manifest scenario]
   (let [base-url (get-in manifest [:manifest/e2e :base-url])
         compiled (step/compile-steps rules (:steps scenario))
         build    (or (:build scenario) (default-build manifest))
         frame    (or (:frame scenario) (get-in manifest [:manifest/e2e :frame]))]
     (if (r/err? compiled)
       compiled
       (r/ok (cond-> {:plan/scenario (:id scenario)
                      :plan/base-url base-url
                      :plan/session  (session-opts manifest)
                      :plan/runtime  (runtime-opts manifest frame)
                      :plan/ops      (cond->> (resolve-urls base-url (:ok compiled))
                                       frame (mapv #(if (= :runtime (:op/channel %))
                                                      (assoc % :op/frame frame)
                                                      %)))}
               build (assoc :plan/build build)))))))

(defn plan-for-id
  "Manifest + scenario id → Result of a RunPlan."
  [manifest id]
  (if-let [sc (manifest/scenario manifest id)]
    (build-plan manifest sc)
    (r/err :scenario/not-found
           {:id id :known (mapv :id (manifest/scenarios manifest))})))

(defn plans-for-tags
  "Manifest + tag set → Result of [RunPlan ...] for every matching scenario."
  [manifest tags]
  (let [scs (manifest/scenarios-by-tag manifest tags)]
    (if (empty? scs)
      (r/err :scenario/none-matched {:tags (vec tags)})
      (reduce (fn [acc sc]
                (let [res (build-plan manifest sc)]
                  (if (r/ok? res)
                    (r/ok (conj (:ok acc) (:ok res)))
                    (reduced (assoc res :scenario (:id sc))))))
              (r/ok [])
              scs))))

(defn channels-used
  "Set of channels a plan's ops require."
  [plan]
  (set (map :op/channel (:plan/ops plan))))

(defn needs-runtime?
  "True when the plan contains a step routed to ICljsEval."
  [plan]
  (contains? (channels-used plan) :runtime))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> absolutize [:=> [:cat s/NonBlankString s/NonBlankString] s/NonBlankString])
(m/=> session-opts [:=> [:cat s/Manifest] [:map-of :keyword :any]])
(m/=> channels-used [:=> [:cat s/RunPlan] [:set s/OpChannel]])
(m/=> needs-runtime? [:=> [:cat s/RunPlan] :boolean])
