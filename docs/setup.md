# Setting up a fresh ClojureScript project

From nothing to a green end-to-end scenario. Assumes hive-cljs is already mounted
in your hive-mcp coordinator — if it isn't, see [hosting.md](hosting.md).

Everything below was executed while building this library; the numbers and error
strings are real. If you would rather start from something that already runs,
[`example/`](../example/) is this walkthrough's end state, committed.

## 1. A minimal shadow-cljs project

```
my-app/
  deps.edn
  shadow-cljs.edn
  package.json
  public/index.html
  src/my/app.cljs
```

`deps.edn`:

```clojure
{:paths ["src" "public"]
 :deps {org.clojure/clojure  {:mvn/version "1.12.1"}
        thheller/shadow-cljs {:mvn/version "3.4.11"}
        reagent/reagent      {:mvn/version "1.2.0"}
        re-frame/re-frame    {:mvn/version "1.4.3"}
        nrepl/nrepl          {:mvn/version "1.7.0"}
        cider/cider-nrepl    {:mvn/version "0.58.0"}}}
```

`shadow-cljs.edn` — **the two ports hive-cljs needs come from here**:

```clojure
{:deps true
 :nrepl {:port 7889}            ; ← the runtime channel (cljs eval, :expect-sub)
 :dev-http {8280 "public"}      ; ← what the browser navigates to
 :builds {:app {:target :browser
                :output-dir "public/js"
                :asset-path "/js"
                :modules {:main {:init-fn my.app/init}}}}}
```

npm deps (React 19 needs a `process` shim under shadow-cljs):

```bash
npm init -y && npm install react react-dom process
```

Omitting `process` fails the build with
`The required JS dependency "process" is not available` — which hive-cljs will
report verbatim under `:build/errors`.

## 2. Declare the hive-cljs config

Two places work; pick one (details in [configuration.md](configuration.md)).

**In `.hive-project.edn`** — preferred when the project is already a hive project,
since it keeps one descriptor:

```clojure
{:project-id "my-app"
 :parent     "hive"
 :hive.cljs  {:shadow {:nrepl-port 7889}
              :builds {:app {:http-port 8280}}}}
```

**Or in `hive-cljs.edn`** — for a project that is not part of a hive tree:

```clojure
{:hive.cljs/shadow {:nrepl-port 7889}
 :hive.cljs/builds {:app {:http-port 8280}}}
```

The rest has defaults: shadow at `localhost:9630`, base-url inferred from
`:http-port`, chromium, headless, 15s timeout, 500ms watch debounce.

## 3. Start the build

```bash
clojure -M -m shadow.cljs.devtools.cli watch app
```

⚠️ **Check the port it actually bound.** shadow takes the next free one with only a
warning:

```
TCP Port 9630 in use.
server version: 3.4.11 running at http://localhost:9633
```

A manifest still pointing at 9630 will connect cleanly to *another project's*
server and return confident, wrong build verdicts. Set `:shadow {:port 9633}` to
match, and sanity-check that `cljs status` reports files you recognise.

## 4. Confirm the wiring

```clojure
code {command: "cljs doctor", directory: "/abs/path/to/my-app"}
```

```clojure
{:manifest :ok
 :builds   [:app]
 :base-url "http://localhost:8280"
 :ports    {:build-tool :ok :cljs-eval :ok :browser :ok}}
```

Any `:down` port carries a typed reason:

| error | meaning |
|---|---|
| `:relay/server-unreachable` | no shadow server on that host/port |
| `:cljs-eval/no-nrepl-port` | add `:nrepl-port` to the config |
| `:cljs-eval/connect-failed` | nREPL port wrong or shadow not up |
| `:browser/unavailable` | browser adapter missing from the classpath |

## 5. First scenario

```clojure
{:hive.cljs/e2e
 {:scenarios [{:id :login :tags [:smoke]
               :steps [[:goto "/"]
                       [:wait-for "#go"]
                       [:expect-hidden "#hi"]
                       [:click "#go"]
                       [:expect-text "#hi" "Hello, pedro"]
                       [:expect-sub [:current-user] "#(= % \"pedro\")"]
                       [:screenshot "logged-in"]]}]}}
```

```clojure
code {command: "cljs e2e run", directory: "/abs/path/to/my-app", scenario: "login"}
```

```
login: pass (8 pass, 0 fail, 0 error, 0 skipped)
```

`:expect-sub` is the part a DOM-only tool cannot do: it evaluates
`@(re-frame.core/subscribe [:current-user])` **inside the running app**. See
[steps.md](steps.md) for the full vocabulary.

The runtime channel needs a page open, because that is what connects a JS runtime
to shadow. `No available JS runtime` from `cljs eval` means no browser has loaded
the app — not a misconfiguration. A scenario avoids this by construction: its
`:goto` opens the page before any runtime step runs.

## 6. Let it run itself

```clojure
{:hive.cljs/watch {:on-build-success [[:run-e2e {:tags [:smoke]}]]
                   :debounce-ms 300}}
```

```clojure
code {command: "cljs watch start", directory: "/abs/path/to/my-app"}
```

Now editing a `.cljs` file recompiles, and the watcher runs the smoke scenarios
unprompted:

```
{:at …685 :build :app :state :compiling :decisions [:ignore]}
{:at …783 :build :app :state :completed :decisions [:run-e2e]}
report -> :pass
```

`cljs watch status` shows the debounce config, per-build last-run stamps and the
recent log. `cljs watch stop` unsubscribes.

## 7. In a test suite

The same execution path, from `deftest`:

```clojure
(ns my.app.e2e-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-cljs.test-api :as cljs-e2e]))

(def root "/abs/path/to/my-app")

(use-fixtures :once (fn [f] (f) (cljs-e2e/close! root)))

(deftest login-works
  (let [res (cljs-e2e/run-scenario! root :login)]
    (is (cljs-e2e/passed? res) (cljs-e2e/explain res))))

(deftest ad-hoc-steps-need-no-manifest-entry
  (is (cljs-e2e/passed?
       (cljs-e2e/run-steps! root :probe
                            [[:goto "/"] [:click "#go"]
                             [:expect-sub [:current-user] "some?"]]))))
```

`explain` renders the summary plus the first failing step, so a red CI log tells
you which step broke and what it saw.

## Troubleshooting

**A step fails but the app looks right in a screenshot.** Check whether the DOM
assertion and the runtime assertion disagree: `:expect-text` red while
`:expect-sub` green means state is correct and rendering is not — classically a
form-2 Reagent component dereferencing its subscription in the outer `let`.

**`:fill` reports pass but the input stays empty.** Reproduce against a plain
static HTML page. If it fails there too, the app is exonerated and the driver or
environment is at fault — in one sandboxed shell here, focus landed but
keystrokes never reached the renderer, in raw playwright-java with no hive code
involved.

**Build verdicts describe a project you don't recognise.** You are pointed at
another shadow server; see the port warning in step 3.
