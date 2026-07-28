(ns hive-cljs.addon
  "IAddon façade for hive-mcp-compatible hosts.

   Discovered via the classpath manifest
   `META-INF/hive-addons/hive-cljs.edn` (:addon/init-ns this ns,
   :addon/init-fn `addon-ctor`).

   Implementation lives in:
   - hive-cljs.addon.handlers  (subcommand bodies)
   - hive-cljs.addon.registry  (tool-def + dispatch)
   - hive-cljs.addon.lifecycle (IAddon record)"
  (:require [hive-cljs.addon.lifecycle :as lifecycle]
            [hive-cljs.addon.registry :as registry]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def tool-def registry/tool-def)
(def tools    registry/tools)
(def dispatch registry/dispatch)

(def addon-id      lifecycle/addon-id)
(def addon-ctor    lifecycle/addon-ctor)
(def init-as-addon! lifecycle/init-as-addon!)

(def ->CljsAddon    lifecycle/->CljsAddon)
(def map->CljsAddon lifecycle/map->CljsAddon)
