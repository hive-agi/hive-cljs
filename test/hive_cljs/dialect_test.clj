(ns hive-cljs.dialect-test
  "The runtime DIALECT seam — what a step means in the language of the runtime
   that will evaluate it.

   Rendering used to live in `boundary`, which reached into the shadow nREPL
   adapter for it while claiming to name no vendor. The claims here are that the
   rendering is now the channel's own, and that a channel which cannot render a
   step says so instead of letting the step look green."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.dialect.js :as js]
            [hive-cljs.dialect.re-frame :as re-frame]
            [hive-cljs.ports :as ports]
            [hive-cljs.stub.ports :as stub]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- op [kind & args]
  {:op/kind kind :op/channel :runtime :op/args (vec args)})

;; =============================================================================
;; The shipped dialect
;; =============================================================================

(deftest the-re-frame-dialect-renders-the-step-vocabulary
  (is (= "(some? @(re-frame.core/subscribe [:user]))"
         (re-frame/assertion-source (op :expect-sub [:user] "some?"))))
  (is (= "(some? (get-in @re-frame.db/app-db [:user]))"
         (re-frame/assertion-source (op :expect-db [:user] "some?"))))
  (is (= "(do (re-frame.core/dispatch-sync [:go]) :dispatched)"
         (re-frame/assertion-source (op :dispatch [:go]))))
  (is (= "(js/alert 1)"
         (re-frame/assertion-source (op :eval-cljs "(js/alert 1)")))
      "an authored expression passes through untranslated"))

(deftest the-re-frame-dialect-declines-a-kind-it-does-not-know
  ;; nil, not a best-effort expression assembled out of the wrong arguments:
  ;; the caller turns nil into :incomplete, and a guess would turn it into a
  ;; pass or a fail that means nothing.
  (is (nil? (re-frame/assertion-source (op :expect-js "window.model"))))
  (is (nil? (re-frame/probe-source (op :expect-sub [:a] "some?")))
      "an assertion kind is not a probe kind"))

(deftest a-probe-keeps-the-observed-value
  ;; 'never happened' and 'not yet' need different fixes, so the polled form
  ;; yields the value alongside the predicate result.
  (let [src (re-frame/probe-source (op :wait-for-sub [:user] "some?"))]
    (is (str/includes? src "@(re-frame.core/subscribe [:user])"))
    (is (str/includes? src "[(boolean"))))

;; =============================================================================
;; The channel owns its rendering
;; =============================================================================

(deftest the-shipped-clojurescript-channel-carries-both-capabilities
  (let [ce (stub/cljs-eval)]
    (is (ports/runtime-dialect? ce))
    (is (ports/runtime-introspection? ce))
    (is (= "(some? @(re-frame.core/subscribe [:user]))"
           (ports/assertion-source ce (op :expect-sub [:user] "some?")))
        "the channel renders through the same dialect, not one of its own")))

;; =============================================================================
;; Degradation
;; =============================================================================

(deftest a-channel-without-a-dialect-reports-incomplete-rather-than-a-pass
  (let [ce  (stub/cljs-eval-without-dialect)
        out (boundary/perform-runtime! ce :app (op :expect-sub [:user] "some?"))]
    (is (= :incomplete (:state out)))
    (is (empty? (stub/evals ce))
        "nothing was evaluated, so nothing may be claimed about the app")))

(deftest a-kind-the-connected-dialect-cannot-render-is-incomplete
  (let [ce  (stub/cljs-eval)
        out (boundary/perform-runtime! ce :app (op :expect-elm-model "user"))]
    (is (= :incomplete (:state out)))
    (is (str/includes? (:detail out) ":expect-elm-model"))
    (is (empty? (stub/evals ce)))))

