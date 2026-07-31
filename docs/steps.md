# Step reference

A scenario is a vector of step vectors. The head keyword is the step kind; the
rest are its arguments. Steps are **data** — they compile to port-neutral ops
before anything touches a browser.

Each step is routed to one of two channels:

- **browser** → `IBrowserDriver` (Playwright): the DOM
- **runtime** → `ICljsEval` (shadow cljs-eval over nREPL): the running app

One scenario mixes both freely. That is the point: `:expect-text` proves what the
user sees, `:expect-sub` proves what the app believes.

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
| `[:click "#go"]` | click a selector |
| `[:fill "#user" "pedro"]` | set an input's value |
| `[:select "#country" "BR"]` | choose an option |
| `[:check "#agree"]` | check a checkbox |
| `[:press "#user" "Enter"]` | press a key on an element |
| `[:hover "#menu"]` | hover |

### Synchronisation

| Step | Does |
|---|---|
| `[:wait-for "#chart"]` | wait for a selector to appear |
| `[:wait-ms 250]` | fixed pause — a last resort |

### DOM assertions

| Step | Passes when |
|---|---|
| `[:expect-text "#hi" "Hello"]` | element's text CONTAINS the expected string |
| `[:expect-value "#user" "pedro"]` | input's value equals exactly |
| `[:expect-visible "#chart"]` | element is visible |
| `[:expect-hidden "#hi"]` | element is absent or hidden |
| `[:expect-count ".row" 3]` | selector matches exactly N elements |
| `[:expect-url "/dashboard"]` | current URL CONTAINS the expected string |

### Artifacts

| Step | Does |
|---|---|
| `[:screenshot "logged-in"]` | PNG into `:artifacts-dir`, path recorded in `:run/artifacts` |

## Runtime steps

Evaluated inside the running application — in the page the scenario itself drives,
not merely in some runtime attached to the build. All require `:nrepl-port` in the
config and a build id (explicit `:build`, or inherited when the project has one
build).

| Step | Evaluates |
|---|---|
| `[:eval-cljs "(+ 1 2)"]` | the form; passes if it returns without error |
| `[:dispatch [:login "pedro"]]` | `(re-frame.core/dispatch-sync [:login "pedro"])` |
| `[:expect-sub [:current-user] "some?"]` | `(some? @(re-frame.core/subscribe [:current-user]))` |
| `[:expect-db [:user :name] "some?"]` | `(some? (get-in @re-frame.db/app-db [:user :name]))` |
| `[:wait-for-sub [:selected] "some?"]` | the same, polled until it holds |
| `[:wait-for-db [:items] "seq"]` | the same, polled until it holds |

The predicate is source text, so any expression works:
`"#(= % \"pedro\")"`, `"string?"`, `"#(> (count %) 3)"`.

`:expect-sub` and `:expect-db` are **assertions** — a `false` or `nil` result
fails the step. `:eval-cljs` and `:dispatch` are **actions** — they pass unless
evaluation errors.

### Condition-waits on state

`:wait-for` waits on the DOM; `:wait-for-sub` and `:wait-for-db` wait on what the
app *believes*. They take the same predicate strings as the matching `:expect-*`,
poll every `:poll-ms` (default 250) until `:timeout-ms`, and pass the moment the
predicate holds.

Reach for one whenever an assertion follows an async mutation:

```clojure
[:click "#save"]
[:wait-for-sub [:selected] "some?"]        ; not [:wait-ms 2500]
[:expect-sub [:selected] "#(= \"active\" (:status %))"]
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
[:fill "#a"]      ; => :step/malformed {:expected-arity 2 :got-arity 1 :index 2}
[:teleport "/x"]  ; => :step/unknown-kind {:known [:goto :back … ]}
["goto" "/x"]     ; => :step/no-kind
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
    (compile-op [_ st] (r/ok {:op/kind :swipe :op/channel :browser
                              :op/args (vec (rest st)) :op/source (vec st)}))))

(step/compile-step (conj step/default-rules swipe) [:swipe "#a" :left])
```

For a browser kind, also add a `perform-op` defmethod in the adapter — it
dispatches on `:op/kind`, so that too is open for extension.
