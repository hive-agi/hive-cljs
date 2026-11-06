(ns hive-cljs.locator
  "PROMOTE layer — what a locator predicate MEANS, as pure functions over facts.

   A scenario asks about an element as data — `[:wait-for sel {:state :enabled}]`,
   `[:expect sel {:value :non-empty}]` — instead of authoring JavaScript. The
   browser adapter only GATHERS the facts `facts-needed` names (match count,
   visibility, value, text …) through its own locator API; whether they satisfy
   the predicate is decided here, so the decision is testable without a browser."
  (:require [clojure.string :as str]
            [hive-cljs.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def Facts
  "What the adapter observed about a selector: how many elements match, and —
   when at least one does — the facts of the FIRST match the predicate needs."
  [:map
   [:count [:int {:min 0 :max 50}]]
   [:visible {:optional true} :boolean]
   [:enabled {:optional true} :boolean]
   [:checked {:optional true} :boolean]
   [:editable {:optional true} :boolean]
   [:value {:optional true} [:maybe :string]]
   [:text {:optional true} [:maybe :string]]])

(def Verdict
  [:map {:closed true}
   [:held? :boolean]
   [:detail :string]])

(def hint
  "The shape a malformed locator predicate should have taken."
  "{:state :visible|:hidden|:attached|:detached|:enabled|:disabled|:checked|:unchecked|:editable :value \"x\"|:non-empty|:empty :count n :text \"substring\"|#\"regex\" :absent true}")

(def ^:private state-facts
  {:visible :visible :hidden :visible :enabled :enabled :disabled :enabled
   :checked :checked :unchecked :checked :editable :editable})

(defn facts-needed
  "The facts `pred` reads, so the adapter asks the page for nothing more."
  [pred]
  (cond-> #{:count}
    (state-facts (:state pred)) (conj (state-facts (:state pred)))
    (contains? pred :value)     (conj :value)
    (contains? pred :text)      (conj :text)))

(defn- text-matches?
  [expected actual]
  (let [actual (str actual)]
    (if (string? expected)
      (str/includes? actual expected)
      #?(:clj  (.find (.matcher ^java.util.regex.Pattern expected actual))
         :cljs (.test expected actual)))))

(defn- state-holds?
  [state {:keys [count] :as facts}]
  (let [present? (pos? count)]
    (case state
      :attached  present?
      :detached  (not present?)
      :visible   (and present? (true? (:visible facts)))
      :hidden    (or (not present?) (false? (:visible facts)))
      :enabled   (and present? (true? (:enabled facts)))
      :disabled  (and present? (false? (:enabled facts)))
      :checked   (and present? (true? (:checked facts)))
      :unchecked (and present? (false? (:checked facts)))
      :editable  (and present? (true? (:editable facts))))))

(defn- value-holds?
  [expected {:keys [count value]}]
  (and (pos? count)
       (case expected
         :non-empty (not (str/blank? value))
         :empty     (= "" (str value))
         (= (str expected) (str value)))))

(defn- clause
  "[holds? description] for one key of a predicate."
  [k v facts]
  (case k
    :absent [(= (boolean v) (zero? (:count facts)))
             (str (if v "absent" "present") ", matched " (:count facts))]
    :count  [(= v (:count facts)) (str "count " v ", matched " (:count facts))]
    :state  [(boolean (state-holds? v facts))
             (str "state " (name v) ", saw " (pr-str (dissoc facts :value :text)))]
    :value  [(boolean (value-holds? v facts)) (str "value " (pr-str v) ", saw " (pr-str (:value facts)))]
    :text   [(boolean (and (pos? (:count facts)) (text-matches? v (:text facts))))
             (str "text " (pr-str (str v)) ", saw " (pr-str (:text facts)))]))

(defn judge
  "Does `facts` satisfy every clause of `pred`? The detail names each clause
   that failed with what was observed, or every clause when all held."
  [pred facts]
  (let [results (map (fn [[k v]] (clause k v facts)) (sort-by (comp str key) pred))
        failed  (remove first results)]
    {:held?  (empty? failed)
     :detail (str/join "; " (map second (if (seq failed) failed results)))}))

(defn branch-ops
  "Ops a compiled `:when` op runs, given how many elements its condition's
   selector matched: the body when the condition holds, else none."
  [op match-count]
  (let [[[test _] body] (:op/args op)
        holds?          (if (= :exists test) (pos? match-count) (zero? match-count))]
    (if holds? (vec body) [])))

(defn pick-option
  "The option value a `:select` step chooses among `values`, the select's
   option values in document order: `:first-non-empty` picks the first
   non-blank one (nil when there is none); any other choice is itself."
  [choice values]
  (if (= :first-non-empty choice)
    (first (remove str/blank? values))
    (str choice)))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> facts-needed [:=> [:cat s/LocatorPredicate] [:set :keyword]])
(m/=> judge [:=> [:cat s/LocatorPredicate Facts] Verdict])
(m/=> pick-option [:=> [:cat :any [:sequential [:maybe :string]]] [:maybe :string]])
