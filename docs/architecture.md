# Architecture

CPPB-stratified, three ports. Dependencies point down; effects live only at the
boundary.

```
BOUNDARY   addon/handlers · watch/supervisor · boundary   ← ports injected as arguments
PIPELINE   plan   (scenario + manifest → run-plan)
           watch  (build event + policy → decisions)
PROMOTE    verdict (raw payload → verdict / report)
           step    (authored datum → op, OCP rule chain)
COLLECT    manifest (raw EDN → normalized, defaults resolved)
TYPES      schema (malli value objects) · ports · profile (provider behaviour as data)

ADAPTERS   shadow/relay → IBuildTool     shadow/nrepl → ICljsEval
           browser/playwright → IBrowserDriver
```

A vendor is named **only** in an adapter namespace. Everything above depends on
`hive-cljs.ports`.

## The three ports

```clojure
(defprotocol IBuildTool
  (builds [this]) (build-status [this build-id]) (compile-once! [this build-id])
  (subscribe! [this k f]) (unsubscribe! [this k]))

(defprotocol IBrowserDriver
  (open-session! [this opts]) (perform! [this session op]) (close-session! [this session]))

(defprotocol ICljsEval
  (eval-cljs [this build-id form-str]) (runtime-available? [this build-id]))
```

`ICljsEval` is what makes this more than a Playwright wrapper — it lets one
scenario assert on the DOM and on live re-frame state.

## Layer notes

**`schema`** is the single source: it drives `m/=>` contracts on the pure fns AND
the property/mutation facets synthesized by `hive-schemas.test`. Tighten a schema
and the tests tighten with it.

**`profile`** holds provider behaviour as data — relay op spelling, sync-db table
and attribute names, status vocabulary, browser launch defaults — in a registry
that is the DIP swap point. Swapping a toolchain is a `register!`, not a code
hunt. Values were read off shadow-cljs's own source, not third-party clients:
every op lives in the `shadow.cljs` namespace, and build status arrives via the
sync-db rather than a subscribe topic.

**`step`** is an ordered `IStepRule` chain — first match wins, so a new step kind
is an appended rule and an earlier rule can shadow a built-in. See
[steps.md](steps.md#adding-a-step-kind).

**`plan`** is pure orchestration: it resolves the base URL, compiles steps to ops
and produces a `RunPlan` as data. No port is touched.

**`boundary`** is the only place a plan meets a port, and every collaborator
arrives as an argument (`{:build-tool … :driver … :cljs-eval …}`).

**`watch`** decides; `watch/supervisor` executes. Debounce is *decided* purely
from timestamps; only sleeping, subscribing and running live in the supervisor.

## Testing

Every test injects a stub through the ports — `StubBuildTool` (with
`emit-build!` to simulate a compile finishing), `StubDriver` (recording, with a
`fail-on` variant), `StubCljsEval`. **No test namespace names a vendor**, so the
suite runs with nothing installed:

```bash
clojure -M:test    # 77 tests, 316 assertions
```

Pure layers additionally carry schema-synthesized property + mutation facets via
`hive-schemas.test/deftrifecta-from-schema` — no hand-written generators.

One caveat worth repeating: `deftrifecta-predicate` fits only a predicate that IS
its schema. A state check like `build-ok?` ignores most keys, so schema
corruption cannot flip it; use `deftrifecta-from-schema` with `:out :boolean` and
a `:rel` restating the decision.

## Extension points

| Want to | Do |
|---|---|
| add a step kind | append an `IStepRule` (+ a `perform-op` defmethod for browser kinds) |
| support another build tool | implement `IBuildTool`, register a relay profile |
| swap the browser | implement `IBrowserDriver` |
| change what a build event triggers | add a `:hive.cljs/watch` action and a `watch/action->decision` case |
