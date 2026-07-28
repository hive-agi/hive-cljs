# hive-cljs

ClojureScript development as a hive IAddon: **shadow-cljs build status, cljs-eval,
Playwright e2e scenarios and build→e2e watching**, driven by config in your
project root.

One addon. One `cljs` subdomain on the `code` tool. Three ports.

## Documentation

| | |
|---|---|
| **[Setting up a fresh project](docs/setup.md)** | nothing → a green scenario, with the traps that cost real time |
| **[Configuration reference](docs/configuration.md)** | `.hive-project.edn` vs `hive-cljs.edn`, every key, every default |
| **[Step reference](docs/steps.md)** | the browser + runtime vocabulary, semantics, adding a kind |
| **[Mounting in a host](docs/hosting.md)** | wiring into hive-mcp, why a subdomain, diagnosing a silent mount |
| **[Architecture](docs/architecture.md)** | CPPB layers, the ports, extension points |

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

Then, with `shadow-cljs watch app` running:

```clojure
code {command: "cljs doctor",  directory: "/path/to/my-app"}
code {command: "cljs e2e run", directory: "/path/to/my-app", scenario: "login"}
code {command: "cljs watch start", directory: "/path/to/my-app"}
```

Full walkthrough: **[docs/setup.md](docs/setup.md)**.

## Tool surface

| Subcommand | Does |
|---|---|
| `cljs doctor` | validate config, report per-port connectivity |
| `cljs status [build]` | build verdict — one build or all |
| `cljs compile <build>` | one compile cycle, returns the verdict |
| `cljs eval <build> <code>` | evaluate cljs in the running runtime |
| `cljs e2e list \| run` | run a scenario (`scenario`) or a tag set (`tags`) |
| `cljs watch start \| stop \| status` | couple build success to e2e runs |
| `cljs help` | subcommand index |

All accept `directory` (project root; defaults to cwd).

## In a test suite

```clojure
(require '[hive-cljs.test-api :as cljs-e2e])

(deftest login-works
  (let [res (cljs-e2e/run-scenario! project-root :login)]
    (is (cljs-e2e/passed? res) (cljs-e2e/explain res))))
```

Same execution path as the tool and the watcher. See
[setup.md](docs/setup.md#7-in-a-test-suite).

## Installing

One line in the host's `local.deps.edn`:

```clojure
io.github.hive-agi/hive-cljs #:local{:root "../hive-cljs"}
```

Batteries included — the relay transport, nREPL client and browser driver all
ride in on hive-cljs's own `:deps`. The host declares nothing about our vendors.
Details and the reasoning: [docs/hosting.md](docs/hosting.md).

## Requirements

- a running shadow-cljs server (`shadow-cljs watch <build>`) for build status and e2e
- `:nrepl-port` in the config for the runtime channel, plus a browser with the app
  open so a JS runtime is connected — a scenario's `:goto` handles that itself

## Testing

```bash
clojure -M:test    # 77 tests, 316 assertions — stubs only, no vendors needed
```

## License

MIT.
