(ns hive-cljs.profile
  "Provider behaviour as DATA — the DIP swap point for toolchain quirks.

   A profile records what a provider actually does (relay op spelling, sync-db
   table/attribute names, status vocabulary, launch defaults). Generic layers
   read the profile; swapping a provider is a `register!`, never a code edit.

   Values here are READ OFF the provider's own source, not from third-party
   docs. Re-verify before changing them."
  (:require [clojure.string :as str]
            [hive-cljs.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Profile schemas
;; =============================================================================

(def RelayProfile
  [:map {:closed true}
   [:profile/id :keyword]
   [:relay/client-id :string]
   [:relay/path :string]
   [:relay/token-pattern :any]
   [:relay/hello-op :keyword]
   [:relay/client-info [:map-of :keyword :any]]
   [:relay/welcome-op :keyword]
   [:relay/ping-op :keyword]
   [:relay/pong-op :keyword]
   [:relay/db-sync-init-op :keyword]
   [:relay/db-sync-op :keyword]
   [:relay/db-update-op :keyword]
   [:relay/builds-key :keyword]
   [:relay/build-table :keyword]
   [:relay/build-id-key :keyword]
   [:relay/build-status-key :keyword]
   [:relay/worker-active-key :keyword]
   [:relay/compile-op :keyword]
   [:relay/watch-compile-op :keyword]
   [:relay/watch-start-op :keyword]
   [:relay/watch-stop-op :keyword]
   [:relay/build-id-arg :keyword]
   [:relay/server-runtime-id :int]
   [:relay/status-map [:map-of :keyword s/BuildState]]
   [:relay/reconnect-ms s/Millis]
   [:relay/compile-timeout-ms s/Millis]
   [:relay/poll-ms s/Millis]])

(def BrowserProfile
  [:map {:closed true}
   [:profile/id :keyword]
   [:browser/engines [:set s/BrowserEngine]]
   [:browser/default-engine s/BrowserEngine]
   [:browser/nav-settle-ms s/Millis]
   [:browser/assert-poll-ms s/Millis]])

(def CoverageProfile
  "How a coverage tool is invoked, and how the toolchain it measures names the
   modules on disk."
  [:map {:closed true}
   [:profile/id :keyword]
   [:coverage/command [:vector s/NonBlankString]]
   [:coverage/include-flag s/NonBlankString]
   [:coverage/exclude-flag s/NonBlankString]
   [:coverage/reporter-flag s/NonBlankString]
   [:coverage/reporters [:vector {:min 1} s/NonBlankString]]
   [:coverage/summary-reporter s/NonBlankString]
   [:coverage/report-dir-flag s/NonBlankString]
   [:coverage/summary-file s/NonBlankString]
   [:coverage/compiled-layout s/CompiledLayout]
   [:coverage/compiled-root s/NonBlankString]
   [:coverage/module-suffix s/NonBlankString]])

;; =============================================================================
;; Measured profiles
;; =============================================================================

(def shadow-relay-default
  "shadow-cljs remote-relay behaviour.

   Op and attribute spellings taken from shadow-cljs source:
   `shadow.cljs.devtools.server.remote-ext` (op table), `…server.sync-db`
   (change-tuple format), `shadow.cljs.ui.db.builds` (client send shape).
   All are in the `shadow.cljs` namespace — the `shadow.cljs.model` spelling
   used by third-party clients does not match this server."
  {:profile/id              :shadow/default
   :relay/client-id         "hive-cljs"
   :relay/path              "/api/remote-relay"
   :relay/token-pattern     #"<meta\s+content=\"([^\"]+)\"\s+name=\"shadow-remote-token\""
   :relay/hello-op          :hello
   :relay/client-info       {:type :hive-cljs}
   :relay/welcome-op        :welcome
   :relay/ping-op           :ping
   :relay/pong-op           :pong
   :relay/db-sync-init-op   :shadow.cljs/db-sync-init!
   :relay/db-sync-op        :shadow.cljs/db-sync
   :relay/db-update-op      :shadow.cljs/db-update
   :relay/builds-key        :shadow.cljs/builds
   :relay/build-table       :shadow.cljs/build
   :relay/build-id-key      :shadow.cljs/build-id
   :relay/build-status-key  :shadow.cljs/build-status
   :relay/worker-active-key :shadow.cljs/build-worker-active
   :relay/compile-op        :shadow.cljs/build-compile!
   :relay/watch-compile-op  :shadow.cljs/build-watch-compile!
   :relay/watch-start-op    :shadow.cljs/build-watch-start!
   :relay/watch-stop-op     :shadow.cljs/build-watch-stop!
   :relay/build-id-arg      :shadow.cljs/build-id
   :relay/server-runtime-id 1
   :relay/status-map        {:completed        :completed
                             :compiling        :compiling
                             :pending          :pending
                             :failed           :failed
                             :configure        :pending
                             :compile-warnings :completed}
   :relay/reconnect-ms      2000
   :relay/compile-timeout-ms 120000
   :relay/poll-ms           50})

(def browser-default
  {:profile/id             :browser/default
   :browser/engines        #{:chromium :firefox :webkit}
   :browser/default-engine :chromium
   :browser/nav-settle-ms  250
   :browser/assert-poll-ms 100})

(def c8-default
  "c8 (V8 coverage + istanbul reporters) measuring a shadow-cljs `:node-test`
   bundle.

   `:coverage/compiled-layout :flat-dotted` and `:coverage/compiled-root` are
   read off shadow-cljs's own emitted tree, not its docs: every namespace lands
   in one flat directory as `<munged.ns>.js`. The include/exclude globs are
   built from that layout, because c8 filters the COMPILED script URL — the
   `.cljs` paths in the report only exist after source-map remapping."
  {:profile/id                 :coverage/c8
   :coverage/command           ["npx" "c8"]
   :coverage/include-flag      "--include"
   :coverage/exclude-flag      "--exclude"
   :coverage/reporter-flag     "--reporter"
   :coverage/reporters         ["json-summary" "text-summary"]
   :coverage/summary-reporter  "json-summary"
   :coverage/report-dir-flag   "--report-dir"
   :coverage/summary-file      "coverage-summary.json"
   :coverage/compiled-layout   :flat-dotted
   :coverage/compiled-root     ".shadow-cljs/builds/{build}/dev/out/cljs-runtime"
   :coverage/module-suffix     ".js"})

;; =============================================================================
;; Registry — the swap point
;; =============================================================================

(defonce ^:private registry
  (atom {:relay    {:shadow/default shadow-relay-default}
         :browser  {:browser/default browser-default}
         :coverage {:coverage/c8 c8-default}}))

(defn register!
  "Register a profile under `family` (:relay | :browser). Returns the id."
  [family profile]
  (swap! registry assoc-in [family (:profile/id profile)] profile)
  (:profile/id profile))

(defn profile
  "Look up a registered profile. Returns nil when absent."
  [family id]
  (get-in @registry [family id]))

(defn relay-profile
  ([] (relay-profile :shadow/default))
  ([id] (or (profile :relay id) shadow-relay-default)))

(defn browser-profile
  ([] (browser-profile :browser/default))
  ([id] (or (profile :browser id) browser-default)))

(defn coverage-profile
  ([] (coverage-profile :coverage/c8))
  ([id] (or (profile :coverage id) c8-default)))

(defn normalize-state
  "Map a provider's raw status token to `schema/BuildState`."
  [prof raw]
  (let [k (cond
            (keyword? raw) raw
            (string? raw)  (keyword (str/replace raw #"^:" ""))
            :else          nil)]
    (get (:relay/status-map prof) k :unknown)))

(m/=> normalize-state [:=> [:cat :any :any] s/BuildState])
(m/=> register! [:=> [:cat :keyword [:map-of :keyword :any]] :keyword])
