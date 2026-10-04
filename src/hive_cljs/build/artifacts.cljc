(ns hive-cljs.build.artifacts
  "PIPELINE layer — decide when a build nobody here ran has emitted new output.

   An external `vite --watch`, `elm-live` or `tsc --watch` compiles without
   telling anyone. What it cannot hide is its OUTPUT: the bundle on disk is the
   artifact under test, so a change to it is a truthful witness that a compile
   finished — no integration with the tool is needed.

   Pure: the boundary samples the filesystem and the clock through ports and
   hands the stamps here; this namespace owns only the decision.

   The rule, per build:
   - the first observation is a BASELINE, never an event — whatever was on disk
     when watching began is not a compile we saw happen;
   - a changed fingerprint must hold still for the quiet window before it is
     reported, because a bundler writes many files over time and a verdict
     taken between two writes would describe a half-written bundle;
   - with a `:ready-marker` the tool itself says when it is done, so the
     marker changing is reported at once and nothing else is looked at;
   - every artifact gone is a build in progress (vite empties `outDir` first),
     never an event."
  (:require [hive-cljs.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def default-quiet-ms
  "How long changed output must hold still before it counts as one compile."
  300)

(def initial-state
  "Nothing observed yet."
  {:primed? false :baseline {} :pending nil :since nil})

;; =============================================================================
;; Fingerprints
;; =============================================================================

(defn watched-paths
  "Paths that witness a compile of the build `spec` — the ready-marker alone
   when one is declared, else every declared output."
  [spec]
  (if-let [marker (:ready-marker spec)]
    [marker]
    (vec (:outputs spec))))

(defn identity-of
  "What makes one file's state THE state: its content when the boundary could
   hash it, else its size and modification time."
  [stamp]
  (if (contains? stamp :hash)
    [(:size stamp) (:hash stamp)]
    [(:size stamp) (:modified stamp)]))

(defn fingerprint
  "`{path stamp}` → a comparable `{path identity}`."
  [stamps]
  (into (sorted-map)
        (map (fn [[p stamp]] [p (identity-of stamp)]))
        stamps))

(defn changed-paths
  "Paths whose identity differs between two fingerprints, appearing and
   disappearing ones included, sorted."
  [before after]
  (->> (concat (keys before) (keys after))
       distinct
       (remove #(= (get before %) (get after %)))
       sort
       vec))

;; =============================================================================
;; The decision
;; =============================================================================

(defn- settle
  [state fp]
  (assoc state :baseline fp :pending nil :since nil))

(defn step
  "Fold one observation into the build's watch state.

   `spec` is the build's artifact spec, `now` the clock reading and `stamps` the
   `{path {:modified :size :hash?}}` facts sampled for `(watched-paths spec)`.
   Returns `{:state state' :changed [path ...]-or-nil}`; `:changed` is set
   exactly when a compile is to be reported."
  [state spec now stamps]
  (let [fp    (fingerprint stamps)
        quiet (or (:quiet-ms spec) default-quiet-ms)]
    (cond
      (not (:primed? state))
      {:state (assoc (settle state fp) :primed? true) :changed nil}

      (= fp (:baseline state))
      {:state (assoc state :pending nil :since nil) :changed nil}

      (empty? fp)
      {:state (assoc state :pending nil :since nil) :changed nil}

      (:ready-marker spec)
      {:state (settle state fp) :changed (changed-paths (:baseline state) fp)}

      (not= fp (:pending state))
      {:state (assoc state :pending fp :since now) :changed nil}

      (>= (- now (:since state)) quiet)
      {:state (settle state fp) :changed (changed-paths (:baseline state) fp)}

      :else
      {:state state :changed nil})))

(defn rebase
  "Adopt `stamps` as the build's baseline without reporting anything — for
   output hive-cljs wrote itself, whose compile was already reported by the
   tool that ran it."
  [state stamps]
  (assoc (settle state (fingerprint stamps)) :primed? true))

(defn status-of
  "The `BuildStatus` an observed compile reports.

   `:completed` and nothing stronger: fresh output is what a finished compile
   leaves behind. A failing external compile usually writes nothing, so it is
   not seen at all rather than seen as green."
  [build-id changed]
  {:build/id       build-id
   :build/state    :completed
   :build/warnings []
   :build/errors   []
   :build/files    (mapv (fn [p] {:file/path p}) changed)})

(defn event-of
  "The `BuildEvent` an observed compile emits — the same shape a compile driven
   through hive-cljs emits, so the watcher cannot tell them apart."
  [build-id changed now]
  {:event/build  build-id
   :event/status (status-of build-id changed)
   :event/at     now})

;; =============================================================================
;; Contracts
;; =============================================================================

(def ^:private Stamp
  [:map
   [:modified s/Millis]
   [:size [:int {:min 0}]]
   [:hash {:optional true} :any]])

(def ^:private State
  [:map
   [:primed? :boolean]
   [:baseline [:map-of :string :any]]
   [:pending [:maybe [:map-of :string :any]]]
   [:since [:maybe s/Millis]]])

(m/=> watched-paths [:=> [:cat s/ArtifactSpec] [:vector s/NonBlankString]])
(m/=> changed-paths [:=> [:cat [:map-of :string :any] [:map-of :string :any]]
                     [:vector :string]])
(m/=> step [:=> [:cat State s/ArtifactSpec s/Millis [:map-of :string Stamp]]
            [:map [:state State] [:changed [:maybe [:vector :string]]]]])
(m/=> event-of [:=> [:cat s/BuildId [:vector s/NonBlankString] s/Millis] s/BuildEvent])
