(ns hive-cljs.dialect.js
  "The JavaScript runtime dialect — how a runtime step becomes source text ANY
   page can evaluate, whatever compiled it.

   This is the stack-agnostic half of the runtime vocabulary. Elm, React,
   Svelte, Vue and hand-written JavaScript all present the same surface to a
   browser, so one dialect covers all of them.

   Two levels of it. `:eval-js` / `:expect-js` / `:wait-for-js` take raw
   expressions and reach into whatever the app happens to expose — always
   available, and per-app. `:expect-state` / `:wait-for-state` read through the
   `@hive-agi/probe` contract instead, which is what makes ONE scenario
   vocabulary span stacks rather than each app inventing its own accessor."
  (:require [clojure.string :as str]))

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
;; The probe contract — @hive-agi/probe
;; =============================================================================

(def probe-key
  "Global the probe package installs itself under."
  "__hive__")

(def probe-missing-message
  (str "hive probe not installed on this page — `npm i -D @hive-agi/probe`, then "
       "`expose(\"model\", () => yourState)` where the app starts up"))

(defn- json-scalar
  [x]
  (cond
    (keyword? x) (pr-str (name x))
    (string? x)  (pr-str x)
    (number? x)  (str x)
    :else        (pr-str (str x))))

(defn json-path
  "A path vector as a JSON array literal. Segments are identifiers and indices,
   so Clojure's own string escaping is JSON's."
  [path]
  (str "[" (str/join "," (map json-scalar path)) "]"))

(defn read-source
  "JS reading `path` out of the probe's exposed state.

   A missing probe THROWS, naming the fix. An absent probe and a genuinely
   absent value are different failures — only the second is about the
   application — and reading `undefined` off a missing global would report them
   identically."
  [path]
  (str "(() => { if (!window." probe-key ") throw new Error("
       (pr-str probe-missing-message) "); return window." probe-key ".read("
       (json-path path) "); })()"))

(defn state-assertion
  "Assert `pred` — a JS expression over the bound `v` — against the value at
   `path`. Yields the value when it holds, false when it does not."
  [path pred]
  (str "(() => { const v = " (read-source path) "; return (" (expr pred) ") ? v : false; })()"))

(defn state-probe
  "The polled counterpart of `state-assertion`: `[held? value]`."
  [path pred]
  (str "(() => { const v = " (read-source path) "; return [!!(" (expr pred) "), v]; })()"))

;; =============================================================================
;; Op → source
;; =============================================================================

(defn assertion-source
  "Source text a runtime op asserts on, or nil for a kind this dialect does not
   render."
  [op]
  (let [[a b] (:op/args op)]
    (case (:op/kind op)
      :eval-js      (expr a)
      :expect-js    (truthy-value (expr a))
      :expect-state (state-assertion a b)
      nil)))

(defn probe-source
  "Source text a condition-wait op polls: `[truthy? last-value]`."
  [op]
  (let [[a b] (:op/args op)]
    (case (:op/kind op)
      :wait-for-js    (truthy-probe (expr a))
      :wait-for-state (state-probe a b)
      nil)))
