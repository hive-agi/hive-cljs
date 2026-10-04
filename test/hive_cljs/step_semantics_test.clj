(ns hive-cljs.step-semantics-test
  "A step's semantics — does its value assert, does it poll, does it only
   observe — travel ON the compiled op, stamped by the rule that defines its
   kind. The boundary reads the op, so a third-party rule gets the right
   behaviour with no edit to hive-cljs. The old kind sets stay as the fallback
   for an op that carries no flag."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.mutation :as mutation]
            [hive-cljs.ports :as ports]
            [hive-cljs.schema :as s]
            [hive-cljs.step :as step]
            [hive-dsl.result :as r]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private sample-steps
  "One well-formed authored step per built-in kind."
  {:goto [:goto "/x"] :back [:back] :reload [:reload]
   :click [:click "#a"] :fill [:fill "#a" "v"] :select [:select "#a" "v"]
   :check [:check "#a"] :press [:press "#a" "Enter"] :hover [:hover "#a"]
   :wait-for [:wait-for "#a"] :wait-ms [:wait-ms 10]
   :expect-text [:expect-text "#a" "t"] :expect-value [:expect-value "#a" "v"]
   :expect-visible [:expect-visible "#a"] :expect-hidden [:expect-hidden "#a"]
   :expect-count [:expect-count "#a" 2] :expect-attr [:expect-attr "#a" "href" "x"]
   :expect-url [:expect-url "/x"] :expect-no-errors [:expect-no-errors]
   :eval-cljs [:eval-cljs "(+ 1 2)"] :dispatch [:dispatch [:evt]]
   :expect-sub [:expect-sub [:q] 'some?] :expect-db [:expect-db [:p] 'map?]
   :wait-for-sub [:wait-for-sub [:q] 'some?] :wait-for-db [:wait-for-db [:p] 'seq]
   :eval-js [:eval-js "1"] :expect-js [:expect-js "true"]
   :wait-for-js [:wait-for-js "true"] :expect-fits [:expect-fits ".a"]
   :expect-state [:expect-state :k "v"] :wait-for-state [:wait-for-state :k "v"]
   :http [:http {:method :post :url "http://localhost:1/pay"}]
   :expect-http [:expect-http {:status 200}]
   :screenshot [:screenshot "shot"]})

(defn- compiled [kind] (:ok (step/compile-step (get sample-steps kind))))

(def ^:private pre-change
  "The three closed sets exactly as they stood before ops carried their
   semantics — the answers this change must not move."
  {:assert    #{:expect-sub :expect-db :expect-js :expect-fits :expect-state}
   :poll      #{:wait-for-sub :wait-for-db :wait-for-js :wait-for-state}
   :read-only #{:expect-text :expect-value :expect-visible :expect-hidden :expect-count
                :expect-attr :expect-url :expect-no-errors :hive-cljs/at-origin
                :expect-sub :expect-db :expect-fits :wait-for
                :wait-for-sub :wait-for-db :wait-ms :screenshot}})

(deftest every-registered-kind-has-a-sample
  (is (= (set (step/known-kinds step/default-rules)) (set (keys sample-steps)))
      "a new built-in kind needs a sample here, so the checks below cover it"))

(deftest every-registered-rule-declares-its-semantics
  (doseq [rule step/default-rules]
    (testing (str (step/rule-id rule))
      (is (satisfies? step/IStepSemantics rule))
      (let [sem (step/semantics rule)]
        (is (= (set step/semantic-flags) (set (keys sem))))
        (is (every? boolean? (vals sem)))))))

(deftest every-compiled-op-carries-its-rules-semantics
  (doseq [rule step/default-rules
          :let [k   (step/rule-id rule)
                res (step/compile-step (get sample-steps k))]]
    (testing (str k)
      (is (r/ok? res) (pr-str res))
      (is (m/validate s/Op (:ok res)) (pr-str (m/explain s/Op (:ok res))))
      (is (= (step/semantics rule) (select-keys (:ok res) step/semantic-flags))))))

(deftest the-fallback-sets-are-unchanged
  (is (= (:assert pre-change) step/assertion-kinds))
  (is (= (:poll pre-change) step/poll-kinds boundary/wait-kinds))
  (is (= (:read-only pre-change) step/read-only-kinds boundary/read-only-kinds)))

