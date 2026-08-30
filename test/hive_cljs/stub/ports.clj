(ns hive-cljs.stub.ports
  "In-memory port stubs.

   Every test depends on `hive-cljs.ports` and injects one of these; no test
   namespace names a build toolchain or a browser."
  (:require [hive-cljs.ports :as ports]
            [hive-dsl.result :as r]
            [hive-cljs.dialect.re-frame :as re-frame]))

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
    (r/ok nil))

  ports/IPageMarker
  (mark-session! [_ _session token]
    (swap! state-ref update :marks conj token)
    (r/ok token))

  ports/IPageBootstrap
  (bootstrap! [_ _session source]
    (swap! state-ref update :bootstraps conj source)
    (r/ok :installed))

  ports/IPageEval
  (eval-in-page [_ session source]
    (swap! state-ref update :page-evals conj [(:stub-session session) source])
    (let [v ((:page-value-fn @state-ref) source)]
      (if (and (map? v) (contains? v :error))
        v
        (r/ok {:value v :printed (pr-str v)})))))

(defrecord StubDriverNoMarker [state-ref outcome-fn]
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

(defn- driver-state []
  (atom {:sessions 0 :ops [] :closed 0 :marks [] :bootstraps []
         :page-evals [] :page-value-fn (constantly true)}))

(defn driver
  ([] (driver always-pass))
  ([outcome-fn] (->StubDriver (driver-state) outcome-fn)))

(defn driver-without-marking
  "A driver that cannot stamp its page — exercises the degradation path."
  ([] (driver-without-marking always-pass))
  ([outcome-fn] (->StubDriverNoMarker (driver-state) outcome-fn)))

(defn performed-ops [stub] (:ops @(:state-ref stub)))
(defn sessions-opened [stub] (:sessions @(:state-ref stub)))
(defn sessions-closed [stub] (:closed @(:state-ref stub)))

(defn page-evals
  "`[[session-id source] …]` — what was evaluated in which page."
  [stub]
  (:page-evals @(:state-ref stub)))

(defn bootstraps
  "Document bootstrap sources installed on this driver, in order."
  [stub]
  (:bootstraps @(:state-ref stub)))

(defn answering-page
  "Make the driver's page answer `f` (a fn of the source text) for every
   in-page evaluation. Returns the driver."
  [stub f]
  (swap! (:state-ref stub) assoc :page-value-fn f)
  stub)

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

  (runtime-available? [_ _] true)

  ports/IRuntimeAffinity
  (bind-runtime! [_ build-id token]
    (swap! state-ref update :binds conj [build-id token])
    (let [{:keys [accept-any-token? runtimes]} @state-ref]
      (if-let [rt (if accept-any-token? 1 (get runtimes token))]
        (do (swap! state-ref assoc :bound rt) (r/ok rt))
        (r/err :cljs-eval/runtime-not-identified
               {:build build-id :token token :connected (vec (vals runtimes))}))))

  (unbind-runtime! [_]
    (swap! state-ref dissoc :bound)
    (r/ok nil))

  ports/IRuntimeInventory
  (connected-runtimes [_ build-id]
    (if-let [e (:inventory-error @state-ref)]
      (r/err e {:build build-id})
      (r/ok (vec (get-in @state-ref [:connected build-id])))))

  (pinned-runtime [_] (:bound @state-ref))

  ;; A stub can only discharge a contract it actually mirrors, so it renders
  ;; through the SAME dialect the shipped ClojureScript channel uses rather
  ;; than inventing a source text of its own — a test asserting on the emitted
  ;; expression is then asserting on the real rendering.
  ports/IRuntimeDialect
  (assertion-source [_ op] (re-frame/assertion-source op))
  (probe-source [_ op] (re-frame/probe-source op))

  ports/IRuntimeIntrospection
  (invariant-source [_ schema frame]
    (re-frame/app-db-invariant-form schema (re-frame/db-root-form frame)))
  (registry-source [_ kinds] (re-frame/registry-map-form kinds))
  (neutralize-source [_ kind id] (re-frame/neutralize-form kind id)))

