# hive-cljs

ClojureScript development as a hive IAddon: **shadow-cljs build status, cljs-eval, Playwright
e2e scenarios and build→e2e watching**, driven by one EDN manifest in your project root.

One tool, `cljs`, with subcommands. One addon, `hive.cljs`. Three ports.

## Why not just a build-status tool

A scenario asserts on the **DOM and the live re-frame runtime in the same step vector**:

```clojure
[[:goto "/login"]
 [:click "#go"]
 [:expect-text "#hi" "Hello, pedro"]      ; browser channel  → IBrowserDriver
 [:expect-sub [:current-user] "some?"]    ; runtime channel  → ICljsEval
 [:expect-db  [:user] "some?"]]
```

`:expect-sub` / `:expect-db` / `:dispatch` / `:eval-cljs` are evaluated **inside the running
application** over shadow's nREPL. Everything else drives a real browser.

## Manifest — `hive-cljs.edn` at the project root

```clojure
{:hive.cljs/shadow {:host "localhost" :port 9630 :nrepl-port 7889}

 :hive.cljs/builds {:app {:shadow/id :app :http-port 8280}}

 :hive.cljs/e2e    {:base-url  "http://localhost:8280"   ; inferred from :http-port if omitted
                    :browser   :chromium
                    :headless  true
                    :scenarios [{:id    :login
                                 :build :app             ; inherited when the project has one build
                                 :tags  #{:smoke}
                                 :steps [[:goto "/"]
                                         [:click "#go"]
                                         [:expect-sub [:current-user] "some?"]]}]}

 :hive.cljs/watch  {:on-build-success [[:run-e2e {:tags #{:smoke}}]]
                    :debounce-ms      300}}
```

## Tool surface

| Subcommand | Does |
|---|---|
| `cljs doctor` | validate the manifest, report per-port connectivity |
| `cljs status [build]` | build verdict — one build or all |
| `cljs compile <build>` | one compile cycle, returns the verdict |
| `cljs eval <build> <code>` | evaluate cljs in the running runtime |
| `cljs e2e list \| run` | run a scenario (`scenario`) or a tag set (`tags`) |
| `cljs watch start \| stop \| status` | couple build success to e2e runs |
| `cljs help` | subcommand index |

All accept `directory` (project root; defaults to cwd).

## Step kinds

**Browser** — `:goto :back :reload :click :fill :select :check :press :hover :wait-for :wait-ms
:expect-text :expect-value :expect-visible :expect-hidden :expect-count :expect-url :screenshot`

**Runtime** — `:eval-cljs :dispatch :expect-sub :expect-db`

A new kind is a new `IStepRule` appended to the rule vector — no edit to existing code.

## In a test suite

```clojure
(require '[hive-cljs.test-api :as cljs-e2e])

(deftest login-works
  (is (cljs-e2e/passed? (cljs-e2e/run-scenario! project-root :login))))

(deftest ad-hoc-steps
  (let [res (cljs-e2e/run-steps! project-root :probe
                                 [[:goto "/"] [:click "#go"] [:expect-sub [:current-user] "some?"]])]
    (is (cljs-e2e/passed? res) (cljs-e2e/explain res))))
```

## Architecture

```
BOUNDARY   addon/handlers · watch/supervisor · boundary   ← ports injected as arguments
PIPELINE   plan (scenario→run-plan)   watch (event→decisions)
PROMOTE    verdict (payload→verdict)  step (datum→op, OCP rule-chain)
COLLECT    manifest (raw EDN→normalized)
TYPES      schema (malli value objects) · ports · profile (provider behaviour as data)

ADAPTERS   shadow/relay → IBuildTool    shadow/nrepl → ICljsEval    browser/playwright → IBrowserDriver
```

The vendor is named only in the adapters. Playwright sits behind an optional `:browser` alias —
the library loads, tests and reports health with no browser present.

## Running the tests

```bash
clojure -M:test -e "(require 'hive-cljs.boundary-test) ..."   # stubs only, no vendors needed
clojure -M:browser -e "..."                                    # adds playwright-java
```

## Requirements

- shadow-cljs server running (`shadow-cljs watch <build>`) for build status and e2e
- `:nrepl-port` in the manifest for the runtime channel; a browser must have loaded the app so a
  JS runtime is connected
- the `:browser` alias for anything that drives a page
