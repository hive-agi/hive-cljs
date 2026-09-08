(ns hive-cljs.derive
  "Forward-chaining derivation over a fact base, with provenance.

   A small logic engine: facts are tuples, a rule is a conjunction of tuple
   PATTERNS plus a function from the bindings they matched to the facts that
   follow, and `run` drives the rules to a fixpoint. Every derived fact records
   which rule produced it and which premises it consumed, so a consumer can ask
   WHY a value came out the way it did rather than trusting it.

   It exists because the interesting questions about a page's layout are
   relational, not procedural: `a heading is illegible at this viewport BECAUSE
   the render scale is 0.37 AND the declared size is 40px AND the legibility
   floor is 16px`. Written as rules that is one clause per fact of the matter;
   written as functions it is a nest of ifs whose reasons are gone by the time
   the answer comes out.

   Deliberately not core.logic or a datalog store. The output of a derivation
   here is a build artifact -- generated CSS, a token file -- so the engine has
   to run wherever the build runs, which for a ClojureScript project means
   every host its .cljc loads on. That rules out a JVM-only dependency, and the
   fragment of logic programming this needs is conjunctive matching over ground
   tuples with no negation and no recursion through function symbols. Anything
   larger belongs in a real reasoner."
  (:require [clojure.string :as str]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Patterns and unification
;; =============================================================================

(defn variable?
  "Whether `x` is a logic variable: a symbol spelled `?something`."
  [x]
  (and (symbol? x) (str/starts-with? (name x) "?")))

(defn wildcard?
  "Whether `x` matches anything and binds nothing."
  [x]
  (= '_ x))

(defn unify
  "Match one `pattern` against one `fact` under `bindings`.

   => extended bindings, or nil when they do not match. A variable already
   bound must match the same value again, which is what makes a conjunction of
   patterns a JOIN rather than a sequence of independent lookups."
  [pattern fact bindings]
  (when (and (sequential? fact) (= (count pattern) (count fact)))
    (reduce (fn [acc [p f]]
              (cond
                (nil? acc) (reduced nil)
                (wildcard? p) acc
                (variable? p) (if (contains? acc p)
                                (if (= (get acc p) f) acc (reduced nil))
                                (assoc acc p f))
                (= p f) acc
                :else (reduced nil)))
            bindings
            (map vector pattern fact))))

(defn matches
  "Every way `pattern` matches a fact in `facts`, as [bindings fact] pairs."
  [pattern facts bindings]
  (keep (fn [fact]
          (when-let [b (unify pattern fact bindings)]
            [b fact]))
        facts))

(defn solve
  "Every way the conjunction `patterns` is satisfied by `facts`.

   => a seq of {:bindings map :premises [fact ...]}. Patterns are matched left
   to right, so ordering them from most selective to least is what keeps a
   large fact base cheap -- the engine does not reorder them for you."
  ([patterns facts] (solve patterns facts {}))
  ([patterns facts bindings]
   (reduce (fn [solutions pattern]
             (mapcat (fn [{:keys [bindings premises]}]
                       (map (fn [[b fact]]
                              {:bindings b :premises (conj premises fact)})
                            (matches pattern facts bindings)))
                     solutions))
           [{:bindings bindings :premises []}]
           patterns)))

;; =============================================================================
;; Rules
;; =============================================================================

(defn rule
  "A derivation rule.

   `:when`  a vector of tuple patterns, joined on their shared variables
   `:guard` optional (fn [bindings] boolean) -- an arithmetic side condition,
            which is the one thing tuple matching cannot express
   `:then`  (fn [bindings] fact-or-facts) -- nil derives nothing, which is how
            a rule declines a match its guard could not have expressed

   `:then` must be a FUNCTION OF ITS BINDINGS ALONE. A rule that reads anything
   else is not monotone, and the fixpoint below assumes monotonicity: it stops
   when an iteration adds no fact, which is only sound if a rule cannot retract
   or change one."
  [id doc when-patterns then & [guard]]
  (cond-> {:rule/id id :rule/doc doc :rule/when (vec when-patterns) :rule/then then}
    guard (assoc :rule/guard guard)))

(defn- derived-facts
  "The facts `rule` produces from `facts`, as [fact provenance] pairs."
  [{:rule/keys [id when guard then]} facts]
  (for [{:keys [bindings premises]} (solve when facts)
        :when (or (nil? guard) (guard bindings))
        :let [produced (then bindings)]
        fact (cond (nil? produced) []
                   (and (sequential? produced) (sequential? (first produced))) produced
                   :else [produced])
        :when (some? fact)]
    [fact {:rule/id id :premises premises :bindings bindings}]))

;; =============================================================================
;; Fixpoint
;; =============================================================================

(def max-iterations
  "How many rounds `run` will make before declaring the rules non-terminating.

   A guard, not a tuning knob. A monotone rule set over a finite fact base
   always converges; hitting this means a rule invents a new value every round
   (an unbounded arithmetic derivation is the usual culprit), and stopping
   loudly beats looping."
  64)

(defn run
  "Drive `rules` over `facts` to a fixpoint.

   => {:facts set, :derived set, :why {fact provenance}, :iterations n}

   Semi-naive: each round only re-derives from what the previous round added,
   so a rule whose premises did not change is not retried. The first
   derivation of a fact keeps its provenance -- a second derivation by another
   rule does not overwrite the reason the fact is already there."
  ([rules facts] (run rules facts {}))
  ([rules facts {:keys [max-rounds] :or {max-rounds max-iterations}}]
   (loop [known (set facts)
          fresh (set facts)
          why {}
          round 0]
     (if (or (empty? fresh) (>= round max-rounds))
       (do
         (when (and (seq fresh) (>= round max-rounds))
           (throw (ex-info (str "hive-cljs.derive: no fixpoint after " max-rounds
                                " rounds; a rule is deriving new values forever")
                           {:error :derive/no-fixpoint
                            :rounds max-rounds
                            :last-round (vec (take 10 fresh))})))
         {:facts known
          :derived (into #{} (remove (set facts)) known)
          :why why
          :iterations round})
       (let [produced (mapcat #(derived-facts % known) rules)
             new-pairs (remove (fn [[fact _]] (contains? known fact)) produced)
             ;; A rule may derive the same fact twice in one round; the first
             ;; reason wins, so the round is order-stable.
             added (reduce (fn [acc [fact prov]]
                             (if (contains? acc fact) acc (assoc acc fact prov)))
                           {}
                           new-pairs)]
         (recur (into known (keys added))
                (set (keys added))
                (merge why added)
                (inc round)))))))

(defn run-strata
  "Run several rule sets in order, asserting derived facts between them.

   A stratum is `{:rules [...] :assert (fn [fixpoint] facts-or-why)}`. Each one
   sees every fact the strata before it produced, and its `:assert` may compute
   what the rules themselves cannot. It returns either a collection of facts,
   or a MAP of fact -> the premises it was computed from -- and the map form is
   the one to prefer, because an aggregation is otherwise where a provenance
   chain goes dead: everything downstream of it can say it came from the
   aggregate, and nothing can say what the aggregate came from.

   That is what AGGREGATION needs. `the largest scale any token demands` is a
   fact about a SET of facts, and a monotone rule over ground tuples cannot
   state it -- it would have to say `and no larger one exists`, which is
   negation, and negation inside a fixpoint is how a rule set stops being
   monotone and starts depending on the order it ran in. Stratifying puts the
   aggregation between fixpoints instead, where it is a pure function of a
   finished one.

   => the final fixpoint, whose `:why` carries every stratum's provenance.
   Facts an `:assert` introduces are asserted, and `why` correctly says so."
  [strata facts]
  (let [asserted (set facts)
        final (reduce (fn [{:keys [facts why]} {:keys [rules assert]}]
                        (let [fx (run rules facts)
                              produced (when assert (assert fx))
                              extra (if (map? produced) (keys produced) produced)
                              extra-why (when (map? produced)
                                          (into {}
                                                (map (fn [[fact premises]]
                                                       [fact {:rule/id :stratum/aggregate
                                                              :premises (vec premises)}]))
                                                produced))]
                          {:facts (into (:facts fx) extra)
                           :why (merge why (:why fx) extra-why)}))
                      {:facts asserted :why {}}
                      strata)]
    (assoc final
           :derived (into #{} (remove asserted) (:facts final))
           :iterations (count strata))))

;; =============================================================================
;; Reading the answer
;; =============================================================================

(defn query
  "Every binding map satisfying `patterns` against a fixpoint or a fact set."
  [fixpoint-or-facts patterns]
  (let [facts (if (map? fixpoint-or-facts) (:facts fixpoint-or-facts) fixpoint-or-facts)]
    (mapv :bindings (solve patterns facts))))

(defn facts-matching
  "Every fact in a fixpoint matching one pattern."
  [fixpoint-or-facts pattern]
  (let [facts (if (map? fixpoint-or-facts) (:facts fixpoint-or-facts) fixpoint-or-facts)]
    (into #{} (comp (map second) (map identity)) (matches pattern facts {}))))

(defn why
  "How `fact` came to be: the rule that derived it and the premises it used.

   => nil for a fact that was ASSERTED rather than derived, which is the
   correct answer -- an asserted fact has no derivation, and saying so is not
   the same as failing to find one."
  [{:keys [why]} fact]
  (get why fact))

(defn trace
  "`fact` back to the asserted facts it rests on, depth first.

   => a seq of {:fact :rule/id :premises} for each derived step. What a
   generated artifact quotes when it has to say why a value is what it is."
  [fixpoint fact]
  (letfn [(walk [f seen]
            (if (or (contains? seen f) (nil? (get (:why fixpoint) f)))
              []
              (let [{:rule/keys [id] :keys [premises]} (get (:why fixpoint) f)]
                (into [{:fact f :rule/id id :premises premises}]
                      (mapcat #(walk % (conj seen f)) premises)))))]
    (walk fact #{})))

(defn explain
  "One derived fact as prose: what it is, which rule said so, and from what."
  [fixpoint fact]
  (if-let [{:rule/keys [id] :keys [premises]} (get (:why fixpoint) fact)]
    (str (pr-str fact) " by " id " from " (str/join ", " (map pr-str premises)))
    (str (pr-str fact) " (asserted)")))
