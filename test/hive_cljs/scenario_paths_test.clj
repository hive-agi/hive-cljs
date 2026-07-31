(ns hive-cljs.scenario-paths-test
  "Scenarios declared next to the rest of the suite rather than in the root
   manifest, and the third staleness axis that reads the emitted bundle.

   Both touch the filesystem, so both are exercised against a real temp tree."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.staleness :as staleness]
            [hive-dsl.result :as r])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- tmp-dir []
  (str (Files/createTempDirectory "hive-cljs-test" (into-array FileAttribute []))))

(defn- spit-in!
  "Write `content` at `rel` under `root`, creating parents. Returns the path."
  [root rel content]
  (let [f (io/file root rel)]
    (io/make-parents f)
    (spit f content)
    (.getPath f)))

;; =============================================================================
;; :scenario-paths
;; =============================================================================

(deftest scenario-files-are-collected-in-a-stable-order
  (let [root (tmp-dir)]
    (spit-in! root "test/e2e/b_flows.edn" "[]")
    (spit-in! root "test/e2e/a_smoke.edn" "[]")
    (spit-in! root "test/e2e/notes.md" "not a scenario file")
    (let [found (boundary/scenario-files root ["test/e2e"])]
      (is (= 2 (count found)))
      (is (= ["a_smoke.edn" "b_flows.edn"] (mapv #(.getName (io/file %)) found))
          "sorted, so scenario order does not depend on directory listing order"))))

(deftest a-scenario-path-may-name-a-single-file
  (let [root (tmp-dir)]
    (spit-in! root "test/e2e/only.edn" "[]")
    (is (= 1 (count (boundary/scenario-files root ["test/e2e/only.edn"]))))))

(deftest a-path-that-does-not-exist-contributes-nothing
  (is (empty? (boundary/scenario-files (tmp-dir) ["test/e2e"]))))

(deftest scenario-files-compose-with-scenarios-declared-inline
  (let [root (tmp-dir)]
    (spit-in! root "test/e2e/flows.edn"
              (pr-str [{:id :from-file :steps [[:goto "/"]]}]))
    (let [raw {:hive.cljs/e2e {:scenario-paths ["test/e2e"]
                               :scenarios [{:id :inline :steps [[:goto "/x"]]}]}}
          [raw' files] (boundary/load-scenario-paths root raw)]
      (is (= [:inline :from-file]
             (mapv :id (get-in raw' [:hive.cljs/e2e :scenarios])))
          "inline first, then files — :scenarios and :scenario-paths compose")
      (is (= 1 (count files))))))

(deftest a-scenario-file-may-be-a-vector-a-map-or-a-wrapper
  (let [root (tmp-dir)]
    (spit-in! root "e2e/vec.edn"  (pr-str [{:id :a :steps [[:goto "/"]]}]))
    (spit-in! root "e2e/one.edn"  (pr-str {:id :b :steps [[:goto "/"]]}))
    (spit-in! root "e2e/wrap.edn" (pr-str {:scenarios [{:id :c :steps [[:goto "/"]]}]}))
    (let [[raw' _] (boundary/load-scenario-paths
                    root {:hive.cljs/e2e {:scenario-paths ["e2e"]}})]
      (is (= [:b :a :c] (mapv :id (get-in raw' [:hive.cljs/e2e :scenarios])))
          "one.edn, vec.edn, wrap.edn — sorted by path"))))

(deftest the-scenario-files-join-the-sources-so-an-edit-invalidates-the-cache
  (let [root (tmp-dir)]
    (spit-in! root "hive-cljs.edn"
              (pr-str {:hive.cljs/builds {:app {:http-port 8280}}
                       :hive.cljs/e2e    {:scenario-paths ["test/e2e"]}}))
    (let [scenario-path (spit-in! root "test/e2e/flows.edn"
                                  (pr-str [{:id :flow :steps [[:goto "/"]]}]))
          res (boundary/load-manifest root)]
      (is (r/ok? res) (pr-str res))
      (is (= [:flow] (mapv :id (manifest/scenarios (:ok res)))))
      (is (contains? (set (:manifest/sources (:ok res))) scenario-path)
          "editing a scenario file must look exactly like editing the manifest"))))

(deftest a-duplicate-scenario-id-is-a-config-error-not-a-silent-merge
  (let [root (tmp-dir)]
    (spit-in! root "hive-cljs.edn"
              (pr-str {:hive.cljs/builds {:app {:http-port 8280}}
                       :hive.cljs/e2e    {:scenario-paths ["test/e2e"]
                                          :scenarios [{:id :flow :steps [[:goto "/"]]}]}}))
    (spit-in! root "test/e2e/flows.edn"
              (pr-str [{:id :flow :steps [[:goto "/other"]]}]))
    (let [res (boundary/load-manifest root)]
      (is (r/err? res))
      (is (= :manifest/duplicate-scenario (:error res)))
      (is (= [:flow] (:ids res))))))

(deftest duplicate-detection-reports-every-collision-sorted
  (is (= [:a :b]
         (manifest/duplicate-scenario-ids
          [{:id :b} {:id :a} {:id :b} {:id :c} {:id :a}])))
  (is (= [] (manifest/duplicate-scenario-ids [{:id :a} {:id :b}])))
  (is (= [] (manifest/duplicate-scenario-ids []))))

;; =============================================================================
;; Staleness axis 3 — the emitted bundle vs its own sources
;; =============================================================================

(deftest bundle-freshness-needs-both-timestamps
  (testing "a missing side cannot support a staleness claim"
    (is (= :unknown (staleness/bundle-freshness 0 100)))
    (is (= :unknown (staleness/bundle-freshness 100 0)))
    (is (= :unknown (staleness/bundle-freshness nil 100)))
    (is (= :unknown (staleness/bundle-freshness 100 nil))))

  (testing "output older than source is the outage this axis exists for"
    (is (= :stale (staleness/bundle-freshness 100 200))))

  (testing "output at or after source is fresh"
    (is (= :fresh (staleness/bundle-freshness 200 100)))
    (is (= :fresh (staleness/bundle-freshness 100 100)))))

(deftest a-stale-bundle-is-named-so-doctor-can-warn-about-it
  (let [stamps (staleness/bundle-stamps {:app   {:output-dir "out" :compiled 100 :newest-source 200}
                                         :admin {:output-dir "out2" :compiled 300 :newest-source 200}})]
    (is (= [:admin :app] (mapv :bundle/build stamps)) "sorted by build id")
    (is (= [:app] (staleness/stale-bundles stamps)))))

(deftest bundle-facts-are-read-off-the-project-shadow-config
  (let [root (tmp-dir)]
    (spit-in! root "shadow-cljs.edn"
              (pr-str {:source-paths ["src"]
                       :builds {:app {:target :browser :output-dir "public/js"}}}))
    (spit-in! root "src/app/core.cljs" "(ns app.core)")
    (spit-in! root "public/js/main.js" "// compiled")
    (let [facts (boundary/bundle-facts root [:app])]
      (is (= "public/js" (get-in facts [:app :output-dir])))
      (is (pos? (get-in facts [:app :compiled])))
      (is (pos? (get-in facts [:app :newest-source]))))

    (testing "a build the config does not declare has no output to compare"
      (is (zero? (get-in (boundary/bundle-facts root [:ghost]) [:ghost :compiled]))))))

(deftest a-project-without-a-shadow-config-reports-no-bundle-facts
  (is (= {} (boundary/bundle-facts (tmp-dir) [:app]))))

(deftest a-bundle-older-than-its-sources-is-detected-end-to-end
  (let [root (tmp-dir)]
    (spit-in! root "shadow-cljs.edn"
              (pr-str {:source-paths ["src"] :builds {:app {:output-dir "public/js"}}}))
    (let [bundle (io/file (spit-in! root "public/js/main.js" "// compiled"))
          source (io/file (spit-in! root "src/app/core.cljs" "(ns app.core)"))]
      (.setLastModified bundle 1000000)
      (.setLastModified source 2000000)
      (let [stamps (staleness/bundle-stamps (boundary/bundle-facts root [:app]))]
        (is (= [:app] (staleness/stale-bundles stamps)))))))
