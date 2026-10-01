(ns hive-cljs.dialect.probe
  "Runtime probes AUTHORED AS DATA — a small ClojureScript-shaped language
   rendered to one JavaScript expression.

   `hive-cljs.dialect.js` renders an op to source text; this is the authoring
   side of that. A manifest is EDN, so a probe can be a form instead of a JS
   string blob:

     [:wait-for-js (= 1 (dom/count \"#welcome .fragment.visible\"))]
     [:expect-js   (every? dom/visible? (dom/all \"#main [data-composition-id]\"))]
     [:expect-state [\"model\" \"loading\"] (= v false)]

   Every `dom/*` selector argument may be a string (verbatim) or selector DATA
   (`hive-cljs.selector`), compiled with the CSS-only dialect at render time:

     (dom/count [:li.row {:data-state \"open\"}])

   The language is CLOSED. Every head is either one this namespace knows, an
   interop form (`.method`, `.-prop`), a local bound by `let`/`fn`, or an
   explicit `js/` name. Anything else is a compile error raised while the plan
   is built — a typo must fail the plan, not reach the page as a
   ReferenceError, and a string argument can never smuggle source past the
   literal it renders into.

   Semantics are JavaScript's where they differ: `=` is `===` (two arrays are
   never equal), truthiness is JS truthiness. `get`/`get-in` are nil-safe, as
   in Clojure. Evaluation runs in whatever document the step targets, so with
   `:iframe` set `document` already IS the composition's."
  (:require [clojure.string :as str]
            [hive-cljs.dialect.source :as src]
            [hive-cljs.selector :as selector]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def free-globals
  "Bare symbols a probe may name without a `js/` prefix."
  #{"window" "document" "location" "navigator" "Math" "JSON" "Object" "Array"
    "Number" "String" "Boolean" "Date" "localStorage" "sessionStorage"
    "getComputedStyle" "Infinity" "NaN" "undefined"})

(def ^:private js-path-re #"^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
(def ^:private js-ident-re #"^[A-Za-z_$][A-Za-z0-9_$]*$")

(defn- bad [msg data]
  (throw (ex-info (str "probe: " msg) (assoc data :probe/error true))))

(defn- lit-str
  "A JS string literal. Clojure's escaping of `\"` `\\` and control characters
   is valid JavaScript."
  [s]
  (src/pr-source (str s)))

(defn- kw-str [k]
  (if-let [n (namespace k)] (str n "/" (name k)) (name k)))

(defn- local-name
  "A fresh JS identifier for a local, so shadowing in `let` stays Clojure's."
  [ctx sym]
  (let [base (str/replace (name sym) #"[^A-Za-z0-9_$]" "_")
        base (if (re-find #"^[0-9]" base) (str "_" base) base)]
    (str base "_" (vswap! (:counter ctx) inc))))

;; =============================================================================
;; Head normalisation
;; =============================================================================

(def ^:private core-nses #{"clojure.core" "cljs.core"})
(def ^:private string-nses #{"str" "string" "clojure.string"})

(defn- head-key
  "The name a known head is looked up by: `count`, `str/includes?`,
   `dom/all`. nil for an interop or js/ head."
  [sym]
  (let [ns (namespace sym) n (name sym)]
    (cond
      (nil? ns)                  n
      (core-nses ns)             n
      (string-nses ns)           (str "str/" n)
      (#{"dom" "hive.dom"} ns)   (str "dom/" n)
      :else                      nil)))

;; =============================================================================
;; Emit
;; =============================================================================

(declare emit)

(defn- emit-all [ctx env xs] (mapv #(emit ctx env %) xs))

(defn- args-list [ctx env xs] (str/join ", " (emit-all ctx env xs)))

(defn- emit-symbol [env sym]
  (let [ns (namespace sym) n (name sym)]
    (cond
      (= "js" ns)
      (if (re-matches js-path-re n) n
          (bad (str "js/" n " is not a JavaScript name") {:symbol sym}))

      (and (nil? ns) (contains? env sym))
      (get env sym)

      (and (nil? ns) (re-matches js-path-re n)
           (free-globals (first (str/split n #"\."))))
      n

      :else
      (bad (str "unknown symbol " sym " — bind it with let/fn, or name a "
                "global as js/" n)
           {:symbol sym}))))

(defn- chain
  "A comparison over 1+ operands with Clojure's n-ary meaning."
  [ctx env op xs]
  (case (count xs)
    0 (bad (str op " needs at least one operand") {})
    1 (str "(" (emit ctx env (first xs)) ", true)")
    2 (let [[a b] (emit-all ctx env xs)] (str "(" a " " op " " b ")"))
    (str "((...xs) => xs.every((x, i) => i === 0 || xs[i - 1] " op " x))("
         (args-list ctx env xs) ")")))

(defn- infix [ctx env op unit xs]
  (if (empty? xs)
    unit
    (str "(" (str/join (str " " op " ") (emit-all ctx env xs)) ")")))

(defn- once
  "Apply a JS arrow `(x) => body` to one emitted argument, so it is evaluated
   once however often the body mentions it."
  [body arg]
  (str "((x) => " body ")(" arg ")"))

(defn- coll [arg] (str "Array.from((" arg ") ?? [])"))

(def ^:private count-body
  "(x == null ? 0 : typeof x.length === 'number' ? x.length : typeof x.size === 'number' ? x.size : Object.keys(x).length)")

(defn- selector-datum?
  "True for selector DATA (`hive-cljs.selector`): a keyword, attribute map or
   hiccup vector. A string is already a selector and renders verbatim."
  [form]
  (or (keyword? form) (map? form) (vector? form)))

(defn- emit-sel
  "A selector argument. Selector data is compiled with the CSS-only dialect,
   because the PAGE evaluates it with querySelector — Playwright's
   pseudo-classes would be a SyntaxError there. Anything else emits as usual."
  [ctx env form]
  (if (selector-datum? form)
    (let [res (selector/compile-selector form {:dialect :css})]
      (if (:ok res)
        (lit-str (:ok res))
        (bad (str "selector " (pr-str form) " does not compile: " (:problem res))
             {:selector form :problem (:problem res)})))
    (emit ctx env form)))

(defn- el-of
  "An element argument: a selector string queries the document, anything else
   is taken to be an element already."
  [arg]
  (once "(typeof x === 'string' ? document.querySelector(x) : x)" arg))

(def ^:private visible-body
  (str "(() => { if (!x) return false; const cs = getComputedStyle(x); "
       "return cs.display !== 'none' && cs.visibility !== 'hidden' && "
       "Number(cs.opacity) > 0; })()"))

(defn- root-and-sel
  "`(dom/all sel)` or `(dom/all root sel)` → [root-js sel-js]."
  [ctx env head xs]
  (case (count xs)
    1 ["document" (emit-sel ctx env (first xs))]
    2 [(el-of (emit-sel ctx env (first xs))) (emit-sel ctx env (second xs))]
    (bad (str head " takes (sel) or (root sel)") {:args xs})))

(defn- arity! [head xs n]
  (when-not (if (set? n) (n (count xs)) (= n (count xs)))
    (bad (str head " takes " (if (set? n) (str/join " or " (sort n)) n)
              " argument(s), got " (count xs))
         {:head head :args xs})))

(defn- bindings! [head bv]
  (when-not (and (vector? bv) (every? symbol? (if (= head "let") (take-nth 2 bv) bv))
                 (or (not= head "let") (even? (count bv))))
    (bad (str head " needs a vector of plain symbols"
              (when (= head "let") " and values"))
         {:bindings bv})))

(defn- emit-do [ctx env body]
  (case (count body)
    0 "null"
    1 (emit ctx env (first body))
    (str "(" (args-list ctx env body) ")")))

(defn- emit-let [ctx env [bv & body]]
  (bindings! "let" bv)
  (let [[env stmts]
        (reduce (fn [[env stmts] [sym x]]
                  (let [js-name (local-name ctx sym)]
                    [(assoc env sym js-name)
                     (conj stmts (str "const " js-name " = " (emit ctx env x) ";"))]))
                [env []]
                (partition 2 bv))]
    (str "(() => { " (str/join " " stmts) " return " (emit-do ctx env body) "; })()")))

(defn- emit-fn [ctx env xs]
  (let [xs (if (symbol? (first xs)) (rest xs) xs)
        [params & body] xs]
    (bindings! "fn" params)
    (let [names (mapv #(local-name ctx %) params)
          env   (into env (map vector params names))]
      (str "((" (str/join ", " names) ") => " (emit-do ctx env body) ")"))))

(defn- emit-cond [ctx env clauses]
  (when (odd? (count clauses)) (bad "cond needs test/expr pairs" {}))
  (reduce (fn [else [t x]]
            (str "(" (if (= :else t) "true" (emit ctx env t)) " ? "
                 (emit ctx env x) " : " else ")"))
          "null"
          (reverse (partition 2 clauses))))

(defn- emit-get-in [ctx env m path]
  (when-not (vector? path) (bad "get-in needs a literal path vector" {:path path}))
  (str "(" (emit ctx env m) ")"
       (apply str (map #(str "?.[" (emit ctx env %) "]") path))))

(defn- seq-method
  "`(every? f xs)` and friends. The callback is applied to the element ALONE:
   JavaScript would also pass (index, array), which turns `(map js/parseInt …)`
   into a different function."
  [ctx env method [f c]]
  (let [p (local-name ctx 'x)]
    (str (coll (emit ctx env c)) "." method "((" p ") => (" (emit ctx env f) ")(" p "))")))

(defn- emit-known
  "Emit a call to a head this language defines, or nil when `k` is not one."
  [ctx env k xs]
  (let [e  #(emit ctx env %)
        es #(emit-sel ctx env %)
        a1 #(do (arity! k xs 1) (e (first xs)))
        a1-sel #(do (arity! k xs 1) (es (first xs)))]
    (case k
      ("=" "==") (chain ctx env "===" xs)
      "not="     (str "!" (chain ctx env "===" xs))
      ("<" ">" "<=" ">=") (chain ctx env k xs)
      "+"   (infix ctx env "+" "0" xs)
      "*"   (infix ctx env "*" "1" xs)
      "-"   (if (= 1 (count xs)) (str "(- " (e (first xs)) ")") (infix ctx env "-" "0" xs))
      "/"   (do (arity! k xs #{2}) (infix ctx env "/" "1" xs))
      "mod" (do (arity! k xs 2) (infix ctx env "%" "0" xs))
      "inc" (str "(" (a1) " + 1)")
      "dec" (str "(" (a1) " - 1)")
      "max" (str "Math.max(" (args-list ctx env xs) ")")
      "min" (str "Math.min(" (args-list ctx env xs) ")")
      "and" (infix ctx env "&&" "true" xs)
      "or"  (infix ctx env "||" "null" xs)
      "not" (str "!(" (a1) ")")
      "if"  (do (arity! k xs #{2 3})
                (str "(" (e (first xs)) " ? " (e (second xs)) " : "
                     (if (= 3 (count xs)) (e (nth xs 2)) "null") ")"))
      "when" (str "(" (e (first xs)) " ? " (emit-do ctx env (rest xs)) " : null)")
      "cond" (emit-cond ctx env xs)
      "do"   (emit-do ctx env xs)
      "let"  (emit-let ctx env xs)
      ("fn" "fn*") (emit-fn ctx env xs)
      "nil?"   (str "(" (a1) " == null)")
      "some?"  (str "(" (a1) " != null)")
      "true?"  (str "(" (a1) " === true)")
      "false?" (str "(" (a1) " === false)")
      "string?" (str "(typeof " (a1) " === 'string')")
      "number?" (str "(typeof " (a1) " === 'number')")
      "count"  (once count-body (a1))
      "empty?" (str "(" (once count-body (a1)) " === 0)")
      "first"  (str (coll (a1)) "[0]")
      "last"   (once "((a) => a[a.length - 1])(Array.from(x ?? []))" (a1))
      "nth"    (do (arity! k xs 2) (str (coll (e (first xs))) "[" (e (second xs)) "]"))
      "keys"   (str "Object.keys((" (a1) ") ?? {})")
      "vals"   (str "Object.values((" (a1) ") ?? {})")
      "get"    (do (arity! k xs #{2 3})
                   (str "((" (e (first xs)) ")?.[" (e (second xs)) "]"
                        (when (= 3 (count xs)) (str " ?? " (e (nth xs 2)))) ")"))
      "get-in" (do (arity! k xs 2) (emit-get-in ctx env (first xs) (second xs)))
      "aget"   (str "(" (e (first xs)) ")"
                    (apply str (map #(str "[" (e %) "]") (rest xs))))
      "contains?" (do (arity! k xs 2)
                      (str "(" (e (second xs)) " in Object(" (e (first xs)) "))"))
      "str"    (str "[" (args-list ctx env xs) "].map((x) => x == null ? '' : String(x)).join('')")
      "every?" (do (arity! k xs 2) (seq-method ctx env "every" xs))
      "some"   (do (arity! k xs 2) (seq-method ctx env "some" xs))
      "filter" (do (arity! k xs 2) (seq-method ctx env "filter" xs))
      "map"    (do (arity! k xs 2) (seq-method ctx env "map" xs))
      "remove" (do (arity! k xs 2)
                   (let [p (local-name ctx 'x)]
                     (str (coll (e (second xs))) ".filter((" p ") => !(" (e (first xs)) ")(" p "))")))
      "str/includes?"    (do (arity! k xs 2) (str "String(" (e (first xs)) ").includes(" (e (second xs)) ")"))
      "str/starts-with?" (do (arity! k xs 2) (str "String(" (e (first xs)) ").startsWith(" (e (second xs)) ")"))
      "str/ends-with?"   (do (arity! k xs 2) (str "String(" (e (first xs)) ").endsWith(" (e (second xs)) ")"))
      "str/trim"         (str "String(" (a1) ").trim()")
      "str/lower-case"   (str "String(" (a1) ").toLowerCase()")
      "str/upper-case"   (str "String(" (a1) ").toUpperCase()")
      "str/blank?"       (once "(x == null || String(x).trim() === '')" (a1))
      "str/join"         (if (= 1 (count xs))
                           (str (coll (e (first xs))) ".join('')")
                           (do (arity! k xs 2)
                               (str (coll (e (second xs))) ".join(" (e (first xs)) ")")))
      "dom/one"   (let [[r s] (root-and-sel ctx env k xs)] (str "(" r ")?.querySelector(" s ")"))
      "dom/all"   (let [[r s] (root-and-sel ctx env k xs)]
                    (str "Array.from((" r ")?.querySelectorAll(" s ") ?? [])"))
      "dom/count" (let [[r s] (root-and-sel ctx env k xs)]
                    (str "((" r ")?.querySelectorAll(" s ").length ?? 0)"))
      "dom/text"  (str "(" (el-of (a1-sel)) ")?.textContent")
      "dom/attr"  (do (arity! k xs 2)
                      (str "(" (el-of (es (first xs))) ")?.getAttribute(" (e (second xs)) ")"))
      "dom/style" (do (arity! k xs 2)
                      (str "((el) => el ? getComputedStyle(el)[" (e (second xs)) "] : null)("
                           (el-of (es (first xs))) ")"))
      "dom/visible?" (once visible-body (el-of (a1-sel)))
      "dom/matches?" (do (arity! k xs 2)
                         (str "!!(" (el-of (es (first xs))) ")?.matches(" (es (second xs)) ")"))
      "dom/has-class?" (do (arity! k xs 2)
                           (str "!!(" (el-of (es (first xs))) ")?.classList.contains(" (e (second xs)) ")"))
      nil)))

;; A known one-argument function named bare, e.g. (every? dom/visible? xs),
;; renders as an arrow over one parameter.
(def ^:private fn-values
  #{"dom/visible?" "dom/text" "nil?" "some?" "not" "true?" "false?" "string?"
    "number?" "count" "empty?" "first" "last" "keys" "vals" "inc" "dec"
    "str/trim" "str/lower-case" "str/upper-case" "str/blank?"})

(defn- emit-call [ctx env [head & xs :as form]]
  (cond
    (and (symbol? head) (nil? (namespace head)) (str/starts-with? (name head) ".-"))
    (let [prop (subs (name head) 2)]
      (when-not (re-matches js-ident-re prop) (bad (str "bad property " prop) {:form form}))
      (arity! (name head) xs 1)
      (str "(" (emit ctx env (first xs)) ")." prop))

    (and (symbol? head) (nil? (namespace head)) (str/starts-with? (name head) ".")
         (not= "." (name head)))
    (let [method (subs (name head) 1)]
      (when-not (re-matches js-ident-re method) (bad (str "bad method " method) {:form form}))
      (when (empty? xs) (bad (str (name head) " needs a target") {:form form}))
      (str "(" (emit ctx env (first xs)) ")." method "(" (args-list ctx env (rest xs)) ")"))

    (and (symbol? head) (not (contains? env head)))
    (let [k (head-key head)]
      (or (when k (emit-known ctx env k xs))
          (if (= "js" (namespace head))
            (str (emit-symbol env head) "(" (args-list ctx env xs) ")")
            (bad (str "unknown function " head
                      " — use a known probe function, a let/fn local, or js/" (name head))
                 {:form form}))))

    :else
    (str "(" (emit ctx env head) ")(" (args-list ctx env xs) ")")))

(defn- emit-map [ctx env m]
  (str "({"
       (str/join ", "
                 (for [[k v] m]
                   (str (cond (keyword? k) (lit-str (kw-str k))
                              (string? k)  (lit-str k)
                              :else        (str "[" (emit ctx env k) "]"))
                        ": " (emit ctx env v))))
       "})"))

(defn- emit-fn-value [ctx env sym]
  (let [k (head-key sym)]
    (when (and k (fn-values k) (not (contains? env sym)))
      (let [p (local-name ctx 'x)]
        (str "((" p ") => " (emit-known ctx (assoc env 'x p) k ['x]) ")")))))

(defn emit
  "JS source for one probe form, under `env` (symbol → JS local name)."
  [ctx env form]
  (cond
    (string? form)  (lit-str form)
    (nil? form)     "null"
    (boolean? form) (str form)
    (integer? form) (str form)
    (number? form)  (if (#?(:clj ratio? :cljs (constantly false)) form)
                      (str (double form))
                      (str form))
    (keyword? form) (lit-str (kw-str form))
    (symbol? form)  (or (emit-fn-value ctx env form) (emit-symbol env form))
    (vector? form)  (str "[" (args-list ctx env form) "]")
    (map? form)     (emit-map ctx env form)
    (seq? form)     (if (empty? form) "[]" (emit-call ctx env form))
    :else (bad (str "cannot render " (pr-str form)) {:form form})))

;; =============================================================================
;; Public surface
;; =============================================================================

(defn ->js
  "JS expression for a probe `form`. `locals` names symbols bound by the
   caller, e.g. `{'v \"v\"}` for an :expect-state predicate. Throws ex-info
   carrying `:probe/error` when the form is outside the language."
  ([form] (->js form {}))
  ([form locals]
   (emit {:counter (volatile! 0)} locals form)))

(defn fn-form?
  "True when `form` denotes a FUNCTION rather than a value: a `(fn …)` form or
   a known one-argument function named bare. A predicate given as one is
   applied, never tested for truthiness — a function value is always truthy."
  [form]
  (boolean
   (or (and (seq? form) (contains? #{'fn 'fn*} (first form)))
       (and (symbol? form) (contains? fn-values (head-key form))))))

(defn problem
  "nil when `form` renders, else the reason it does not."
  ([form] (problem form {}))
  ([form locals]
   (try (->js form locals) nil
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
          (if (:probe/error (ex-data e)) (ex-message e) (throw e))))))
