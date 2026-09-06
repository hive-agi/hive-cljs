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
            #?(:clj [clojure.java.io :as io])))

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

   An uninstalled probe THROWS rather than reading `undefined` off a missing
   global. Three failures have to stay distinguishable, and only one of them is
   about the application: the probe was never installed (the adapter cannot
   bootstrap a document), nothing was exposed under that name (a wiring
   mistake — the probe itself reports this, listing what was), or the value is
   genuinely absent (an ordinary assertion failure, which reads null)."
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
;; Contrast sampling
;; =============================================================================

(def ^:private backdrop-js
  "JS collecting an element's background LAYERS and the opacity above them.

   Returns `[layers, opacity]`: every background an ancestor paints, innermost
   first, up to and including the first opaque one, and the product of the
   `opacity` property from the element up to that ancestor.

   Layers rather than one colour, because a translucent card over a dark page
   is neither of those two colours; opacity separately, because a computed
   `color` does not carry it. Both are folded in `contrast`, not here: this
   side of the boundary collects, it does not decide."
  (str "const __hcBackdrop = (el) => {\n"
       "  const layers = [];\n"
       "  let opacity = 1;\n"
       "  let n = el;\n"
       "  while (n) {\n"
       "    const cs = getComputedStyle(n);\n"
       "    const o = parseFloat(cs.opacity);\n"
       "    const c = cs.backgroundColor;\n"
       "    const m = c && c.match(/^rgba?\\(([^)]+)\\)/);\n"
       "    let a = 0;\n"
       "    if (m) {\n"
       "      const p = m[1].split(/[,\\/\\s]+/).filter(s => s.length);\n"
       "      a = p.length > 3 ? parseFloat(p[3]) : 1;\n"
       "      if (a > 0) layers.push(c);\n"
       "    }\n"
       "    if (a >= 1) return [layers, opacity];\n"
       "    if (o >= 0 && o < 1) opacity *= o;\n"
       "    n = n.parentElement;\n"
       "  }\n"
       "  const root = getComputedStyle(document.documentElement).backgroundColor;\n"
       "  return [layers.concat([root]), opacity];\n"
       "};\n"))

(defn- side-name
  "A border side as the computed-style property spells it: `Top`, `Left`, ..."
  [side]
  (let [s (name (or side :top))]
    (str (str/upper-case (subs s 0 1)) (str/lower-case (subs s 1)))))

(defn- spec-literal [{:keys [id selector role side limit]}]
  (str "{id:" (json-scalar (name (or id :sample)))
       ",selector:" (json-scalar selector)
       ",role:" (if (= :non-text role) (json-scalar "non-text") "null")
       ",side:" (json-scalar (side-name side))
       ",limit:" (long (or limit 10)) "}"))

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
  (str "(() => {\n"
       backdrop-js
       "  const out = [];\n"
       "  const specs = [" (str/join "," (map spec-literal specs)) "];\n"
       "  for (const spec of specs) {\n"
       "    let els;\n"
       "    try {\n"
       "      els = Array.from(document.querySelectorAll(spec.selector))\n"
       "        .slice(0, spec.limit);\n"
       "    } catch (e) {\n"
       "      out.push([spec.id + '-selector', null, null, spec.role, null, false, 1]);\n"
       "      continue;\n"
       "    }\n"
       "    els.forEach((el, i) => {\n"
       "      const cs = getComputedStyle(el);\n"
       "      const backdrop = __hcBackdrop(el);\n"
       "      const id = spec.id + '-' + i;\n"
       "      if (spec.role === 'non-text') {\n"
       "        const w = parseFloat(cs['border' + spec.side + 'Width']);\n"
       "        if (cs['border' + spec.side + 'Style'] === 'none' || !(w > 0)) return;\n"
       "        out.push([id, cs['border' + spec.side + 'Color'], backdrop[0],\n"
       "                  'non-text', null, false, backdrop[1]]);\n"
       "      } else {\n"
       "        if (!el.textContent || !el.textContent.trim()) return;\n"
       "        out.push([id, cs.color, backdrop[0], null,\n"
       "                  parseFloat(cs.fontSize),\n"
       "                  parseInt(cs.fontWeight, 10) >= 700,\n"
       "                  backdrop[1]]);\n"
       "      }\n"
       "    });\n"
       "  }\n"
       "  return out;\n"
       "})()"))

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