(deftest a-channel-that-cannot-read-state-does-not-quietly-skip-the-invariant
  ;; An invariant that could not run is not an invariant that held.
  (let [ce  (stub/cljs-eval-without-dialect)
        out (boundary/check-invariant! ce :app {:app-db-schema 'my.app/Schema})]
    (is (= :incomplete (:state out)))
    (is (str/includes? (:detail out) "my.app/Schema"))))

(deftest deriving-a-fault-catalog-needs-a-channel-that-can-introspect
  (let [ce  (stub/cljs-eval-without-dialect)
        res (boundary/derive-faults! {:cljs-eval ce} {} [:sub])]
    (is (r/err? res))
    (is (= :mutation/no-introspection (:error res)))
    (testing "and says what to do instead"
      (is (str/includes? (:hint res) ":faults")))))

;; =============================================================================
;; Contrast sampling
;; =============================================================================

(deftest the-contrast-probe-asks-the-page-what-it-actually-painted
  (let [source (js/contrast-rows-source
                [{:id :body :selector "p" :limit 3}
                 {:id :field :selector ".field input" :role :non-text}])]
    (testing "it climbs for a background, which is the whole point of asking"
      (is (str/includes? source "parentElement")
          "an element almost never paints its own background")
      (is (str/includes? source "documentElement")
          "and the root is the backstop when no ancestor painted one"))

    (testing "it collects the LAYERS and the opacity, and decides neither"
      (is (str/includes? source "layers.push(c)")
          "a translucent card over a dark page is neither of those colours")
      (is (str/includes? source "opacity *= o")
          "and a computed colour does not carry the opacity property"))

    (testing "each spec reaches the page as the selector it named"
      (is (str/includes? source "\"p\""))
      (is (str/includes? source "\".field input\""))
      (is (str/includes? source "\"non-text\""))
      (is (str/includes? source "spec.limit")))

    (testing "only :non-text is asserted; text is classified from its size"
      (is (str/includes? source "role:null")
          "a page that stamped 'text' on a heading would raise its bar")
      (is (str/includes? source "cs.fontSize"))
      (is (str/includes? source "fontWeight")))

    (testing "what would be a false failure is skipped, not reported"
      (is (str/includes? source "=== 'none'")
          "an element with no border has no edge to measure")
      (is (str/includes? source "textContent")
          "and an empty node has no text to read"))

    (testing "rows are positional, so no key spelling crosses the host boundary"
      (is (str/includes? source "out.push([")))))

(deftest a-border-side-reaches-the-page-as-a-property-that-exists
  (testing "a keyword side is spelled the way computed style spells it"
    (doseq [[given expected] {:top "Top" :left "Left" :bottom "Bottom"
                              "Top" "Top" "left" "Left" nil "Top"}]
      (is (str/includes? (js/contrast-rows-source
                          [{:id :e :selector ".x" :role :non-text :side given}])
                         (str "side:\"" expected "\""))
          (str (pr-str given) " must not emit a property nothing answers to")))))

(deftest one-bad-selector-is-one-bad-spec
  (let [source (js/contrast-rows-source
                [{:id :bad :selector "p:::nope"} {:id :good :selector "p"}])]
    (is (str/includes? source "catch (e)")
        "a selector the browser rejects must not abort every other spec")
    (is (str/includes? source "-selector")
        "it is reported as one unresolvable row instead")))

(deftest a-selector-cannot-smuggle-source-into-the-page
  (let [source (js/contrast-rows-source
                [{:id :evil :selector "a\"); alert(1); //"}])]
    (testing "the payload survives, INSIDE the string literal it was given as"
      (is (str/includes? source "selector:\"a\\\"); alert(1); //\"")
          "the quote that would close the literal is escaped, so the rest of
           the selector stays data"))

    (testing "and it never appears unescaped, which is what breaking out means"
      (is (not (str/includes? source "selector:\"a\");"))))))

;; =============================================================================
;; The architecture claim itself
;; =============================================================================

(deftest the-boundary-names-no-vendor
  ;; boundary's own docstring says so, and it was false: it required the shadow
  ;; nREPL adapter for its form rendering. A guard, because the require that
  ;; broke it looked entirely reasonable at the call site.
  (require 'hive-cljs.boundary)
  (let [vendors (->> (ns-aliases 'hive-cljs.boundary)
                     vals
                     (map (comp str ns-name))
                     (filter #(or (str/starts-with? % "hive-cljs.shadow")
                                  (str/starts-with? % "hive-cljs.browser")))
                     sort
                     vec)]
    (is (= [] vendors)
        (str "boundary must depend on ports, not adapters — found " vendors))))
