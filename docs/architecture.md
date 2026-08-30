# Architecture

CPPB-stratified, three ports plus three optional capabilities. Dependencies point
down; effects live only at the boundary.

```
SURFACE    test-api (fns) · test (defscenarios → clojure.test vars)
BOUNDARY   addon/handlers · watch/supervisor · boundary   ← ports injected as arguments
PIPELINE   plan     (scenario + manifest → run-plan)
           watch    (build event + policy → decisions)
           mutation (fault + run-plan → mutated plan; reports → score)
PROMOTE    verdict   (raw payload → verdict / report)
           step      (authored datum → op, OCP rule chain)
           staleness (cached state + world → is the view still true)
COLLECT    manifest (raw EDN → normalized, defaults resolved)
TYPES      schema (malli value objects) · ports · profile (provider behaviour as data)

REGISTRY   toolchain (id → IToolchain)   ← the composition root's swap point
ADAPTERS   shadow/toolchain → IToolchain
             ├ shadow/relay → IBuildTool
             └ shadow/nrepl → ICljsEval
           browser/playwright → IBrowserDriver
```

A vendor is named **only** in an adapter namespace. Everything above depends on
`hive-cljs.ports`. That includes `system`, the composition root — it resolves a
project's declared toolchain through the registry rather than calling a
connector by name, which is what makes the extension points below reachable at
all rather than merely declared.

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

## The toolchain seam

The three ports say *what* a channel does. `IToolchain` says who opens it:

```clojure
(defprotocol IToolchain
  (open-build-tool [this manifest]) (open-runtime [this manifest])
  (close-build-tool! [this build-tool]) (close-runtime! [this runtime]))
```

`system/open!` resolves `:manifest/toolchain` — `:hive.cljs/toolchain` in
config, defaulting to `:shadow-cljs` — through `hive-cljs.toolchain` and asks the
result for both channels. So a stack this library has never heard of is mounted
with a `register!`:

```clojure
(toolchain/register! :my-stack (reify ports/IToolchain …))
;; or a symbol, resolved on first use
(toolchain/register! :my-stack 'my.ns/toolchain)
```

Teardown lives on `IToolchain` rather than on the ports because adding a method
to a shipped port protocol would break every third-party implementation of it.

Two properties the registry is built to keep:

- **Nothing loads a vendor to know it exists.** A shipped adapter is registered
  as a symbol and `requiring-resolve`d on first use — the same soft resolution
  `browser.factory` uses — so the subsystem loads and tests with none of its
  vendors on the classpath, and a missing one is a typed error at open time.
- **An unresolvable toolchain explains both dead channels.** `doctor` reports it
  under `:ports {:toolchain :down}` and repeats the error as the reason for the
  build and runtime channels, rather than showing two unexplained absences that
  send the reader hunting for a server that was never the problem.

The browser is deliberately *not* behind this: `IBrowserDriver` is already stack
agnostic — it drives a page, and a page is a page whatever compiled it.

## The optional capabilities

Segregated rather than folded into the ports above: an adapter that implements
none still works, and third-party implementations of the three ports keep
compiling.

```clojure
(defprotocol IPageMarker        ; browser side
  (mark-session! [this session token]))

(defprotocol IRuntimeAffinity   ; runtime side — mutates
  (bind-runtime! [this build-id token]) (unbind-runtime! [this]))

(defprotocol IRuntimeInventory  ; runtime side — observes
  (connected-runtimes [this build-id]) (pinned-runtime [this]))
```

`IRuntimeInventory` is what `doctor` reports under `:runtimes`. It is kept apart
from `IRuntimeAffinity` because observing is not binding: an adapter may be able
to say what is attached without being able to pin anything, and adding a method
to the shipped affinity protocol would break every implementation of it.

The first two answer *which page am I asserting about*. The driver stamps every
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
clojure -M:test    # 181 tests, 727 assertions
```

`defscenarios` is tested by expanding it against real temp project trees and
inspecting the emitted forms — no browser, and the generated bodies are never
invoked. The one exception is the empty-selection case, whose generated test is
evaluated and run, because "a selection that matches nothing fails" is a claim
about what the var *does*, not about its shape.

The stubs also model the *absence* of the optional capabilities —
`driver-without-marking` and `cljs-eval-without-affinity` — so the degradation
path is covered rather than assumed.

A stub can only discharge a contract it actually mirrors. The runtime-affinity
work was additionally verified against real ports by an A/B on a live app with a
decoy browser open: pinned passed, and the same run with `IPageMarker` reified
away failed on the decoy's state. The inventory report was verified the same way
— one runtime and no warning, then a second browser opened on the same build and
the `:runtime/ambiguous` warning appeared with both user-agents named. A stub
holds one runtime by construction and cannot express either hazard; where that is
true, the real-port check is the evidence.

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
| support another frontend stack | implement `IToolchain`, `toolchain/register!` it, declare `:hive.cljs/toolchain` |
| support another build tool | implement `IBuildTool`, return it from a toolchain's `open-build-tool` |
| swap the browser | implement `IBrowserDriver` (+ `IPageMarker` to keep runtime pinning) |
| swap the runtime channel | implement `ICljsEval` and return it from a toolchain's `open-runtime` (+ `IRuntimeAffinity` to keep runtime pinning, `IRuntimeInventory` to keep doctor's runtime report) |
| change what a build event triggers | add a `:hive.cljs/watch` action and a `watch/action->decision` case |
