(ns hive-cljs.addon.lifecycle
  "IAddon record and constructor for the hive.cljs addon."
  (:require [hive-addon.protocol :as proto]
            [hive-cljs.addon.handlers :as h]
            [hive-cljs.addon.registry :as registry]
            [hive-cljs.browser.factory :as browser]
            [hive-cljs.system :as system]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def addon-id "hive.cljs")

(defrecord CljsAddon [config-ref]
  proto/IAddon
  (addon-id [_] addon-id)
  (addon-type [_] :native)
  (capabilities [_] #{:tools :health-reporting})

  (initialize! [_ config]
    (reset! config-ref (or (:addon/config config) {}))
    {:success? true
     :errors   []
     :metadata {:tools (mapv :name registry/tools)
                :browser-adapter (if (browser/available?) :present :absent)}})

  (shutdown! [_]
    (doseq [root (h/running-watchers)]
      (h/close {:directory root}))
    (system/close-all!)
    nil)

  (tools [_] registry/tools)

  (schema-extensions [_] [])

  (health [_]
    {:status :ok
     :details {:watchers (h/running-watchers)
               :browser-adapter (if (browser/available?) :present :absent)}})

  (excluded-tools [_] #{})

  (hooks [_] {}))

(defn addon-ctor
  "Manifest entry point — returns an uninitialized addon instance."
  ([] (addon-ctor {}))
  ([_config] (->CljsAddon (atom {}))))

(defn init-as-addon!
  "Construct and initialize in one step."
  ([] (init-as-addon! {}))
  ([config]
   (let [a (addon-ctor config)]
     (proto/initialize! a config)
     a)))
