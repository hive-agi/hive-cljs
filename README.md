# hive-cljs

<!-- hive-badges -->

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/hive-cljs.svg)](https://clojars.org/io.github.hive-agi/hive-cljs)
[![cljdoc](https://cljdoc.org/badge/io.github.hive-agi/hive-cljs)](https://cljdoc.org/d/io.github.hive-agi/hive-cljs/CURRENT)
[![release](https://github.com/hive-agi/hive-cljs/actions/workflows/release.yml/badge.svg)](https://github.com/hive-agi/hive-cljs/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

<!-- /hive-badges -->

ClojureScript development as a hive IAddon: **shadow-cljs build status, cljs-eval,
Playwright e2e scenarios, per-namespace coverage and build→e2e watching**, driven
by config in your project root.

One addon. One `cljs` subdomain on the `code` tool. Three ports.

Runtime assertions are pinned to the page the scenario drives, and a step that
could not be attempted is `:incomplete` — never a silent pass.

## Documentation

| | |
|---|---|
| **[Setting up a fresh project](docs/setup.md)** | nothing → a green scenario, with the traps that cost real time |
| **[Configuration reference](docs/configuration.md)** | `.hive-project.edn` vs `hive-cljs.edn`, every key, every default |
| **[Step reference](docs/steps.md)** | the browser + runtime vocabulary, semantics, adding a kind |
| **[Mounting in a host](docs/hosting.md)** | wiring into hive-mcp, why a subdomain, diagnosing a silent mount |
| **[Architecture](docs/architecture.md)** | CPPB layers, the ports, extension points |
| **[Runnable example](example/)** | a wired shadow-cljs + re-frame app you can `cljs e2e run` against |

## Why not just a build-status tool

A scenario asserts on the **DOM and the live re-frame runtime in the same step
vector**:

```clojure
[[:goto "/"]
 [:click "#go"]
 [:expect-text "#hi" "Hello, pedro"]      ; browser  → IBrowserDriver
 [:expect-sub [:current-user] "some?"]    ; runtime  → ICljsEval
 [:expect-db  [:user] "some?"]]
```

`:expect-sub` / `:expect-db` / `:dispatch` / `:eval-cljs` are evaluated **inside
the running application** over shadow's nREPL. Everything else drives a real
browser.

That split is also a debugging instrument: `:expect-text` red while `:expect-sub`
green localises a bug to rendering rather than state.

For that reading to be trustworthy the runtime assertion has to be about the page
the scenario is driving — so hive-cljs stamps the page it opens and pins
evaluation to it. Otherwise any other connected runtime (a stray tab, a forgotten
headless browser, the shadow UI) answers instead, and the scenario grades the
wrong page while reporting green.

## Quick start

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

…or in a standalone `hive-cljs.edn`. Both work; both together merge, with the
dedicated file winning. A single build id is the only required key.

Either file is found by walking **up** from wherever you invoked, so a
subdirectory works. A workspace can hold shared defaults, but a child inherits
them only by asking: `:hive.cljs/inherit true`. Values can come from the
environment with `#hive/env PORT`.

Then, with `shadow-cljs watch app` running:

```clojure
code {command: "cljs doctor",  directory: "/path/to/my-app"}
code {command: "cljs e2e run", directory: "/path/to/my-app", scenario: "login"}
code {command: "cljs watch start", directory: "/path/to/my-app"}
```

Full walkthrough: **[docs/setup.md](docs/setup.md)**. Working code to copy from:
**[example/](example/)** — both config sources, both assertion channels, the
input vocabulary and the watcher, in one small app.

## Tool surface

| Subcommand | Does |
|---|---|
| `cljs doctor` | validate config, report per-port connectivity and which runtimes are attached |
| `cljs staleness` | three axes: cached config vs disk, declared vs served builds, emitted bundle vs source |
| `cljs status [build]` | build verdict — one build or all |
| `cljs compile <build>` | one compile cycle, returns the verdict |
| `cljs eval <build> <code>` | evaluate cljs in the running runtime |
| `cljs e2e list \| run` | run a scenario (`scenario`) or a tag set (`tags`) |
| `cljs e2e run-all` | fan the same run out over every descendant project that authors config |
| `cljs e2e mutate` | inject faults and report the ones no scenario killed |
| `cljs coverage` | per-namespace coverage of your own ClojureScript, worst first |
| `cljs coverage baseline` | freeze the current numbers so the next run reports a delta |
| `cljs watch start \| stop \| status` | couple build success to e2e runs |
| `cljs help` | subcommand index |

All accept `directory` (project root; defaults to cwd).

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
| a fixed `[:wait-ms 2500]` that passes warm and fails cold | `[:wait-for-sub [:selected] "some?"]` — poll the state, and report the last value seen on timeout |
| every assertion green while app-db quietly rots around them | `:app-db-schema` — a malli schema asserted between steps, so a scenario also proves the state stayed well-formed |
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

## Installing

One line in the host's `local.deps.edn`:

```clojure
io.github.hive-agi/hive-cljs {:mvn/version "0.1.3"}
```

…or, when hacking on hive-cljs itself, `#:local{:root "../hive-cljs"}`.

Batteries included — the relay transport, nREPL client and browser driver all
ride in on hive-cljs's own `:deps`. The host declares nothing about our vendors.
Details and the reasoning: [docs/hosting.md](docs/hosting.md).

## Requirements

- a running shadow-cljs server (`shadow-cljs watch <build>`) for build status and e2e
- `:nrepl-port` in the config for the runtime channel, plus a browser with the app
  open so a JS runtime is connected — a scenario's `:goto` handles that itself

## Testing

```bash
clojure -M:test    # 181 tests, 727 assertions — stubs only, no vendors needed
```

## License

MIT.
