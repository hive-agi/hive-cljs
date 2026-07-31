(ns hive-cljs.shadow.nrepl
  "ICljsEval adapter — evaluate ClojureScript in a running shadow-cljs build via
   its nREPL, using shadow's `repl` / `cljs-eval` entry points."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [hive-cljs.ports :as ports]
            [hive-dsl.result :as r]
            [nrepl.core :as nrepl])
  (:import [java.io PushbackReader StringReader]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private default-timeout-ms 30000)

(defn- read-edn
  "Read one EDN form from a printed nREPL value, or keep the string."
  [s]
  (try
    (with-open [rdr (PushbackReader. (StringReader. s))]
      (edn/read {:eof ::eof} rdr))
    (catch Exception _ s)))

(defn cljs-eval-form
  "Wrap a user form so it is evaluated in build-id's cljs runtime from a CLJ
   nREPL session. A non-nil runtime-id pins the eval to that runtime."
  ([build-id form-str] (cljs-eval-form build-id form-str nil))
  ([build-id form-str runtime-id]
   (str "(shadow.cljs.devtools.api/cljs-eval " (pr-str (keyword (name build-id))) " "
        (pr-str form-str) " "
        (pr-str (if runtime-id {:runtime-id runtime-id} {})) ")")))

(defn repl-runtimes-form
  "Source text listing the runtimes connected to build-id."
  [build-id]
  (str "(mapv #(select-keys % [:client-id :user-agent :host])"
       " (shadow.cljs.devtools.api/repl-runtimes " (pr-str (keyword (name build-id))) "))"))

(def token-read-form
  "Source text reading the page stamp `IPageMarker/mark-session!` writes."
  "(.-__hiveCljsToken js/window)")

(defn- collect
  "Fold nREPL response messages into {:value :printed :errors :status}."
  [msgs]
  (reduce (fn [acc m]
            (cond-> acc
              (:value m)   (update :values conj (:value m))
              (:out m)     (update :printed str (:out m))
              (:err m)     (update :printed str (:err m))
              (:ex m)      (assoc :ex (:ex m))
              (:status m)  (update :status into (map keyword) (:status m))))
          {:values [] :printed "" :status #{}}
          msgs))

(defn- eval-in-session
  [client form-str timeout-ms]
  (let [msgs (nrepl/message client {:op "eval" :code form-str :timeout timeout-ms})]
    (collect (doall msgs))))

(defn- eval-clj
  "Evaluate a CLJ form on the shadow server session.
   Returns a Result of {:value <edn> :printed str}."
  [conn form-str timeout-ms]
  (if-let [{:keys [client]} conn]
    (try
      (let [{:keys [values printed ex status]} (eval-in-session client form-str timeout-ms)]
        (cond
          ex                             (r/err :cljs-eval/threw {:ex ex :printed printed})
          (contains? status :eval-error) (r/err :cljs-eval/eval-error {:printed printed})
          (empty? values)                (r/err :cljs-eval/no-value {:printed printed})
          :else                          (r/ok {:value (read-edn (last values))
                                                :printed printed})))
      (catch Exception e
        (r/err :cljs-eval/transport-failed {:cause (.getMessage e)})))
    (r/err :cljs-eval/not-connected {})))

(defn- run-cljs
  "Evaluate form-str in build-id's runtime, optionally pinned to runtime-id.
   Returns a Result of {:value <edn> :printed str}."
  [conn build-id form-str runtime-id timeout-ms]
  (let [res (eval-clj conn (cljs-eval-form build-id form-str runtime-id) timeout-ms)]
    (if (r/err? res)
      res
      (let [{:keys [value printed]} (:ok res)
            results (when (map? value) (:results value))
            v       (if (seq results) (read-edn (last results)) value)]
        (if (and (map? value) (seq (:err value)))
          (r/err :cljs-eval/runtime-error {:build build-id :detail (:err value)})
          (r/ok {:value v :printed printed}))))))

(defn- find-runtime
  "Client-id of the connected runtime whose page carries `token`.
   Returns a Result of that id. Probes each runtime at most once."
  [conn build-id token timeout-ms]
  (let [res (eval-clj conn (repl-runtimes-form build-id) timeout-ms)]
    (if (r/err? res)
      res
      (let [runtimes (get-in res [:ok :value])
            carries? (fn [{:keys [client-id]}]
                       (let [probe (run-cljs conn build-id token-read-form client-id timeout-ms)]
                         (and (r/ok? probe) (= token (get-in probe [:ok :value])))))]
        (if-not (seq runtimes)
          (r/err :cljs-eval/no-runtime
                 {:build build-id
                  :hint "no browser is connected to this build — load the app first"})
          (if-let [hit (first (filter carries? runtimes))]
            (r/ok (:client-id hit))
            (r/err :cljs-eval/runtime-not-identified
                   {:build build-id
                    :connected (mapv #(select-keys % [:client-id :user-agent]) runtimes)
                    :hint "no connected runtime carries the session stamp"})))))))

(defrecord ShadowNrepl [conn-atom opts]
  ports/ICljsEval
  (eval-cljs [_ build-id form-str]
    (let [{:keys [runtime-id] :as conn} @conn-atom]
      (run-cljs conn build-id form-str runtime-id
                (:timeout-ms opts default-timeout-ms))))

  (runtime-available? [this build-id]
    (let [res (ports/eval-cljs this build-id "1")]
      (and (r/ok? res) (= 1 (get-in res [:ok :value])))))

  ports/IRuntimeAffinity
  (bind-runtime! [_ build-id token]
    (let [res (find-runtime @conn-atom build-id token
                            (:timeout-ms opts default-timeout-ms))]
      (when (r/ok? res)
        (swap! conn-atom assoc :runtime-id (:ok res)))
      res))

  (unbind-runtime! [_]
    (swap! conn-atom dissoc :runtime-id)
    (r/ok nil))

  ports/IRuntimeInventory
  (connected-runtimes [_ build-id]
    (let [res (eval-clj @conn-atom (repl-runtimes-form build-id)
                        (:timeout-ms opts default-timeout-ms))]
      (if (r/err? res)
        res
        (r/ok (vec (get-in res [:ok :value]))))))

  (pinned-runtime [_] (:runtime-id @conn-atom)))

(defn connect!
  "Open an nREPL connection for cljs evaluation.
   conn: {:host str :nrepl-port int}. Returns a Result of a ShadowNrepl."
  ([conn] (connect! conn {}))
  ([{:keys [host nrepl-port]} opts]
   (if-not nrepl-port
     (r/err :cljs-eval/no-nrepl-port
            {:hint "set :nrepl-port under :hive.cljs/shadow in hive-cljs.edn"})
     (try
       (let [transport (nrepl/connect :host (or host "localhost") :port nrepl-port)
             client    (nrepl/client transport (:timeout-ms opts default-timeout-ms))
             session   (nrepl/client-session client)]
         (r/ok (->ShadowNrepl (atom {:transport transport :client session}) opts)))
       (catch Exception e
         (r/err :cljs-eval/connect-failed {:port nrepl-port :cause (.getMessage e)}))))))

(defn disconnect!
  [^ShadowNrepl c]
  (when-let [{:keys [transport]} @(:conn-atom c)]
    (try (.close ^java.io.Closeable transport) (catch Exception _ nil)))
  (reset! (:conn-atom c) nil)
  (r/ok nil))

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

(defn blank-port?
  [conn]
  (or (nil? (:nrepl-port conn))
      (and (string? (:nrepl-port conn)) (str/blank? (:nrepl-port conn)))))