(defrecord StubCljsEvalNoAffinity [state-ref value-fn]
  ports/ICljsEval
  (eval-cljs [_ build-id form-str]
    (swap! state-ref update :evals conj [build-id form-str])
    (let [v (value-fn build-id form-str)]
      (if (and (map? v) (contains? v :error))
        v
        (r/ok {:value v :printed ""}))))

  (runtime-available? [_ _] true)

  ;; Missing affinity and inventory is a different absence from missing a
  ;; dialect: this stub still speaks the vocabulary, it just cannot pin or
  ;; enumerate a runtime.
  ports/IRuntimeDialect
  (assertion-source [_ op] (re-frame/assertion-source op))
  (probe-source [_ op] (re-frame/probe-source op))

  ports/IRuntimeIntrospection
  (invariant-source [_ schema frame]
    (re-frame/app-db-invariant-form schema (re-frame/db-root-form frame)))
  (registry-source [_ kinds] (re-frame/registry-map-form kinds))
  (neutralize-source [_ kind id] (re-frame/neutralize-form kind id)))

(defn cljs-eval
  "Affinity-capable eval stub. By default any stamp identifies a runtime;
   pass {:accept-any-token? false :runtimes {token id}} to model a page the
   runtime channel cannot recognise, {:connected {build-id [descriptor …]}} to
   model what an inventory read sees, and {:inventory-error kw} to fail it."
  ([] (cljs-eval (constantly true)))
  ([value-fn] (cljs-eval value-fn {:accept-any-token? true}))
  ([value-fn opts]
   (->StubCljsEval (atom (merge {:evals [] :binds []} opts)) value-fn)))

(defn cljs-eval-without-affinity
  "An eval stub carrying NEITHER optional capability — it cannot pin a runtime
   and cannot enumerate one. Exercises both degradation paths."
  ([] (cljs-eval-without-affinity (constantly true)))
  ([value-fn] (->StubCljsEvalNoAffinity (atom {:evals [] :binds []}) value-fn)))

(defrecord StubCljsEvalNoDialect [state-ref value-fn]
  ports/ICljsEval
  (eval-cljs [_ build-id form-str]
    (swap! state-ref update :evals conj [build-id form-str])
    (r/ok {:value (value-fn build-id form-str) :printed ""}))

  (runtime-available? [_ _] true))

(defn cljs-eval-without-dialect
  "A channel that can evaluate but speaks no step vocabulary — what a runtime
   for a stack this library has never heard of looks like before anyone teaches
   it one. Exercises the `:incomplete` degradation rather than a silent pass."
  ([] (cljs-eval-without-dialect (constantly true)))
  ([value-fn] (->StubCljsEvalNoDialect (atom {:evals []}) value-fn)))

(defn binds [stub] (:binds @(:state-ref stub)))
(defn bound-runtime [stub] (:bound @(:state-ref stub)))
(defn marks [stub] (:marks @(:state-ref stub)))

(defn evals [stub] (:evals @(:state-ref stub)))

;; =============================================================================
;; IToolchain
;; =============================================================================

(defrecord StubToolchain [state-ref]
  ports/IToolchain

  (open-build-tool [_ manifest]
    (swap! state-ref update :opened conj [:build-tool (:manifest/root manifest)])
    (or (:build-tool-result @state-ref) (r/ok (:build-tool @state-ref))))

  (open-runtime [_ manifest]
    (swap! state-ref update :opened conj [:runtime (:manifest/root manifest)])
    (or (:runtime-result @state-ref) (r/ok (:runtime @state-ref))))

  (close-build-tool! [_ _] (swap! state-ref update :closed conj :build-tool) nil)
  (close-runtime! [_ _] (swap! state-ref update :closed conj :runtime) nil))

(defn toolchain
  "A toolchain whose channels are the stubs above, recording what it opened and
   released. Pass :build-tool-result / :runtime-result to model a channel that
   refuses to connect."
  ([] (toolchain {}))
  ([opts]
   (->StubToolchain (atom (merge {:build-tool (build-tool)
                                  :runtime    (cljs-eval)
                                  :opened     []
                                  :closed     []}
                                 opts)))))

(defn opened [stub] (:opened @(:state-ref stub)))
(defn released [stub] (:closed @(:state-ref stub)))
