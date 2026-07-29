(ns hive-cljs.test-api
  "Entry points for using hive-cljs scenarios inside an ordinary test suite.

   Consumers get the same execution path the MCP tool and the watcher use, with
   the ports resolved from their project's `hive-cljs.edn`."
  (:require [hive-cljs.boundary :as boundary]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.plan :as plan]
            [hive-cljs.system :as system]
            [hive-cljs.verdict :as verdict]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn run-scenario!
  "Run a manifest scenario for the project at `root`.
   Returns a Result of a RunReport."
  [root scenario-id]
  (r/bind (system/session root)
          (fn [s] (boundary/run-scenario! (system/run-deps s) (:manifest s) scenario-id))))

(defn run-tagged!
  "Run every scenario at `root` carrying any of `tags`.
   Returns a Result of [RunReport ...]."
  [root tags]
  (r/bind (system/session root)
          (fn [s] (boundary/run-tagged! (system/run-deps s) (:manifest s) tags))))

(defn run-steps!
  "Run an ad-hoc step vector as a synthetic scenario — no manifest entry needed."
  [root id steps]
  (r/bind (system/session root)
          (fn [s]
            (r/bind (plan/build-plan (:manifest s) {:id id :steps (vec steps)})
                    #(boundary/run-plan! (system/run-deps s) %)))))

(defn passed?
  "True when a Result of a RunReport is a green run."
  [res]
  (and (r/ok? res) (verdict/run-ok? (:ok res))))

(defn explain
  "Human-readable failure detail for a run Result."
  [res]
  (if (r/err? res)
    (pr-str res)
    (let [rep (:ok res)]
      (str (verdict/summarize rep)
           (when-let [bad (seq (filter #(contains? #{:fail :error} (:step/state %))
                                       (:run/steps rep)))]
             (str " — " (pr-str (first bad))))))))

(defn scenarios
  "Scenario ids declared at `root`."
  [root]
  (r/bind (system/session root)
          (fn [s] (r/ok (mapv :id (manifest/scenarios (:manifest s)))))))

(defn close!
  "Release the project's ports — use in a test fixture's teardown."
  [root]
  (system/close! root))

(defn close-all!
  "Release every open project's ports — the whole-suite teardown.

   A JVM with a relay still connected cannot exit, so an embedded suite that
   opens more than one project needs this rather than a per-root `close!`."
  []
  (system/close-all!))
