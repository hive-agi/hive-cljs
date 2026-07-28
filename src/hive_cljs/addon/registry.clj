(ns hive-cljs.addon.registry
  "Tool definition for the consolidated `cljs` tool: subcommand dispatch map,
   MCP input schema, and the handler that routes a call."
  (:require [clojure.string :as str]
            [hive-cljs.addon.handlers :as h]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def subcommands
  "command string → {:fn handler :doc str :params [...]}"
  {"doctor"       {:fn h/doctor
                   :doc "Validate hive-cljs.edn and report port connectivity"
                   :params []}
   "status"       {:fn h/status
                   :doc "Build verdict for one build, or all known builds"
                   :params [:build]}
   "compile"      {:fn h/compile-build
                   :doc "Trigger one compile cycle and return the verdict"
                   :params [:build]}
   "eval"         {:fn h/eval-cljs
                   :doc "Evaluate ClojureScript in a build's running runtime"
                   :params [:build :code]}
   "e2e list"     {:fn h/e2e-list
                   :doc "List scenarios declared in the manifest"
                   :params []}
   "e2e run"      {:fn h/e2e-run
                   :doc "Run a scenario by :scenario, or every scenario matching :tags"
                   :params [:scenario :tags]}
   "watch start"  {:fn h/watch-start
                   :doc "Couple build success to e2e runs per :hive.cljs/watch"
                   :params []}
   "watch stop"   {:fn h/watch-stop
                   :doc "Stop the watcher"
                   :params []}
   "watch status" {:fn h/watch-status
                   :doc "Watcher state, debounce config and recent run log"
                   :params []}
   "close"        {:fn h/close
                   :doc "Release relay, nREPL and browser resources for the project"
                   :params []}})

(defn help
  [_params]
  (r/ok {:tool "cljs"
         :subcommands (mapv (fn [[k v]] {:command k :doc (:doc v) :params (:params v)})
                            (sort-by key subcommands))
         :manifest "hive-cljs.edn at the project root (:directory param, else cwd)"}))

(defn- normalize-command
  [c]
  (-> (or c "help") str str/trim (str/replace #"\s+" " ") str/lower-case))

(defn dispatch
  "Route a tool call to its subcommand handler."
  [{:keys [command] :as params}]
  (let [c (normalize-command command)]
    (if (= "help" c)
      (help params)
      (if-let [{:keys [fn]} (get subcommands c)]
        (try
          (fn params)
          (catch Exception e
            (r/err :cljs/handler-threw {:command c :cause (.getMessage e)})))
        (r/err :cljs/unknown-command {:command c
                                      :known (vec (sort (keys subcommands)))})))))

(def tool-def
  {:name "cljs"
   :description
   (str "ClojureScript development surface. Subcommands: "
        (str/join ", " (sort (keys subcommands))) ", help. "
        "Driven by hive-cljs.edn at the project root.")
   :inputSchema
   {:type "object"
    :properties
    {"command"   {:type "string"
                  :description "Subcommand, e.g. 'status', 'e2e run', 'watch start'"}
     "directory" {:type "string"
                  :description "Project root holding hive-cljs.edn (defaults to cwd)"}
     "build"     {:type "string" :description "shadow-cljs build id, e.g. 'app'"}
     "code"      {:type "string" :description "ClojureScript source for 'eval'"}
     "scenario"  {:type "string" :description "Scenario id for 'e2e run'"}
     "tags"      {:type "string" :description "Comma-separated tags for 'e2e run'"}}
    :required ["command"]}
   :handler dispatch})

(def tools [tool-def])
