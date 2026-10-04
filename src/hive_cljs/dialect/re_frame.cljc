(ns hive-cljs.dialect.re-frame
  "The ClojureScript / re-frame runtime dialect — how a runtime step becomes
   source text an application compiled from ClojureScript can evaluate.

   Extracted from the shadow nREPL adapter, where none of it belonged: this is
   re-frame specific, not shadow specific, and conflating the two axes was what
   kept `boundary` — which claims to name no vendor — requiring one."
  (:require [hive-cljs.dialect.js :as jsd]
            [hive-cljs.dialect.source :as src]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Forms
;; =============================================================================
;;
;; Every builder below returns a FORM, not text. An authored string argument
;; rides inside as a `source/verbatim` leaf, so the whole expression is printed
;; ONCE, under pinned printer vars, at the edge that hands it to a runtime:
;; `assertion-source` / `probe-source` here, `eval-cljs` in the channel adapter
;; for the introspection forms.

(defn form->string
  "Render an authored form argument as source text: a string verbatim, a form
   printed under pinned printer vars (see `hive-cljs.dialect.source`)."
  [x]
  (src/form->string x))

(def ^:private arg src/arg)

(defn sub-form
  "Form that dereferences a re-frame subscription. With a `frame` id the deref
   is pinned via re-frame.core/with-frame — re-frame2 frame-scoped apps refuse
   a bare subscribe with :rf.error/no-frame-context."
  ([query] (sub-form query nil))
  ([query frame]
   (let [deref-sub (list 'deref (list 're-frame.core/subscribe (arg query)))]
     (if frame
       (list 're-frame.core/with-frame (arg frame) deref-sub)
       deref-sub))))

(defn db-root-form
  "Form for the whole re-frame app-db map. With a `frame` id the read goes
   through re-frame.core/app-db-value — re-frame2 keeps app-db per frame, so
   the global re-frame.db/app-db atom is not the app's state."
  ([] (db-root-form nil))
  ([frame]
   (if frame
     (list 're-frame.core/app-db-value (arg frame))
     (list 'deref 're-frame.db/app-db))))

(defn db-form
  "Form that reads a path out of the re-frame app-db."
  ([path] (db-form path nil))
  ([path frame]
   (list 'get-in (db-root-form frame) (arg path))))

(defn dispatch-form
  "Form that dispatches a re-frame event synchronously. With a `frame` id the
   dispatch is pinned via re-frame.core/with-frame (re-frame2)."
  ([event] (dispatch-form event nil))
  ([event frame]
   (let [dispatch (list 're-frame.core/dispatch-sync (arg event))]
     (list 'do
           (if frame (list 're-frame.core/with-frame (arg frame) dispatch) dispatch)
           :dispatched))))

(defn predicate-call
  "Apply an authored predicate to a value form."
  [pred value-form]
  (list (arg pred) value-form))

(defn probe-call
  "Apply an authored predicate to a value form, keeping the value.

   Yields `[pred-result value]` so a poll can report what it last observed —
   'never happened' and 'not yet' are different failures and a bare false
   cannot tell them apart."
  [pred value-form]
  (list 'let ['v value-form] [(list 'boolean (list (arg pred) 'v)) 'v]))

(defn app-db-invariant-form
  "Form validating a whole app-db against a malli schema var.

   Yields nil when the state conforms, else a vector of {:path :value} entries.
   `schema-sym` is resolved in the APP's runtime, so the app build must carry
   both that namespace and malli."
  [schema-sym db-form]
  (list 'when-let ['e (list 'malli.core/explain (arg schema-sym) db-form)]
        (list 'mapv
              (list 'fn ['x] {:path (list 'vec (list :in 'x)) :value (list :value 'x)})
              (list :errors 'e))))

(defn registry-ids-form
  "Form listing the handler ids re-frame registered under `kind` (`:sub` or
   `:event`) — the zero-config half of a mutation catalog."
  [kind]
  (list 'vec (list 'keys (list 'get (list 'deref 're-frame.registrar/kind->id->handler)
                               kind))))

(defn registry-map-form
  "Form reading several registries in ONE round trip: `{kind [ids…] …}`.

   One trip, because each one costs a page: the registries can only be read
   from a running app, and the app is only running while a browser holds it."
  [kinds]
  (into {} (map (juxt identity registry-ids-form)) kinds))

(defn neutralize-form
  "Form re-registering a re-frame handler as a no-op.

   The subscription cache is cleared on both sides of the re-registration:
   re-frame memoizes reactions, so a stale one would keep answering with the
   pre-fault behaviour and the fault would look killed by nothing."
  [kind id]
  (case kind
    :sub   (list 'do
                 '(re-frame.core/clear-subscription-cache!)
                 (list 're-frame.core/reg-sub id '(fn [_ _] nil))
                 '(re-frame.core/clear-subscription-cache!)
                 :neutralized)
    :event (list 'do
                 (list 're-frame.core/reg-event-db id '(fn [db _] db))
                 :neutralized)))

;; =============================================================================
;; Op → source
;; =============================================================================

(defn js-form
  "Form evaluating a JavaScript expression from a ClojureScript runtime.

   A page compiled from ClojureScript is still a page, so the stack-agnostic
   `:*-js` kinds render here too: the JS dialect supplies the expression and its
   truthiness rules, js/eval runs it, and js->clj brings the answer back as
   data the JVM side can read. The JavaScript stays a STRING by design — it is
   the JS dialect's output, a literal argument to js/eval."
  [source]
  (list 'js->clj (list 'js/eval source)))

(defn- assertion-form
  [op]
  (let [[a b] (:op/args op)
        frame (:op/frame op)]
    (case (:op/kind op)
      :eval-cljs   (arg a)
      :dispatch    (dispatch-form a frame)
      :expect-sub  (predicate-call b (sub-form a frame))
      :expect-db   (predicate-call b (db-form a frame))
      :eval-js     (js-form (jsd/expr a))
      :expect-js   (js-form (jsd/truthy-value (jsd/expr a)))
      :expect-fits (js-form (jsd/fits-source a))
      ::none)))

(defn assertion-source
  "Source text a runtime op asserts on, or nil for a kind this dialect does not
   render — an unknown kind must reach the caller as `:incomplete`, not as an
   expression assembled out of the wrong arguments."
  [op]
  (let [f (assertion-form op)]
    (when-not (= ::none f) (src/pr-source f))))

(defn probe-source
  "Source text a condition-wait op polls: `[pred-result last-value]`."
  [op]
  (let [[a b] (:op/args op)
        frame (:op/frame op)]
    (some-> (case (:op/kind op)
              :wait-for-sub (probe-call b (sub-form a frame))
              :wait-for-db  (probe-call b (db-form a frame))
              :wait-for-js  (js-form (jsd/truthy-probe (jsd/expr a)))
              nil)
            src/pr-source)))
