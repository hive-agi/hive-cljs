# hive-cljs

<!-- hive-badges -->

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/hive-cljs.svg)](https://clojars.org/io.github.hive-agi/hive-cljs)
[![cljdoc](https://cljdoc.org/badge/io.github.hive-agi/hive-cljs)](https://cljdoc.org/d/io.github.hive-agi/hive-cljs/CURRENT)
[![release](https://github.com/hive-agi/hive-cljs/actions/workflows/release.yml/badge.svg)](https://github.com/hive-agi/hive-cljs/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

<!-- /hive-badges -->

Frontend development as a hive IAddon: **build status, runtime eval, Playwright
e2e scenarios, coverage and build→e2e watching**, driven by config in your
project root.

Works with **any frontend stack**. ClojureScript gets the deepest integration —
shadow-cljs build supervision, re-frame state assertions, per-namespace coverage.
Elm, React, Svelte, Vue and hand-written JavaScript get the same scenario
vocabulary, the same reports and the same tool surface.

One addon. One `cljs` subdomain on the `code` tool. Three ports.

A step that could not be attempted is `:incomplete` — never a silent pass.

## Why not just a browser driver

A scenario asserts on the **DOM and on what the application believes, in the same
step vector**:

```clojure
[[:goto "/inbox"]
 [:click :#refresh]
 [:wait-for-state ["model" "loading"] (= v false)]
 [:expect-state  ["model" "items" "length"] (= v 3)]   ; state
 [:expect-text   :#count "3 messages"]]                ; rendering
```

That split is a debugging instrument. `:expect-text` red while `:expect-state`
green localises a bug to **rendering**; both red points at **state**. A suite
that only reads the DOM cannot tell you which.

The same scenario in a ClojureScript project reads the live re-frame runtime
instead, over shadow's nREPL:

```clojure
[[:goto "/"]
 [:click :#go]
 [:expect-text :#hi "Hello, pedro"]       ; browser → IBrowserDriver
 [:expect-sub [:current-user] some?]      ; runtime → ICljsEval
 [:expect-db  [:user] some?]]
```

Both are the same machinery. What differs is only the **runtime vocabulary** —
see [the three vocabularies](docs/steps.md#runtime-steps).

Selectors (`:#go`, `[:li {:has-text "X"}]`), probes and predicates are Clojure
**data**, compiled and checked before a browser opens. A CSS, JavaScript or
ClojureScript string is still accepted verbatim, but only as an escape hatch for
what the data cannot say: see
[Strings are the escape hatch](docs/steps.md#strings-are-the-escape-hatch).

## Documentation

| | |
|---|---|
| **[Setting up a project](docs/setup.md)** | nothing → a green scenario, ClojureScript or otherwise, with the traps that cost real time |
| **[Configuration reference](docs/configuration.md)** | `.hive-project.edn` vs `hive-cljs.edn`, every key, every default |
| **[Step reference](docs/steps.md)** | the browser + runtime vocabularies, semantics, adding a kind |
| **[Mounting in a host](docs/hosting.md)** | wiring into hive-mcp, why a subdomain, diagnosing a silent mount |
| **[Architecture](docs/architecture.md)** | CPPB layers, the ports, the toolchain and dialect seams, extension points |
| **[Runnable example](example/)** | a wired shadow-cljs + re-frame app you can `cljs e2e run` against |

## Quick start

Pick a toolchain. It decides who opens the build and runtime channels; the
browser channel is the same either way.

### Any stack — Elm, React, Svelte, Vue, plain JS

```clojure
;; hive-cljs.edn
{:hive.cljs/toolchain :browser
 :hive.cljs/builds {:app {:http-port 8000
                          :command ["elm" "make" "src/Main.elm"
                                    "--output=public/app.js"]}}
 :hive.cljs/e2e {:scenarios [{:id :smoke :steps [[:goto "/"]
                                                 [:expect-text :h1 "Inbox"]]}]}}
```

Serve the app, and DOM scenarios work with **no changes to your application**.

To assert on state, hand the probe a getter. There is nothing to install — the
run injects it into every document before your scripts:

```js
window.__hive__?.expose('model', () => store.getState())
```

The `?.` is the whole production story: outside a scenario nothing injects the
probe, so the line is a no-op. No dependency, no build flag, nothing shipped to
users. Elm pushes instead, through a port:

```js
app.ports.hiveState.subscribe(window.__hive__?.pushed('model'))
```

`:command` is optional — without it scenarios still run, you just get no build
verdict.

### ClojureScript

`shadow-cljs.edn` supplies the two ports:

```clojure
{:deps true
 :nrepl {:port 7889}          ; runtime channel
 :dev-http {8280 "public"}    ; what the browser opens
 :builds {:app {:target :browser ...}}}
```

Config — in your existing `.hive-project.edn`:

```clojure
{:project-id "my-app"
 :hive.cljs  {:shadow {:nrepl-port 7889}
              :builds {:app {:http-port 8280}}}}
```

`:hive.cljs/toolchain` defaults to `:shadow-cljs`, so this needs no toolchain
key. A single build id is the only required config.

### Either way

Config may live in `.hive-project.edn` or a standalone `hive-cljs.edn`. Both
work; both together merge, with the dedicated file winning. Either is found by
walking **up** from wherever you invoked, so a subdirectory works. A workspace
can hold shared defaults, but a child inherits them only by asking:
`:hive.cljs/inherit true`. Values can come from the environment with
`#hive/env PORT`.

```clojure
code {command: "cljs doctor",  directory: "/path/to/my-app"}
code {command: "cljs e2e run", directory: "/path/to/my-app", scenario: "smoke"}
code {command: "cljs watch start", directory: "/path/to/my-app"}
```

Full walkthrough: **[docs/setup.md](docs/setup.md)**.

## Tool surface

| Subcommand | Does |
|---|---|
| `cljs doctor` | validate config, report the toolchain, per-port connectivity and which runtimes are attached |
| `cljs staleness` | three axes: cached config vs disk, declared vs served builds, emitted bundle vs source |
| `cljs status [build]` | build verdict — one build or all |
| `cljs compile <build>` | one compile cycle, returns the verdict |
| `cljs eval <build> <code>` | evaluate in the running runtime |
| `cljs e2e list \| run` | run a scenario (`scenario`) or a tag set (`tags`) |
| `cljs e2e run-all` | fan the same run out over every descendant project that authors config |
| `cljs e2e mutate` | inject faults and report the ones no scenario killed |
| `cljs coverage` | per-namespace coverage of your own ClojureScript, worst first |
| `cljs coverage baseline` | freeze the current numbers so the next run reports a delta |
| `cljs watch start \| stop \| status` | couple build success to e2e runs |
| `cljs help` | subcommand index |

All accept `directory` (project root; defaults to cwd).

Three of these are ClojureScript-only, and say so rather than pretending:
`cljs coverage` (see below), the `:app-db-schema` invariant, and `e2e mutate`'s
`--auto` fault derivation. All three mean reading or rewriting the application's
own handler registry, which reading a page does not permit. Declared `:faults`
still work everywhere.

## Coverage, in ClojureScript terms

cloverage is JVM-only, so ClojureScript projects tend to have no coverage number
at all. `cljs coverage` runs the node-test bundle under a coverage provider and
remaps V8's output through shadow-cljs source maps, so the report is keyed by
**namespace**, not by compiled artifact:

```clojure
{:verdict    #:coverage{:state :pass :breaches []}
 :totals     #:coverage{:namespaces 83
                        :lines #:metric{:covered 9577 :total 11447 :pct 83.66}}
 :namespaces [{:ns "payment-flow.views.upload-file-list" :lines [32.2 40 124] ...}
              ...]}
```

Two things it refuses to do. It will not let you write file globs: you declare
`:source-prefixes ["your-app"]` in namespace spelling, and the emitted-module
layout is provider data — shadow-cljs writes one flat directory of dotted names,
so a hand-written path glob silently matches nothing and reports `0/0`. And it
will not report a delta in percentages: V8 only enumerates branch ranges inside
functions it actually ran, so **new tests enlarge the denominator** and a
percentage can fall while coverage rose. Deltas are covered counts.

A run that measured nothing is `:unavailable`, never a pass.

## Three ways a suite can lie, and what answers each

| The lie | The answer |
|---|---|
| a fixed `[:wait-ms 2500]` that passes warm and fails cold | `[:wait-for-state …]` / `[:wait-for-sub …]` — poll the state, and report the last value seen on timeout |
| every assertion green while app state quietly rots around them | `:app-db-schema` — a malli schema asserted between steps, so a scenario also proves the state stayed well-formed |
| a green suite that no bug can turn red | `cljs e2e mutate` — break the live app on purpose; a fault nothing kills is a hole in the suite |

The last one is the JVM trifecta's missing half: `hive-schemas.test` mutates
*values* against schemas, this mutates *behaviour* against scenarios. `:auto`
derives the catalog from the app's own re-frame registries — no config, no
knowledge of its internals.

## In a test suite

Scenarios are tests, so they run as tests — one form generates the namespace:

```clojure
(ns my.app.e2e-test
  (:require [hive-cljs.test :refer [defscenarios]]))

(defscenarios)
```

One `deftest` per declared scenario, scenario `:tags` as var metadata for
`--focus-meta`, root resolved by walking up from the working directory, teardown
registered. A scenario added to `test/e2e` is a test on the next run, with
nothing here to keep in sync. `hive-cljs.test-api` is the function surface
underneath, for hand-written tests and ad-hoc step vectors.

Same execution path as the tool and the watcher. See
[setup.md](docs/setup.md#7-in-a-test-suite).

## Fit and derive

Two pure `.cljc` libraries ride along with the addon. Neither needs a build, a
browser or a REPL.

**`hive-cljs.fit`** checks whether a laid-out thing (a slide, a card, a print
page) stays inside the box it was given. It has one judge and accepts many
measurers. A measurer is a `ports/IFitSource`, and it reports at one of two
rungs: `:measured` (read off a layout engine) or `:estimated` (a box model).
**`hive-cljs.fit.test/deffit`** turns a source into `clojure.test` vars:

```clojure
(ns my.deck.fit-gate-test
  (:require [hive-cljs.fit.test :refer [deffit]]))

(deffit deck
  {:source   (my.deck/estimated-source)  ; ports/IFitSource
   :universe (my.deck/content-model)     ; ports/IFitUniverse, from the document model
   :policy   {:fit/exempt {:code "no deck ships a code slide yet"}}})
;; => deck-was-measured, deck-rung-holds, deck-fits, deck-covers-model
```

Two of its rules are easy to miss, and missing either one gives you a gate that
passes for the wrong reason:

- **A source declares its own rung, and an estimated finding inside the
  source's stated `:fit/margin` never fails a build.** Only a finding larger
  than the margin fails. Smaller ones warn. A box model says `:estimated`
  however confident it is. `:fit/strict? true` turns every warning into a
  failure.
- **A coverage universe must come from the document model, not from the source
  being checked.** Otherwise the assertion is X ⊆ X, which holds for every X.

A source that measured nothing and a universe that is empty are both
`:unavailable`, never a pass.

**`hive-cljs.derive`** is forward chaining over tuple facts. Rules are
conjunctions of `?var` patterns with an optional arithmetic guard. `run` applies
them until nothing new appears, and records provenance for every derived fact
(`why`, `trace`, `explain`). `run-strata` adds stratified aggregation between
fixpoints.

```clojure
(require '[hive-cljs.derive :as d])

(def fx (d/run [(d/rule :illegible "Rendered below the 16px floor."
                        '[[:size ?el ?px] [:scale :slide ?s]]
                        (fn [{:syms [?el]}] [:illegible ?el])
                        (fn [{:syms [?px ?s]}] (< (* ?px ?s) 16)))]
               #{[:size :h1 40] [:size :body 18] [:scale :slide 0.5]}))

(:derived fx)                    ; => #{[:illegible :body]}
(d/explain fx [:illegible :body])
;; => "[:illegible :body] by :illegible from [:size :body 18], [:scale :slide 0.5]"
```

Full reference with runnable examples: **[docs/fit.md](docs/fit.md)**. The worked
example of a real gate is plato's `gate/plato/fit_gate_test.clj`.

## Installing

One line in the host's `local.deps.edn`:

```clojure
io.github.hive-agi/hive-cljs {:mvn/version "0.3.0"}
```

…or, when hacking on hive-cljs itself, `#:local{:root "../hive-cljs"}`.

Batteries included — the relay transport, nREPL client and browser driver all
ride in on hive-cljs's own `:deps`. The host declares nothing about our vendors.
Details and the reasoning: [docs/hosting.md](docs/hosting.md).

## Requirements

**Any stack** (`:hive.cljs/toolchain :browser`)

- your app served somewhere, and its port in `:http-port`
- a `:command` on the build, if you want a build verdict
- nothing installed in the application; the probe is injected

**ClojureScript** (`:shadow-cljs`, the default)

- a running shadow-cljs server (`shadow-cljs watch <build>`) for build status
- `:nrepl-port` in the config for the runtime channel, plus a browser with the
  app open so a JS runtime is connected — a scenario's `:goto` handles that

## Testing

```bash
clojure -M:test               # 311 tests, 1137 assertions — stubs only, no vendors needed
node test/js/probe_test.mjs   # 31 — the injected probe, which Clojure cannot exercise
```

No test namespace names a vendor: every test injects a stub through the ports,
so the suite runs with nothing installed.

## Releasing

A push to `main` that touches `src/`, `resources/` or `deps.edn` runs the suite,
then `clojure -T:build bump :level :patch`, tags `v{VERSION}` and deploys to
Clojars. `bump` (overridden in `build.clj`) decides the version from `./VERSION`
and the newest `v*` tag, compared as semver:

| `VERSION` vs newest tag | published version                    |
|-------------------------|--------------------------------------|
| ahead (`0.3.0` > `v0.2.24`) | `VERSION` verbatim — `0.3.0`     |
| equal (`0.2.24`)        | patch bump — `0.2.25`                |
| behind (`0.2.23`)       | patch bump of the tag — `0.2.25`     |
| no tag yet              | `VERSION` verbatim                   |

So to cut a minor or major, set `VERSION` by hand (e.g. `0.3.0`) in the
commit that lands on `main`; CI publishes exactly that number. Leave it alone
and every release is the next patch. The rule is the pure
`hive-cljs.release/decide` (`build/hive_cljs/release.clj`), unit-tested in
`test/hive_cljs/release_test.clj`; `build/` never enters the jar.

## License

MIT.
