(ns hive-cljs.addon.host
  "Soft resolution of host services.

   The host is a RUNTIME, not a dependency: nothing here may appear in a
   `:require`. Every capability is resolved on demand and degrades to a no-op
   when absent, so the addon loads and tests with no host present."
  (:require [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private contribute-sym
  'hive-mcp.extensions.registry/contribute-commands!)

(def ^:private retract-sym
  'hive-mcp.extensions.registry/retract-commands!)

(defn- try-resolve
  [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defn available?
  "True when the host exposes the command-contribution seam."
  []
  (some? (try-resolve contribute-sym)))

(defn contribute-commands!
  "Merge `commands` into the host's composite `tool-name` under `addon-id`.
   Returns true when contributed, false when no host is present."
  [tool-name addon-id commands]
  (if-let [f (try-resolve contribute-sym)]
    (do (f tool-name addon-id commands)
        (log/info "hive-cljs: contributed" (pr-str (keys commands))
                  "subdomain to the" tool-name "tool")
        true)
    false))

(defn retract-commands!
  "Remove this addon's contributions from `tool-name`. Idempotent."
  [tool-name addon-id]
  (if-let [f (try-resolve retract-sym)]
    (do (f tool-name addon-id) true)
    false))
