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

;; =============================================================================
;; Predicates
;; =============================================================================

(defn build-tool? [x] (satisfies? IBuildTool x))
(defn browser-driver? [x] (satisfies? IBrowserDriver x))
(defn cljs-eval? [x] (satisfies? ICljsEval x))
