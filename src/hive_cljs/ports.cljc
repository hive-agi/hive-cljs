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

(defn toolchain? [x] (satisfies? IToolchain x))
