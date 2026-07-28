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
   nREPL session."
  [build-id form-str]
  (str "(shadow.cljs.devtools.api/cljs-eval " (pr-str (keyword (name build-id))) " "
       (pr-str form-str) " {})"))

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

(defrecord ShadowNrepl [conn-atom opts]
  ports/ICljsEval
  (eval-cljs [_ build-id form-str]
    (if-let [{:keys [client]} @conn-atom]
      (try
        (let [{:keys [values printed ex status]}
              (eval-in-session client
                               (cljs-eval-form build-id form-str)
                               (:timeout-ms opts default-timeout-ms))]
          (cond
            ex (r/err :cljs-eval/threw {:build build-id :ex ex :printed printed})

            (contains? status :eval-error)
            (r/err :cljs-eval/eval-error {:build build-id :printed printed})

            (empty? values)
            (r/err :cljs-eval/no-value {:build build-id :printed printed})

            :else
            (let [raw (last values)
                  ;; shadow's cljs-eval returns {:results ["<printed>"] :err ...}
                  parsed (read-edn raw)
                  results (when (map? parsed) (:results parsed))
                  value (if (seq results)
                          (read-edn (last results))
                          parsed)]
              (if (and (map? parsed) (seq (:err parsed)))
                (r/err :cljs-eval/runtime-error {:build build-id
                                                 :detail (:err parsed)})
                (r/ok {:value value :printed printed})))))
        (catch Exception e
          (r/err :cljs-eval/transport-failed {:cause (.getMessage e)})))
      (r/err :cljs-eval/not-connected {})))

  (runtime-available? [this build-id]
    (let [res (ports/eval-cljs this build-id "1")]
      (and (r/ok? res) (= 1 (get-in res [:ok :value]))))))

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
  "Source text that dereferences a re-frame subscription."
  [query]
  (str "@(re-frame.core/subscribe " (form->string query) ")"))

(defn db-form
  "Source text that reads a path out of the re-frame app-db."
  [path]
  (str "(get-in @re-frame.db/app-db " (form->string path) ")"))

(defn dispatch-form
  "Source text that dispatches a re-frame event synchronously."
  [event]
  (str "(do (re-frame.core/dispatch-sync " (form->string event) ") :dispatched)"))

(defn predicate-call
  "Apply an authored predicate form to a value expression."
  [pred value-expr]
  (str "(" (form->string pred) " " value-expr ")"))

(defn blank-port?
  [conn]
  (or (nil? (:nrepl-port conn))
      (and (string? (:nrepl-port conn)) (str/blank? (:nrepl-port conn)))))
