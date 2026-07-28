# Configuration reference

hive-cljs reads its config from **either or both** of two files in the project
root. Nothing is required beyond one build id.

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

`cljs doctor` reports which files were actually used under `:manifest/sources`.
Neither file present — or a descriptor with no `:hive.cljs` keys — yields
`:manifest/not-found` listing both paths searched.

## Sections

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
       :entry     "/"}}       ; optional
```

A scenario that names no `:build` inherits the project's build when there is
exactly one. With two or more the choice is ambiguous, `:plan/build` is left
unset, and any runtime step returns a typed error telling you to set `:build`.

### `:hive.cljs/e2e` — browser and scenarios

```clojure
{:base-url      "http://localhost:8280"   ; inferred from a build's :http-port
 :browser       :chromium                 ; :chromium | :firefox | :webkit
 :headless      true
 :timeout-ms    15000
 :artifacts-dir "<root>/.hive-cljs/artifacts"
 :scenarios     [{:id    :login           ; required
                  :build :app             ; optional — inherited if unambiguous
                  :tags  [:smoke]         ; optional — selects for `e2e run` / watch
                  :doc   "…"              ; optional
                  :steps [[:goto "/"] …]}]}
```

Relative `:goto` URLs resolve against `:base-url`; absolute ones pass through.
Screenshots land in `:artifacts-dir` and are listed in the run report's
`:run/artifacts`.

Step vocabulary: [steps.md](steps.md).

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

## Defaults, in one place

| Key | Default |
|---|---|
| `shadow :host` / `:port` | `"localhost"` / `9630` |
| `shadow :nrepl-port` | none — runtime channel disabled |
| `e2e :base-url` | `http://localhost:<first build's :http-port>`, else `http://localhost:8080` |
| `e2e :browser` / `:headless` / `:timeout-ms` | `:chromium` / `true` / `15000` |
| `e2e :artifacts-dir` | `<root>/.hive-cljs/artifacts` |
| `watch :debounce-ms` | `500` |
| `watch :on-build-success` / `:on-build-failure` | `[]` / `[]` |
| `watch :builds` | all builds |

## Validation

The normalized manifest is checked against a closed malli schema. A bad value is
returned as `:manifest/invalid` with a humanized explanation rather than throwing:

```clojure
{:error :manifest/invalid
 :explain {:manifest/shadow {:port ["should be an integer"]}}}
```

Unparseable EDN gives `:manifest/unreadable` with the cause.
