(ns hive-cljs.example-elm-test
  "example/elm is executable documentation, so its config must not drift from
   the schema it documents. Everything here is checked WITHOUT a browser or an
   elm compiler: the manifest loads, every scenario compiles to a plan, and the
   page carries the one line that hands the Elm port to the injected probe.

   Running the scenarios for real is in example/elm/README.md."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.plan :as plan]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def root (.getCanonicalPath (io/file "example/elm")))

(defn- loaded []
  (boundary/load-manifest root))

(deftest the-example-manifest-validates
  (let [res (loaded)]
    (is (r/ok? res) (pr-str res))
    (let [m (:ok res)]
      (testing "it is resolved from its own directory, not the shadow example above it"
        (is (= root (str (:manifest/root m)))))
      (testing "the non-shadow toolchain, with elm make as the build verdict"
        (is (= :browser (:manifest/toolchain m)))
        (is (= ["npx" "--no-install" "elm" "make" "src/Main.elm" "--output=public/app.js"]
               (get (manifest/build-commands m) :app))))
      (testing "the scenarios the README names"
        (is (= [:smoke :add :clear] (mapv :id (manifest/scenarios m))))))))

(deftest every-scenario-compiles-to-a-plan
  (let [m (:ok (loaded))]
    (doseq [sc (manifest/scenarios m)]
      (let [p (plan/build-plan m sc)]
        (is (r/ok? p) (str (:id sc) " → " (pr-str p)))
        (is (= "http://localhost:8471" (get-in p [:ok :plan/base-url]))
            "base-url is inferred from the :app build's :http-port")))))

(deftest a-scenario-asserts-on-both-channels
  (testing ":add demonstrates the rendering-vs-state split in one step vector"
    (let [m     (:ok (loaded))
          kinds (->> (plan/build-plan m (manifest/scenario m :add))
                     :ok :plan/ops (map :op/kind) set)]
      (is (contains? kinds :expect-text) "rendering")
      (is (contains? kinds :expect-state) "state")
      (is (contains? kinds :wait-for-state)))))

(deftest the-page-hands-the-port-to-the-probe
  (let [html (slurp (io/file root "public/index.html"))
        elm  (slurp (io/file root "src/Main.elm"))]
    (is (str/includes? html "if (window.__hive__) app.ports.hiveState.subscribe(window.__hive__.pushed('model'))")
        "guarded with if: subscribing `?.pushed` would hand Elm undefined outside a scenario")
    (is (str/includes? elm "port hiveState : Encode.Value -> Cmd msg"))))
