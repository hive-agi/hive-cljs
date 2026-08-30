(ns hive-cljs.ports
  "Ports of the hive-cljs subsystem — the abstraction the pure layers and the
   boundary depend on. No namespace here names a vendor.

   Adapters live under `hive-cljs.shadow.*` (IBuildTool, ICljsEval) and
   `hive-cljs.browser.*` (IBrowserDriver). Tests inject stubs through the same
   protocols.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; IBuildTool — the compiler/build supervisor
;; =============================================================================

(defprotocol IBuildTool
  "Read and drive a ClojureScript build toolchain."

  (builds [this]
    "Return a Result of [BuildId ...] the toolchain currently knows about.")

  (build-status [this build-id]
    "Return a Result of `schema/BuildStatus` for build-id.
     A build never seen yields state :unknown, not an error.")

  (compile-once! [this build-id]
    "Trigger one compile cycle and return a Result of the resulting BuildStatus.")

  (subscribe! [this k f]
    "Register `f` under key `k` to receive `schema/BuildEvent` maps.
     Returns a Result of k. Re-registering the same k replaces the fn.")

  (unsubscribe! [this k]
    "Remove the subscriber registered under k. Returns a Result of k.
     Idempotent — unknown k is not an error."))

;; =============================================================================
;; IBrowserDriver — the DOM channel
;; =============================================================================

(defprotocol IBrowserDriver
  "Drive a real browser with port-neutral ops."

  (open-session! [this opts]
    "Open a browser session.
     opts: {:browser engine-kw :headless bool :base-url str :timeout-ms int}
     Returns a Result of an opaque session value.")

  (perform! [this session op]
    "Execute one `schema/Op` whose :op/channel is :browser.
     Returns a Result of {:state :pass|:fail :detail str :artifacts [path ...]}.")

  (close-session! [this session]
    "Release the session. Returns a Result of nil. Idempotent."))

(defprotocol IPageMarker
  "Optional: stamp the driven page so another channel can recognise it."

  (mark-session! [this session token]
    "Stamp every document this session loads with `token`, readable from page
     JS as `window.__hiveCljsToken`. Must survive navigation.
     Returns a Result of token."))

(defprotocol IPageEval
  "Optional: evaluate source text in the page a session is driving.

   The DOM channel is already stack agnostic — a page is a page whatever
   compiled it — so a driver that can evaluate is a runtime channel for every
   stack at once, reached through `browser.page-eval`."

  (eval-in-page [this session source]
    "Evaluate `source` in `session`'s page and return a Result of
     {:value <data> :printed str}. Host collections arrive as Clojure data, so a
     JavaScript array reads back as a vector."))

(defprotocol IPageBootstrap
  "Optional: run source in every document a session loads, before page scripts.

   Must survive navigation and must land BEFORE the application starts — a
   contract the app is expected to call into at startup is useless if it is
   installed after startup."

  (bootstrap! [this session source]
    "Install `source` for every document `session` loads. Returns a Result."))

;; =============================================================================
;; ICljsEval — the runtime channel
;; =============================================================================

(defprotocol ICljsEval
  "Evaluate ClojureScript inside the running application runtime."

  (eval-cljs [this build-id form-str]
    "Evaluate form-str in build-id's runtime.
     Returns a Result of {:value <edn> :printed str}.")

  (runtime-available? [this build-id]
    "Return true when a runtime is connected for build-id."))

(defprotocol IRuntimeAffinity
  "Optional: pin evaluation to one specific connected runtime."

  (bind-runtime! [this build-id token]
    "Pin subsequent `eval-cljs` calls to the runtime whose page carries `token`
     (as stamped by `IPageMarker/mark-session!`).
     Returns a Result of the bound runtime's id, or an err when no connected
     runtime carries the token.")

  (unbind-runtime! [this]
    "Drop any pin, returning to the toolchain's own runtime choice.
     Returns a Result of nil. Idempotent."))

(defprotocol IRuntimeInventory
  "Optional: report which runtimes the channel can see, and which one it uses."

  (connected-runtimes [this build-id]
    "Return a Result of [{:client-id … :user-agent … :host …} …] — every runtime
     currently attached to build-id, in the toolchain's own order.")

  (pinned-runtime [this]
    "Return the runtime id `IRuntimeAffinity/bind-runtime!` pinned, or nil when
     evaluation still goes to the toolchain's own choice."))

(defprotocol IRuntimeDialect
  "Optional: render a runtime op as source text this channel's runtime evaluates.

   Segregated from `ICljsEval` because being able to EVALUATE is not the same as
   knowing a step VOCABULARY. The shipped ClojureScript channel speaks re-frame;
   a browser-backed one speaks JavaScript; a third party may speak neither and
   still serve a bare eval of the author's own source text.

   Both methods return nil for an op kind the dialect has no rendering for — the
   step is then reported `:incomplete`, never silently passed."

  (assertion-source [this op]
    "Source text whose value `op` asserts on.")

  (probe-source [this op]
    "Source text yielding `[predicate-result observed-value]` for a polled op.
     The observed value is what lets a timeout say `never happened` apart from
     `not yet`."))

(defprotocol IRuntimeIntrospection
  "Optional: read and rewrite the application's own handler registry.

   Kept apart from `IRuntimeDialect` because rendering an assertion and
   rewriting a live registry are different powers: a channel may well do the
   first for any application and the second for none."

  (invariant-source [this schema frame]
    "Source text validating the whole application state against `schema`,
     yielding nil when it conforms.")

  (registry-source [this kinds]
    "Source text reading the app's registered handler ids for `kinds` in ONE
     round trip: `{kind [id …] …}`.")

  (neutralize-source [this kind id]
    "Source text re-registering handler `id` of `kind` as a no-op."))

(defprotocol ISessionBound
  "Optional: a runtime channel that evaluates INSIDE the browser session the
   scenario drives.

   Such a channel has no runtime to CHOOSE and therefore nothing to pin — it is
   in-band by construction, which is why `IPageMarker`/`IRuntimeAffinity` do not
   apply to it. Those exist only because an out-of-band REPL can be answered by
   a runtime nobody is driving."

  (with-session [this session]
    "Return a channel bound to `session`. The receiver is left unchanged, so one
     channel value serves every run.")

  (bootstrap-source [this]
    "Source this channel needs run in every document BEFORE the page's own
     scripts, or nil when it needs none.

     The channel owns its contract, so the boundary can install it without
     knowing what it says — and an application wires itself to that contract
     with a guarded one-liner rather than a dependency."))

;; =============================================================================
;; IToolchain — how one frontend stack's channels are opened and released
;; =============================================================================

(defprotocol IToolchain
  "Open and release the channels of ONE frontend toolchain.

   The composition root resolves a manifest's declared toolchain to an
   implementation and asks it for the ports, so mounting a stack this library
   has never heard of is a registration rather than an edit to the wiring.

   Teardown lives here rather than on the ports themselves because adding a
   method to a shipped port protocol would break every third-party
   implementation of it."

  (open-build-tool [this manifest]
    "Return a Result of a connected `IBuildTool` for `manifest`.")

  (open-runtime [this manifest]
    "Return a Result of an `ICljsEval` for `manifest`.")

  (close-build-tool! [this build-tool]
    "Release a build tool this toolchain opened. Idempotent; never throws.")

  (close-runtime! [this runtime]
    "Release a runtime channel this toolchain opened. Idempotent; never throws."))

;; =============================================================================
;; Predicates
;; =============================================================================

(defn build-tool? [x] (satisfies? IBuildTool x))
(defn browser-driver? [x] (satisfies? IBrowserDriver x))
(defn cljs-eval? [x] (satisfies? ICljsEval x))
(defn page-marker? [x] (satisfies? IPageMarker x))
(defn runtime-affinity? [x] (satisfies? IRuntimeAffinity x))
(defn runtime-inventory? [x] (satisfies? IRuntimeInventory x))

(defn runtime-dialect? [x] (satisfies? IRuntimeDialect x))
(defn runtime-introspection? [x] (satisfies? IRuntimeIntrospection x))

(defn page-eval? [x] (satisfies? IPageEval x))
(defn session-bound? [x] (satisfies? ISessionBound x))

(defn page-bootstrap? [x] (satisfies? IPageBootstrap x))

(defn toolchain? [x] (satisfies? IToolchain x))
