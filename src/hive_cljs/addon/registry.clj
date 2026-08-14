(ns hive-cljs.addon.registry
  "Tool definition for the consolidated `cljs` tool: subcommand dispatch map,
   MCP input schema, and the handler that routes a call."
  (:require [clojure.string :as str]
            [hive-cljs.addon.handlers :as h]
            [hive-dsl.result :as r]
            [clojure.pprint :as pp]
            [hive-addon.cli.response :as response]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def subcommands
  "command string → {:fn handler :doc str :params [...]}"
  {"doctor"       {:fn h/doctor
                   :doc "Validate hive-cljs config and report port connectivity"
                   :params []}
   "staleness"    {:fn h/staleness
                   :doc "Report config-vs-disk freshness, served builds, and bundle-vs-source age"
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
   "coverage"     {:fn h/coverage
                   :doc "Coverage over the project's own ClojureScript, worst namespace first"
                   :params [:filter]}
   "coverage baseline" {:fn h/coverage-baseline
                        :doc "Freeze the current summary as the baseline for the next delta"
                        :params []}
   "e2e list"     {:fn h/e2e-list
                   :doc "List scenarios declared in the manifest"
                   :params []}
   "e2e run"      {:fn h/e2e-run
                   :doc "Run a scenario by :scenario, or every scenario matching :tags"
                   :params [:scenario :tags]}
   "e2e run-all"  {:fn h/e2e-run-all
                   :doc "Run :scenario/:tags in every descendant project that authors config"
                   :params [:scenario :tags :depth]}
   "e2e mutate"   {:fn h/e2e-mutate
                   :doc "Mutation score: inject faults and report the ones no scenario killed"
                   :params [:scenario :tags :auto]}
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

(defn ->response
  "Render a handler Result as the host's tool-result shape."
  [result]
  (if (r/err? result)
    (response/error (pr-str result))
    (response/text (with-out-str (pp/pprint (:ok result))))))

;; =============================================================================
;; `code cljs …` subdomain — contributed to the host's consolidated code tool
;; =============================================================================

(declare tool-def)

(def subdomain "cljs")

(defn strip-subdomain-prefix
  "Drop the `cljs ` subdomain prefix from a `code cljs <cmd>` command,
   yielding the bare subcommand."
  [cmd]
  (let [s (str cmd)
        p (str subdomain " ")]
    (if (and (>= (count s) (count p)) (= p (subs s 0 (count p))))
      (subs s (count p))
      s)))

(defn dispatch-subdomain
  "Handler for `code cljs <cmd>`: strip the prefix, dispatch, render."
  [params]
  (->response
   (dispatch (assoc params :command (strip-subdomain-prefix (:command params))))))

(def code-contributions
  "The `cljs` subdomain contributed to the consolidated `code` tool."
  {subdomain
   {:handler     dispatch-subdomain
    :description
    (str "ClojureScript development — shadow-cljs build status, cljs-eval in the "
         "running runtime, Playwright e2e scenarios and build→e2e watching. "
         "Subcommands: " (str/join ", " (sort (keys subcommands))) ", help. "
         "Driven by hive-cljs.edn at the project root. "
         "Use `code cljs help` to list all.")}})

(def ^:private host-core-params
  "Parameter names the consolidated code tool already owns — never re-contributed."
  (into #{} (mapcat (fn [n] [n (keyword n)]))
        #{"command" "code" "symbol" "prefix" "pattern" "mode" "session_name"
          "name" "port" "host" "timeout" "project_dir" "repl_type" "file_path"
          "line" "template" "file" "path" "namespace" "function" "query"
          "depth" "limit" "directory" "scope"}))

(defn code-schema-ext
  "This tool's inputSchema properties minus the ones the code tool already
   declares — merged into the code tool via the schema-extensions seam."
  []
  (apply dissoc (get-in tool-def [:inputSchema :properties] {}) host-core-params))

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
                  :description "Subcommand, e.g. 'status', 'e2e run', 'coverage'"}
     "directory" {:type "string"
                  :description "Project root holding hive-cljs.edn (defaults to cwd)"}
     "build"     {:type "string" :description "shadow-cljs build id, e.g. 'app'"}
     "code"      {:type "string" :description "ClojureScript source for 'eval'"}
     "scenario"  {:type "string" :description "Scenario id for 'e2e run'"}
     "tags"      {:type "string" :description "Comma-separated tags for 'e2e run'"}
     "depth"     {:type "string" :description "Search depth for 'e2e run-all' (default 3)"}
     "filter"    {:type "string"
                  :description "Narrow 'coverage' to namespaces containing this substring"}
     "auto"      {:type "string"
                  :description "Derive faults for 'e2e mutate': 'true', or 'sub,event'"}}
    :required ["command"]}
   :handler dispatch})

(def tools [tool-def])
