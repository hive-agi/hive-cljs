# hive-cljs example

A minimal shadow-cljs + reagent + re-frame app, wired for hive-cljs. Small enough
to read in one sitting, complete enough that every documented feature has
something to point at.

## Run it

```bash
npm install                       # react + react-dom
clojure -M -m shadow.cljs.devtools.cli watch app
```

Watch the startup banner for the **actual** HTTP/relay port — shadow takes the
next free one above 9630 with only a warning, and a stale `:port` is the single
most common cause of a green-but-wrong build report. Adjust `hive-cljs.edn` if
it is not 9633.

Then, from the repo root or anywhere with `directory` pointed here:

```clojure
code {command: "cljs doctor",  directory: "…/hive-cljs/example"}
code {command: "cljs e2e run", directory: "…/hive-cljs/example", scenario: "login"}
code {command: "cljs e2e run", directory: "…/hive-cljs/example", tags: "inputs"}
code {command: "cljs watch start", directory: "…/hive-cljs/example"}
```

With the watcher started, edit `src/demo/app.cljs` — the successful build fires
the `:smoke` scenario unprompted.

## What it demonstrates

**Config split across both sources.** `.hive-project.edn` carries connectivity
and builds; `hive-cljs.edn` carries scenarios and overrides `:port` to 9633. The
merge is one level deep and the dedicated file wins key by key, so `:host` and
`:nrepl-port` survive from the descriptor. `cljs doctor` reports both files under
`:manifest/sources`. See [configuration.md](../docs/configuration.md).

**Both assertion channels in one scenario.** `:login` asserts on the DOM
(`:expect-text`) and on the live re-frame runtime (`:expect-sub`, `:expect-db`)
in the same step vector. Red on one and green on the other localises a bug to
rendering rather than state.

**The input vocabulary.** `:inputs` exercises `:fill`, `:select`, `:check`,
`:hover`, `:press` and `:expect-value` against a controlled re-frame form, so a
keystroke that never reaches the renderer shows up as a failed `:expect-value`
rather than as a mystery further downstream.

**Build → e2e coupling.** `:hive.cljs/watch` runs the `:smoke` tag on every
successful build, debounced at 300ms.

## Files

| | |
|---|---|
| `.hive-project.edn` | project descriptor + hive-cljs connectivity |
| `hive-cljs.edn` | scenarios, watch policy, `:port` override |
| `shadow-cljs.edn` | the two ports hive-cljs consumes: `:nrepl` and `:dev-http` |
| `src/demo/app.cljs` | the app under test — one button, one form |
| `public/index.html` | mount point |
