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

(def AppDbCheck
  "How often the app-db invariant is asserted during a run."
  [:enum :every-step :mutations :final])

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
;; Mutation — injected behavioural faults
;; =============================================================================

(def Fault
  "One behavioural fault: source the runtime evaluates to break the live app."
  [:map {:closed true}
   [:fault/id :keyword]
   [:fault/form NonBlankString]
   [:fault/target {:optional true} :symbol]
   [:fault/doc {:optional true} :string]])

(def FaultVerdict
  "Whether the suite noticed one fault. Killed is the GOOD outcome."
  [:map {:closed true}
   [:fault/id :keyword]
   [:fault/killed? :boolean]
   [:fault/by {:optional true} [:vector ScenarioId]]
   [:fault/detail {:optional true} :string]])

(def MutationReport
  [:map {:closed true}
   [:mutation/scenarios [:vector ScenarioId]]
   [:mutation/verdicts [:vector FaultVerdict]]
   [:mutation/killed [:vector :keyword]]
   [:mutation/survived [:vector :keyword]]
   [:mutation/score [:double {:min 0.0 :max 1.0}]]])

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
   [:poll-ms Millis]
   [:frame {:optional true} :keyword]
   [:app-db-schema {:optional true} :symbol]
   [:app-db-check {:optional true} AppDbCheck]
   [:faults [:vector Fault]]
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

(def RawManifest
  "Project-authored `hive-cljs.edn` before normalization."
  [:map
   [:hive.cljs/shadow {:optional true} [:map-of :keyword :any]]
   [:hive.cljs/builds {:optional true} [:map-of :keyword :any]]
   [:hive.cljs/e2e {:optional true} [:map-of :keyword :any]]
   [:hive.cljs/watch {:optional true} [:map-of :keyword :any]]
   [:hive.cljs/coverage {:optional true} [:map-of :keyword :any]]])

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

(def BundleFreshness
  "Whether a build's emitted output is newer than the sources it was built from."
  [:enum :fresh :stale :unknown])

(def BundleStamp
  "One build's compiled-output-vs-source comparison."
  [:map {:closed true}
   [:bundle/build BuildId]
   [:bundle/state BundleFreshness]
   [:bundle/output-dir {:optional true} NonBlankString]
   [:bundle/compiled Millis]
   [:bundle/newest-source Millis]])

(def StalenessReport
  [:map {:closed true}
   [:staleness/manifest ManifestFreshness]
   [:staleness/sources [:vector SourceStamp]]
   [:staleness/server ServerMatch]
   [:staleness/declared-builds [:vector BuildId]]
   [:staleness/reported-builds [:vector BuildId]]
   [:staleness/bundles [:vector BundleStamp]]])

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
   [:plan/runtime [:map-of :keyword :any]]
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

;; =============================================================================
;; Coverage
;; =============================================================================

(def Count
  "A non-negative instrumentation count. Bounded far above any real codebase so
   that totals over generated data stay inside a long."
  [:int {:min 0 :max 100000000}])

(def CoverageMetric
  "Covered/total for one instrumentation dimension, plus the derived percentage."
  [:map {:closed true}
   [:metric/covered Count]
   [:metric/total Count]
   [:metric/pct [:double {:min 0.0 :max 100.0}]]])

(def CoverageRow
  "One ClojureScript namespace's coverage, source-mapped back from the bundle."
  [:map {:closed true}
   [:coverage/ns NonBlankString]
   [:coverage/file NonBlankString]
   [:coverage/lines CoverageMetric]
   [:coverage/branches CoverageMetric]
   [:coverage/functions CoverageMetric]])

(def SummedMetric
  "A metric aggregated over many namespaces. Its counts are deliberately
   unbounded above: a sum of per-namespace `Count`s legitimately exceeds the
   per-namespace ceiling."
  [:map {:closed true}
   [:metric/covered [:int {:min 0}]]
   [:metric/total [:int {:min 0}]]
   [:metric/pct [:double {:min 0.0 :max 100.0}]]])

