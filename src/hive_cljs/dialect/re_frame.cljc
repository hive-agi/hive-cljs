(ns hive-cljs.dialect.re-frame
  "The ClojureScript / re-frame runtime dialect — how a runtime step becomes
   source text an application compiled from ClojureScript can evaluate.

   Extracted from the shadow nREPL adapter, where none of it belonged: this is
   re-frame specific, not shadow specific, and conflating the two axes was what
   kept `boundary` — which claims to name no vendor — requiring one."
  (:require [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Forms
;; =============================================================================

(defn form->string
  "Render an authored form argument as source text."
  [x]
  (if (string? x) x (pr-str x)))

(defn sub-form
  "Source text that dereferences a re-frame subscription. With a `frame` id the
   deref is pinned via re-frame.core/with-frame — re-frame2 frame-scoped apps
   refuse a bare subscribe with :rf.error/no-frame-context."
  ([query] (sub-form query nil))
  ([query frame]
   (if frame
     (str "(re-frame.core/with-frame " (form->string frame)
          " @(re-frame.core/subscribe " (form->string query) "))")
     (str "@(re-frame.core/subscribe " (form->string query) ")"))))

(defn db-root-form
  "Source text for the whole re-frame app-db map. With a `frame` id the read
   goes through re-frame.core/app-db-value — re-frame2 keeps app-db per frame,
   so the global re-frame.db/app-db atom is not the app's state."
  ([] (db-root-form nil))
  ([frame]
   (if frame
     (str "(re-frame.core/app-db-value " (form->string frame) ")")
     "@re-frame.db/app-db")))

(defn db-form
  "Source text that reads a path out of the re-frame app-db."
  ([path] (db-form path nil))
  ([path frame]
   (str "(get-in " (db-root-form frame) " " (form->string path) ")")))

(defn dispatch-form
  "Source text that dispatches a re-frame event synchronously. With a `frame`
   id the dispatch is pinned via re-frame.core/with-frame (re-frame2)."
  ([event] (dispatch-form event nil))
  ([event frame]
   (if frame
     (str "(do (re-frame.core/with-frame " (form->string frame)
          " (re-frame.core/dispatch-sync " (form->string event) ")) :dispatched)")
     (str "(do (re-frame.core/dispatch-sync " (form->string event) ") :dispatched)"))))

(defn predicate-call
  "Apply an authored predicate form to a value expression."
  [pred value-expr]
  (str "(" (form->string pred) " " value-expr ")"))

(defn probe-call
  "Apply an authored predicate to a value expression, keeping the value.

   Yields `[pred-result value]` so a poll can report what it last observed —
   'never happened' and 'not yet' are different failures and a bare false
   cannot tell them apart."
  [pred value-expr]
  (str "(let [v " value-expr "] [(boolean (" (form->string pred) " v)) v])"))

(defn app-db-invariant-form
  "Source text validating a whole app-db against a malli schema var.

   Yields nil when the state conforms, else a vector of {:path :value} entries.
   `schema-sym` is resolved in the APP's runtime, so the app build must carry
   both that namespace and malli."
  [schema-sym db-expr]
  (str "(when-let [e (malli.core/explain " (form->string schema-sym) " " db-expr ")]"
       " (mapv (fn [x] {:path (vec (:in x)) :value (:value x)}) (:errors e)))"))

(defn registry-ids-form
  "Source text listing the handler ids re-frame registered under `kind`
   (`:sub` or `:event`) — the zero-config half of a mutation catalog."
  [kind]
  (str "(vec (keys (get @re-frame.registrar/kind->id->handler " (pr-str kind) ")))"))

(defn registry-map-form
  "Source text reading several registries in ONE round trip: `{kind [ids…] …}`.

   One trip, because each one costs a page: the registries can only be read
   from a running app, and the app is only running while a browser holds it."
  [kinds]
  (str "{" (str/join " " (map (fn [k] (str (pr-str k) " " (registry-ids-form k))) kinds)) "}"))

(defn neutralize-form
  "Source text re-registering a re-frame handler as a no-op.

   The subscription cache is cleared on both sides of the re-registration:
   re-frame memoizes reactions, so a stale one would keep answering with the
   pre-fault behaviour and the fault would look killed by nothing."
  [kind id]
  (case kind
    :sub   (str "(do (re-frame.core/clear-subscription-cache!)"
                " (re-frame.core/reg-sub " (pr-str id) " (fn [_ _] nil))"
                " (re-frame.core/clear-subscription-cache!) :neutralized)")
    :event (str "(do (re-frame.core/reg-event-db " (pr-str id)
                " (fn [db _] db)) :neutralized)")))

;; =============================================================================
;; Op → source
;; =============================================================================

(defn assertion-source
  "Source text a runtime op asserts on, or nil for a kind this dialect does not
   render — an unknown kind must reach the caller as `:incomplete`, not as an
   expression assembled out of the wrong arguments."
  [op]
  (let [[a b] (:op/args op)
        frame (:op/frame op)]
    (case (:op/kind op)
      :eval-cljs  (form->string a)
      :dispatch   (dispatch-form a frame)
      :expect-sub (predicate-call b (sub-form a frame))
      :expect-db  (predicate-call b (db-form a frame))
      nil)))

(defn probe-source
  "Source text a condition-wait op polls: `[pred-result last-value]`."
  [op]
  (let [[a b] (:op/args op)
        frame (:op/frame op)]
    (case (:op/kind op)
      :wait-for-sub (probe-call b (sub-form a frame))
      :wait-for-db  (probe-call b (db-form a frame))
      nil)))