(deftest the-old-sets-answers-are-preserved
  ;; Kinds added after the sets were frozen state their semantics on the op
  ;; only (`:expect-http` asserts with no entry in any set); they are checked
  ;; by `every-compiled-op-carries-its-rules-semantics` instead.
  (doseq [k (remove #{:http :expect-http} (keys sample-steps))
          :let [op (compiled k)]]
    (testing (str k)
      (is (= (contains? (:assert pre-change) k) (step/assertion-op? op) (:op/assert? op)))
      (is (= (contains? (:poll pre-change) k) (step/poll-op? op) (boundary/wait-op? op)
             (:op/poll? op)))
      (is (= (contains? (:read-only pre-change) k) (step/read-only-op? op)
             (:op/read-only? op))))))

(deftest a-flagless-op-falls-back-to-the-kind-sets
  (let [bare (fn [k] {:op/kind k :op/channel :runtime :op/args [] :op/source [k]})]
    (doseq [k (into #{:swipe :goto :eval-cljs} (mapcat val pre-change))]
      (testing (str k)
        (is (m/validate s/Op (bare k)))
        (is (= (contains? (:assert pre-change) k) (step/assertion-op? (bare k))))
        (is (= (contains? (:poll pre-change) k) (step/poll-op? (bare k))))
        (is (= (contains? (:read-only pre-change) k) (step/read-only-op? (bare k))))))))

(deftest the-ops-own-flag-wins-over-the-sets
  (let [op (assoc (compiled :expect-sub) :op/assert? false :op/read-only? false)]
    (is (not (step/assertion-op? op)))
    (is (not (step/read-only-op? op))))
  (is (step/poll-op? {:op/kind :custom :op/poll? true})))

(deftest hand-built-ops-state-their-semantics
  (let [fault (mutation/fault-op {:fault/id :x :fault/form "(x)" :fault/doc "x"})
        guard (mutation/guard-op "http://app.test")]
    (is (m/validate s/Op fault))
    (is (m/validate s/Op guard))
    (is (= {:op/assert? false :op/poll? false :op/read-only? false}
           (select-keys fault step/semantic-flags)))
    (is (= {:op/assert? false :op/poll? false :op/read-only? true}
           (select-keys guard step/semantic-flags)))))

;; =============================================================================
;; A kind hive-cljs has never heard of
;; =============================================================================

(defn- channel
  "A runtime channel that renders ANY op to a fixed probe and answers with
   successive values of `answers` (the last one repeats)."
  [answers]
  (let [left (atom answers)]
    (reify
      ports/ICljsEval
      (eval-cljs [_ _ _]
        (let [[v & more] @left]
          (when more (reset! left more))
          (r/ok {:value v :printed ""})))
      (runtime-available? [_ _] true)
      ports/IRuntimeDialect
      (assertion-source [_ _] '(probe))
      (probe-source [_ _] '(probe)))))

(def ^:private swipe-check
  "A third-party assertion: a kind in NO set, so only the op says what it means."
  {:op/kind :expect-swiped :op/channel :runtime :op/args [:left]
   :op/assert? true :op/poll? false :op/read-only? true
   :op/source [:expect-swiped :left]})

(deftest a-third-party-kind-gets-its-semantics-with-no-edit-to-hive-cljs
  (is (m/validate s/Op swipe-check))
  (is (not-any? #(contains? % :expect-swiped) (vals pre-change)))

  (testing "a falsy value FAILS an op that says it asserts"
    (is (= :fail (:state (boundary/perform-runtime! (channel [false]) :app swipe-check)))))

  (testing "the same value is merely reported by an op that says it does not"
    (is (= :pass (:state (boundary/perform-runtime!
                          (channel [false]) :app (assoc swipe-check :op/assert? false))))))

  (testing "an op that says it polls is polled until it holds"
    (let [polled (assoc swipe-check :op/kind :wait-for-swiped :op/assert? false :op/poll? true
                        :op/source [:wait-for-swiped :left])
          out    (boundary/perform-runtime! (channel [[false nil] [false nil] [true :ok]])
                                            :app polled {:timeout-ms 2000 :poll-ms 20})]
      (is (= :pass (:state out)))
      (is (= (pr-str :ok) (:detail out)))))

  (testing "an op that says it only observes skips a :mutations invariant"
    (let [cfg {:app-db-schema :any :app-db-check :mutations}]
      (is (not (boundary/invariant-applies? cfg swipe-check false)))
      (is (boundary/invariant-applies? cfg (assoc swipe-check :op/read-only? false) false)))))
