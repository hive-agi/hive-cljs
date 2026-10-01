(ns hive-cljs.selector-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-cljs.schema :as s]
            [hive-cljs.selector :as sel]
            [hive-cljs.step :as step]
            [hive-dsl.result :as r]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(deftest strings-pass-through-untouched
  (doseq [x ["#go" ".row > td" "text=Sign in" "li:has-text(\"X\")" "role=button[name=\"OK\"]"]]
    (is (= x (sel/css x)))))

(deftest keywords-are-tag-id-class
  (is (= "#go" (sel/css :#go)))
  (is (= ".row" (sel/css :.row)))
  (is (= "li.row.active" (sel/css :li.row.active)))
  (is (= "button#ok.primary" (sel/css :button#ok.primary)))
  (is (= "*" (sel/css :*))))

(deftest attribute-maps
  (is (= "[role=\"tablist\"]" (sel/css {:role "tablist"})))
  (is (= "[data-testid^=\"site-proposta-\"]" (sel/css {:data-testid/prefix "site-proposta-"})))
  (is (= "[href$=\".pdf\"]" (sel/css {:href/suffix ".pdf"})))
  (is (= "[title*=\"a\"]" (sel/css {:title/contains "a"})))
  (is (= "[disabled]" (sel/css {:disabled true})))
  (is (= "#x.a.b[type=\"submit\"]" (sel/css {:id "x" :class "a b" :type "submit"})))
  (is (= "[aria-label=\"say \\\"hi\\\"\"]" (sel/css {:aria-label "say \"hi\""}))
      "quotes in a value are escaped, never terminate the string")
  (is (= "[data-n=\"3\"]" (sel/css {:data-n 3}))))

(deftest hiccup-nesting-is-descendant
  (is (= "li[data-testid^=\"site-proposta-\"]:has-text(\"X\") button[data-testid=\"aprovar\"]"
         (sel/css [:li {:data-testid/prefix "site-proposta-" :has-text "X"}
                   [:button {:data-testid "aprovar"}]])))
  (is (= "ul li a" (sel/css [:ul [:li [:a]]])))
  (is (= "form#login input[name=\"user\"]"
         (sel/css [:form#login [:input {:name "user"}]]))))

(deftest combinators
  (is (= "nav > a.active" (sel/css [:> :nav :a.active])))
  (is (= "main [role=\"dialog\"] button" (sel/css [:in :main {:role "dialog"} :button])))
  (is (= ":is(#a, .b)" (sel/css [:or :#a :.b])))
  (is (= "li:has(button.del):not(.done)" (sel/css [:li {:has :button.del :not :.done}]))))

(deftest helpers-return-data-that-composes
  (is (= {:data-testid "ok"} (sel/testid "ok")))
  (is (= "button[data-testid=\"ok\"]" (sel/css (sel/testid :button "ok"))))
  (is (= "li[data-testid^=\"p-\"]" (sel/css (sel/testid-prefix :li "p-"))))
  (is (= "[aria-selected=\"true\"][role=\"tab\"]"
         (sel/css (sel/role "tab" {:aria-selected "true"}))))
  (is (= "[role=\"tablist\"] [role=\"tab\"]"
         (sel/css (sel/within (sel/role "tablist") (sel/role "tab")))))
  (is (= "ul > li" (sel/css (sel/child-of :ul :li))))
  (is (= ":is(#a, #b)" (sel/css (sel/any-of :#a :#b))))
  (is (= "li:has-text(\"X\")" (sel/css (sel/with-text :li "X"))))
  (is (= "li.p:has-text(\"X\")" (sel/css (sel/with-text [:li.p] "X"))))
  (is (= "[data-testid^=\"p-\"]:has-text(\"X\")"
         (sel/css (sel/with-text (sel/testid-prefix "p-") "X")))))

(sel/defselector proposal-row
  "A proposal row."
  [:li {:data-testid/prefix "site-proposta-"}])

(deftest defselector-precompiles-literal-selectors
  (is (= [:li {:data-testid/prefix "site-proposta-"}] proposal-row))
  (is (= "li[data-testid^=\"site-proposta-\"]" (:selector/css (meta #'proposal-row))))
  (is (= "A proposal row." (:doc (meta #'proposal-row))))
  (testing "a malformed literal fails at macroexpansion"
    (is (thrown? Exception
                 (macroexpand-1 '(hive-cljs.selector/defselector bad [:li :a :b]))))))

(deftest malformed-selectors-are-typed-errors
  (doseq [bad ["" "  " [] {} 42 nil :foo/bar :a#
               [:li :a :b]            ; two children
               ["li"]                 ; head is not a keyword
               [:in]                  ; empty chain
               {:data-testid/bogus "x"}
               {:data-testid/prefix ""}
               {"role" "x"}
               {:visible "yes"}
               {:role {:nested 1}}]]
    (testing (pr-str bad)
      (let [res (sel/compile-selector bad)]
        (is (= :selector/malformed (:error res)))
        (is (string? (:problem res)))
        (is (= bad (:selector res)))))))

(deftest css-dialect-refuses-playwright-pseudos
  (is (r/ok? (sel/compile-selector {:has-text "x"})))
  (is (= :selector/malformed
         (:error (sel/compile-selector {:has-text "x"} {:dialect :css})))))

;; -----------------------------------------------------------------------------
;; Wiring: the step compiler is the single point selectors are compiled
;; -----------------------------------------------------------------------------

(deftest steps-compile-selector-data
  (let [res (step/compile-step [:click [:li {:has-text "X"} [:button {:data-testid "ok"}]]])
        op  (:ok res)]
    (is (r/ok? res))
    (is (m/validate s/Op op))
    (is (= ["li:has-text(\"X\") button[data-testid=\"ok\"]"] (:op/args op)))
    (is (= [:click [:li {:has-text "X"} [:button {:data-testid "ok"}]]] (:op/source op))
        "the authored datum survives for the report"))
  (is (= ["#user" "pedro"] (:op/args (:ok (step/compile-step [:fill :#user "pedro"])))))
  (is (= ["[role=\"tab\"]" 3] (:op/args (:ok (step/compile-step [:expect-count {:role "tab"} 3])))))
  (is (= [".a" "href" "x"] (:op/args (:ok (step/compile-step [:expect-attr :.a "href" "x"]))))))

(deftest string-steps-are-unchanged
  (doseq [st [[:click "#a"] [:fill "#a" "v"] [:expect-text ".x" "t"] [:expect-fits "main"]]]
    (is (= (vec (rest st)) (:op/args (:ok (step/compile-step st)))))))

(deftest non-selector-arguments-are-not-compiled
  (is (= ["/x"] (:op/args (:ok (step/compile-step [:goto "/x"])))))
  (is (= ["shot"] (:op/args (:ok (step/compile-step [:screenshot "shot"])))))
  (is (= [[:evt]] (:op/args (:ok (step/compile-step [:dispatch [:evt]]))))))

(deftest a-malformed-selector-fails-the-plan
  (let [res (step/compile-step [:click [:li :a :b]])]
    (is (= :selector/malformed (:error res)))
    (is (= :click (:kind res))))
  (is (= :selector/malformed (:error (step/compile-step [:expect-fits {:has-text "x"}])))
      ":expect-fits runs querySelectorAll in the page, which has no :has-text")
  (let [res (step/compile-steps [[:goto "/"] [:click 42]])]
    (is (= 1 (:index res)))))

(deftest selector-schema-agrees-with-the-compiler
  (is (m/validate s/Selector "#a"))
  (is (m/validate s/Selector [:li {:has-text "X"}]))
  (is (not (m/validate s/Selector [:li :a :b])))
  (is (not (m/validate s/Selector ""))))

;; -----------------------------------------------------------------------------
;; Round-trip property: data → selector is total over well-formed data,
;; deterministic, and every string it produces is itself a selector that
;; passes through unchanged.
;; -----------------------------------------------------------------------------

(def gen-ident
  (gen/fmap (fn [[c cs]] (apply str c cs))
            (gen/tuple gen/char-alpha (gen/vector (gen/elements (seq "abcxyz019-_")) 0 6))))

(def gen-tag (gen/elements [:div :li :button :a :span :input :*]))

(def gen-keyword-sel
  (gen/fmap (fn [[t id cls]]
              (keyword (str (name t) (when id (str "#" id)) (apply str (map #(str "." %) cls)))))
            (gen/tuple gen-tag (gen/one-of [(gen/return nil) gen-ident]) (gen/vector gen-ident 0 2))))

(def gen-attrs
  (gen/fmap (fn [[k op v t]]
              (cond-> {(if op (keyword k op) (keyword k)) v}
                t (assoc :has-text t)))
            (gen/tuple gen-ident
                       (gen/elements [nil "prefix" "suffix" "contains"])
                       (gen/not-empty gen/string-alphanumeric)
                       (gen/one-of [(gen/return nil) gen/string]))))

(def gen-selector
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of
      [(gen/fmap (fn [[t a c]] (cond-> [t] a (conj a) c (conj c)))
                 (gen/tuple gen-tag (gen/one-of [(gen/return nil) gen-attrs])
                            (gen/one-of [(gen/return nil) inner])))
       (gen/fmap #(into [:in] %) (gen/vector inner 1 3))
       (gen/fmap #(into [:>] %) (gen/vector inner 1 3))]))
   (gen/one-of [gen-keyword-sel gen-attrs])))

(defspec data-to-selector-round-trips 200
  (prop/for-all [x gen-selector]
    (let [res (sel/compile-selector x)
          out (:ok res)]
      (and (r/ok? res)
           (string? out)
           (not (str/blank? out))
           (= out (sel/css x))
           (= out (sel/css out))
           (= out (first (:op/args (:ok (step/compile-step [:click x])))))))))