(def CoverageTotals
  [:map {:closed true}
   [:coverage/namespaces [:int {:min 0}]]
   [:coverage/lines SummedMetric]
   [:coverage/branches SummedMetric]
   [:coverage/functions SummedMetric]])

(def CoverageDelta
  "Change in COVERED COUNTS against a baseline; `:delta/new?` marks a namespace
   the baseline never saw."
  [:map {:closed true}
   [:delta/ns NonBlankString]
   [:delta/lines :int]
   [:delta/branches :int]
   [:delta/functions :int]
   [:delta/new? :boolean]])

(def CoverageDimension
  [:enum :lines :branches :functions])

(def Percentage
  [:double {:min 0.0 :max 100.0}])

(def CoverageThresholds
  "Minimum acceptable percentage per dimension. An absent dimension is ungated."
  [:map {:closed true}
   [:lines {:optional true} Percentage]
   [:branches {:optional true} Percentage]
   [:functions {:optional true} Percentage]])

(def CoverageState
  [:enum :pass :below-threshold :unavailable])

(def ThresholdBreach
  [:map {:closed true}
   [:breach/dimension CoverageDimension]
   [:breach/required Percentage]
   [:breach/actual Percentage]])

(def CoverageVerdict
  [:map {:closed true}
   [:coverage/state CoverageState]
   [:coverage/breaches [:vector ThresholdBreach]]])

(def CompiledLayout
  "How the toolchain names emitted modules on disk. `:flat-dotted` is what
   shadow-cljs writes (payment_flow.views.editar.js); `:nested` is the directory
   spelling other toolchains use."
  [:enum :flat-dotted :nested])

(def CoverageConfig
  "Normalized `:hive.cljs/coverage` section."
  [:map {:closed true}
   [:coverage/build BuildId]
   [:coverage/bundle NonBlankString]
   [:coverage/profile :keyword]
   [:coverage/compile [:vector NonBlankString]]
   [:coverage/source-prefixes [:vector {:min 1} NonBlankString]]
   [:coverage/exclude [:vector NonBlankString]]
   [:coverage/report-dir NonBlankString]
   [:coverage/baseline {:optional true} NonBlankString]
   [:coverage/thresholds CoverageThresholds]
   [:coverage/runner [:vector {:min 1} NonBlankString]]])

(def CoveragePlan
  "Pure, executable description of one coverage run.

   `:plan/compile-argv` is empty when the project opts out of compiling first
   (an already-built bundle, or a toolchain driven some other way)."
  [:map {:closed true}
   [:plan/compile-argv [:vector NonBlankString]]
   [:plan/argv [:vector {:min 1} NonBlankString]]
   [:plan/cwd NonBlankString]
   [:plan/summary-path NonBlankString]])

(def CoverageReport
  [:map {:closed true}
   [:coverage/build BuildId]
   [:coverage/rows [:vector CoverageRow]]
   [:coverage/totals CoverageTotals]
   [:coverage/deltas {:optional true} [:vector CoverageDelta]]
   [:coverage/verdict CoverageVerdict]
   [:coverage/report-dir NonBlankString]])

;; =============================================================================
;; Manifest — normalized
;; =============================================================================

(def Manifest
  "Normalized project config — every default resolved.

   Defined after the section schemas it composes, so the coverage section can be
   a typed entry rather than an opaque map."
  [:map {:closed true}
   [:manifest/root NonBlankString]
   [:manifest/sources {:optional true} [:vector NonBlankString]]
   [:manifest/shadow ShadowConfig]
   [:manifest/builds [:map-of BuildId BuildSpec]]
   [:manifest/e2e E2eConfig]
   [:manifest/watch WatchPolicy]
   [:manifest/coverage {:optional true} CoverageConfig]])
