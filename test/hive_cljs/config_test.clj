(ns hive-cljs.config-test
  "Config discovery: `.hive-project.edn` (:hive.cljs …) and/or `hive-cljs.edn`."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.manifest :as manifest]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Pure extraction
;; =============================================================================

(deftest project-config-accepts-both-spellings
  (testing "nested :hive.cljs submap with short keys"
    (is (= {:hive.cljs/builds {:app {}}}
           (manifest/project-config {:project-id "x" :hive.cljs {:builds {:app {}}}}))))

  (testing "flat :hive.cljs/* keys"
    (is (= {:hive.cljs/builds {:app {}}}
           (manifest/project-config {:project-id "x" :hive.cljs/builds {:app {}}}))))

  (testing "unrelated descriptor keys are ignored"
    (is (= {} (manifest/project-config {:project-id "x" :parent "hive"
                                        :carto {:source-roots ["src"]}}))))

  (testing "flat wins over nested on collision"
    (is (= {:hive.cljs/builds {:flat {}}}
           (manifest/project-config {:hive.cljs        {:builds {:nested {}}}
                                     :hive.cljs/builds {:flat {}}}))))

  (testing "degrades rather than throws"
    (is (= {} (manifest/project-config nil)))
    (is (= {} (manifest/project-config [1 2 3])))
    (is (= {} (manifest/project-config {:hive.cljs "not-a-map"})))))

(deftest merge-config-lets-the-later-source-win-per-section
  (is (= {:hive.cljs/shadow {:port 9630 :nrepl-port 7889}}
         (manifest/merge-config {:hive.cljs/shadow {:port 9630}}
                                {:hive.cljs/shadow {:nrepl-port 7889}})))
  (testing "a scalar in a later source replaces"
    (is (= {:hive.cljs/shadow {:port 9999}}
           (manifest/merge-config {:hive.cljs/shadow {:port 9630}}
                                  {:hive.cljs/shadow {:port 9999}}))))
  (testing "nils are skipped and the empty case is a map"
    (is (= {} (manifest/merge-config nil nil)))))

;; =============================================================================
;; File discovery
;; =============================================================================

(defn- tmp-root
  "A fresh temp project dir. Tests never touch a real project path."
  [label]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "hive-cljs-" label "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getAbsolutePath d)))

(defn- spit-edn [root filename form]
  (spit (io/file root filename) (pr-str form)))

(deftest loads-from-the-project-descriptor-alone
  (let [root (tmp-root "descriptor")]
    (spit-edn root ".hive-project.edn"
              {:project-id "demo" :parent "hive"
               :hive.cljs {:builds {:app {:http-port 8280}}
                           :shadow {:nrepl-port 7889}}})
    (let [res (boundary/load-manifest root)]
      (is (r/ok? res))
      (is (= [:app] (manifest/build-ids (:ok res))))
      (is (= 7889 (get-in res [:ok :manifest/shadow :nrepl-port])))
      (is (= "http://localhost:8280" (get-in res [:ok :manifest/e2e :base-url])))
      (testing "the source is recorded"
        (is (= [".hive-project.edn"]
               (mapv #(.getName (io/file %)) (get-in res [:ok :manifest/sources]))))))))

(deftest loads-from-the-dedicated-file-alone
  (let [root (tmp-root "dedicated")]
    (spit-edn root "hive-cljs.edn" {:hive.cljs/builds {:app {:http-port 8080}}})
    (let [res (boundary/load-manifest root)]
      (is (r/ok? res))
      (is (= [:app] (manifest/build-ids (:ok res))))
      (is (= ["hive-cljs.edn"]
             (mapv #(.getName (io/file %)) (get-in res [:ok :manifest/sources])))))))

(deftest both-sources-merge-and-the-dedicated-file-wins
  (let [root (tmp-root "both")]
    (spit-edn root ".hive-project.edn"
              {:project-id "demo"
               :hive.cljs {:shadow {:port 9630 :nrepl-port 7889}
                           :builds {:app {:http-port 8280}}}})
    (spit-edn root "hive-cljs.edn"
              {:hive.cljs/shadow {:port 9633}
               :hive.cljs/e2e    {:scenarios [{:id :smoke :steps [[:goto "/"]]}]}})
    (let [res  (boundary/load-manifest root)
          m    (:ok res)]
      (is (r/ok? res))
      (testing "the dedicated file overrides the descriptor key by key"
        (is (= 9633 (get-in m [:manifest/shadow :port]))))
      (testing "and the descriptor's other keys in the SAME section survive"
        (is (= 7889 (get-in m [:manifest/shadow :nrepl-port]))))
      (testing "sections only one source declares are kept"
        (is (= [:app] (manifest/build-ids m)))
        (is (= [:smoke] (mapv :id (manifest/scenarios m)))))
      (testing "both sources are recorded, descriptor first"
        (is (= [".hive-project.edn" "hive-cljs.edn"]
               (mapv #(.getName (io/file %)) (:manifest/sources m))))))))

(deftest a-descriptor-without-hive-cljs-keys-is-not-a-source
  (let [root (tmp-root "no-keys")]
    (spit-edn root ".hive-project.edn" {:project-id "demo" :parent "hive"
                                        :carto {:source-roots ["src"]}})
    (let [res (boundary/load-manifest root)]
      (is (r/err? res))
      (is (= :manifest/not-found (:error res)))
      (is (= 2 (count (:searched res)))))))

(deftest neither-file-reports-what-was-searched
  (let [res (boundary/load-manifest (tmp-root "empty"))]
    (is (= :manifest/not-found (:error res)))
    (is (= [".hive-project.edn" "hive-cljs.edn"]
           (mapv #(.getName (io/file %)) (:searched res))))))

(deftest unreadable-edn-is-a-typed-error
  (let [root (tmp-root "broken")]
    (spit (io/file root "hive-cljs.edn") "{:hive.cljs/builds {:app ")
    (let [res (boundary/load-manifest root)]
      (is (= :manifest/unreadable (:error res)))
      (is (string? (:cause res))))))
