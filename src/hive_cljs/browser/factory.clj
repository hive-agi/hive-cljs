(ns hive-cljs.browser.factory
  "Soft resolution of the browser adapter.

   The vendor is an optional dependency: every layer above asks here and gets a
   typed Result, so the subsystem loads, tests and reports health with no
   browser on the classpath."
  (:require [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private adapter-sym 'hive-cljs.browser.playwright/driver)

(defn available?
  "True when a browser adapter can be loaded."
  []
  (boolean
   (try (requiring-resolve adapter-sym)
        (catch Throwable _ false))))

(defn driver
  "Result of an IBrowserDriver, or :browser/unavailable when the optional
   adapter is absent."
  []
  (try
    (if-let [ctor (requiring-resolve adapter-sym)]
      (r/ok (ctor))
      (r/err :browser/unavailable {:hint "add the :browser alias (playwright-java)"}))
    (catch Throwable e
      (r/err :browser/unavailable {:hint "add the :browser alias (playwright-java)"
                                   :cause (.getMessage e)}))))
