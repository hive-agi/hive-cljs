(ns hive-cljs.stub.ports
  "In-memory port stubs.

   Every test depends on `hive-cljs.ports` and injects one of these; no test
   namespace names a build toolchain or a browser."
  (:require [hive-cljs.ports :as ports]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; IBuildTool
;; =============================================================================

(defrecord StubBuildTool [state-ref]
  ports/IBuildTool
  (builds [_] (r/ok (vec (keys (:statuses @state-ref)))))

  (build-status [_ build-id]
    (r/ok (or (get-in @state-ref [:statuses build-id])
              {:build/id build-id :build/state :unknown
               :build/warnings [] :build/errors [] :build/files []})))

  (compile-once! [this build-id]
    (swap! state-ref update :compiles conj build-id)
    (ports/build-status this build-id))

  (subscribe! [_ k f] (swap! state-ref assoc-in [:subs k] f) (r/ok k))
  (unsubscribe! [_ k] (swap! state-ref update :subs dissoc k) (r/ok k)))

(defn build-tool
  ([] (build-tool {}))
  ([statuses] (->StubBuildTool (atom {:statuses statuses :subs {} :compiles []}))))

(defn emit-build!
  "Push a build event to every subscriber — simulates a compile finishing."
  [stub status at]
  (let [event {:event/build (:build/id status) :event/status status :event/at at}]
    (swap! (:state-ref stub) assoc-in [:statuses (:build/id status)] status)
    (doseq [[_ f] (:subs @(:state-ref stub))] (f event))
    event))

(defn compiles [stub] (:compiles @(:state-ref stub)))

;; =============================================================================
;; IBrowserDriver
;; =============================================================================

(defrecord StubDriver [state-ref outcome-fn]
  ports/IBrowserDriver
  (open-session! [_ opts]
    (swap! state-ref update :sessions inc)
    (r/ok {:stub-session (:sessions @state-ref) :opts opts}))

  (perform! [_ session op]
    (swap! state-ref update :ops conj op)
    (r/ok (outcome-fn session op)))

  (close-session! [_ _]
    (swap! state-ref update :closed inc)
    (r/ok nil)))

(defn always-pass [_ op] {:state :pass :detail (str (:op/kind op)) :elapsed-ms 1})

(defn fail-on
  "Outcome fn that fails the first op of `kind`."
  [kind]
  (fn [_ op]
    (if (= kind (:op/kind op))
      {:state :fail :detail (str "stub failure on " kind) :elapsed-ms 1}
      {:state :pass :elapsed-ms 1})))

(defn driver
  ([] (driver always-pass))
  ([outcome-fn] (->StubDriver (atom {:sessions 0 :ops [] :closed 0}) outcome-fn)))

(defn performed-ops [stub] (:ops @(:state-ref stub)))
(defn sessions-opened [stub] (:sessions @(:state-ref stub)))
(defn sessions-closed [stub] (:closed @(:state-ref stub)))

;; =============================================================================
;; ICljsEval
;; =============================================================================

(defrecord StubCljsEval [state-ref value-fn]
  ports/ICljsEval
  (eval-cljs [_ build-id form-str]
    (swap! state-ref update :evals conj [build-id form-str])
    (let [v (value-fn build-id form-str)]
      (if (and (map? v) (contains? v :error))
        v
        (r/ok {:value v :printed ""}))))

  (runtime-available? [_ _] true))

(defn cljs-eval
  ([] (cljs-eval (constantly true)))
  ([value-fn] (->StubCljsEval (atom {:evals []}) value-fn)))

(defn evals [stub] (:evals @(:state-ref stub)))
