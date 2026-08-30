(ns hive-cljs.dialect.js
  "The JavaScript runtime dialect — how a runtime step becomes source text ANY
   page can evaluate, whatever compiled it.

   This is the stack-agnostic half of the runtime vocabulary. Elm, React,
   Svelte, Vue and hand-written JavaScript all present the same surface to a
   browser, so one dialect covers all of them; what differs per stack is only
   HOW an app exposes its state, which is the probe's job, not this one's.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn expr
  "Source text an authored argument contributes."
  [x]
  (if (string? x) x (pr-str x)))

(defn truthy-value
  "JS yielding the VALUE when it is truthy and false when it is not.

   Not a bare `!!(…)`: a passing assertion should report what it saw, and
   JavaScript falsiness is not Clojure falsiness — 0 and \"\" are failures here
   and would both survive a `some?` check on the way back."
  [source]
  (str "(() => { const v = (" source "); return v ? v : false; })()"))

(defn truthy-probe
  "JS yielding `[truthy? value]` — the polled counterpart of `truthy-value`.

   The value rides along so a timeout can say 'never happened' apart from 'not
   yet'; the array reads back as a Clojure vector."
  [source]
  (str "(() => { const v = (" source "); return [!!v, v]; })()"))

;; =============================================================================
;; Op → source
;; =============================================================================

(defn assertion-source
  "Source text a runtime op asserts on, or nil for a kind this dialect does not
   render."
  [op]
  (let [[a] (:op/args op)]
    (case (:op/kind op)
      :eval-js   (expr a)
      :expect-js (truthy-value (expr a))
      nil)))

(defn probe-source
  "Source text a condition-wait op polls: `[truthy? last-value]`."
  [op]
  (let [[a] (:op/args op)]
    (case (:op/kind op)
      :wait-for-js (truthy-probe (expr a))
      nil)))
