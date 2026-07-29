# Architecture

CPPB-stratified, three ports plus two optional capabilities. Dependencies point
down; effects live only at the boundary.

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

## The two optional capabilities

Segregated rather than folded into the ports above: an adapter that implements
neither still works, and third-party implementations of the three ports keep
compiling.

```clojure
(defprotocol IPageMarker        ; browser side
  (mark-session! [this session token]))

(defprotocol IRuntimeAffinity   ; runtime side
  (bind-runtime! [this build-id token]) (unbind-runtime! [this]))
```

Together they answer *which page am I asserting about*. The driver stamps every
document its session loads with a token; the eval channel finds the connected
runtime carrying that token and pins evaluation to it. Without this the CLJS REPL
answers from whatever runtime the toolchain happens to pick, so any other open tab
silently decides every state assertion — a whole class of tests that grade the
wrong page while looking green.

Two constraints shape the wiring in `boundary/run-plan!`:

- **The stamp must survive navigation.** A scenario opens with `:goto`, which
  discards anything set on the previous document — so the mark is installed as an
  init script on the browser context, not evaluated once.
- **Binding cannot happen at session open.** The page is blank then and no runtime
  is attached to the build yet; the app registers only once `:goto` loads it. So
  the bind is lazy — once per run, immediately before the first runtime step — and
  released in a `finally`.

`boundary/affinity-possible?` gates the whole thing on `satisfies?`, so this is a
capability, not a requirement.

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
clojure -M:test    # 118 tests, 552 assertions
```

The stubs also model the *absence* of the optional capabilities —
`driver-without-marking` and `cljs-eval-without-affinity` — so the degradation
path is covered rather than assumed.

A stub can only discharge a contract it actually mirrors. The runtime-affinity
work was additionally verified against real ports by an A/B on a live app with a
decoy browser open: pinned passed, and the same run with `IPageMarker` reified
away failed on the decoy's state. Where a stub cannot express the hazard, the
real-port check is the evidence.

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
| swap the browser | implement `IBrowserDriver` (+ `IPageMarker` to keep runtime pinning) |
| swap the runtime channel | implement `ICljsEval` (+ `IRuntimeAffinity` to keep runtime pinning) |
| change what a build event triggers | add a `:hive.cljs/watch` action and a `watch/action->decision` case |
