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

Evaluated inside the running application. All require `:nrepl-port` in the config
and a build id (explicit `:build`, or inherited when the project has one build).

| Step | Evaluates |
|---|---|
| `[:eval-cljs "(+ 1 2)"]` | the form; passes if it returns without error |
| `[:dispatch [:login "pedro"]]` | `(re-frame.core/dispatch-sync [:login "pedro"])` |
| `[:expect-sub [:current-user] "some?"]` | `(some? @(re-frame.core/subscribe [:current-user]))` |
| `[:expect-db [:user :name] "some?"]` | `(some? (get-in @re-frame.db/app-db [:user :name]))` |

The predicate is source text, so any expression works:
`"#(= % \"pedro\")"`, `"string?"`, `"#(> (count %) 3)"`.

`:expect-sub` and `:expect-db` are **assertions** — a `false` or `nil` result
fails the step. `:eval-cljs` and `:dispatch` are **actions** — they pass unless
evaluation errors.

## Semantics

**Failure halts the run.** The first `:fail` or `:error` stops execution; every
later step is reported `:skipped`. The browser session is still closed.

**A missing runtime is a skip, not a failure.** Without `:nrepl-port`, runtime
steps report `:skipped` with the reason, and a browser-only scenario still passes.
A missing *browser* when the plan needs one is a hard `:run/no-driver` error.

**Step states**: `:pass`, `:fail` (assertion did not hold), `:error` (the step
threw or the channel failed), `:skipped`. The run's state is its worst step —
`:error` > `:fail` > `:pass`; `:skipped` alone does not fail a run.

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
