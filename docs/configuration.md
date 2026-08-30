# Configuration reference

hive-cljs reads its config from **either or both** of two files, found by walking
up from the directory you invoked it in. Nothing is required beyond one build id.

## The two sources

| File | Shape | Use when |
|---|---|---|
| `.hive-project.edn` | `:hive.cljs {…}` submap, or flat `:hive.cljs/*` keys | the project is already a hive project — one descriptor, no extra file |
| `hive-cljs.edn` | flat `:hive.cljs/*` keys | standalone project, or you want scenarios in their own file |

Both present ⇒ they **merge one level deep**, and `hive-cljs.edn` wins key by key.
So a descriptor can hold connectivity while the dedicated file holds scenarios:

```clojure
;; .hive-project.edn
{:project-id "my-app"
 :hive.cljs {:shadow {:port 9630 :nrepl-port 7889}
             :builds {:app {:http-port 8280}}}}

;; hive-cljs.edn — overrides :port, keeps :nrepl-port, adds :e2e
{:hive.cljs/shadow {:port 9633}
 :hive.cljs/e2e    {:scenarios [{:id :smoke :steps [[:goto "/"]]}]}}
```

resolves to shadow `{:port 9633 :nrepl-port 7889}`, builds `[:app]`, scenarios
`[:smoke]`.

Inside `.hive-project.edn` both spellings work — nested short keys
(`:hive.cljs {:builds …}`) and flat namespaced keys (`:hive.cljs/builds …`).
Flat wins on collision. Descriptor keys that are not hive-cljs's (`:project-id`,
`:carto`, …) are ignored.

### Keeping the project root uncluttered

Config lives at the root because the directory that authors it *is*
`:manifest/root`, and `:artifacts-dir`, the inferred `:base-url`, the
`shadow-cljs.edn` lookup and the staleness source scan all derive from it. What
the root does **not** have to carry:

- **A second file.** A project that already has `.hive-project.edn` puts
  `:hive.cljs {…}` there and never writes `hive-cljs.edn` at all.
