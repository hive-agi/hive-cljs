(ns hive-cljs.fit
  "PURE layer for fit: does a laid-out thing stay inside the box it was given.

   A page is authored against a box -- a slide, a card, a print page, a fixed
   viewport -- and content that overflows that box is lost, not scrolled to.
   This layer takes `schema/FitMeasurement`s from whatever produced them and
   turns them into a `schema/FitReport`. No IO, and no opinion about how a
   subject was measured: reading a laid-out DOM and estimating from a document
   value are both `ports/IFitSource`.

   Two rungs, and they are not peers. A MEASURED rung read a layout engine, so
   a finding at that rung is a fact and may fail a build. An ESTIMATED rung ran
   a box model, so it states an uncertainty with every extent, and this layer
   refuses to fail a build on a finding that falls inside it. That is the whole
   reason an estimate is allowed near a gate at all: it warns early without
   ever claiming what only a browser can settle.

   Coverage is the other half, and it is the half a hand-written suite cannot
   enforce on itself. `coverage` compares what a source REACHED against a
   universe the caller must derive independently of the source -- a document
   model's own registry of content kinds, never the estimator's table of arms.
   An empty universe is `:unavailable`, never a pass."
  (:require [clojure.string :as str]
            [hive-cljs.ports :as ports]
            [hive-cljs.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Policy
;; =============================================================================

(def default-policy
  "How much overflow to ignore, how far a subject may be shrunk, and whether a
   warning is a failure.

   `:fit/tolerance` exists because scroll sizes are integers rounded UP from
   fractional layout: an element landing on a half pixel reports a pixel of
   overflow it does not have.

   `:fit/min-scale` is where shrinking stops being a remedy. Below it the
   subject fits and nobody can read it, which is a worse outcome than the
   build failing.

   `:fit/strict?` promotes every warning to a failure, which is what a
   consumer sets once its estimator has been calibrated against a measured
   rung and it is prepared to gate on it."
  #:fit{:tolerance 2.0
        :min-scale 0.6
        :strict? false})

(defn policy-of
  "Caller policy layered over `default-policy`."
  [policy]
  (merge default-policy policy))

;; =============================================================================
;; Findings
;; =============================================================================

(defn- px [x] (double (or x 0)))

(defn- excess
  "A dimension finding, or nil when the excess is within tolerance."
  [kind actual limit tolerance]
  (let [by (- (px actual) (px limit))]
    (when (> by tolerance)
      #:fit{:kind kind :by by})))

(defn- clip-findings
  "Descendants whose content is cut off by their own box.

   A subject laid out in a fixed box is not scrollable by its audience, so a
   clipped element is lost content rather than content further down."
  [clipped tolerance]
  (into []
        (comp (filter (fn [c]
                        (or (> (px (:fit/over-w c)) tolerance)
                            (> (px (:fit/over-h c)) tolerance))))
              (map (fn [c] #:fit{:kind :clipped :clip c
                                 :by (max (px (:fit/over-w c))
                                          (px (:fit/over-h c)))})))
        clipped))

(defn findings
  "Measurement -> every way the subject fails to fit. Empty when it fits."
  ([measurement] (findings measurement nil))
  ([{:fit/keys [box extent clipped]} policy]
   (let [tolerance (:fit/tolerance (policy-of policy))]
     (into (into [] (keep identity)
                 [(excess :taller-than-box (:h extent) (:h box) tolerance)
                  (excess :wider-than-box (:w extent) (:w box) tolerance)])
           (clip-findings clipped tolerance)))))

(defn fit-scale
  "Measurement -> the uniform scale that brings the subject inside its box, or
   1 when it already fits.

   Exact rather than searched: a uniform scale never reflows, so a subject's
   painted size is its laid-out size times this factor and one division answers
   it."
  [{:fit/keys [box extent]}]
  (min 1.0
       (/ (px (:h box)) (max 1.0 (px (:h extent))))
       (/ (px (:w box)) (max 1.0 (px (:w extent))))))

;; =============================================================================
;; Judgement
;; =============================================================================

(defn state-of
  "Measurement + its findings -> a `schema/FitState`."
  [{:fit/keys [policy modelled?]} fs scale min-scale]
  (let [clipped? (boolean (some (comp #{:clipped} :fit/kind) fs))]
    (cond
      (false? modelled?) :unknown
      (empty? fs) (if policy :stale-waiver :ok)
      (= :allow policy) :waived
      (not= :shrink policy) :overflows
      ;; Scaling a subject scales its clipped descendants with it, so the ratio
      ;; that cuts content off survives at every scale.
      clipped? :overflows
      (< scale min-scale) :too-small
      :else :shrunk)))

(defn- raw-severity
  "State + rung + margin -> what a gate is ENTITLED to do about it, before
   `:fit/strict?` is applied.

   The rung is the whole of the rule. A finding smaller than the source's own
   stated uncertainty is not evidence of anything, so an estimated rung warns
   there and fails only outside the band; a measured rung has no band."
  [state rung fs margin]
  (cond
    (contains? #{:ok :waived :shrunk} state) :pass
    (contains? #{:unknown :stale-waiver} state) :warn
    (= :measured rung) :fail
    (some #(> (px (:fit/by %)) (px margin)) fs) :fail
    :else :warn))

(defn evaluate
  "One measurement judged. => `schema/FitVerdict`."
  ([measurement] (evaluate measurement nil))
  ([measurement policy]
   (let [{:fit/keys [min-scale strict?]} (policy-of policy)
         fs (findings measurement policy)
         scale (fit-scale measurement)
         rung (:fit/rung measurement)
         margin (px (:fit/margin measurement))
         state (state-of measurement fs scale min-scale)
         severity (let [sev (raw-severity state rung fs margin)]
                    (if (and strict? (= :warn sev)) :fail sev))]
     (cond-> #:fit{:id (:fit/id measurement)
                   :rung rung
                   :state state
                   :severity severity
                   :findings fs
                   :margin margin}
       (contains? #{:shrunk :too-small} state) (assoc :fit/scale scale)))))


(defn report
  "Every measurement judged. => `schema/FitReport`.

   A report over no measurements is `:unavailable`. A gate that judged nothing
   must never read as a pass -- an empty source is the failure mode that looks
   exactly like success."
  ([measurements] (report measurements nil))
  ([measurements policy]
   (let [verdicts (mapv #(evaluate % policy) measurements)
         by-sev (fn [sev] (filterv #(= sev (:fit/severity %)) verdicts))
         failures (by-sev :fail)
         warnings (by-sev :warn)]
     #:fit{:rung (or (:fit/rung (first measurements)) :estimated)
           :verdicts verdicts
           :failures failures
           :warnings warnings
           :tally (frequencies (map :fit/state verdicts))
           :state (cond
                    (empty? verdicts) :unavailable
                    (seq failures) :fail
                    (seq warnings) :warn
                    :else :pass)})))

(defn mixed-rung?
  "Whether `measurements` were not all taken at the same rung.

   A report carries ONE rung, so a source that mixes them is reporting under a
   label that is true of only part of it."
  [measurements]
  (> (count (into #{} (map :fit/rung) measurements)) 1))

;; =============================================================================
;; Coverage
;; =============================================================================

(defn reached
  "The kinds a set of measurements actually drew on."
  [measurements]
  (into #{} (mapcat :fit/kinds) measurements))

(defn coverage
  "Universe + reached (+ exemptions) -> a `schema/FitCoverage`.

   `universe` must come from somewhere independent of what produced `reached`.
   Derive it from the property being checked and the assertion becomes
   `X is a subset of X`, which holds for every X -- including the one missing
   the members you built the gate for.

   `exempt` is {kind reason-string}: kinds nothing measures ON PURPOSE. A
   reason is required rather than a bare set, because an exemption without one
   is a skip list, and a skip list outlives whatever justified it. An exemption
   for a kind that IS now reached is reported in its own right, the same way a
   waiver for an overflow that no longer happens is."
  ([universe reached-kinds] (coverage universe reached-kinds nil))
  ([universe reached-kinds exempt]
   (let [universe (set universe)
         reached-kinds (set reached-kinds)
         excused (set (keys exempt))
         missing (vec (sort (remove (some-fn reached-kinds excused) universe)))
         stale (vec (sort (filter reached-kinds excused)))]
     #:fit{:universe universe
           :reached reached-kinds
           :missing missing
           :exempt (or exempt {})
           :stale-exemptions stale
           :state (cond
                    (empty? universe) :unavailable
                    (seq missing) :fail
                    (seq stale) :fail
                    :else :pass)})))

;; =============================================================================
;; Reporting
;; =============================================================================

(defn- percent
  "Scale -> a whole-percent string. Nobody acts on a third decimal place."
  [scale]
  (str (long (Math/round (* 100.0 (px scale)))) "%"))

(defn- round [x] (long (Math/round (px x))))

(defn describe-finding
  "One finding as a phrase."
  [{:fit/keys [kind by clip]}]
  (case kind
    :taller-than-box (str "is " (round by) "px taller than its box")
    :wider-than-box (str "is " (round by) "px wider than its box")
    :clipped (str "clips <" (or (:fit/tag clip) "?")
                  (when (not (str/blank? (:fit/class clip)))
                    (str " class=\"" (:fit/class clip) "\""))
                  ">"
                  (when (pos? (px (:fit/over-w clip)))
                    (str ", " (round (:fit/over-w clip)) "px of it horizontally"))
                  (when (pos? (px (:fit/over-h clip)))
                    (str ", " (round (:fit/over-h clip)) "px of it vertically")))
    (str "reports " (name kind)
         (when by (str ", by " (round by) "px")))))

(defn describe
  "One verdict as a line, or nil when there is nothing to say about it."
  [{:fit/keys [id state findings scale severity margin rung]} policy]
  (let [min-scale (:fit/min-scale (policy-of policy))]
    (when-not (= :pass severity)
      (str "  " (name severity) "  " id " -- "
           (case state
             :unknown "could not be modelled by this source, so it is unchecked"
             :stale-waiver "declares an overflow policy but now fits; drop it"
             :too-small (str "would have to shrink to " (percent scale)
                             " to fit, under the readable floor of "
                             (percent min-scale)
                             "; cut content rather than shrink it")
             (str/join "; " (map describe-finding findings)))
           (when (and (= :estimated rung) (= :warn severity) (pos? margin))
             (str " (estimated, within the source's own "
                  (round margin) "px margin)"))))))

(defn explain
  "Report -> a human-readable account of everything that is not fine, or nil
   when every subject is."
  ([report] (explain report nil))
  ([{:fit/keys [verdicts state rung]} policy]
   (cond
     (= :unavailable state)
     "no subject was measured at all, so nothing was proven"

     :else
     (let [lines (keep #(describe % policy) verdicts)]
       (when (seq lines)
         (str (count lines) " of " (count verdicts)
              " subjects have something to answer for, at the "
              (name rung) " rung:\n"
              (str/join "\n" lines)))))))

(defn explain-coverage
  "Coverage -> why it is not a pass, or nil when it is."
  [{:fit/keys [universe missing state stale-exemptions]}]
  (case state
    :pass nil
    :unavailable (str "the coverage universe is EMPTY, so this gate asserts "
                      "nothing; derive the universe from the document model, "
                      "not from what the source happens to reach")
    (str/join
     "\n"
     (cond-> []
       (seq missing)
       (conj (str (count missing) " of " (count universe)
                  " kinds the document model admits were never reached by any "
                  "measured subject: " (str/join ", " (map name missing))
                  ". Exercise them in a document the gate measures, or declare "
                  "an exemption WITH ITS REASON."))

       (seq stale-exemptions)
       (conj (str (count stale-exemptions) " exemption(s) are now reached and "
                  "should be dropped: " (str/join ", " (map name stale-exemptions))))))))

;; =============================================================================
;; A source over literal measurements
;; =============================================================================

(defn source
  "An `ports/IFitSource` over measurements already in hand.

   What a consumer's own source degenerates to once it has done its work, and
   what a test of this layer injects."
  [rung measurements]
  (reify ports/IFitSource
    (fit-rung [_] rung)
    (fit-measurements [_] (vec measurements))))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> policy-of [:=> [:cat [:maybe [:map-of :keyword :any]]] [:map-of :keyword :any]])
(m/=> findings [:function
                [:=> [:cat s/FitMeasurement] [:vector s/FitFinding]]
                [:=> [:cat s/FitMeasurement :any] [:vector s/FitFinding]]])
(m/=> fit-scale [:=> [:cat s/FitMeasurement] :double])
(m/=> evaluate [:function
                [:=> [:cat s/FitMeasurement] s/FitVerdict]
                [:=> [:cat s/FitMeasurement :any] s/FitVerdict]])
(m/=> report [:function
              [:=> [:cat [:sequential s/FitMeasurement]] s/FitReport]
              [:=> [:cat [:sequential s/FitMeasurement] :any] s/FitReport]])
(m/=> mixed-rung? [:=> [:cat [:sequential s/FitMeasurement]] :boolean])
(m/=> reached [:=> [:cat [:sequential s/FitMeasurement]] [:set :keyword]])
(def ^:private KeywordSet
  [:maybe [:or [:set :keyword] [:sequential :keyword]]])

(m/=> coverage [:function
                [:=> [:cat KeywordSet KeywordSet] s/FitCoverage]
                [:=> [:cat KeywordSet KeywordSet [:maybe [:map-of :keyword :string]]]
                 s/FitCoverage]])
(m/=> explain-coverage [:=> [:cat s/FitCoverage] [:maybe :string]])
