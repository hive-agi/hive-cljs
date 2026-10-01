# Step reference

A scenario is a vector of step vectors. The head keyword is the step kind; the
rest are its arguments. Steps are **data** — they compile to port-neutral ops
before anything touches a browser.

Each step is routed to one of two channels:

- **browser** → `IBrowserDriver` (Playwright): the DOM
- **runtime** → `ICljsEval` (shadow cljs-eval over nREPL): the running app

One scenario mixes both freely. That is the point: `:expect-text` proves what the
user sees, `:expect-sub` proves what the app believes.

Both channels address the page the session opened. When the application under
test is rendered inside an iframe (a slide player, a preview pane, an embedded
editor), point `:iframe` at it and every selector and expression below resolves
in that document instead: see
[configuration.md](configuration.md#iframe-when-the-application-under-test-is-a-child-document).

## Selectors are data

Every step argument named "selector" below (and `:expect-fits`, the `:iframe`
option, and the selector arguments of the `dom/` probe functions) is **selector
data**, which `hive-cljs.selector` compiles while the step compiles:

| Data | Compiles to |
|---|---|
| `:#go` / `:li.row.active` | `#go` / `li.row.active` |
| `{:role "tablist"}` | `[role="tablist"]` |
| `{:data-testid/prefix "p-"}` | `[data-testid^="p-"]` (also `/suffix` `/contains` `/word`) |
| `{:disabled true}` | `[disabled]` |
| `[:li {:has-text "X"} [:button {:data-testid "ok"}]]` | `li:has-text("X") button[data-testid="ok"]`; nesting means descendant |
| `[:> :nav :a]` / `[:in :main :button]` / `[:or :#a :.b]` | `nav > a` / `main button` / `:is(#a, .b)` |
| `[:li {:has :button.del :not :.done}]` | `li:has(button.del):not(.done)` |

`:has-text`, `:text-is` and `:visible` are Playwright pseudo-classes. They are
refused in `:expect-fits` and in the `dom/` functions of a
[probe form](#probe-forms), because those run the page's own
`querySelectorAll`.

`:iframe` takes selector data as well, on the e2e config or on one scenario:
`:iframe [:in :hyperframes-player :iframe]`. See
[configuration.md](configuration.md#iframe-when-the-application-under-test-is-a-child-document).

Helpers return data, so they compose: `testid`, `testid-prefix`, `role`,
`within`, `child-of`, `any-of`, `with-text`. `defselector` names a selector
and compiles a literal one when the namespace loads:

```clojure
(require '[hive-cljs.selector :as sel])
(sel/defselector proposal [:li {:data-testid/prefix "site-proposta-"}])
[:click (sel/within (sel/with-text proposal "X") (sel/testid :button "aprovar"))]
```

A malformed selector (`[:li :a :b]`, `{}`, `42`) is a `:selector/malformed`
error when the plan compiles, and carries `:problem`. It never reaches the
browser, so it cannot turn into a timeout.

## Strings are the escape hatch

Selectors, probes and predicates are written as Clojure data throughout this
reference. A string is still accepted everywhere one of them is, and is passed
through **verbatim**: a selector string goes to Playwright as written, a
`:*-js` / `:*-state` string is JavaScript, an `:eval-cljs` / `:expect-sub` /
`:expect-db` string is ClojureScript source.

Reach for a string only when the data cannot say it:

- a Playwright engine the selector data has no form for (`"text=Sign in"`,
  `"role=button[name=\"OK\"]"`, `>>` chains);
- JavaScript the probe language does not cover (statements, `new`, `await`,
  operators it has no head for);
- a ClojureScript reader macro EDN cannot carry, such as a regex `#"^p"`.

A string is not checked at plan time, so a typo in one surfaces in the page,
not when the plan compiles. That is the price, and the reason it is not the
default.

## Browser steps

### Navigation

| Step | Does |
|---|---|
| `[:goto "/login"]` | navigate; relative URLs resolve against `:base-url` |
| `[:back]` | history back |
| `[:reload]` | reload the page |

### Interaction

| Step | Does |
|---|---|
| `[:click :#go]` | click a selector |
| `[:fill :#user "pedro"]` | set an input's value |
| `[:select :#country "BR"]` | choose an option |
| `[:check :#agree]` | check a checkbox |
| `[:press :#user "Enter"]` | press a key on an element |
| `[:hover :#menu]` | hover |

### Synchronisation

| Step | Does |
|---|---|
| `[:wait-for :#chart]` | wait for a selector to appear |
| `[:wait-ms 250]` | fixed pause — a last resort |

### DOM assertions

| Step | Passes when |
|---|---|
| `[:expect-text :#hi "Hello"]` | element's text CONTAINS the expected string |
| `[:expect-value :#user "pedro"]` | input's value equals exactly |
| `[:expect-visible :#chart]` | element is visible |
| `[:expect-hidden :#hi]` | element is absent or hidden |
| `[:expect-count :.row 3]` | selector matches exactly N elements |
| `[:expect-attr :#menu "aria-expanded" "true"]` | attribute equals exactly |
| `[:expect-url "/dashboard"]` | current URL CONTAINS the expected string |
| `[:expect-no-errors]` | no console error was logged and no uncaught page error thrown since the page opened |
| `[:expect-no-errors {:ignore ["favicon"] :sources #{:pageerror}}]` | same, excusing errors containing a substring or restricted to one source |

`:expect-attr` distinguishes an **absent** attribute from one holding the wrong
value, because they are different mistakes: `no such attribute` is a selector or
a spelling to fix, a wrong value is the application to fix.

`:expect-no-errors` options are data, never JS: `:sources` is a set of
`:console`/`:pageerror` (default both), `:ignore` a vector of substrings.

### Artifacts

| Step | Does |
|---|---|
| `[:screenshot "logged-in"]` | PNG into `:artifacts-dir`, path recorded in `:run/artifacts` |

## Runtime steps

Evaluated inside the running application — in the page the scenario itself drives,
not merely in some runtime attached to the build.

There are **two runtime vocabularies**, and which one your project can use is
decided by `:hive.cljs/toolchain`:

| Vocabulary | Kinds | Speaks to |
|---|---|---|
| re-frame | `:eval-cljs` `:dispatch` `:expect-sub` `:expect-db` `:wait-for-sub` `:wait-for-db` | a ClojureScript app over the shadow nREPL (`:shadow-cljs`) |
| JavaScript | `:eval-js` `:expect-js` `:wait-for-js` | **any** app, evaluated in the page (`:browser`, and any driver that can evaluate) |
| probe | `:expect-state` `:wait-for-state` | any app that exposes a getter to the injected probe |
| layout | `:expect-fits` | **any** app: a named question about the rendered box, with no expression to author |

A step whose vocabulary the connected channel does not speak reports
`:incomplete` — never a pass, and never a failure of your application:

```
the runtime channel has no rendering for :expect-sub — that step vocabulary
belongs to another stack
```

### The re-frame vocabulary

Requires `:nrepl-port` in the config and a build id (explicit `:build`, or
inherited when the project has one build).

| Step | Evaluates |
|---|---|
| `[:eval-cljs (+ 1 2)]` | the form; passes if it returns without error |
| `[:dispatch [:login "pedro"]]` | `(re-frame.core/dispatch-sync [:login "pedro"])` |
| `[:expect-sub [:current-user] some?]` | `(some? @(re-frame.core/subscribe [:current-user]))` |
| `[:expect-db [:user :name] some?]` | `(some? (get-in @re-frame.db/app-db [:user :name]))` |
| `[:wait-for-sub [:selected] some?]` | the same, polled until it holds |
| `[:wait-for-db [:items] seq]` | the same, polled until it holds |

#### Arguments are forms

The manifest is EDN, so a step argument simply **is** the form. A source string
would cost escaping, editor support, linting and indexing, and a typo inside one
ships as a runtime error rather than failing to read.

```clojure
[:eval-cljs (my.app/reset!)]                       ; a form
[:expect-sub [:current-user] some?]                ; a symbol
[:expect-db [:user :name] (fn [v] (= v "pedro"))]  ; a fn form
```

EDN has no `#(…)` or `#"…"` reader macros, so write `(fn [u] (= u "pedro"))`
rather than `#(= % "pedro")`. A regex has no EDN spelling at all; that is one
of the few places a string is still needed (see
[Strings are the escape hatch](#strings-are-the-escape-hatch)). `:dispatch` has
always taken `[:login "pedro"]` rather than text for the same reason.

A form is printed with `pr-str` under **pinned** printer vars
(`*print-namespace-maps*` false, `*print-length*` and `*print-level*` nil,
`*print-meta*` false, `*print-readably*` true), so the source sent to the app is
the same whatever bindings the calling thread carries: a REPL with
`*print-length*` set cannot truncate `[1 2 3 4]` into `[1 2 ...]`, and
`{:user/id 1}` never arrives as `#:user{:id 1}`. A string is sent verbatim.

The predicate is applied to the value, so any one-argument function works:
`some?`, `string?`, `(fn [xs] (> (count xs) 3))`.

`:expect-sub` and `:expect-db` are **assertions** — a `false` or `nil` result
fails the step. `:eval-cljs` and `:dispatch` are **actions** — they pass unless
evaluation errors.

### The JavaScript vocabulary

Works for Elm, React, Svelte, Vue and hand-written JavaScript alike: the
expression is evaluated in the page the scenario is driving, so there is no
runtime to configure and no `:nrepl-port` to set.

| Step | Evaluates |
|---|---|
| `[:eval-js (.reset js/window.app)]` | the expression; passes unless it throws |
| `[:expect-js (= js/document.title "Inbox")]` | the expression as an assertion |
| `[:wait-for-js (.-ready (.getState js/window.store))]` | the same, polled until it holds |

The argument is a **probe form**, rendered to one JavaScript expression; see
[Probe forms](#probe-forms) below for what it may contain.

`:expect-js` uses **JavaScript** truthiness, so `0`, `""`, `null`, `undefined`
and `NaN` all fail the step. A passing assertion reports the value it saw rather
than a bare `true`, so the report says what the page actually held.

These steps need a page, which means a `:goto` (or any navigating step) has to
come first — otherwise there is no application to ask:

```clojure
[[:goto "/"]
 [:click :#load]
 [:wait-for-js (> (count (.-items js/window.__elmModel)) 0)]
 [:expect-js (= 3 (dom/count :.item))]]
```

How an app exposes its state to that first expression is the app's business: an
Elm port writing to `window`, a Redux store, a Svelte store, a signal. Nothing
here reaches into a framework's internals — which is exactly why it works for
all of them.

#### Probe forms

The argument to `:eval-js`, `:expect-js` and `:wait-for-js` (and the predicate
of `:expect-state` / `:wait-for-state`) is a **form**.
`hive-cljs.dialect.probe` renders it to one JavaScript expression, so a manifest
carries no JS blobs:

```clojure
[:wait-for-js (= 1 (dom/count [:in :#welcome :.fragment.visible]))]
[:expect-js   (every? dom/visible? (dom/all [:in :#main {:data-composition-id true}]))]
[:expect-js   (>= (count (keys (.-__timelines js/window))) 16)]
[:expect-js   (let [tl (get (.-__timelines js/window) "animation")]
                (.seek tl 3)
                (= 3 (.time tl)))]
[:expect-state ["model" "loading"] (= v false)]   ; v is the value read
[:expect-state ["model" "user"] some?]            ; a function is applied to it
```

| Group | Forms |
|---|---|
| DOM | `dom/one` `dom/all` `dom/count` (each `(sel)` or `(root sel)`), `dom/text` `dom/attr` `dom/style` `dom/visible?` `dom/matches?` `dom/has-class?` (element or selector) |
| core | `=` `not=` `<` `>` `<=` `>=` `+` `-` `*` `/` `mod` `inc` `dec` `min` `max` `and` `or` `not` `if` `when` `cond` `do` `let` `fn` `nil?` `some?` `true?` `false?` `string?` `number?` `count` `empty?` `first` `last` `nth` `keys` `vals` `get` `get-in` `aget` `contains?` `str` `every?` `some` `filter` `remove` `map` |
| strings | `str/includes?` `str/starts-with?` `str/ends-with?` `str/trim` `str/lower-case` `str/upper-case` `str/blank?` `str/join` |
| interop | `(.method obj args…)`, `(.-prop obj)`, `js/name`; `window`, `document`, `Math`, `JSON` and a few other globals need no prefix |

JavaScript semantics where they differ: `=` is `===` (two arrays are never
equal; compare `(str/join "," xs)` instead) and truthiness is JS truthiness.
`get`/`get-in` are nil-safe. The language is closed: an unknown function or an
unbound symbol fails the **plan** with `:step/malformed`, before a browser opens,
rather than reaching the page as a `ReferenceError`. A string is still accepted
verbatim, as the escape hatch for anything the forms cannot say.

Every selector argument of a `dom/` function is [selector data](#selectors-are-data)
too, compiled while the plan compiles. Because the page itself runs it with
`querySelector`, it is compiled as plain CSS: the Playwright-only `:has-text`,
`:text-is` and `:visible` are refused there, exactly as in `:expect-fits`.

Combined with `:iframe`, `document` is already the child document, so a probe
needs no prelude to find it.

Two things the JavaScript channel deliberately cannot do, both of which report
rather than pretend: the `:app-db-schema` invariant, and `cljs e2e mutate`'s
`--auto` fault derivation. Both mean rewriting the application's own handler
registry, which reading a page does not permit. Declared `:faults` still work.

### The probe vocabulary

`:expect-js` works, but every app spells its own state differently, so a suite
written that way is a pile of per-app expressions with nothing in common. The
probe is one accessor every stack shares.

The run **injects** it into every document before the page's own scripts, so the
application depends on nothing and authors one guarded line:

```js
window.__hive__?.expose('model', () => store.getState())
```

The `?.` is the whole production story: nothing injects the probe outside a
scenario, so the line is a no-op and there is no build flag to guard. Getters
run at read time, once per assertion.

| Step | Reads |
|---|---|
| `[:expect-state ["model" "user" "name"] (some? v)]` | `window.__hive__.read(["model","user","name"])` |
| `[:wait-for-state ["model" "loading"] (= v false)]` | the same, polled until it holds |

The first path segment names the exposed source; the rest indexes into it, and
integer segments index arrays (`["model" "items" 0 "id"]`). The predicate is a
[probe form](#probe-forms): `v` in it is the value that was read, and a function
such as `some?` or `(fn [x] (> x 2))` is applied to that value. A passing assertion reports that value rather than
a bare `true`.

This is the counterpart of `:expect-sub` for stacks that are not re-frame — same
shape, same debugging property: `:expect-text` red while `:expect-state` green
localises a bug to rendering rather than to state.

Three failures that must not be confused, and are not:

| | reads |
|---|---|
| the path runs off the end of the data | `null` — an ordinary assertion failure |
| nothing was exposed under that name | throws, listing what *was* exposed — a wiring mistake |
| the probe was never installed | throws, naming the adapter — not about your app at all |

Elm keeps no state in JavaScript, so it pushes instead. `pushed` returns the
subscriber and exposes the latest value it received — the same shape for a
websocket or any other push source:

```js
if (window.__hive__) app.ports.hiveState.subscribe(window.__hive__.pushed('model'))
```

Values cross a structured-clone boundary, so they are projected to JSON shapes:
functions become `"#function"`, DOM nodes `"#node:div"`, `Date`s ISO strings,
`Map`s objects, `Set`s arrays. Cycles become `"#cycle"` and nesting stops at
depth 12 — a store graph much larger than the value under test would otherwise
hang the assertion rather than fail it.

### The layout vocabulary

An overflow check is the same question every time, so it is a step kind rather
than an expression each manifest re-authors:

```clojure
[[:goto "/certificado"]
 [:wait-for :main]
 [:expect-fits :main]
 [:expect-fits :h1]]
```

| Step | Passes when |
|---|---|
| `[:expect-fits :main]` | every element the selector matches stays inside its box |

Two questions, because an element can overflow in two directions and only one of
them shows up in a scroll size. It must sit inside the **viewport**
horizontally, and it must not clip its own content.

Rectangles rather than `scrollWidth <= clientWidth` alone: an inline element
reports both as `0`, so that comparison is `0 <= 0` and passes on every input,
which is how a fit gate gets written, run, and is never able to fail. The
self-clip half is therefore asked only of elements that have a box; the
containment half is asked of every element that has a rectangle. A one-pixel
tolerance absorbs sub-pixel rounding.

Three answers, and the third is the point:

| | reports |
|---|---|
| everything fits | the NUMBER of elements measured, so a pass says how much was looked at |
| something overflows | `false`, which fails the step |
| nothing was measurable | throws: the selector matched nothing, or matched only elements with no rectangle |

That last row is why this is not `[:expect-js (dom/count …)]` with a
count of zero: a gate that could not look must never read as a gate that looked
and was happy.

Pair it with a per-scenario `:viewport` to ask the same question at several
widths; the selector is then the only thing that varies:

```clojure
{:id :phone   :viewport {:width 390 :height 844}  :steps [[:goto "/"] [:expect-fits :main]]}
{:id :desktop :viewport {:width 1440 :height 900} :steps [[:goto "/"] [:expect-fits :main]]}
```

### Condition-waits on state

`:wait-for` waits on the DOM; `:wait-for-sub` and `:wait-for-db` wait on what the
app *believes*. They take the same predicate forms as the matching `:expect-*`,
poll every `:poll-ms` (default 250) until `:timeout-ms`, and pass the moment the
predicate holds.

Reach for one whenever an assertion follows an async mutation:

```clojure
[:click :#save]
[:wait-for-sub [:selected] some?]        ; not [:wait-ms 2500]
[:expect-sub [:selected] (fn [s] (= "active" (:status s)))]
```

A fixed pause is a guess about a machine you are not running on: it passes warm
and fails cold. A condition-wait is a claim about the state you care about, and
its timeout failure reports the **last observed value** — enough to tell "never
happened" from "not yet":

```
condition never held within 15000ms — last value {:status "pending"}
```

Waiting on a DOM element as a proxy for state only works when a suitable element
happens to exist. These need none.

## The app-db invariant channel

Declare a malli schema for the whole app-db and every scenario becomes a
state-corruption detector on top of its own assertions:

```clojure
:hive.cljs/e2e {:app-db-schema inventory.frontend.schema/app-db
                :app-db-check  :every-step}   ; :mutations | :final
```

After each passing step, hive-cljs evaluates `(malli.core/explain schema @app-db)`
in the runtime; any explanation fails that step with the offending paths in
`:step/detail`. The app build must carry both the schema's namespace and malli.

`:every-step` (default) checks after every step, `:mutations` skips the steps that
only observe, `:final` checks once at the end. A step that already failed is not
re-blamed on the invariant, and an invariant that cannot be evaluated is reported
as an `:error` — an invariant that did not run is not an invariant that held.

## Semantics

**Failure halts the run.** The first `:fail` or `:error` stops execution; every
later step is reported `:skipped`. The browser session is still closed.

**A step that could not be attempted is `:incomplete` — never a pass.** Without a
connected runtime channel, `:expect-sub` / `:expect-db` report `:incomplete` and
the run's state becomes `:incomplete`. The run still produces a report rather
than exploding, and browser steps still report their own results — but the run is
not green, because assertions that never executed prove nothing. A browser-only
scenario is unaffected. A missing *browser* when the plan needs one is a hard
`:run/no-driver` error.

**Step states**: `:pass`, `:fail` (assertion did not hold), `:error` (the step
threw or the channel failed), `:incomplete` (the step could not be attempted),
`:skipped` (the verdict was already decided). The run's state is its worst step:

```
:error  >  :fail  >  :incomplete  >  :pass
```

`:skipped` never decides a run — it only ever follows a step that already did.
`:incomplete` ranks below `:fail` because a real failure is the more actionable
signal, and above `:pass` because an unexecuted assertion is not evidence.

**State assertions read the browser the scenario drives.** When both channels
support it, hive-cljs stamps the page it opened and pins runtime evaluation to
that exact page. Without this, a second connected runtime — a stray tab, a
forgotten headless browser, devcards, the shadow UI — answers the assertions
instead, and the scenario silently grades the wrong page. Binding happens once
per run, just before the first runtime step. If the page cannot be identified,
runtime steps are `:incomplete`; no assertion is answered by a runtime that may
not be yours.

## Malformed steps fail the plan, not the run

Arity and shape are checked while compiling, before a browser opens:

```clojure
[:fill :#a]       ; => :step/malformed {:expected-arity 2 :got-arity 1 :index 2}
[:teleport "/x"]  ; => :step/unknown-kind {:known [:goto :back … ]}
["goto" "/x"]     ; => :step/no-kind
[:click [:li :a :b]] ; => :selector/malformed {:problem "…at most one child…"}
```

## Adding a step kind

Steps compile through an ordered rule chain (`hive-cljs.step/IStepRule`); the
first rule that `applies?` wins. A new kind is a new rule appended to the vector —
no edit to existing code, and an earlier rule can shadow a built-in one.

```clojure
(def swipe
  (reify step/IStepRule
    (rule-id   [_] :swipe)
    (applies?  [_ st] (= :swipe (first st)))
    (compile-op [_ [_ target dir :as st]]
      ;; compile the selector datum here, as the built-in kinds do, so the
      ;; adapter only ever sees a string
      (let [res (sel/compile-selector target)]
        (if (r/ok? res)
          (r/ok {:op/kind :swipe :op/channel :browser
                 :op/args [(:ok res) dir] :op/source (vec st)})
          res)))))

(step/compile-step (conj step/default-rules swipe) [:swipe :#carousel :left])
```

For a browser kind, also add a `perform-op` defmethod in the adapter — it
dispatches on `:op/kind`, so that too is open for extension.
