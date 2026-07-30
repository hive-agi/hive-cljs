(ns hive-cljs.schema
  "Malli value objects for the hive-cljs subsystem.

   Single source: these schemas drive `m/=>` contracts on the pure layers AND
   the property/mutation facets synthesized by `hive-schemas.test`.

   Two families:
   - `Raw*`        — permissive, boundary-facing (project EDN, MCP string coercion)
   - everything else — `:closed true` internal plan/report shapes")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Primitives
;; =============================================================================

(def BuildId
  "Identifier of a shadow-cljs build target."
  :keyword)

(def ScenarioId
  "Identifier of an e2e scenario."
  :keyword)

(def Port
  [:int {:min 1 :max 65535}])

(def NonBlankString
  [:string {:min 1}])

(def Millis
  [:int {:min 0}])

;; =============================================================================
;; Connectivity
;; =============================================================================

(def ShadowConfig
  "Where the shadow-cljs server and its cljs nREPL live."
  [:map {:closed true}
   [:host NonBlankString]
   [:port Port]
   [:nrepl-port {:optional true} Port]])

(def BrowserEngine
  [:enum :chromium :firefox :webkit])

;; =============================================================================
;; Build status
;; =============================================================================

(def BuildState
  "Lifecycle state of a shadow-cljs build, normalized from the relay's spelling."
  [:enum :unknown :pending :compiling :completed :failed])

(def CompiledFile
  [:map {:closed true}
   [:file/path NonBlankString]
   [:file/elapsed-ms {:optional true} Millis]])

(def BuildStatus
  "Normalized verdict of one build cycle."
  [:map {:closed true}
   [:build/id BuildId]
   [:build/state BuildState]
   [:build/resources {:optional true} [:int {:min 0}]]
   [:build/compiled {:optional true} [:int {:min 0}]]
   [:build/duration-ms {:optional true} Millis]
   [:build/warnings [:vector :any]]
   [:build/errors [:vector :any]]
   [:build/files [:vector CompiledFile]]
   [:build/at {:optional true} NonBlankString]])

;; =============================================================================
;; Scenario steps — authored form and compiled form
;; =============================================================================

(def Step
  "Authored step: a vector whose head is the step kind keyword.

   Arity and argument types are validated per-kind by the step rule-chain,
   not here — this schema is the boundary shape only."
  [:and
   [:vector {:min 1} :any]
   [:fn {:error/message "step must start with a keyword"}
    #(keyword? (first %))]])

(def OpChannel
  "Which port executes a compiled op."
  [:enum :browser :runtime])

(def Op
  "Compiled step: the port-neutral instruction an adapter interprets."
  [:map {:closed true}
   [:op/kind :keyword]
   [:op/channel OpChannel]
   [:op/args [:vector :any]]
   [:op/frame {:optional true} :keyword]
   [:op/expect {:optional true} [:map-of :keyword :any]]
   [:op/source Step]])

(def Scenario
  [:map {:closed true}
   [:id ScenarioId]
   [:build {:optional true} BuildId]
   [:frame {:optional true} :keyword]
   [:tags {:optional true} [:set :keyword]]
   [:doc {:optional true} :string]
   [:steps [:vector {:min 1} Step]]])

;; =============================================================================
;; Manifest — normalized
;; =============================================================================

(def BuildSpec
  [:map {:closed true}
   [:shadow/id BuildId]
   [:http-port {:optional true} Port]
   [:entry {:optional true} NonBlankString]])

(def E2eConfig
  [:map {:closed true}
   [:base-url NonBlankString]
   [:browser BrowserEngine]
   [:headless :boolean]
   [:timeout-ms Millis]
   [:frame {:optional true} :keyword]
   [:artifacts-dir NonBlankString]
   [:scenarios [:vector Scenario]]])

(def WatchAction
  "A declarative reaction to a build event."
  [:tuple :keyword [:map-of :keyword :any]])

(def WatchPolicy
  [:map {:closed true}
   [:on-build-success [:vector WatchAction]]
   [:on-build-failure [:vector WatchAction]]
   [:debounce-ms Millis]
   [:builds {:optional true} [:set BuildId]]])

(def Manifest
  "Normalized project config — every default resolved."
  [:map {:closed true}
   [:manifest/root NonBlankString]
   [:manifest/sources {:optional true} [:vector NonBlankString]]
   [:manifest/shadow ShadowConfig]
   [:manifest/builds [:map-of BuildId BuildSpec]]
   [:manifest/e2e E2eConfig]
   [:manifest/watch WatchPolicy]])

(def RawManifest
  "Project-authored `hive-cljs.edn` before normalization."
  [:map
   [:hive.cljs/shadow {:optional true} [:map-of :keyword :any]]
   [:hive.cljs/builds {:optional true} [:map-of :keyword :any]]
   [:hive.cljs/e2e {:optional true} [:map-of :keyword :any]]
   [:hive.cljs/watch {:optional true} [:map-of :keyword :any]]])

;; =============================================================================
;; Staleness
;; =============================================================================

(def SourceStamp
  "Filesystem facts about one file that contributed config."
  [:map {:closed true}
   [:source/path NonBlankString]
   [:source/exists? :boolean]
   [:source/modified Millis]
   [:source/size [:int {:min 0}]]])

(def ManifestFreshness
  [:enum :fresh :stale])

(def ServerMatch
  "Whether the connected toolchain is serving the builds the manifest declares."
  [:enum :ok :mismatch :unknown])

(def StalenessReport
  [:map {:closed true}
   [:staleness/manifest ManifestFreshness]
   [:staleness/sources [:vector SourceStamp]]
   [:staleness/server ServerMatch]
   [:staleness/declared-builds [:vector BuildId]]
   [:staleness/reported-builds [:vector BuildId]]])

;; =============================================================================
;; Run plan and report
;; =============================================================================

(def RunPlan
  "Pure, executable description of one scenario run."
  [:map {:closed true}
   [:plan/scenario ScenarioId]
   [:plan/build {:optional true} BuildId]
   [:plan/base-url NonBlankString]
   [:plan/session [:map-of :keyword :any]]
   [:plan/ops [:vector {:min 1} Op]]])

(def StepState
  [:enum :pass :fail :error :skipped :incomplete])

(def StepResult
  [:map {:closed true}
   [:step/index [:int {:min 0}]]
   [:step/kind :keyword]
   [:step/state StepState]
   [:step/detail {:optional true} :string]
   [:step/elapsed-ms {:optional true} Millis]])

(def RunState
  [:enum :pass :fail :error :incomplete])

(def RunReport
  [:map {:closed true}
   [:run/scenario ScenarioId]
   [:run/state RunState]
   [:run/steps [:vector StepResult]]
   [:run/elapsed-ms {:optional true} Millis]
   [:run/artifacts {:optional true} [:vector NonBlankString]]])

;; =============================================================================
;; Watch decisions
;; =============================================================================

(def BuildEvent
  [:map {:closed true}
   [:event/build BuildId]
   [:event/status BuildStatus]
   [:event/at Millis]])

(def Decision
  "What the watcher decided to do about a build event."
  [:map {:closed true}
   [:decision/kind [:enum :run-e2e :report :ignore]]
   [:decision/build {:optional true} BuildId]
   [:decision/scenarios {:optional true} [:vector ScenarioId]]
   [:decision/reason NonBlankString]])
