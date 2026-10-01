(ns hive-cljs.selector
  "Selectors as DATA — hiccup-shaped Clojure values compiled to the selector
   strings Playwright (and `document.querySelectorAll`) read.

   A string is still a selector and passes through untouched, so every
   manifest written before this namespace keeps working. Everything else is
   data:

     :#go                         => \"#go\"
     :li.row.active               => \"li.row.active\"
     {:role \"tablist\"}            => \"[role=\\\"tablist\\\"]\"
     {:data-testid/prefix \"p-\"}   => \"[data-testid^=\\\"p-\\\"]\"
     [:li {:has-text \"X\"}
      [:button {:data-testid \"ok\"}]]
                                  => \"li:has-text(\\\"X\\\") button[data-testid=\\\"ok\\\"]\"

   Vector forms:
     [tag attrs? child?]   compound; the one child is a DESCENDANT
     [:in a b ...]         descendant chain of arbitrary selectors
     [:> a b ...]          child chain
     [:or a b ...]         any of them, as `:is(a, b)`

   Attribute map keys:
     :attr \"v\"          [attr=\"v\"]   (`true` => presence `[attr]`)
     :attr/prefix \"v\"   [attr^=\"v\"]   /suffix $=   /contains *=   /word ~=
     :id \"x\"            #x
     :class \"a b\"       .a.b         (also a collection)
     :has sel           :has(sel)
     :not sel           :not(sel)
     :has-text \"X\"      :has-text(\"X\")   Playwright only
     :text-is \"X\"       :text-is(\"X\")    Playwright only
     :visible true      :visible         Playwright only

   A malformed selector is a typed `:selector/malformed` error at COMPILE
   time — never a selector the browser waits twenty seconds to reject.

   Dialects: `:playwright` (default) admits Playwright's pseudo-classes;
   `:css` refuses them, for selectors evaluated by the page itself
   (`:expect-fits` runs `querySelectorAll`)."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r])
  #?(:cljs (:require-macros [hive-cljs.selector])))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Errors
;; =============================================================================

(defn- malformed!
  [problem data]
  (throw (ex-info (str "malformed selector: " problem)
                  (assoc data ::malformed true :problem problem))))

;; =============================================================================
;; Lexical pieces
;; =============================================================================

(def ^:private ident-re #"-?[A-Za-z_][A-Za-z0-9_-]*")
(def ^:private tag-re #"\*|[A-Za-z][A-Za-z0-9-]*")
(def ^:private keyword-re
  #"(\*|[A-Za-z][A-Za-z0-9-]*)?((?:[#.]-?[A-Za-z_][A-Za-z0-9_-]*)*)")

(defn- css-ident? [s] (boolean (and (string? s) (re-matches ident-re s))))

(defn css-string
  "`s` as a double-quoted CSS string literal."
  [s]
  (str "\""
       (-> (str s)
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\"")
           (str/replace "\n" "\\a ")
           (str/replace "\r" "\\d ")
           (str/replace "\f" "\\c "))
       "\""))

;; =============================================================================
;; Compilation
;; =============================================================================

(declare compile*)

(def ^:private attr-ops
  {"prefix" "^=" "suffix" "$=" "contains" "*=" "word" "~=" "dash" "|="})

(def ^:private playwright-pseudos #{:has-text :text-is :visible})

(def ^:private pseudo-order [:has :not :has-text :text-is :visible])

(defn- scalar-text
  [v k]
  (cond
    (string? v)  v
    (number? v)  (str v)
    (keyword? v) (name v)
    :else (malformed! "attribute value must be a string, number or keyword"
                      {:key k :value v})))

(defn- attr-clause
  [k v]
  (let [attr (or (namespace k) (name k))
        op   (if (namespace k)
               (or (attr-ops (name k))
                   (malformed! (str "unknown attribute operator /" (name k))
                               {:key k :known (vec (keys attr-ops))}))
               "=")]
    (when-not (css-ident? attr)
      (malformed! "attribute name is not a CSS identifier" {:key k}))
    (cond
      (and (true? v) (= "=" op)) (str "[" attr "]")
      (true? v) (malformed! "a presence test takes no operator" {:key k})
      :else (let [text (scalar-text v k)]
              (when (and (str/blank? text) (not= "=" op))
                (malformed! "an operator needs a non-blank value" {:key k}))
              (str "[" attr op (css-string text) "]")))))

(defn- id-clause
  [v]
  (let [text (scalar-text v :id)]
    (cond
      (css-ident? text)      (str "#" text)
      (str/blank? text)  (malformed! ":id must not be blank" {:key :id})
      :else              (str "[id=" (css-string text) "]"))))

(defn- class-clause
  [v]
  (let [classes (if (or (string? v) (keyword? v))
                  (remove str/blank? (str/split (scalar-text v :class) #"\s+"))
                  (map #(scalar-text % :class) v))]
    (when (empty? classes)
      (malformed! ":class names no class" {:key :class :value v}))
    (apply str (map #(if (css-ident? %)
                       (str "." %)
                       (str "[class~=" (css-string %) "]"))
                    classes))))

(defn- pseudo-clause
  [k v opts]
  (when (and (playwright-pseudos k) (= :css (:dialect opts)))
    (malformed! (str k " is a Playwright pseudo-class; this step is evaluated "
                     "by the page's own querySelectorAll")
                {:key k :dialect :css}))
  (case k
    :has      (str ":has(" (compile* v opts) ")")
    :not      (str ":not(" (compile* v opts) ")")
    :has-text (str ":has-text(" (css-string (scalar-text v k)) ")")
    :text-is  (str ":text-is(" (css-string (scalar-text v k)) ")")
    :visible  (if (true? v)
                ":visible"
                (malformed! ":visible takes true" {:key k :value v}))))

(defn- attrs->css
  [m opts]
  (when (empty? m)
    (malformed! "an attribute map must name at least one attribute" {:selector m}))
  (doseq [k (keys m)]
    (when-not (keyword? k)
      (malformed! "attribute keys are keywords" {:key k})))
  (let [specials #{:id :class :has :not :has-text :text-is :visible}
        attrs    (sort-by (juxt #(or (namespace %) (name %)) #(name %))
                          (remove specials (keys m)))]
    (str (when (contains? m :id) (id-clause (:id m)))
         (when (contains? m :class) (class-clause (:class m)))
         (apply str (map #(attr-clause % (get m %)) attrs))
         (apply str (for [k pseudo-order :when (contains? m k)]
                      (pseudo-clause k (get m k) opts))))))

(defn- keyword->css
  [k]
  (when (namespace k)
    (malformed! "a selector keyword has no namespace (did you mean an attribute map?)"
                {:selector k}))
  (let [s (name k)]
    (if (and (seq s) (re-matches keyword-re s))
      s
      (malformed! "keyword is not tag#id.class" {:selector k}))))

(defn- chain
  [sep parts opts sel]
  (when (empty? parts)
    (malformed! (str (first sel) " needs at least one selector") {:selector sel}))
  (str/join sep (map #(compile* % opts) parts)))

(defn- vector->css
  [[head & more :as sel] opts]
  (when-not (keyword? head)
    (malformed! "a selector vector starts with a tag or :in/:>/:or" {:selector sel}))
  (case head
    :in (chain " " more opts sel)
    :>  (chain " > " more opts sel)
    :or (str ":is(" (chain ", " more opts sel) ")")
    (let [[attrs children] (if (map? (first more))
                             [(first more) (rest more)]
                             [nil more])
          tag (keyword->css head)]
      (when (> (count children) 1)
        (malformed! (str "a hiccup selector has at most one child (nesting means "
                         "descendant); use {:has ...} to require several")
                    {:selector sel}))
      (str tag
           (when attrs (attrs->css attrs opts))
           (when-let [child (first children)]
             (str " " (compile* child opts)))))))

(defn- compile*
  [sel opts]
  (cond
    (string? sel)  (if (str/blank? sel)
                     (malformed! "a selector string must not be blank" {:selector sel})
                     sel)
    (keyword? sel) (keyword->css sel)
    (map? sel)     (attrs->css sel opts)
    (vector? sel)  (if (empty? sel)
                     (malformed! "an empty vector selects nothing" {:selector sel})
                     (vector->css sel opts))
    :else (malformed! "a selector is a string, keyword, attribute map or vector"
                      {:selector sel})))

;; =============================================================================
;; Public API
;; =============================================================================

(defn compile-selector
  "Result of the selector string `sel` denotes, or a `:selector/malformed`
   error carrying :selector (the whole authored value) and :problem.
   `opts` may carry `:dialect` (`:playwright`, the default, or `:css`)."
  ([sel] (compile-selector sel {}))
  ([sel opts]
   (try
     (r/ok (compile* sel opts))
     (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
       (let [d (ex-data e)]
         (if (::malformed d)
           (r/err :selector/malformed
                  (-> d (dissoc ::malformed) (assoc :selector sel :at (:selector d))))
           (throw e)))))))

(defn css
  "The selector string `sel` denotes; throws ex-info on a malformed one."
  ([sel] (css sel {}))
  ([sel opts]
   (let [res (compile-selector sel opts)]
     (if (r/ok? res)
       (:ok res)
       (throw (ex-info (str "malformed selector: " (:problem res)) res))))))

(defn valid?
  "True when `sel` compiles."
  ([sel] (valid? sel {}))
  ([sel opts] (r/ok? (compile-selector sel opts))))

;; =============================================================================
;; Helpers — return selector DATA, so they compose with each other and with
;; literal hiccup.
;; =============================================================================

(defn testid
  "Element whose data-testid is exactly `id`; `tag` narrows it."
  ([id] {:data-testid id})
  ([tag id] [tag {:data-testid id}]))

(defn testid-prefix
  "Element whose data-testid starts with `prefix`."
  ([prefix] {:data-testid/prefix prefix})
  ([tag prefix] [tag {:data-testid/prefix prefix}]))

(defn role
  "Element with ARIA role `r`, optionally narrowed by more attributes."
  ([r] {:role r})
  ([r attrs] (merge {:role r} attrs)))

(defn within
  "`inner` anywhere inside `outer` (more selectors chain deeper)."
  [outer inner & deeper]
  (into [:in outer inner] deeper))

(defn child-of
  "`inner` as a direct child of `outer`."
  [outer inner & deeper]
  (into [:> outer inner] deeper))

(defn any-of
  "Whatever matches any of `sels`."
  [& sels]
  (into [:or] sels))

(defn with-text
  "`sel` narrowed to elements containing `text` (Playwright only)."
  [sel text]
  (cond
    (map? sel)                                 (assoc sel :has-text text)
    (keyword? sel)                             [sel {:has-text text}]
    (and (vector? sel) (map? (second sel))
         (not (#{:in :> :or} (first sel)))
         (<= (count sel) 2))                   (update sel 1 assoc :has-text text)
    (and (vector? sel) (= 1 (count sel))
         (not (#{:in :> :or} (first sel))))    (conj sel {:has-text text})
    :else                                      [:* {:has sel :has-text text}]))

;; =============================================================================
;; Macro
;; =============================================================================

#?(:clj
   (defn- literal?
     [form]
     (not-any? #(or (symbol? %) (seq? %))
               (tree-seq coll? seq form))))

#?(:clj
   (defmacro defselector
     "Define `name` as selector data. A literal selector is compiled at
      macroexpansion, so a malformed one fails the LOAD of the namespace that
      wrote it, not a run. The compiled string rides on the var's metadata as
      :selector/css. A selector built from helpers is checked when the var is
      defined."
     [name & body]
     (let [[doc form] (if (and (string? (first body)) (next body))
                        [(first body) (second body)]
                        [nil (first body)])
           compiled  (when (literal? form) (css form))]
       `(def ~(vary-meta name merge
                         (cond-> {}
                           doc      (assoc :doc doc)
                           compiled (assoc :selector/css compiled)))
          (let [v# ~form]
            (css v#)
            v#)))))
