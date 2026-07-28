(ns hive-cljs.watch
  "PIPELINE layer — a build event plus the watch policy become decisions.

   Pure: debounce is decided here from timestamps, but sleeping, scheduling and
   running belong to the supervisor."
  (:require [hive-cljs.manifest :as manifest]
            [hive-cljs.schema :as s]
            [hive-cljs.verdict :as verdict]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn watched?
  "True when the policy covers this build. An absent :builds set watches all."
  [policy build-id]
  (let [builds (:builds policy)]
    (or (nil? builds) (empty? builds) (contains? builds build-id))))

(defn debounced?
  "True when `now` falls inside the debounce window after `last-at`."
  [policy last-at now]
  (boolean
   (and last-at
        (< (- now last-at) (:debounce-ms policy 0)))))

(defn- ignore
  [build-id reason]
  {:decision/kind :ignore :decision/build build-id :decision/reason reason})

(defn action->decision
  "One policy action + context → `schema/Decision`."
  [manifest build-id [kind opts]]
  (case kind
    :run-e2e
    (let [scs (cond
                (seq (:scenarios opts))
                (filterv #(contains? (set (:scenarios opts)) (:id %))
                         (manifest/scenarios manifest))

                (seq (:tags opts))
                (manifest/scenarios-by-tag manifest (:tags opts))

                :else
                (vec (manifest/scenarios manifest)))]
      (if (empty? scs)
        (ignore build-id "no scenario matched the action selector")
        {:decision/kind      :run-e2e
         :decision/build     build-id
         :decision/scenarios (mapv :id scs)
         :decision/reason    "build succeeded"}))

    :report
    {:decision/kind :report :decision/build build-id
     :decision/reason "policy requested a report"}

    (ignore build-id (str "unknown watch action " (pr-str kind)))))

(defn decide
  "Manifest + build event + last-fired timestamp → [Decision ...].

   Always returns at least one decision so the supervisor can log why nothing
   ran."
  [manifest event last-at]
  (let [policy   (:manifest/watch manifest)
        build-id (:event/build event)
        status   (:event/status event)
        now      (:event/at event)]
    (cond
      (not (watched? policy build-id))
      [(ignore build-id "build not watched by policy")]

      (debounced? policy last-at now)
      [(ignore build-id "within debounce window")]

      (verdict/build-ok? status)
      (let [actions (:on-build-success policy)]
        (if (empty? actions)
          [(ignore build-id "no :on-build-success actions configured")]
          (mapv #(action->decision manifest build-id %) actions)))

      (= :failed (:build/state status))
      (let [actions (:on-build-failure policy)]
        (if (empty? actions)
          [{:decision/kind :report :decision/build build-id
            :decision/reason "build failed"}]
          (mapv #(action->decision manifest build-id %) actions)))

      :else
      [(ignore build-id (str "build state " (name (:build/state status))
                             " triggers nothing"))])))

(defn runnable
  "Scenario ids the decisions ask to run, de-duplicated in order."
  [decisions]
  (into [] (distinct) (mapcat :decision/scenarios decisions)))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> watched? [:=> [:cat s/WatchPolicy s/BuildId] :boolean])
(m/=> debounced? [:=> [:cat s/WatchPolicy [:maybe s/Millis] s/Millis] :boolean])
(m/=> action->decision [:=> [:cat s/Manifest s/BuildId s/WatchAction] s/Decision])
(m/=> decide [:=> [:cat s/Manifest s/BuildEvent [:maybe s/Millis]] [:vector s/Decision]])
(m/=> runnable [:=> [:cat [:vector s/Decision]] [:vector s/ScenarioId]])
