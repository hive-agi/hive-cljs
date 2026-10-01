(ns hive-cljs.dialect-test
  "The runtime DIALECT seam — what a step means in the language of the runtime
   that will evaluate it.

   Rendering used to live in `boundary`, which reached into the shadow nREPL
   adapter for it while claiming to name no vendor. The claims here are that the
   rendering is now the channel's own, and that a channel which cannot render a
   step says so instead of letting the step look green."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.dialect.js :as js]
            [hive-cljs.dialect.re-frame :as re-frame]
            [hive-cljs.dialect.source :as source]
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

(deftest a-manifest-is-edn-so-a-step-argument-may-be-a-FORM
  ;; "eval-cljs should need to push a clj string, that's bad design." A source
  ;; string inside EDN costs escaping, editor support, linting and indexing: a
  ;; typo in it ships as a runtime error. The manifest is already EDN, so the
  ;; form can simply BE the argument, and :dispatch has always taken one.
  (testing "a form renders as its own source"
    (is (= "(+ 1 2)" (re-frame/assertion-source (op :eval-cljs '(+ 1 2))))))

  (testing "a predicate reads as a symbol or a fn form, not only as text"
    (is (= "(some? @(re-frame.core/subscribe [:user]))"
           (re-frame/assertion-source (op :expect-sub [:user] 'some?))))
    (is (= "((fn [v] (= v \"pedro\")) (get-in @re-frame.db/app-db [:user :name]))"
           (re-frame/assertion-source
            (op :expect-db [:user :name] '(fn [v] (= v "pedro"))))))
    (is (= "(let [v @(re-frame.core/subscribe [:x])] [(boolean (seq v)) v])"
           (re-frame/probe-source (op :wait-for-sub [:x] 'seq)))))

  (testing "and the string spelling still works, because #(…) and #\"…\" have no
            EDN form and must stay authorable"
    (is (= (re-frame/assertion-source (op :expect-sub [:user] 'some?))
           (re-frame/assertion-source (op :expect-sub [:user] "some?"))))))

(def ^:private printer-bindings
  "Every printer-var setting a caller's thread could plausibly carry."
  (for [ns-maps [true false]
        length  [nil 0 2]
        level   [nil 1]
        meta?   [true false]
        readably [true false]]
    {#'*print-namespace-maps* ns-maps
     #'*print-length*         length
     #'*print-level*          level
     #'*print-meta*           meta?
     #'*print-readably*       readably}))

(def ^:private sample-forms
  [(list 'when-not (list '= :ok (list :state (list 'plato.fit/verdict-for "welcome")))
         (list 'throw (list 'ex-info "not ok" {:app/step 1 :app/why [1 2 3 4 5]})))
   (with-meta '(fn [v] (= v "pedro")) {:line 3})
   [:user/login {:user/name "pedro" :user/roles #{:admin}}]
   '(get-in db [:a [:b [:c [:d]]]])])

(deftest a-form-renders-the-same-source-whatever-the-callers-printer-vars
  ;; pr-str obeys the CALLER's printer vars: *print-length* truncates a vector
  ;; to (1 2 ...), *print-namespace-maps* rewrites {:a/x 1} as #:a{:x 1},
  ;; *print-readably* false drops a string's quotes. Each of those changes what
  ;; the app is asked, so the source must be thread-independent.
  (let [ops      (concat (map #(op :eval-cljs %) sample-forms)
                         [(op :expect-sub [:user/current {:user/id 1}] '(fn [v] (= v "pedro")))
                          (op :expect-db [:a :b :c :d] '(fn [v] (contains? #{:x/y} v)))
                          (op :dispatch [:user/login {:user/name "pedro"}])])
        baseline (mapv re-frame/assertion-source ops)]
    (testing "the baseline is plain readable source"
      (is (str/includes? (first baseline) "\"welcome\""))
      (is (str/includes? (first baseline) "{:app/step 1, :app/why [1 2 3 4 5]}"))
      (is (not (str/includes? (second baseline) "^{")) "no metadata leaks into source"))
    (doseq [b printer-bindings]
      (with-bindings b
        (is (= baseline (mapv re-frame/assertion-source ops))
            (str "source changed under " (pr-str (update-keys b #(.sym ^clojure.lang.Var %)))))))))

(deftest the-pinned-printer-reads-back-as-the-form-it-printed
  (doseq [b printer-bindings
          f sample-forms]
    (with-bindings b
      (is (= f (read-string (source/pr-source f)))))))

(deftest a-string-is-the-escape-hatch-and-passes-through-verbatim
  (doseq [b printer-bindings]
    (with-bindings b
      (is (= "#(= % \"pedro\")" (source/form->string "#(= % \"pedro\")")))
      (is (= "(#(> (count %) 3) @(re-frame.core/subscribe [:items]))"
             (re-frame/assertion-source (op :expect-sub [:items] "#(> (count %) 3)")))))))

(deftest the-js-dialect-also-pins-the-printer
  (let [base (js/assertion-source (op :expect-state [:user/a 1] "v != null"))]
    (doseq [b printer-bindings]
      (with-bindings b
        (is (= base (js/assertion-source (op :expect-state [:user/a 1] "v != null"))))))))

(deftest the-re-frame-dialect-also-renders-the-javascript-kinds
  ;; A page compiled from ClojureScript is still a page: the stack-agnostic
  ;; kinds render through js/eval with the JS dialect's own truthiness, so one
  ;; scenario can hold a re-frame assertion and a DOM one side by side.
  (is (= (str "(js->clj (js/eval " (pr-str (js/truthy-value "1 + 1")) "))")
         (re-frame/assertion-source (op :expect-js "1 + 1"))))
  (is (= (str "(js->clj (js/eval " (pr-str (js/truthy-probe "document.title")) "))")
         (re-frame/probe-source (op :wait-for-js "document.title"))))
  (is (= "(js->clj (js/eval \"x\"))"
         (re-frame/assertion-source (op :eval-js "x")))))

(deftest the-re-frame-dialect-declines-a-kind-it-does-not-know
  ;; nil, not a best-effort expression assembled out of the wrong arguments:
  ;; the caller turns nil into :incomplete, and a guess would turn it into a
  ;; pass or a fail that means nothing. The probe contract is the one kind a
  ;; ClojureScript runtime does not answer through this dialect.
  (is (nil? (re-frame/assertion-source (op :expect-state [:model] "v"))))
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

(defn- node-eval
  "Evaluate `source` in node against a fake page built by `page-js`, which
   defines `document`, `window` and `getComputedStyle`. The JSON of the
   result, or `THROW <message>`. nil when node is not installed."
  [page-js source]
  (when (try (zero? (:exit (sh/sh "node" "--version"))) (catch Exception _ false))
    (let [script (str page-js "\n"
                      "try { console.log(JSON.stringify(" source ")); }\n"
                      "catch (e) { console.log('THROW ' + e.message); }\n")
          res    (sh/sh "node" "-e" script)]
      (str/trim (:out res)))))

(def ^:private fake-page
  "A page whose elements are plain objects: `el({...})` gives one, `pages`
   maps a selector to the elements it matches."
  "const el = (o) => ({ parentElement: null, textContent: '', clientWidth: 0,
                        scrollWidth: 0, style: {},
                        getBoundingClientRect: () => o.r ?? {width:0,height:0,left:0,right:0},
                        ...o });
   const box = (l, w, extra = {}) => el({ r: {left:l, right:l+w, width:w, height:10}, ...extra });
   const pages = {};
   const window = { innerWidth: 100 };
   const getComputedStyle = (n) => n.style;
   const document = {
     documentElement: el({ style: { backgroundColor: 'rgb(255, 255, 255)', opacity: '1' } }),
     querySelectorAll: (s) => { if (s.includes(':::')) throw new Error('bad selector');
                                return pages[s] ?? []; } };")

(defn- on-page [setup source] (node-eval (str fake-page "\n" setup) source))

(deftest the-contrast-probe-asks-the-page-what-it-actually-painted
  (let [source (js/contrast-rows-source
                [{:id :body :selector "p" :limit 3}
                 {:id :field :selector ".field input" :role :non-text}])]
    (testing "it climbs for a background, which is the whole point of asking"
      (is (str/includes? source "parentElement")
          "an element almost never paints its own background")
      (is (str/includes? source "documentElement")
          "and the root is the backstop when no ancestor painted one"))

    (testing "each spec reaches the page as the selector it named"
      (is (str/includes? source "\"p\""))
      (is (str/includes? source "\".field input\""))
      (is (str/includes? source "\"non-text\"")))

    (testing "only :non-text is asserted; text is classified from its size"
      (is (str/includes? source "\"role\": null")
          "a page that stamped 'text' on a heading would raise its bar")
      (is (str/includes? source "fontSize"))
      (is (str/includes? source "fontWeight"))))

  (testing "on a page"
    (when-let [out (on-page
                    "const solid = el({ style: { backgroundColor: 'rgb(1, 2, 3)', opacity: '0.5' } });
                     const card = el({ parentElement: solid,
                                       style: { backgroundColor: 'rgba(0, 0, 0, 0.5)', opacity: '0.8' } });
                     const page = el({ parentElement: card,
                                       style: { backgroundColor: 'rgb(10 20 30 / 0.5)', opacity: '1' } });
                     const text = (t, w, parent) => el({ textContent: t, parentElement: parent,
                       style: { color: 'red', fontSize: '16px', fontWeight: w,
                                backgroundColor: 'rgba(0,0,0,0)', opacity: '1' } });
                     const edge = (w, st) => el({ parentElement: card,
                       style: { borderTopWidth: w, borderTopStyle: st, borderTopColor: 'green', opacity: '1' } });
                     pages['p'] = [text(' hi ', '700', page), text('  ', '400', page),
                                   text('x', '400', null), text('y', '400', null)];
                     pages['.field input'] = [edge('2px', 'solid'), edge('0px', 'solid'), edge('2px', 'none')];"
                    (js/contrast-rows-source
                     [{:id :body :selector "p" :limit 3}
                      {:id :field :selector ".field input" :role :non-text}]))]
      (testing "it collects the LAYERS and the opacity, and decides neither"
        (is (str/includes?
             out "[\"body-0\",\"red\",[\"rgb(10 20 30 / 0.5)\",\"rgba(0, 0, 0, 0.5)\",\"rgb(1, 2, 3)\"],null,16,true,0.8]")
            "a translucent card over a dark page is neither of those colours,
             and a computed colour does not carry the opacity property"))
      (testing "the root is the backstop, and the limit is honoured"
        (is (str/includes? out "[\"body-2\",\"red\",[\"rgb(255, 255, 255)\"],null,16,false,1]"))
        (is (not (str/includes? out "body-3"))))
      (testing "what would be a false failure is skipped, not reported"
        (is (not (str/includes? out "body-1")) "an empty node has no text to read")
        (is (str/includes? out "[\"field-0\",\"green\",[\"rgba(0, 0, 0, 0.5)\",\"rgb(1, 2, 3)\"],\"non-text\",null,false,0.8]"))
        (is (not (str/includes? out "field-1")) "a zero-width border has no edge")
        (is (not (str/includes? out "field-2")) "nor has a border styled none")))))

(deftest a-border-side-reaches-the-page-as-a-property-that-exists
  (testing "a keyword side is spelled the way computed style spells it"
    (doseq [[given expected] {:top "Top" :left "Left" :bottom "Bottom"
                              "Top" "Top" "left" "Left" nil "Top"}]
      (is (str/includes? (js/contrast-rows-source
                          [{:id :e :selector ".x" :role :non-text :side given}])
                         (str "\"side\": \"" expected "\""))
          (str (pr-str given) " must not emit a property nothing answers to")))))

(deftest one-bad-selector-is-one-bad-spec
  (let [source (js/contrast-rows-source
                [{:id :bad :selector "p:::nope"} {:id :good :selector "p"}])]
    (is (str/includes? source "catch (")
        "a selector the browser rejects must not abort every other spec")
    (when-let [out (on-page "pages['p'] = [el({ textContent: 'x',
                               style: { color: 'red', fontSize: '10px', fontWeight: '400', opacity: '1' } })];"
                            source)]
      (is (str/starts-with? out "[[\"bad-selector\",null,null,null,null,false,1],[\"good-0\"")
          "it is reported as one unresolvable row instead"))))

(deftest a-selector-cannot-smuggle-source-into-the-page
  (let [source (js/contrast-rows-source
                [{:id :evil :selector "a\"); alert(1); //"}])]
    (testing "the payload survives, INSIDE the string literal it was given as"
      (is (str/includes? source "\"selector\": \"a\\\"); alert(1); //\"")
          "the quote that would close the literal is escaped, so the rest of
           the selector stays data"))

    (testing "and it never appears unescaped, which is what breaking out means"
      (is (not (str/includes? source "\"selector\": \"a\");"))))))

(deftest expect-fits-asks-one-named-question-instead-of-a-copied-predicate
  ;; The manifest that motivated this carried the same overflow predicate six
  ;; times, once per viewport, because a viewport is per scenario. A step kind
  ;; makes the SELECTOR the only thing that varies.
  (let [source (js/assertion-source (op :expect-fits ".card"))]
    (is (= source (js/fits-source ".card")))
    (testing "the selector reaches the page as data, inside its own literal"
      (is (str/includes? source "querySelectorAll(\".card\")")))

    (testing "rectangles, because an inline element reports scrollWidth 0 and
              `scrollWidth <= clientWidth` is then 0 <= 0 on every input"
      (is (str/includes? source "getBoundingClientRect")))

    (testing "on a page"
      (let [fits (fn [setup] (on-page setup source))]
        (when (fits "")
          (testing "all fit: the number measured"
            (is (= "2" (fits "pages['.card'] = [box(0, 50), box(10, 20, {clientWidth: 20, scrollWidth: 20})];")))
            (is (= "1" (fits "pages['.card'] = [box(0, 101)];"))
                "within the tolerance"))
          (testing "an overflow answers false, which the runtime channel fails on"
            (is (= "false" (fits "pages['.card'] = [box(0, 50), box(80, 30)];")))
            (is (= "false" (fits "pages['.card'] = [box(-5, 50)];"))))
          (testing "the self-clip half is asked only of elements that have a box"
            (is (= "false" (fits "pages['.card'] = [box(0, 50, {clientWidth: 50, scrollWidth: 60})];")))
            (is (= "1" (fits "pages['.card'] = [box(0, 50), el({})];"))
                "an inline element, 0 <= 0, is neither measured nor a pass by itself"))
          (testing "nothing measurable THROWS — a gate that could not look must
                    never read as a gate that looked and was happy"
            (is (= "THROW expect-fits: nothing matches .card" (fits "")))
            (is (= "THROW expect-fits: 2 element(s) match .card and none has a rectangle"
                   (fits "pages['.card'] = [el({}), el({})];")))))))))

(deftest expect-fits-needs-no-probe-installed
  (is (not (str/includes? (js/fits-source ".card") "__hive__"))
      "a fit gate runs on any page, not only one the probe was injected into"))

(deftest a-fits-selector-cannot-smuggle-source-into-the-page
  (let [source (js/fits-source "a\"); alert(1); //")]
    (is (str/includes? source "querySelectorAll(\"a\\\"); alert(1); //\")")
        "the quote that would close the literal is escaped, so the rest stays data")
    (is (not (str/includes? source "\"a\");"))
        "and it never appears unescaped, which is what breaking out means")
    (when-let [out (on-page "" source)]
      (is (= "THROW expect-fits: nothing matches a\"); alert(1); //" out)
          "the page sees the selector as one string"))))

(deftest a-page-is-a-page-whatever-compiled-it
  ;; The same argument the JavaScript kinds already carry: a shadow-cljs app
  ;; renders to a DOM, so it can be asked whether that DOM fits.
  (is (= (str "(js->clj (js/eval " (pr-str (js/fits-source "html")) "))")
         (re-frame/assertion-source (op :expect-fits "html")))))

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
