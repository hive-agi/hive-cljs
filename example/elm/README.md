# hive-cljs example — Elm

The end state of [docs/setup.md, "Any other stack"](../../docs/setup.md#any-other-stack--elm-react-svelte-vue),
committed. A one-module Elm inbox driven by the `:browser` toolchain: no build
server, no nREPL, no npm dependency in the app — the page is the runtime
channel, and one guarded line hands Elm's state to the injected probe.

## Run it

```bash
cd example/elm
npm install          # pins elm 0.19.1-5 as a devDependency (skip if elm is on PATH)
npm run build        # elm make src/Main.elm --output=public/app.js
npm start            # node serve.js — dependency-free static server on :8471
```

Then, with `directory` pointed at this folder (the manifest here shadows the
shadow-cljs one in `example/` because resolution walks **up** and stops at the
nearest config):

```clojure
code {command: "cljs doctor",  directory: "…/hive-cljs/example/elm"}
code {command: "cljs e2e run", directory: "…/hive-cljs/example/elm", scenario: "add"}
code {command: "cljs e2e run", directory: "…/hive-cljs/example/elm", tags: "state"}
```

or from a REPL on this repo:

```clojure
(require '[hive-cljs.test-api :as t])
(def root "example/elm")
(for [id [:smoke :add :clear]] (t/explain (t/run-scenario! root id)))
```

`cljs doctor` should report `:toolchain :browser` and every port `:ok`; the
build tool is `elm make` run as a process, judged by its exit code.

## What it demonstrates

**The probe reaches a real page before the app starts.** The run injects
`window.__hive__` with `addInitScript`; `public/index.html` subscribes to the
Elm port with

```js
if (window.__hive__) app.ports.hiveState.subscribe(window.__hive__.pushed('model'))
```

If the probe landed *after* Elm initialised, that guard would skip,
nothing would be subscribed, and every `:expect-state` would throw "nothing
exposed under model". `:smoke` passing is the proof that it does not. Outside a
scenario nothing injects the probe and the line is a no-op — that is the whole
production story.

Why `if` and not `subscribe(window.__hive__?.pushed('model'))`, the one-liner
the card first proposed: without the probe that subscribes `undefined`, and
Elm's port manager then throws `TypeError: currentSubs[i] is not a function` on
every send. Measured here with a plain Playwright page and no probe.

**Both channels in one step vector.** `:add` asserts the model
(`:wait-for-state` / `:expect-state` on `["model" "items" …]`) and then its
rendering (`:expect-text :#count "2 messages"`, `:expect-count`). Red on the
rendering line while the state lines are green localises a bug to `view`.

**State can be ahead of the DOM.** Elm sends port commands as soon as `update`
returns but paints on the next animation frame, so a state assertion can pass
while the old text is still on screen. The scenarios `:wait-for` the rendered
text before asserting on it; without that wait `:clear` reads `"1 message"`
right after the model already says zero items — which is exactly the split the
two channels exist to show.

**Steps as data.** Selectors are keywords and vectors (`:#count`,
`[:in :#items :li]`) and predicates are probe forms (`(= v 2)`), so a malformed
step fails the plan before a browser opens.

## Scenarios

| id | tags | |
|---|---|---|
| `:smoke` | `:smoke` | page renders, probe present, initial model pushed |
| `:add` | `:state` | type, click Add; assert the model, then the rendering |
| `:clear` | `:state` | clicks only — runs where keyboard input cannot |

## Files

| | |
|---|---|
| `src/Main.elm` | the app; `port hiveState` publishes the model after every change |
| `public/index.html` | boots Elm and wires the port to the probe |
| `hive-cljs.edn` | `:hive.cljs/toolchain :browser`, the `elm make` build, the scenarios |
| `serve.js` | a 30-line static server so the example needs no global tool |
| `elm.json`, `package.json` | elm 0.19.1 packages; elm itself pinned as a devDependency |

`test/hive_cljs/example_elm_test.clj` in the main suite checks — without a
browser or a compiler — that this manifest validates and every scenario
compiles to a plan, so the example cannot silently drift from the schema.
