(ns hive-cljs.addon.host
  "Contribution of this addon's commands to a composite host tool.

   The command store is hive-addon.registry.commands, and hive-addon is a LEAF
   library this addon already depends on, so contributing is an ordinary call
   rather than a runtime probe for a namespace that may not be loaded. The HOST
   is still not a dependency -- nothing here requires hive-mcp -- but the seam
   the host publishes is, which is the whole point of the migration: a
   requiring-resolve of a host namespace could only ever find hive-mcp, and
   that is exactly the coupling an addon must not have.

   With no host present the contribution simply sits in the registry until one
   reads it, so these stay safe to call from tests."
  (:require [hive-addon.registry.commands :as addon-cmds]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn available?
  "True when the command-contribution seam is present.

   It now always is: the seam is a compile-time dependency, not a host
   namespace that may or may not have been loaded. Kept so callers that branch
   on it keep compiling, and because 'can I contribute' stays a meaningful
   question to ask even when the answer has become constant."
  []
  true)

(defn contribute-commands!
  "Merge `commands` into the composite `tool-name` under `addon-id`.
   Returns true."
  [tool-name addon-id commands]
  (addon-cmds/contribute! tool-name addon-id commands)
  (log/info "hive-cljs: contributed" (pr-str (keys commands))
            "subdomain to the" tool-name "tool")
  true)

(defn retract-commands!
  "Remove this addon's contributions from `tool-name`. Idempotent."
  [tool-name addon-id]
  (addon-cmds/retract! tool-name addon-id)
  true)
