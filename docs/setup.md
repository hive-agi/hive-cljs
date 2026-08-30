# Setting up a fresh ClojureScript project

From nothing to a green end-to-end scenario. Assumes hive-cljs is already mounted
in your hive-mcp coordinator — if it isn't, see [hosting.md](hosting.md).

Everything below was executed while building this library; the numbers and error
strings are real. If you would rather start from something that already runs,
[`example/`](../example/) is this walkthrough's end state, committed.

> **Not a ClojureScript project?** Skip to
> [Any other stack](#any-other-stack--elm-react-svelte-vue) at the bottom — it is
> shorter, because there is no build server to wire up.

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
 :ports    {:build-tool :ok :cljs-eval :ok :browser :ok}
 :runtimes {:status   :ok
            :pinned   nil
            :by-build {:app {:connected [{:client-id  66
                                          :user-agent "Firefox 152.0 [Linux x86_64]"
                                          :host       :browser}]}}}}
```

`:runtimes` names every browser currently attached to each declared build — the
thing that decides what a state assertion actually answers about, and otherwise
invisible. `:pinned` is nil outside a run; during one it holds the runtime bound
to the driven page. The channel reports `{:status :down}` when it never
connected and `{:status :unsupported}` when the adapter cannot enumerate, so an
empty inventory is never confused with an unanswerable question.

More than one runtime on a build raises a `:runtime/ambiguous` warning. It is
not a failure — scenarios pin their own page — but ad-hoc `cljs eval` has no page
to pin to, so that is exactly when a manual eval starts disagreeing with a run.

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
login: pass (8 pass, 0 fail, 0 error, 0 incomplete, 0 skipped)
```

`:expect-sub` is the part a DOM-only tool cannot do: it evaluates
`@(re-frame.core/subscribe [:current-user])` **inside the running app**. See
[steps.md](steps.md) for the full vocabulary.

The runtime channel needs a page open, because that is what connects a JS runtime
to shadow. `No available JS runtime` from `cljs eval` means no browser has loaded
the app — not a misconfiguration. A scenario avoids this by construction: its
`:goto` opens the page before any runtime step runs.

Inside a scenario the runtime channel is pinned to that page specifically, so
other tabs you have open cannot answer its assertions. Ad-hoc `cljs eval` has no
such page to pin to and still goes to whichever runtime shadow picks — worth
remembering when a manual eval and a scenario disagree.

A run reporting `:incomplete` means assertions could not be attempted at all — no
runtime connected, or the driven page could not be identified. That is neither a
failure of the app nor a pass: nothing was proven. If the shadow nREPL was
restarted or OOM-killed, hive-cljs may be holding a dead connection; `cljs close`
re-probes it, and `cljs doctor` should show `:cljs-eval :ok` before you trust a
green run.

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

Scenarios are tests, so they run as tests — `clojure -M:test`, kaocha, CI, the
same reporter as everything else. One form generates the whole suite:

```clojure
(ns my.app.e2e-test
  (:require [hive-cljs.test :refer [defscenarios]]))

(defscenarios)
```

That is the entire namespace. Expansion resolves the manifest by walking up from
the JVM's working directory — no absolute path to keep in sync — and emits one
`deftest` per declared scenario:

```
my.app.e2e-test/login      ← scenario :login
my.app.e2e-test/checkout   ← scenario :checkout
```

Each scenario's `:tags` land as var metadata, so `--focus-meta :smoke` selects
them, and its `:doc` becomes the var's docstring. The teardown fixture is
registered for you.

**A scenario added to `test/e2e` is a test on the next run.** Nothing here has to
be edited to match — which is the point: a hand-written mirror `deftest` per
scenario is a list that silently stops being true.

Options, all read at macroexpansion:

```clojure
(defscenarios {:root     "/path/to/other-project" ; default: the JVM cwd
               :tags     [:smoke]                 ; default: every scenario
               :fixture? false})                  ; default: true
```

A selection matching nothing emits a single **failing** test rather than an empty
namespace — a generated suite with nothing in it must not read green. A manifest
that does not resolve, or two scenario ids that munge to one test name
(`:a/b` and `:a-b`), throw at expansion.

### The functions underneath

`defscenarios` is a thin layer over `hive-cljs.test-api`, which is there when you
want a hand-written test — an ad-hoc step vector, a scenario run under different
conditions, an assertion the generated body does not make:

```clojure
(ns my.app.probe-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-cljs.test-api :as cljs-e2e]))

(def root (System/getProperty "user.dir"))

(use-fixtures :once (fn [f] (f) (cljs-e2e/close-all!)))

(deftest ad-hoc-steps-need-no-manifest-entry
  (is (cljs-e2e/passed?
       (cljs-e2e/run-steps! root :probe
                            [[:goto "/"] [:click "#go"]
                             [:expect-sub [:current-user] "some?"]]))))
```

`explain` renders the summary plus the first failing step, so a red CI log tells
you which step broke and what it saw.