- **The scenarios.** Those are tests; [`:scenario-paths`](#scenario-paths--scenarios-live-with-the-rest-of-the-suite)
  keeps them in `test/e2e/*.edn` beside the unit tests.

So the tidiest layout is one descriptor holding connectivity, and the suite
holding everything test-shaped:

```
my-app/.hive-project.edn      ← :hive.cljs {:shadow …, :builds …, :e2e {:scenario-paths ["test/e2e"]}}
my-app/test/e2e/smoke.edn     ← the scenarios
my-app/test/my_app/e2e_test.clj  ← (defscenarios) — see setup.md §7
```

## Where config is found

Two separate mechanisms, deliberately: one always on, one you ask for.

**Discovery is unconditional.** Resolution walks up from `directory` to the
nearest ancestor that authors hive-cljs config, exactly as git walks up to the
nearest `.gitignore`. That directory becomes `:manifest/root`. So `cljs doctor`
works from a subdirectory:

```
my-app/.hive-project.edn          ← authors config
my-app/src/frontend/widgets/      ← invoke from here; root resolves to my-app/
```

Because `:manifest/root` moves, `:artifacts-dir` and the inferred `:base-url`
derive from the project, not from where you happened to stand.

**Inheritance is opt-in.** An ancestor that also authors config contributes
nothing unless the nearest level asks for it:

```clojure
;; workspace/.hive-project.edn — shared conventions
{:project-id "workspace"
 :hive.cljs  {:e2e {:browser :chromium :headless true :timeout-ms 15000}}}

;; workspace/my-app/hive-cljs.edn — opts in, overrides one key
{:hive.cljs/inherit true
 :hive.cljs/e2e     {:timeout-ms 30000}
 :hive.cljs/builds  {:app {:http-port 8280}}}
```

resolves to chromium, headless, `:timeout-ms 30000`. Drop `:hive.cljs/inherit`
and the workspace defaults are simply not consulted — a parent descriptor can
never silently change a child's `:base-url` or `:port` behind your back.

`:manifest/sources` lists every file that contributed, **highest precedence
first**: `hive-cljs.edn` before `.hive-project.edn` within a level, nearest level
before its ancestors. `cljs doctor` surfaces it, so the question "where did this
value come from, and what would I have to change to override it?" has one answer.

No level authors config ⇒ `:manifest/not-found`, carrying `:searched` (every path
examined on the way up) and `:candidates` — directories *below* you that do author
config, found to a bounded depth. Invoking from a workspace root with four apps
underneath lists all four rather than guessing one.

## `#hive/env` — values from the environment

Any config value may be read from the environment, for harnesses that assign
ports at run time:

```clojure
{:hive.cljs/shadow {:port       #hive/env SHADOW_PORT
                    :nrepl-port #hive/env [SHADOW_NREPL_PORT 7889]}}
```

Bare form: the variable's value, or `nil` when unset. Vector form: the second
element is the fallback for unset-or-blank. A value that reads as an integer is
coerced to a long, so `:port` type-checks without a wrapper.

## Sections

### `:hive.cljs/toolchain` — who opens the build and runtime channels

```clojure
{:hive.cljs/toolchain :shadow-cljs}   ; default — omit and nothing changes
```

Selects the `IToolchain` that connects this project's build tool and runtime
eval channel.

| Id | Build channel | Runtime channel | For |
|---|---|---|---|
| `:shadow-cljs` (default) | shadow's remote relay | cljs nREPL, re-frame vocabulary | ClojureScript |
| `:browser` | a build's `:command` (argv), else `:build-tool/not-supervised` | the page itself, JavaScript + probe vocabulary | Elm, React, Svelte, Vue, plain JS |

Anything else must have been registered with `hive-cljs.toolchain/register!`
before a session opens. An id nobody registered is reported by `doctor` as
`:toolchain/unknown` with the known ids listed, and the build and runtime
channels read `:down` for that reason rather than for a connectivity one.

With `:browser`, `:hive.cljs/shadow` is ignored. Scenarios and the `-js` /
`-state` runtime steps work as they do for ClojureScript; build supervision
appears once a build declares a [`:command`](#command--a-build-whose-verdict-is-an-exit-code).

The browser channel is not affected: it drives a page, and a page is a page
whatever compiled it.

### `:hive.cljs/shadow` — connectivity

```clojure
{:host       "localhost"   ; default
 :port       9630          ; default — the shadow HTTP/relay port
 :nrepl-port 7889}         ; NO default; omit and the runtime channel is :down
```

`:port` must match what shadow actually bound, which is not always 9630 — it
takes the next free port with only a warning. `:nrepl-port` is `shadow-cljs.edn`'s
`:nrepl {:port …}`; without it, `:eval`, `:expect-sub`, `:expect-db` and
`:dispatch` report `:cljs-eval/no-nrepl-port`.

### `:hive.cljs/builds` — build targets

```clojure
{:app {:shadow/id :app        ; defaults to the map key
       :http-port 8280        ; the dev-http port; used to infer :base-url
       :entry     "/"          ; optional
       :command   ["npx" "vite" "build"]}}   ; optional; see below
```

A scenario that names no `:build` inherits the project's build when there is
exactly one. With two or more the choice is ambiguous, `:plan/build` is left
unset, and any runtime step returns a typed error telling you to set `:build`.

#### `:command` — a build whose verdict is an exit code

Under `:shadow-cljs` the build is a running server, and hive asks it. Every
other toolchain builds by running something:

```clojure
{:hive.cljs/toolchain :browser
 :hive.cljs/builds {:app {:http-port 8000
                          :command ["elm" "make" "src/Main.elm"
                                    "--output=public/app.js"]}}}
```

`cljs compile app` then runs that argv with the project root as cwd. Exit 0 is
`:completed`; anything else is `:failed`, carrying the compiler's own output
(both streams — `elm make` writes errors to stderr, `tsc` to stdout) as the
report's `:build/errors`. A binary that cannot be launched at all is
`:build/exec-failed`, not a red build: that is not your source failing to
compile.

Declare no `:command` and the toolchain reports `:build-tool/not-supervised` —
scenarios still run, `cljs status` and `cljs compile` do not.

One real limit: this channel only sees compiles **it** ran, so `cljs watch`
couples to hive-driven builds and not to an external `vite --watch`. It is
reported that way rather than papered over with a poller that would invent a
verdict between file writes.

### `:hive.cljs/e2e` — browser and scenarios

```clojure
{:base-url       "http://localhost:8280"  ; inferred from a build's :http-port
 :browser        :chromium                ; :chromium | :firefox | :webkit
 :headless       true
 :timeout-ms     15000
 :poll-ms        250                      ; condition-wait poll interval
 :artifacts-dir  "<root>/.hive-cljs/artifacts"
 :scenario-paths ["test/e2e"]             ; optional — scenarios living with the suite
 :app-db-schema  my.app.schema/app-db     ; optional — asserted between steps
 :app-db-check   :every-step              ; :every-step | :mutations | :final
 :faults         [{:id     :status-hole   ; optional — the mutation catalog
                   :target my.app.view-model/derive-status
                   :with   "(constantly nil)"}]
 :scenarios      [{:id    :login          ; required
                   :build :app            ; optional — inherited if unambiguous
                   :tags  [:smoke]        ; optional — selects for `e2e run` / watch
                   :doc   "…"             ; optional
                   :steps [[:goto "/"] …]}]}
```

Relative `:goto` URLs resolve against `:base-url`; absolute ones pass through.
Screenshots land in `:artifacts-dir` and are listed in the run report's
`:run/artifacts`.

Step vocabulary: [steps.md](steps.md).

#### `:scenario-paths` — scenarios live with the rest of the suite

Scenarios are config, but they *are* tests, so they belong next to the other
tests rather than in the project root. Each path names a directory (every `*.edn`
below it, in sorted order) or a single file:

```
my-app/hive-cljs.edn          ← connectivity only: :shadow, :builds, :e2e defaults
my-app/test/e2e/smoke.edn     ← [{:id :home  :steps […]} …]
my-app/test/e2e/billing.edn   ← one file per feature area
```

A scenario file is a vector of scenario maps, a single scenario map, or a map
with `:scenarios`. `:scenarios` and `:scenario-paths` compose — inline first,
then the files. A **duplicate `:id` is a config error** (`:manifest/duplicate-scenario`),
never a last-one-wins merge.

Every scenario file joins `:manifest/sources`, so editing one invalidates the
cached session exactly like editing the manifest: the loop is edit → run.

#### `:app-db-schema` — one schema, asserted between steps

Turns every scenario into a state-corruption detector on top of its own
assertions. See [steps.md](steps.md#the-app-db-invariant-channel).

#### `:faults` — the mutation catalog

Each entry is `{:id :target :with}` (replace a var) or `{:id :form "…"}`
(evaluate arbitrary source). `cljs e2e mutate` injects each one and reports the
ones no scenario turned red. `:auto` derives a catalog from the app's own
re-frame registries with no config at all.

### `:hive.cljs/watch` — build → e2e coupling

```clojure
{:on-build-success [[:run-e2e {:tags [:smoke]}]]
 :on-build-failure []                      ; default: report the failure
 :debounce-ms      500
 :builds           #{:app}}                ; optional — omit to watch all builds
```

Actions are `[kind opts]` tuples; a bare keyword or 1-element vector is accepted
and normalized. Selectors for `:run-e2e`: `{:scenarios [:a :b]}`, `{:tags [:smoke]}`,
or neither, which runs every scenario.

The watcher always produces a decision, including `:ignore` with a reason
(`"within debounce window"`, `"build not watched by policy"`,
`"no :on-build-success actions configured"`), so `cljs watch status` explains why
nothing ran.

### `:hive.cljs/coverage` — how much of your own ClojureScript the suite reaches

```clojure
{:build           :unit-node          ; a shadow build with a :node-test target
 :source-prefixes ["payment-flow"]    ; NAMESPACE patterns, not file paths
 :exclude         ["*-test" "payment-flow.dev.*"]
 :report-dir      "target/coverage"
 :baseline        "target/coverage/baseline.json"   ; optional — enables the delta
 :thresholds      {:lines 80}}                      ; optional — gates the verdict
```

`cljs coverage` compiles the build, runs the bundle under the configured
coverage provider, and reports **per ClojureScript namespace**, worst-covered
first. `cljs coverage baseline` freezes the current numbers so the next run
carries a delta.

You write **namespace patterns**; hive-cljs translates them into the compiled
module globs the provider actually filters on. That translation is the whole
point of the section: shadow-cljs emits one flat directory of dotted module
names (`payment_flow.views.editar.js`), so a path-shaped glob written by hand
matches nothing and the tool reports `0/0` instead of failing. The emitted
layout is profile data (`:coverage/compiled-layout`), so a toolchain that
writes nested directories is a `profile/register!`, not a code change.

A delta is reported in **covered counts, never percentages**. A V8-based
provider only enumerates branch ranges inside functions it actually executed,
so new tests enlarge the denominator and a percentage can fall while coverage
genuinely rose. `:regressions` lists namespaces that lost covered counts.

Other keys: `:profile` picks the coverage provider (default `:coverage/c8`),
`:bundle` overrides the measured artifact (default `target/<build>.js`),
`:runner` overrides how it is executed (default `["node"]`), and `:compile
false` measures a bundle already on disk instead of building one.

A run that measured nothing is `:unavailable`, never a pass.

## Defaults, in one place

| Key | Default |
|---|---|
| `toolchain` | `:shadow-cljs` |
| `shadow :host` / `:port` | `"localhost"` / `9630` |
| `shadow :nrepl-port` | none — runtime channel disabled |
| `e2e :base-url` | `http://localhost:<first build's :http-port>`, else `http://localhost:8080` |
| `e2e :browser` / `:headless` / `:timeout-ms` | `:chromium` / `true` / `15000` |
| `e2e :poll-ms` | `250` |
| `e2e :artifacts-dir` | `<root>/.hive-cljs/artifacts` |
| `e2e :scenario-paths` / `:faults` | none / `[]` |
| `e2e :app-db-schema` | none — no invariant asserted |
| `e2e :app-db-check` | `:every-step` (only consulted with a schema) |
| `watch :debounce-ms` | `500` |
| `watch :on-build-success` / `:on-build-failure` | `[]` / `[]` |
| `watch :builds` | all builds |
| `coverage :build` | `:unit-node` |
| `coverage :profile` | `:coverage/c8` |
| `coverage :bundle` | `target/<build>.js` |
| `coverage :compile` | `npx shadow-cljs compile <build>` (`false` to skip) |
| `coverage :runner` | `["node"]` |
| `coverage :exclude` | `["*-test"]` |
| `coverage :report-dir` | `target/coverage` |
| `coverage :baseline` / `:thresholds` | none — no delta / no gate |

## Validation

The normalized manifest is checked against a closed malli schema. A bad value is
returned as `:manifest/invalid` with a humanized explanation rather than throwing:

```clojure
{:error :manifest/invalid
 :explain {:manifest/shadow {:port ["should be an integer"]}}}
```

Unparseable EDN gives `:manifest/unreadable` with the cause.

## Trusting what you resolved — `cljs staleness`

A session caches the resolved manifest. `cljs staleness` reports whether that
cached view still describes reality:

```clojure
code {command: "cljs staleness", directory: "/path/to/my-app"}
```

```clojure
{:staleness/manifest        :stale
 :staleness/sources         [{:source/path "…/hive-cljs.edn" :source/exists? true
                              :source/modified 1753… :source/size 412} …]
 :staleness/server          :mismatch
 :staleness/declared-builds [:app]
 :staleness/reported-builds [:other-app]
 :staleness/bundles         [{:bundle/build :app :bundle/state :stale
                              :bundle/output-dir "public/js"
                              :bundle/compiled 1753… :bundle/newest-source 1754…}]}
```

| Key | Reads |
|---|---|
| `:staleness/manifest` | `:stale` when a contributing file changed on disk since the session opened |
| `:staleness/server` | `:ok` when the connected server serves a build we declare, `:mismatch` when the sets are disjoint, `:unknown` when either side is empty |
| `:staleness/bundles` | per build: `:stale` when the emitted output is older than the sources it was built from, `:unknown` when either timestamp is missing |

The bundle axis is the one that reads a green run and calls it worthless: a suite
passing against a *previous* compile proves nothing about the code on disk. The
timestamps come from `shadow-cljs.edn` — `:source-paths` against the build's
`:output-dir` / `:output-to` — so no toolchain support is needed and a project
without a shadow config simply reports nothing rather than guessing.

`:mismatch` is the port-drift trap from [setup.md](setup.md) step 3 made visible:
you are talking to *another project's* shadow server, and every verdict it gives
you is confident and wrong. `cljs doctor` carries the same verdict under
`:server`, plus a `:warnings` entry spelling it out.

Both axes need two witnesses. Nothing recorded on either side reports `:unknown`,
never a reassuring `:ok` — an unverified claim is not a passing one.

Reading the report is not required to get fresh config: any command reopens the
session automatically when its sources changed. `staleness` reports against the
view as it was *before* the call, so an edit is still visible in the report that
refreshes it.
