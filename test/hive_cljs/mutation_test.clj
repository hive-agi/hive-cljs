(ns hive-cljs.mutation-test
  "Behavioural mutation: break the live app on purpose and check the suite
   notices. The verdict is inverted — a green run under a fault is the failure."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.dialect.source :as source]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.mutation :as mutation]
            [hive-cljs.plan :as plan]
            [hive-cljs.schema :as s]
            [hive-cljs.stub.ports :as stub]
            [hive-dsl.result :as r]
            [malli.core :as m]
            [hive-cljs.browser.page-eval :as page-eval]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Catalog
;; =============================================================================

(deftest a-target-and-a-replacement-become-a-set-form
  (let [f (mutation/normalize-fault {:id :status-hole
                                     :target 'app.view-model/derive-status
                                     :with "(constantly nil)"})]
    (is (m/validate s/Fault f) (pr-str (m/explain s/Fault f)))
    (is (= "(set! app.view-model/derive-status (constantly nil))"
           (source/form->string (:fault/form f))))
    (is (= 'app.view-model/derive-status (:fault/target f)))))

(deftest a-target-and-a-replacement-FORM-become-a-set-form
  ;; The form spelling is primary: a manifest is EDN, so `:with` can simply BE
  ;; the replacement, and the fault stays a form until the channel prints it.
  (let [f (mutation/normalize-fault {:id :status-hole
                                     :target 'app.view-model/derive-status
                                     :with '(constantly nil)})]
    (is (m/validate s/Fault f) (pr-str (m/explain s/Fault f)))
    (is (= '(set! app.view-model/derive-status (constantly nil)) (:fault/form f))
        "built as a list, not concatenated")
    (is (= "(set! app.view-model/derive-status (constantly nil))"
           (source/form->string (:fault/form f))))
    (testing "the string and form spellings of :with render the same source"
      (is (= (source/form->string (:fault/form f))
             (source/form->string
              (:fault/form (mutation/normalize-fault
                            {:id :status-hole :target 'app.view-model/derive-status
                             :with "(constantly nil)"}))))))
    (testing "a non-list replacement is a form too"
      (is (= "(set! a/b nil)"
             (source/form->string
              (:fault/form (mutation/normalize-fault {:target 'a/b :with 'nil})))))
      (is (= "(set! a/b false)"
             (source/form->string
              (:fault/form (mutation/normalize-fault {:target 'a/b :with false}))))))))

(deftest raw-source-is-taken-as-authored
  (let [f (mutation/normalize-fault {:id :sub-hole :form "(reg-sub :x (fn [_ _] nil))"})]
    (is (= "(reg-sub :x (fn [_ _] nil))" (:fault/form f)))
    (is (nil? (:fault/target f)))))

(deftest a-form-fault-is-taken-as-authored-and-printed-only-at-the-channel
  (let [form '(re-frame.core/reg-sub :x (fn [_ _] nil))
        f    (mutation/normalize-fault {:id :sub-hole :form form})]
    (is (m/validate s/Fault f) (pr-str (m/explain s/Fault f)))
    (is (= form (:fault/form f)))
    (testing "the fault op carries the form; the stub channel prints it"
      (let [ce (stub/cljs-eval)]
        (is (= form (first (:op/args (mutation/fault-op f)))))
        (boundary/perform-runtime! ce :app (mutation/fault-op f))
        (is (= [[:app "(re-frame.core/reg-sub :x (fn [_ _] nil))"]] (stub/evals ce)))))))

(deftest a-fault-form-prints-the-same-whatever-the-callers-printer-vars
  (let [f    (mutation/normalize-fault {:target 'app/f
                                        :with '(fn [m] (assoc m :a/x [1 2 3 4 5]))})
        base (source/form->string (:fault/form f))]
    (is (= "(set! app/f (fn [m] (assoc m :a/x [1 2 3 4 5])))" base))
    (binding [*print-length* 2 *print-namespace-maps* true *print-readably* false
              *print-meta* true *print-level* 1]
      (is (= base (source/form->string (:fault/form f)))))))

(deftest a-blank-string-is-not-a-fault-form
  (is (nil? (mutation/normalize-fault {:id :x :form "   "})))
  (is (nil? (mutation/normalize-fault {:target 'a/b :with ""})))
  (is (not (m/validate s/Fault {:fault/id :x :fault/form ""})))
  (is (m/validate s/Fault {:fault/id :x :fault/form '(do nil)})))

(deftest an-id-is-derived-from-the-target-when-none-is-given
  (is (= :app.view-model.derive-status
         (:fault/id (mutation/normalize-fault {:target 'app.view-model/derive-status
                                               :with "(constantly nil)"})))))

(deftest a-fault-that-names-nothing-to-break-is-dropped
  (is (nil? (mutation/normalize-fault {:id :empty})))
  (is (nil? (mutation/normalize-fault {:target 'a/b})))
  (is (nil? (mutation/normalize-fault {:with "(constantly nil)"})))
  (is (nil? (mutation/normalize-fault {:with '(constantly nil)})))
  (testing "and the drop does not take the usable ones with it"
    (is (= [:good]
           (mapv :fault/id
                 (mutation/normalize-faults [{:id :bad}
                                             {:id :good :form "(set! a/b nil)"}]))))))

(deftest declared-faults-reach-the-normalized-manifest
  (let [m (:ok (manifest/parse
                {:hive.cljs/builds {:app {:http-port 8280}}
                 :hive.cljs/e2e    {:faults [{:id :hole :target 'a/b :with "nil"}
                                             {:id :form-hole :target 'a/c
                                              :with '(constantly nil)}]
                                    :scenarios [{:id :s :steps [[:goto "/"]]}]}}
                "/tmp/hive-cljs-faults"))]
    (is (= [:hole :form-hole] (mapv :fault/id (get-in m [:manifest/e2e :faults]))))
    (is (= '(set! a/c (constantly nil)) (:fault/form (second (get-in m [:manifest/e2e :faults])))))))

;; =============================================================================
;; Injection
;; =============================================================================

(def ^:private fault
  {:fault/id :hole :fault/form "(set! a/b (constantly nil))"})

(defn- manifest-for
  [steps]
  (:ok (manifest/parse
        {:hive.cljs/shadow {:port 9630 :nrepl-port 7889}
         :hive.cljs/builds {:app {:http-port 8280}}
         :hive.cljs/e2e    {:scenarios [{:id :s :build :app :steps steps}]}}
        "/tmp/hive-cljs-mutation")))

(defn- plan-for [steps]
  (let [m (manifest-for steps)]
    (:ok (plan/build-plan m (first (manifest/scenarios m))))))

(deftest the-fault-lands-after-the-navigation-that-would-wipe-it
  (let [p (mutation/inject (plan-for [[:goto "/"] [:click "#go"] [:expect-visible "#p"]])
                           fault)]
    (is (= [:goto :hive-cljs/at-origin :eval-cljs :click :expect-visible]
           (mapv :op/kind (:plan/ops p))))
    (is (m/validate s/RunPlan p) (pr-str (m/explain s/RunPlan p)))
    (testing "the injected op rides the runtime channel"
      (is (= :runtime (:op/channel (nth (:plan/ops p) 2)))))))

(deftest a-plan-that-never-navigates-takes-the-fault-at-the-head
  (let [p (mutation/inject (plan-for [[:expect-sub [:x] "some?"]]) fault)]
    (is (= [:eval-cljs :expect-sub] (mapv :op/kind (:plan/ops p))))))

(deftest the-fault-lands-after-the-waits-that-let-the-runtime-register
  (testing "a runtime that has not registered answers no eval, so a fault
            spliced between the goto and the boot waits ERRORS — and an errored
            fault scores as a kill, which makes every fault look caught"
    (let [p (mutation/inject
             (plan-for [[:goto "/"] [:wait-for "#app"] [:wait-ms 10]
                        [:eval-cljs "(seed!)"] [:expect-visible "#p"]])
             fault)]
      (is (= [:goto :wait-for :wait-ms :hive-cljs/at-origin :eval-cljs :eval-cljs
              :expect-visible]
             (mapv :op/kind (:plan/ops p))))
      (is (= (:fault/form fault)
             (first (:op/args (nth (:plan/ops p) 4))))
          "the fault runs before the scenario's own first eval, not after it")))
  (testing "waits AFTER the scenario's own work are not boot barriers"
    (let [p (mutation/inject
             (plan-for [[:goto "/"] [:click "#go"] [:wait-for "#done"]]) fault)]
      (is (= [:goto :hive-cljs/at-origin :eval-cljs :click :wait-for]
             (mapv :op/kind (:plan/ops p)))))))

(deftest injection-leaves-the-original-plan-alone
  (let [p (plan-for [[:goto "/"] [:click "#go"]])]
    (mutation/inject p fault)
    (is (= 2 (count (:plan/ops p))))))

;; =============================================================================
;; Verdicts
;; =============================================================================

(deftest a-red-scenario-kills-the-fault
  (let [v (mutation/verdict fault [{:run/scenario :a :run/state :pass}
                                   {:run/scenario :b :run/state :fail}])]
    (is (m/validate s/FaultVerdict v) (pr-str (m/explain s/FaultVerdict v)))
    (is (true? (:fault/killed? v)))
    (is (= [:b] (:fault/by v)))))

(deftest an-all-green-run-means-the-fault-survived
  (let [v (mutation/verdict fault [{:run/scenario :a :run/state :pass}])]
    (is (false? (:fault/killed? v)))
    (is (re-find #"blind to this behaviour" (:fault/detail v)))))

(deftest an-error-counts-as-a-kill-only-because-it-is-not-green
  (is (true? (:fault/killed? (mutation/verdict fault [{:run/scenario :a :run/state :error}]))))
  (is (true? (:fault/killed? (mutation/verdict fault [{:run/scenario :a :run/state :incomplete}])))))

(deftest an-unchallenged-suite-scores-zero-never-one
  (is (= 0.0 (mutation/score [])))
  (is (= 0.0 (mutation/score [{:fault/id :a :fault/killed? false}])))
  (is (= 0.5 (mutation/score [{:fault/id :a :fault/killed? true}
                              {:fault/id :b :fault/killed? false}])))
  (is (= 1.0 (mutation/score [{:fault/id :a :fault/killed? true}]))))

(deftest the-report-separates-killed-from-survived
  (let [rep (mutation/report [:s]
                             [{:fault/id :a :fault/killed? true}
                              {:fault/id :b :fault/killed? false}])]
    (is (m/validate s/MutationReport rep) (pr-str (m/explain s/MutationReport rep)))
    (is (= [:a] (:mutation/killed rep)))
    (is (= [:b] (:mutation/survived rep)))
    (is (= 0.5 (:mutation/score rep)))))

;; =============================================================================
;; Running
;; =============================================================================

(def ^:private walk [[:goto "/"] [:expect-visible "#panel"]])

(defn- noticing-deps
  "Ports where the DOM assertion starts failing once the fault form has been
   evaluated — the stub equivalent of a scenario seeing broken behaviour."
  [broken?]
  {:driver    (stub/driver (fn [_ op]
                             (if (and @broken? (= :expect-visible (:op/kind op)))
                               {:state :fail :detail "the panel went blank" :elapsed-ms 1}
                               {:state :pass :elapsed-ms 1})))
   :cljs-eval (stub/cljs-eval (fn [_ form]
                                (when (= (:fault/form fault) form) (reset! broken? true))
                                true))})

(deftest a-fault-the-suite-notices-is-killed
  (let [res (boundary/run-mutations! (noticing-deps (atom false))
                                     [(plan-for walk)] [fault])]
    (is (r/ok? res) (pr-str res))
    (is (= [:hole] (:mutation/killed (:ok res))))
    (is (= 1.0 (:mutation/score (:ok res))))))

(deftest a-fault-nothing-notices-survives-and-is-named
  (let [res (boundary/run-mutations! {:driver    (stub/driver)
                                      :cljs-eval (stub/cljs-eval (constantly true))}
                                     [(plan-for walk)] [fault])]
    (is (r/ok? res) (pr-str res))
    (is (= [:hole] (:mutation/survived (:ok res))))
    (is (= 0.0 (:mutation/score (:ok res))))))

(deftest a-red-baseline-is-refused-rather-than-scored
  (let [res (boundary/run-mutations! {:driver    (stub/driver (stub/fail-on :expect-visible))
                                      :cljs-eval (stub/cljs-eval (constantly true))}
                                     [(plan-for walk)] [fault])]
    (is (r/err? res))
    (is (= :mutation/baseline-red (:error res)))
    (testing "the refusal says what was already failing"
      (is (seq (:summary res))))))

(deftest an-empty-catalog-is-an-error-not-a-perfect-score
  (let [res (boundary/run-mutations! {:driver (stub/driver)} [(plan-for walk)] [])]
    (is (r/err? res))
    (is (= :mutation/no-faults (:error res)))))

(deftest auto-derivation-turns-every-registered-handler-into-a-fault
  (let [ce   (stub/cljs-eval (fn [_ form]
                               (when (re-find #"kind->id->handler" form)
                                 {:sub [:app/items :app/selected] :event [:app/load]})))
        deps {:driver (stub/driver) :cljs-eval ce}
        res  (boundary/derive-faults! deps (plan-for walk) [:sub :event])]
    (is (r/ok? res) (pr-str res))
    (is (= [:sub-app.items :sub-app.selected :event-app.load]
           (mapv :fault/id (:ok res))))

    (testing "one page, one round trip — both registries read in a single probe"
      (is (= 1 (count (filter (fn [[_ form]] (re-find #"kind->id->handler" form))
                              (stub/evals ce))))))

    (testing "a neutralized sub clears the reaction cache on both sides"
      (is (re-find #"clear-subscription-cache!.*reg-sub :app/items.*clear-subscription-cache!"
                   (source/form->string (:fault/form (first (:ok res)))))))

    (testing "derived faults are FORMS; only the channel stringifies them"
      (is (every? (comp seq? :fault/form) (:ok res)))
      (is (= '(do (re-frame.core/reg-event-db :app/load (fn [db _] db)) :neutralized)
             (:fault/form (last (:ok res))))))

    (testing "every derived fault conforms"
      (is (every? #(m/validate s/Fault %) (:ok res))))))

(deftest auto-derivation-without-a-runtime-is-a-typed-absence
  (testing "no runtime channel — the probe cannot answer, so nothing is claimed"
    (let [res (boundary/derive-faults! {:driver (stub/driver)} (plan-for walk) [:sub])]
      (is (r/err? res))
      (is (= :probe/failed (:error res)))))

  (testing "asking for no registries is not an error, it is an empty catalog"
    (is (= [] (:ok (boundary/derive-faults! {} (plan-for walk) []))))))

;; =============================================================================
;; Unapplied faults — never a kill
;; =============================================================================

(deftest a-fault-that-never-ran-is-unapplied-not-killed
  (let [v (mutation/verdict fault [{:run/scenario :a :run/state :error
                                    :fault/applied? false}])]
    (is (false? (:fault/killed? v)))
    (is (= :unapplied (:fault/status v)))
    (is (m/validate s/FaultVerdict v))))

(deftest unapplied-faults-are-reported-apart-and-left-out-of-the-score
  (let [rep (mutation/report [:a]
                             [{:fault/id :k :fault/killed? true :fault/status :killed}
                              {:fault/id :s :fault/killed? false :fault/status :survived}
                              {:fault/id :u :fault/killed? false :fault/status :unapplied}])]
    (is (= [:k] (:mutation/killed rep)))
    (is (= [:s] (:mutation/survived rep)))
    (is (= [:u] (:mutation/unapplied rep)))
    (is (= 0.5 (:mutation/score rep)))
    (is (m/validate s/MutationReport rep)))
  (testing "a catalog where nothing applied proves nothing"
    (is (= 0.0 (mutation/score [{:fault/id :u :fault/killed? false
                                 :fault/status :unapplied}])))))

(defn- absolute-plan [steps]
  (assoc (plan-for steps) :plan/base-url "http://app.test:8080/"))

(deftest a-navigating-plan-guards-the-fault-on-the-app-origin
  (let [p   (mutation/inject (absolute-plan walk) fault)
        ops (:plan/ops p)]
    (is (= [:goto :hive-cljs/at-origin :eval-cljs :expect-visible] (mapv :op/kind ops)))
    (is (= ["http://app.test:8080"] (:op/args (nth ops 1))))
    (is (m/validate s/RunPlan p))))

(deftest a-login-detour-puts-the-fault-after-arrival-on-the-app
  (let [steps [[:goto "https://idp.test/login"] [:fill "#u" "me"] [:click "#go"]
               [:expect-url "http://app.test:8080/home"] [:wait-for "#app"]
               [:expect-visible "#panel"]]
        ops   (:plan/ops (mutation/inject (absolute-plan steps) fault))]
    (is (= [:goto :fill :click :expect-url :wait-for :hive-cljs/at-origin :eval-cljs
            :expect-visible]
           (mapv :op/kind ops)))))

(deftest a-fault-stopped-by-the-origin-guard-is-unapplied
  ;; The redirect to the IdP: the guard fails, the run goes red, and that red
  ;; must not be counted as the suite noticing the fault. With nothing else in
  ;; the catalog the suite was never challenged: unmeasured, not a 0.0.
  (let [deps {:driver    (stub/driver (fn [_ op]
                                        (if (= :hive-cljs/at-origin (:op/kind op))
                                          {:state :fail :detail "on idp" :elapsed-ms 1}
                                          {:state :pass :elapsed-ms 1})))
              :cljs-eval (stub/cljs-eval (constantly true))}
        res  (boundary/run-mutations! deps [(absolute-plan walk)] [fault])]
    (is (r/err? res) (pr-str res))
    (is (= :mutation/unmeasured (:error res)))
    (is (= [] (:mutation/killed (:report res))))
    (is (= [:hole] (:mutation/unapplied (:report res))))
    (is (not (contains? res :mutation/score)))))

(deftest a-fault-whose-eval-errors-is-unapplied
  (let [deps {:driver    (stub/driver)
              :cljs-eval (stub/cljs-eval (fn [_ form]
                                           (if (= (:fault/form fault) form)
                                             (r/err :eval/no-runtime {})
                                             true)))}
        res  (boundary/run-mutations! deps [(absolute-plan walk)] [fault])]
    (is (= :mutation/unmeasured (:error res)) (pr-str res))
    (is (= [:hole] (:mutation/unapplied (:report res))))))

(deftest an-undeterminable-app-page-leaves-the-fault-unapplied
  (let [p   (assoc (plan-for walk) :plan/base-url "relative/only")
        res (boundary/run-mutations! {:driver    (stub/driver)
                                      :cljs-eval (stub/cljs-eval (constantly true))}
                                     [p] [fault])]
    (is (false? (mutation/injectable? p)))
    (is (= :mutation/unmeasured (:error res)) (pr-str res))
    (is (= [:hole] (:mutation/unapplied (:report res))))
    (is (= [] (:mutation/killed (:report res))))))

(deftest a-guarded-applied-fault-the-suite-notices-is-still-killed
  (let [res (boundary/run-mutations! (noticing-deps (atom false))
                                     [(absolute-plan walk)] [fault])]
    (is (r/ok? res) (pr-str res))
    (is (= [:hole] (:mutation/killed (:ok res))))
    (is (= 1.0 (:mutation/score (:ok res))))))

;; =============================================================================
;; The fault rides the runtime channel the toolchain connected
;; =============================================================================

(def ^:private js-fault
  {:fault/id :panel-blanked
   :fault/form "document.querySelector('#panel').remove()"})

(defn- browser-deps
  "A `:browser` toolchain's ports: the stub driver, and the REAL page-eval
   channel over it, which speaks JavaScript and no ClojureScript. The DOM
   assertion fails once the page has evaluated `fault-src`."
  [fault-src]
  (let [broken? (atom false)
        driver  (-> (stub/driver (fn [_ op]
                                   (if (and @broken? (= :expect-visible (:op/kind op)))
                                     {:state :fail :detail "the panel is gone" :elapsed-ms 1}
                                     {:state :pass :elapsed-ms 1})))
                    (stub/answering-page (fn [src]
                                           (when (= fault-src src) (reset! broken? true))
                                           true)))]
    {:driver driver :cljs-eval (page-eval/channel driver) :toolchain-id :browser}))

(deftest a-browser-channel-applies-a-source-fault-as-javascript
  ;; The friction: on a :browser toolchain every fault went out as :eval-cljs,
  ;; which the page-eval channel renders nothing for, so all of them came back
  ;; :unapplied and the suite scored 0.0. The same source as :eval-js is red.
  (let [deps (browser-deps (:fault/form js-fault))
        res  (boundary/run-mutations! deps [(plan-for walk)] [js-fault])]
    (is (r/ok? res) (pr-str res))
    (is (= [:panel-blanked] (:mutation/killed (:ok res))))
    (is (= 1.0 (:mutation/score (:ok res))))
    (testing "the fault source reached the page verbatim, as JavaScript"
      (is (some #(= (:fault/form js-fault) (second %))
                (stub/page-evals (:driver deps)))))))

(deftest the-fault-op-kind-is-chosen-by-what-the-channel-renders
  (let [renders-js?   (fn [op] (= :eval-js (:op/kind op)))
        renders-cljs? (fn [op] (contains? #{:eval-cljs :eval-js} (:op/kind op)))]
    (testing "a ClojureScript channel keeps a source fault on :eval-cljs"
      (is (= :eval-cljs
             (:op/kind (mutation/fault-op (mutation/bind-fault renders-cljs? fault))))))
    (testing "a JavaScript-only channel carries a source fault as :eval-js"
      (let [bound (mutation/bind-fault renders-js? js-fault)]
        (is (m/validate s/Fault bound) (pr-str (m/explain s/Fault bound)))
        (is (= :eval-js (:op/kind (mutation/fault-op bound))))
        (is (= [(:fault/form js-fault)] (:op/args (mutation/fault-op bound))))))
    (testing "a FORM is ClojureScript; a JavaScript-only channel cannot carry it"
      (is (nil? (mutation/bind-fault renders-js?
                                     {:fault/id :f :fault/form '(set! a/b nil)}))))))

(deftest a-cljs-form-fault-on-a-browser-channel-is-refused-up-front
  (let [deps (browser-deps "never")
        f    {:fault/id :status-hole :fault/form '(set! app/derive-status (constantly nil))}
        res  (boundary/run-mutations! deps [(plan-for walk)] [js-fault f])]
    (is (r/err? res))
    (is (= :mutation/fault-channel-mismatch (:error res)))
    (is (= :browser (:toolchain res)) "names the toolchain")
    (is (= [:eval-js] (:channel res)) "names what the channel can carry")
    (is (= [:status-hole] (:faults res)) "names only the faults it cannot carry")
    (testing "up front: not even the baseline ran"
      (is (zero? (stub/sessions-opened (:driver deps)))))))

(deftest a-channel-that-renders-no-fault-at-all-is-refused-up-front
  (testing "no runtime channel connected"
    (let [res (boundary/run-mutations! {:driver (stub/driver) :toolchain-id :browser}
                                       [(plan-for walk)] [js-fault])]
      (is (= :mutation/fault-channel-mismatch (:error res)) (pr-str res))
      (is (= :browser (:toolchain res)))
      (is (= [] (:channel res)))))
  (testing "a channel that speaks no step vocabulary"
    (let [res (boundary/run-mutations! {:driver    (stub/driver)
                                        :cljs-eval (stub/cljs-eval-without-dialect)}
                                       [(plan-for walk)] [js-fault])]
      (is (= :mutation/fault-channel-mismatch (:error res)) (pr-str res))
      (is (= [] (:channel res))))))

(deftest a-catalog-where-nothing-applied-is-unmeasured-never-zero
  ;; Every fault errored on the channel: the suite was never challenged, so
  ;; there is no score to report, not a 0.0 that reads as a blind suite.
  (let [deps {:driver    (stub/driver)
              :cljs-eval (stub/cljs-eval (fn [_ _] (r/err :eval/boom {})))}
        res  (boundary/run-mutations! deps [(plan-for [[:goto "/"]])] [fault])]
    (is (r/err? res) (pr-str res))
    (is (= :mutation/unmeasured (:error res)))
    (is (= [:hole] (:mutation/unapplied (:report res))))
    (is (string? (:hint res)))))