**Close what you opened, or the JVM will not exit.** A session holds a relay
connection; a live connection keeps the process alive after the last `deftest`
returns, so CI hangs on a suite that was already green. `defscenarios` registers
`close-all!` for you; a hand-written namespace does it itself — `close!` releases
one project, `close-all!` every open one, which is what a suite touching more
than one wants.

A shutdown hook is *not* an alternative here: the JVM runs hooks only once it has
decided to exit, and it decides that only when the last non-daemon thread ends.
The hook would run exactly when it is no longer needed. hive-cljs's own relay pool
is created daemon for that reason, so a forgotten teardown degrades to a leaked
connection rather than a wedged CI job — but closing explicitly is still the
contract.

## Any other stack — Elm, React, Svelte, Vue

Everything above wires up a *ClojureScript* project, most of which is shadow-cljs
plumbing. For any other stack there is less to do, because there is no build
server to connect to and no nREPL port to find.

### 1. Config

```clojure
;; hive-cljs.edn
{:project-id "inbox"
 :hive.cljs/toolchain :browser
 :hive.cljs/builds {:app {:http-port 8000
                          :command ["elm" "make" "src/Main.elm"
                                    "--output=public/app.js"]}}
 :hive.cljs/e2e {:scenarios
                 [{:id :smoke
                   :steps [[:goto "/"]
                           [:expect-text "h1" "Inbox"]]}]}}
```

`:command` is optional — leave it out and scenarios still run, you just get no
build verdict. `:http-port` is whatever serves your app; hive infers `:base-url`
from it.

### 2. Serve the app and check the wiring

```bash
elm make src/Main.elm --output=public/app.js
npx http-server public -p 8000
```

```clojure
code {command: "cljs doctor", directory: "/path/to/inbox"}
```

`:toolchain` reads `:browser` and `:ports {:browser :ok}` is the one that has to
be green. `:cljs-eval :ok` too — that channel is the page itself, so it comes up
with the driver.

### 3. Assert on the DOM

```clojure
code {command: "cljs e2e run", directory: "/path/to/inbox", scenario: "smoke"}
```

This much works with no changes to your application at all.

### 4. Assert on state

DOM assertions alone cannot tell "the state is wrong" from "the state is right
and rendering is wrong" — which is most of what an e2e failure needs to tell you.

There is nothing to install. The run injects a probe into every document before
your scripts run; your app hands it a getter with one guarded line:

```js
window.__hive__?.expose('model', () => store.getState())
```

Elm keeps no state in JavaScript, so send it out through a port instead:

```elm
port hiveState : Encode.Value -> Cmd msg

update msg model =
    ( newModel, hiveState (encodeModel newModel) )
```

```js
const app = Elm.Main.init({ node: document.getElementById('root') })
app.ports.hiveState.subscribe(window.__hive__?.pushed('model'))
```

The `?.` is deliberate and is the entire production story: outside a scenario
nothing injects the probe, so the line is a no-op. No dependency, no build flag,
nothing shipped to users.

Now both channels are available in one step vector:

```clojure
[[:goto "/"]
 [:click "#refresh"]
 [:wait-for-state ["model" "loading"] "v === false"]
 [:expect-state  ["model" "items" "length"] "v === 3"]   ; state
 [:expect-text   "#count" "3 messages"]]                  ; rendering
```

Red on the last line while the one above it is green localises the bug to the
view. That split is the reason to expose state at all.

`[:expect-js "…"]` is there too when you would rather write a raw expression
than install anything.

### What this toolchain does not do

Two features are ClojureScript-specific, and both **report** rather than
pretending: the `:app-db-schema` invariant, and `cljs e2e mutate --auto`. Both
mean rewriting the application's own handler registry, which reading a page does
not permit. Declared `:faults` still work, so mutation testing is available — it
just needs you to say what to break.

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

**A manual `cljs eval` and a scenario disagree about the same state.** Read
`:runtimes` in `cljs doctor`. A scenario pins the page it drives; an ad-hoc eval
does not, so with two runtimes attached they can legitimately answer about
different pages. Closing the stray tab makes the two agree.

**Build verdicts describe a project you don't recognise.** You are pointed at
another shadow server; see the port warning in step 3. `cljs staleness` names it
directly — `:staleness/server :mismatch` means the builds you declare and the
builds that server actually serves are disjoint sets.

**A config edit seems to have no effect.** `cljs staleness` reports
`:staleness/manifest`; `:stale` means a contributing file changed since the
session cached it. Any command reopens the session automatically in that case, so
this is a diagnosis rather than a fix — if it reports `:fresh` while you expect
otherwise, you edited a file that is not in `:manifest/sources`, and `cljs doctor`
will tell you which files those are.

**Config in a parent directory is ignored.** That is the default. Walking up to
find the *nearest* config is unconditional, but *inheriting* from an ancestor is
opt-in: set `:hive.cljs/inherit true` in the child. See
[configuration.md](configuration.md#where-config-is-found).
