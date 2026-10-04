(ns hive-cljs.dialect.js
  "The JavaScript runtime dialect — how a runtime step becomes source text ANY
   page can evaluate, whatever compiled it.

   This is the stack-agnostic half of the runtime vocabulary. Elm, React,
   Svelte, Vue and hand-written JavaScript all present the same surface to a
   browser, so one dialect covers all of them.

   Two levels of it. `:eval-js` / `:expect-js` / `:wait-for-js` take raw
   expressions and reach into whatever the app happens to expose — always
   available, and per-app. `:expect-state` / `:wait-for-state` read through the
   probe contract instead, which is what makes ONE scenario vocabulary span
   stacks rather than each app inventing its own accessor.

   BOTH halves of that contract live here: the expressions that read it, and
   `installer`, the probe that answers them. Rendering is portable; loading the
   probe off the classpath is not, so only that part is JVM-side."
  (:require [clojure.string :as str]
            [hive-cljs.dialect.probe :as probe]
            [hive-cljs.dialect.source :as src]
            #?(:clj [clojure.java.io :as io])))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn expr
  "Source text an authored argument contributes.

   A string is JavaScript already and passes through verbatim — the escape
   hatch. A bare dotted symbol (`window.app.ready`) has always passed through as
   its own name and still does. Anything else is a probe FORM, rendered by
   `hive-cljs.dialect.probe`, so a manifest need never carry a JS string blob.

   `locals` names symbols the surrounding expression binds — `v` for a state
   predicate."
  ([x] (expr x {}))
  ([x locals]
   (cond
     (string? x) x
     (and (symbol? x) (nil? (namespace x)) (not (contains? locals x))
          (re-matches #"^[A-Za-z_$][A-Za-z0-9_$.]*$" (name x)))
     (name x)
     :else (probe/->js x locals))))

(def state-locals
  "Locals a `:expect-state` / `:wait-for-state` predicate sees."
  {'v "v"})

(defn state-pred
  "Source of a state predicate. A FUNCTION form (`some?`, `(fn [x] …)`) is
   applied to the read value, the way the re-frame dialect applies its
   predicates; anything else is an expression over `v`."
  [pred]
  (if (probe/fn-form? pred)
    (probe/->js (list pred 'v) state-locals)
    (expr pred state-locals)))

(defn- raw
  "Locals carrying JavaScript the caller already rendered (an authored string,
   a predicate over `v`). A local is spliced as written, so each is
   parenthesised to stay one operand whatever operators it holds."
  [m]
  (update-vals m #(str "(" % ")")))

(defn truthy-value
  "JS yielding the VALUE when it is truthy and false when it is not.

   Not a bare `!!(…)`: a passing assertion should report what it saw, and
   JavaScript falsiness is not Clojure falsiness — 0 and \"\" are failures here
   and would both survive a `some?` check on the way back."
  [source]
  (probe/->js '(let [v src] (if v v false)) (raw {'src source}) {:pin #{'v}}))

(defn truthy-probe
  "JS yielding `[truthy? value]` — the polled counterpart of `truthy-value`.

   The value rides along so a timeout can say 'never happened' apart from 'not
   yet'; the array reads back as a Clojure vector."
  [source]
  (probe/->js '(let [v src] [(boolean v) v]) (raw {'src source}) {:pin #{'v}}))

;; =============================================================================
;; The probe contract — installed by the run, not imported by the app
;; =============================================================================

(def probe-key
  "Global the injected probe installs itself under, and the only name an
   application needs to know."
  "__hive__")

(def probe-missing-message
  (str "the hive probe was never installed on this page — the browser adapter "
       "cannot run a document bootstrap script, so :expect-state and "
       ":wait-for-state have nothing to read. Use :expect-js with your own "
       "expression instead."))

(def installer
  "The probe itself — the JavaScript half of this dialect's contract.

   Injected into every document the driven session loads, so an application
   opts in with one guarded line and no dependency:

     window.__hive__?.expose('model', () => store.getState())

   Shipped as a resource rather than a published npm package: ~90% of it is the
   READ side, which is this library's contract and not the application's code.
   Injecting also removes the version skew between shim and runner that a
   separate package would invite, and means the probe never reaches production.

   A delay on both platforms so callers deref uniformly; only a JVM host has a
   classpath to read it from."
  #?(:clj  (delay (slurp (io/resource "hive_cljs/probe.js")))
     :cljs (delay nil)))

(defn- json-scalar
  [x]
  (cond
    (keyword? x) (src/pr-source (name x))
    (string? x)  (src/pr-source x)
    (number? x)  (str x)
    :else        (src/pr-source (str x))))

(defn json-path
  "A path vector as a JSON array literal. Segments are identifiers and indices,
   so Clojure's own string escaping is JSON's."
  [path]
  (str "[" (str/join "," (map json-scalar path)) "]"))

(defn read-source
  "JS reading `path` out of the probe's exposed state.

   An uninstalled probe THROWS rather than reading `undefined` off a missing
   global. Three failures have to stay distinguishable, and only one of them is
   about the application: the probe was never installed (the adapter cannot
   bootstrap a document), nothing was exposed under that name (a wiring
   mistake — the probe itself reports this, listing what was), or the value is
   genuinely absent (an ordinary assertion failure, which reads null)."
  [path]
  (let [probe-sym (symbol "js" (str "window." probe-key))
        segment   (fn [x] (cond (keyword? x) (name x)
                                (or (string? x) (number? x)) x
                                :else (str x)))]
    (probe/->js (list 'if probe-sym
                      (list '.read probe-sym (mapv segment path))
                      (list 'throw probe-missing-message)))))

(defn state-assertion
  "Assert `pred` — a JS expression over the bound `v` — against the value at
   `path`. Yields the value when it holds, false when it does not."
  [path pred]
  (probe/->js '(let [v value] (if held v false))
              (raw {'value (read-source path) 'held (state-pred pred)})
              {:pin #{'v}}))

(defn state-probe
  "The polled counterpart of `state-assertion`: `[held? value]`."
  [path pred]
  (probe/->js '(let [v value] [(boolean held) v])
              (raw {'value (read-source path) 'held (state-pred pred)})
              {:pin #{'v}}))

(defn fits-source
  "JS asking whether everything `selector` matches stays inside its box.

   Two questions, because an element can overflow in two directions and only
   one of them is visible in a scroll size. It must sit inside the VIEWPORT
   horizontally, and it must not clip its own content.

   Rectangles rather than `scrollWidth <= clientWidth` alone: an INLINE element
   reports both as 0, so that comparison is `0 <= 0` and passes on every input,
   which is how a fit gate can be written, run, and never able to fail. The
   self-clip half is therefore asked only of elements that have a box, and the
   containment half is asked of every element that has a rectangle.

   Three answers, and the third is the point. All fit: the number measured,
   which is truthy and says how much was looked at. Something overflows:
   `false`, which the runtime channel reads as a failed assertion. NOTHING was
   measurable — the selector matched no element, or matched only elements with
   no rectangle: it THROWS, so the step reports an error rather than a pass. A
   gate that could not look must never read as a gate that looked and was
   happy."
  ([selector] (fits-source selector 1))
  ([selector tolerance]
   (probe/->js
    '(let [els (dom/all sel)]
       (if (zero? (count els))
         (throw (new js/Error (str "expect-fits: nothing matches " sel)))
         (let [vw (.-innerWidth js/window)]
           (loop [i 0 measured 0 over 0]
             (if (< i (count els))
               (let [el (aget els i)
                     r  (.getBoundingClientRect el)]
                 (cond
                   (not (and (> (.-width r) 0) (> (.-height r) 0)))
                   (recur (inc i) measured over)

                   (or (> (.-right r) (+ vw tol)) (< (.-left r) (- tol)))
                   (recur (inc i) (inc measured) (inc over))

                   (and (> (.-clientWidth el) 0)
                        (> (.-scrollWidth el) (+ (.-clientWidth el) tol)))
                   (recur (inc i) (inc measured) (inc over))

                   :else
                   (recur (inc i) (inc measured) over)))
               (cond
                 (zero? measured)
                 (throw (new js/Error (str "expect-fits: " (count els)
                                           " element(s) match " sel
                                           " and none has a rectangle")))
                 (pos? over) false
                 :else measured))))))
    {'sel (src/pr-source (str selector))
     'tol (str "(" (double tolerance) ")")})))

;; =============================================================================
;; Contrast sampling
;; =============================================================================

(def ^:private backdrop-form
  "Probe form of a function collecting an element's background LAYERS and the
   opacity above them.

   Returns `[layers, opacity]`: every background an ancestor paints, innermost
   first, up to and including the first opaque one, and the product of the
   `opacity` property from the element up to that ancestor.

   Layers rather than one colour, because a translucent card over a dark page
   is neither of those two colours; opacity separately, because a computed
   `color` does not carry it. Both are folded in `contrast`, not here: this
   side of the boundary collects, it does not decide."
  '(fn [el]
     (loop [n el layers [] opacity 1]
       (if n
         (let [cs (js/getComputedStyle n)
               o  (js/parseFloat (.-opacity cs))
               c  (.-backgroundColor cs)
               m  (and c (re-find #"^rgba?\(([^)]+)\)" c))
               a  (if m
                    (let [p (filter (fn [s] (pos? (count s)))
                                    (.split (nth m 1) #"[,/\s]+"))]
                      (if (> (count p) 3) (js/parseFloat (nth p 3)) 1))
                    0)
               layers (if (> a 0) (conj layers c) layers)]
           (if (>= a 1)
             [layers opacity]
             (recur (.-parentElement n)
                    layers
                    (if (and (>= o 0) (< o 1)) (* opacity o) opacity))))
         [(conj layers (.-backgroundColor
                        (js/getComputedStyle (.-documentElement js/document))))
          opacity]))))

(def ^:private rows-form
  "Probe form collecting the rows for `specs`, given `backdrop`."
  '(mapcat
    (fn [spec]
      (let [els (try (.slice (dom/all (get spec :selector)) 0 (get spec :limit))
                     (catch :default e nil))]
        (if (nil? els)
          [[(str (get spec :id) "-selector") nil nil (get spec :role) nil false 1]]
          (keep-indexed
           (fn [i el]
             (let [cs   (js/getComputedStyle el)
                   b    (backdrop el)
                   id   (str (get spec :id) "-" i)
                   side (get spec :side)]
               (if (= (get spec :role) "non-text")
                 (let [w (js/parseFloat (get cs (str "border" side "Width")))]
                   (when-not (or (= (get cs (str "border" side "Style")) "none")
                                 (not (> w 0)))
                     [id (get cs (str "border" side "Color")) (nth b 0)
                      "non-text" nil false (nth b 1)]))
                 (when-not (or (not (.-textContent el))
                               (not (.trim (.-textContent el))))
                   [id (.-color cs) (nth b 0) nil
                    (js/parseFloat (.-fontSize cs))
                    (>= (js/parseInt (.-fontWeight cs) 10) 700)
                    (nth b 1)]))))
           els))))
    specs))

(defn- side-name
  "A border side as the computed-style property spells it: `Top`, `Left`, ..."
  [side]
  (let [s (name (or side :top))]
    (str (str/upper-case (subs s 0 1)) (str/lower-case (subs s 1)))))

(defn- spec-literal
  "One spec as the map the page reads. Strings throughout, so the probe
   renders each as its own string literal."
  [{:keys [id selector role side limit]}]
  {:id       (name (or id :sample))
   :selector (str selector)
   :role     (when (= :non-text role) "non-text")
   :side     (side-name side)
   :limit    (long (or limit 10))})

(defn contrast-rows-source
  "JS collecting contrast rows for `specs` out of the page a session is driving.

   Each spec is `{:id :selector :role :side :limit}`. Rows come back POSITIONAL
   as `[id foreground background-layers role font-size-px bold? opacity]`, so
   nothing depends on how a host spells a JavaScript key;
   `contrast/rows->samples` promotes them.

   A role is emitted only for `:non-text`, the one role a font size cannot
   imply. Text carries its size and weight instead and is classified from
   those, so a heading is judged at the bar its size earns.

   Three things are skipped rather than reported, because each would be a
   false failure and not a finding: text nodes with nothing in them,
   `:non-text` edges whose border is `none` or zero width, and any spec whose
   selector the browser rejects, which is reported as one unresolvable row
   instead of aborting every other spec."
  [specs]
  (probe/->js (list 'let ['backdrop backdrop-form
                          'specs    (mapv spec-literal specs)]
                    rows-form)))

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
      :expect-fits  (fits-source a)
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
