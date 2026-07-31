(ns hive-cljs.test
  "Generate ordinary `clojure.test` vars from the scenarios a project declares.

   One `deftest` per scenario, scenario tags as var metadata, and the teardown a
   live relay connection requires."
  (:require [clojure.string :as str]
            [clojure.test]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.schema :as s]
            [hive-cljs.test-api :as test-api]
            [hive-dsl.result :as r]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn cwd
  "The JVM working directory — the default manifest root."
  []
  (System/getProperty "user.dir"))

(def ^:private unsafe-in-symbol #"[^a-zA-Z0-9*+!_?<>=-]")

(defn test-sym
  "Symbol naming the generated test var for `scenario-id`.
   Both halves of a namespaced id survive: `:checkout/happy` → `checkout-happy`."
  [scenario-id]
  (symbol (str/replace (subs (str scenario-id) 1) unsafe-in-symbol "-")))

(defn duplicate-test-syms
  "Test symbols claimed by more than one scenario, sorted."
  [scenarios]
  (->> scenarios
       (map (comp test-sym :id))
       frequencies
       (keep (fn [[sym n]] (when (< 1 n) sym)))
       sort
       vec))

(defn scenario-meta
  "Var metadata for a generated test: each scenario tag as a true flag, plus the
   scenario id and its `:doc`."
  [{:keys [id tags doc]}]
  (cond-> (assoc (zipmap tags (repeat true)) :hive.cljs/scenario id)
    doc (assoc :doc doc)))

(defn teardown
  "`:once` fixture releasing every open project once the namespace is done.
   A live relay connection keeps the JVM from exiting."
  [f]
  (try (f) (finally (test-api/close-all!))))

(defn plan-scenarios
  "Scenarios to generate for `root`, filtered by `tags` (empty selects all).

   Returns `[resolved-root scenarios]`. Throws when no manifest resolves from
   `root`, or when two scenario ids claim one test symbol."
  [root tags]
  (let [res (boundary/load-manifest root)]
    (when (r/err? res)
      (throw (ex-info (str "hive-cljs: no manifest resolved from " root) res)))
    (let [m         (:ok res)
          scenarios (manifest/scenarios-by-tag m (or tags []))
          dups      (duplicate-test-syms scenarios)]
      (when (seq dups)
        (throw (ex-info (str "hive-cljs: scenario ids collide as test names: "
                             (str/join ", " dups))
                        {:error :test/duplicate-test-sym :syms dups})))
      [(:manifest/root m) scenarios])))

(defn- scenario-form
  [root scenario]
  (let [id (:id scenario)]
    `(clojure.test/deftest ~(with-meta (test-sym id) (scenario-meta scenario))
       (let [res# (test-api/run-scenario! ~root ~id)]
         (clojure.test/is (test-api/passed? res#) (test-api/explain res#))))))

(defn- empty-form
  [root tags]
  (let [msg (str "hive-cljs: no scenario declared at " root
                 (when (seq tags) (str " carries any of " (pr-str (vec tags))))
                 " — a generated suite with nothing in it must not read green")]
    `(clojure.test/deftest ~'no-scenarios-declared
       (clojure.test/is false ~msg))))

(defmacro defscenarios
  "Emit one `clojure.test/deftest` per scenario the project declares.

   `opts` is read at macroexpansion:
     :root      manifest root (default: the JVM cwd); a non-string form is evaluated
     :tags      generate only scenarios carrying any of these tags
     :fixture?  also register the `:once` teardown fixture (default true)

   Scenario tags become var metadata. A selection that matches nothing emits one
   failing test rather than no tests."
  ([] `(defscenarios {}))
  ([opts]
   (let [{:keys [tags fixture?] :or {fixture? true}} opts
         root (let [rf (:root opts)]
                (cond (string? rf) rf
                      (nil? rf)    (cwd)
                      :else        (eval rf)))
         tags (if (or (nil? tags) (coll? tags)) tags (eval tags))
         [resolved scenarios] (plan-scenarios root tags)]
     `(do
        ~@(when fixture? [`(clojure.test/use-fixtures :once teardown)])
        ~@(if (seq scenarios)
            (map #(scenario-form resolved %) scenarios)
            [(empty-form resolved tags)])))))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> test-sym [:=> [:cat s/ScenarioId] :symbol])
(m/=> duplicate-test-syms [:=> [:cat [:sequential [:map [:id s/ScenarioId]]]]
                           [:vector :symbol]])
(m/=> scenario-meta [:=> [:cat s/Scenario] [:map-of :keyword :any]])
