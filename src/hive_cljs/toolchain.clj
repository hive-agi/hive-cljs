(ns hive-cljs.toolchain
  "Registry of frontend toolchains — the DIP seam at the composition root.

   `system/open!` resolves a manifest's declared toolchain HERE instead of
   calling a vendor's connector by name, so a stack this library has never heard
   of is mounted with `register!` rather than an edit to the wiring.

   A shipped adapter is registered as a SYMBOL and resolved on first use — the
   same soft resolution `browser.factory` uses — so the subsystem loads, tests
   and reports health with none of its vendors on the classpath, and a missing
   one is a typed error at open time rather than a load failure.

   A constructor must be cheap and stateless: per-session state belongs to the
   ports it opens, not to the toolchain."
  (:require [hive-cljs.ports :as ports]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private shipped
  "Adapters this library ships, as symbols naming a 0-arity constructor."
  {:shadow-cljs 'hive-cljs.shadow.toolchain/toolchain})

(defonce ^:private registry (atom {}))

(defn register!
  "Register `impl` under `id`. `impl` is an `IToolchain`, or a symbol naming a
   0-arity constructor of one. Returns the id; re-registering replaces."
  [id impl]
  (swap! registry assoc id impl)
  id)

(defn unregister!
  "Drop a registration. Returns the id. Idempotent; shipped adapters reappear."
  [id]
  (swap! registry dissoc id)
  id)

(defn registered
  "Every toolchain id that can be resolved — shipped plus registered."
  []
  (set (keys (merge shipped @registry))))

(defn- realize
  [id impl]
  (cond
    (ports/toolchain? impl) (r/ok impl)

    (symbol? impl)
    (try
      (if-let [ctor (requiring-resolve impl)]
        (let [tc (ctor)]
          (if (ports/toolchain? tc)
            (r/ok tc)
            (r/err :toolchain/not-a-toolchain {:id id :ctor impl})))
        (r/err :toolchain/unavailable {:id id :ctor impl}))
      (catch Throwable e
        (r/err :toolchain/unavailable {:id id :ctor impl :cause (.getMessage e)})))

    :else (r/err :toolchain/not-a-toolchain {:id id})))

(defn resolve-toolchain
  "Result of the `IToolchain` registered under `id`.

   A registration wins over a shipped adapter of the same id, so a project can
   replace the ClojureScript wiring without unregistering first."
  [id]
  (if-let [impl (get (merge shipped @registry) id)]
    (realize id impl)
    (r/err :toolchain/unknown {:id id :known (sort (registered))})))
