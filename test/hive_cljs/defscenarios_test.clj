(ns hive-cljs.defscenarios-test
  "The generated-suite surface: scenarios a project declares become ordinary
   `clojure.test` vars.

   Expansion reads a real manifest off disk, so every case runs against a temp
   project tree. Nothing here opens a browser — the generated bodies are
   inspected, never invoked."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.test :as gen])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- tmp-dir []
  (str (Files/createTempDirectory "hive-cljs-gen" (into-array FileAttribute []))))

(defn- spit-in! [root rel content]
  (let [f (io/file root rel)]
    (io/make-parents f)
    (spit f content)
    (.getPath f)))

(defn- project!
  "A temp project root authoring `hive-cljs.edn` with `scenarios`."
  [scenarios]
  (let [root (tmp-dir)]
    (spit-in! root "hive-cljs.edn"
              (pr-str {:hive.cljs/builds {:app {:http-port 8280}}
                       :hive.cljs/e2e    {:scenarios scenarios}}))
    root))

(defn- expand
  "Macroexpansion of `(defscenarios opts)`."
  [opts]
  (macroexpand-1 (list `gen/defscenarios opts)))

(defn- deftests
  "The `clojure.test/deftest` forms in an expansion."
  [form]
  (filter #(and (seq? %) (= 'clojure.test/deftest (first %))) (rest form)))

(def ^:private two-scenarios
  [{:id :login    :tags [:smoke] :doc "signs in" :steps [[:goto "/"]]}
   {:id :checkout :tags [:slow]  :steps [[:goto "/cart"]]}])

;; =============================================================================
;; Naming
;; =============================================================================

(deftest a-scenario-id-becomes-a-legal-test-symbol
  (is (= 'login (gen/test-sym :login)))
  (is (= 'checkout-happy-path (gen/test-sym :checkout/happy-path))
      "both halves of a namespaced id survive")
  (is (= 'a-b-c (gen/test-sym :a.b/c)) "dots would read as a namespace separator")
  (is (= 'ok? (gen/test-sym :ok?)) "symbol-legal punctuation is kept"))

(deftest ids-that-munge-to-one-symbol-are-reported
  (is (= '[a-b] (gen/duplicate-test-syms [{:id :a/b} {:id :a-b} {:id :c}]))
      "a silent overwrite is a scenario that stops running")
  (is (= [] (gen/duplicate-test-syms [{:id :a} {:id :b}])))
  (is (= [] (gen/duplicate-test-syms []))))

(deftest scenario-tags-become-var-metadata
  (let [m (gen/scenario-meta (first two-scenarios))]
    (is (true? (:smoke m)) "so kaocha --focus-meta :smoke selects it")
    (is (= :login (:hive.cljs/scenario m)))
    (is (= "signs in" (:doc m))))
  (testing "a scenario without tags or doc still carries its id"
    (is (= {:hive.cljs/scenario :bare}
           (gen/scenario-meta {:id :bare :steps [[:goto "/"]]})))))

;; =============================================================================
;; Planning
;; =============================================================================

(deftest planning-resolves-the-root-by-walking-up
  (let [root (project! two-scenarios)
        [resolved scenarios] (gen/plan-scenarios (str root "/test") nil)]
    (is (= root resolved) "a test ns runs from a subdirectory, not the project root")
    (is (= [:login :checkout] (mapv :id scenarios)))))

(deftest planning-narrows-to-the-requested-tags
  (let [root (project! two-scenarios)]
    (is (= [:login] (mapv :id (second (gen/plan-scenarios root [:smoke])))))
    (is (= [:login :checkout] (mapv :id (second (gen/plan-scenarios root []))))
        "no tags selects everything")))

(deftest planning-without-a-manifest-is-an-error-not-an-empty-suite
  (let [e (is (thrown? clojure.lang.ExceptionInfo (gen/plan-scenarios (tmp-dir) nil)))]
    (is (= :manifest/not-found (:error (ex-data e))))))

(deftest planning-refuses-colliding-test-names
  (let [root (project! [{:id :a/b :steps [[:goto "/"]]}
                        {:id :a-b :steps [[:goto "/"]]}])
        e    (is (thrown? clojure.lang.ExceptionInfo (gen/plan-scenarios root nil)))]
    (is (= :test/duplicate-test-sym (:error (ex-data e))))
    (is (= '[a-b] (:syms (ex-data e))))))

;; =============================================================================
;; Expansion
;; =============================================================================

(deftest one-deftest-is-emitted-per-scenario
  (let [root  (project! two-scenarios)
        forms (deftests (expand {:root root :fixture? false}))]
    (is (= '[login checkout] (mapv second forms)))
    (is (= {:smoke true :hive.cljs/scenario :login :doc "signs in"}
           (meta (second (first forms))))
        "metadata rides the emitted symbol, so `def` lands it on the var")))

(deftest the-teardown-fixture-is-emitted-by-default
  (let [root (project! two-scenarios)
        fixtures (filter #(and (seq? %) (= 'clojure.test/use-fixtures (first %)))
                         (rest (expand {:root root})))]
    (is (= 1 (count fixtures)))
    (is (= [:once `gen/teardown] (vec (drop 1 (first fixtures))))
        "a live relay keeps the JVM alive after the last deftest returns")
    (testing "and is opt-out for a suite that closes projects itself"
      (is (empty? (filter #(and (seq? %) (= 'clojure.test/use-fixtures (first %)))
                          (rest (expand {:root root :fixture? false}))))))))

(deftest a-tag-selection-generates-only-its-own-scenarios
  (let [root (project! two-scenarios)]
    (is (= '[login] (mapv second (deftests (expand {:root root :tags [:smoke]
                                                    :fixture? false})))))))

(deftest a-selection-that-matches-nothing-emits-a-failing-test
  (let [root  (project! two-scenarios)
        forms (deftests (expand {:root root :tags [:nope] :fixture? false}))]
    (is (= '[no-scenarios-declared] (mapv second forms))
        "an empty generated suite must not read green")
    (binding [*ns* (create-ns 'hive-cljs.defscenarios-test.empty-fixture)]
      (eval (first forms)))
    (let [v (ns-resolve 'hive-cljs.defscenarios-test.empty-fixture 'no-scenarios-declared)
          reported (atom [])]
      (binding [clojure.test/report #(swap! reported conj (:type %))]
        ((:test (meta v))))
      (is (= [:fail] @reported)))))

(deftest the-expansion-evaluates-into-runnable-test-vars
  (let [root (project! two-scenarios)
        ns'  (create-ns 'hive-cljs.defscenarios-test.fixture)]
    (binding [*ns* ns'] (eval (expand {:root root})))
    (let [v (ns-resolve ns' 'checkout)]
      (is (some? v))
      (is (fn? (:test (meta v))) "kaocha finds a test by the :test meta")
      (is (true? (:slow (meta v))))
      (is (= [gen/teardown] (:clojure.test/once-fixtures (meta ns')))))))
