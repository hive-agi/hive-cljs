(ns hive-cljs.config-test
  "Config discovery: `.hive-project.edn` (:hive.cljs …) and/or `hive-cljs.edn`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn- mkdir-under
  "Create `segments` beneath `root` and return the absolute path."
  [root & segments]
  (let [d (apply io/file root segments)]
    (.mkdirs d)
    (.getAbsolutePath d)))

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
      (testing "both sources are recorded, highest precedence first"
        (is (= ["hive-cljs.edn" ".hive-project.edn"]
               (mapv #(.getName (io/file %)) (:manifest/sources m))))
        (is (= [(.getPath (io/file root "hive-cljs.edn"))
                (.getPath (io/file root ".hive-project.edn"))]
               (:manifest/sources m)))))))

(deftest a-descriptor-without-hive-cljs-keys-is-not-a-source
  (let [root (tmp-root "no-keys")]
    (spit-edn root ".hive-project.edn" {:project-id "demo" :parent "hive"
                                        :carto {:source-roots ["src"]}})
    (let [res      (boundary/load-manifest root)
          searched (:searched res)]
      (is (r/err? res))
      (is (= :manifest/not-found (:error res)))
      (testing "the descriptor that authors nothing is still reported as searched"
        (is (contains? (set searched) (.getPath (io/file root ".hive-project.edn")))))
      (testing "and so is the dedicated file that never existed"
        (is (contains? (set searched) (.getPath (io/file root "hive-cljs.edn"))))))))

(deftest neither-file-reports-what-was-searched
  (let [root   (tmp-root "empty")
        parent (.getParent (io/file root))
        res    (boundary/load-manifest root)
        names  (mapv #(.getName (io/file %)) (:searched res))]
    (is (= :manifest/not-found (:error res)))
    (testing "the invocation directory is searched first, dedicated file before descriptor"
      (is (= [(.getPath (io/file root "hive-cljs.edn"))
              (.getPath (io/file root ".hive-project.edn"))]
             (vec (take 2 (:searched res))))))
    (testing "every level contributes the same precedence-ordered pair"
      (is (seq names))
      (is (zero? (mod (count names) 2)))
      (is (every? #(= ["hive-cljs.edn" ".hive-project.edn"] (vec %))
                  (partition 2 names))))
    (testing "the walk does not stop at the invocation directory"
      (is (> (count names) 2))
      (is (contains? (set (:searched res)) (.getPath (io/file parent "hive-cljs.edn"))))
      (is (contains? (set (:searched res)) (.getPath (io/file parent ".hive-project.edn")))))
    (testing "and it reports descendants that could have been meant instead"
      (is (= [] (:candidates res))))))

(deftest unreadable-edn-is-a-typed-error
  (let [root (tmp-root "broken")]
    (spit (io/file root "hive-cljs.edn") "{:hive.cljs/builds {:app ")
    (let [res (boundary/load-manifest root)]
      (is (= :manifest/unreadable (:error res)))
      (is (string? (:cause res))))))

(deftest config-is-discovered-by-walking-up-from-a-subdirectory
  (let [ws   (tmp-root "walk-up")
        proj (mkdir-under ws "proj")
        sub  (mkdir-under ws "proj" "src" "app")]
    (spit-edn proj "hive-cljs.edn" {:hive.cljs/builds {:app {:http-port 8290}}})
    (let [res (boundary/load-manifest sub)
          m   (:ok res)]
      (is (r/ok? res))
      (testing "the nearest authoring directory becomes the root, not the invocation dir"
        (is (= proj (:manifest/root m))))
      (testing "derived paths follow the discovered root"
        (is (str/starts-with? (get-in m [:manifest/e2e :artifacts-dir]) proj)))
      (testing "the config found up there is the one that applies"
        (is (= [:app] (manifest/build-ids m)))
        (is (= "http://localhost:8290" (get-in m [:manifest/e2e :base-url])))
        (is (= [(.getPath (io/file proj "hive-cljs.edn"))] (:manifest/sources m)))))))

(deftest ancestor-config-does-not-leak-without-opt-in
  (let [ws   (tmp-root "no-inherit")
        proj (mkdir-under ws "proj")]
    (spit-edn ws "hive-cljs.edn"
              {:hive.cljs/shadow {:port 9700 :nrepl-port 7999}
               :hive.cljs/builds {:workspace {:http-port 8999}}})
    (spit-edn proj "hive-cljs.edn" {:hive.cljs/shadow {:port 9633}})
    (let [res (boundary/load-manifest proj)
          m   (:ok res)]
      (is (r/ok? res))
      (is (= proj (:manifest/root m)))
      (testing "the nearest level's own keys are in force"
        (is (= 9633 (get-in m [:manifest/shadow :port]))))
      (testing "ancestor-only keys never appear without :hive.cljs/inherit"
        (is (nil? (get-in m [:manifest/shadow :nrepl-port])))
        (is (= [] (manifest/build-ids m))))
      (testing "and the ancestor's file is not recorded as a source"
        (is (= [(.getPath (io/file proj "hive-cljs.edn"))] (:manifest/sources m)))))))

(deftest opting-in-merges-ancestor-config-underneath
  (let [ws   (tmp-root "inherit")
        proj (mkdir-under ws "proj")]
    (spit-edn ws "hive-cljs.edn"
              {:hive.cljs/shadow {:port 9700 :nrepl-port 7999}
               :hive.cljs/builds {:workspace {:http-port 8999}}})
    (spit-edn proj "hive-cljs.edn"
              {:hive.cljs/inherit true
               :hive.cljs/shadow  {:port 9633}})
    (let [res (boundary/load-manifest proj)
          m   (:ok res)]
      (is (r/ok? res))
      (is (= proj (:manifest/root m)))
      (testing "the child still wins on a key both levels set"
        (is (= 9633 (get-in m [:manifest/shadow :port]))))
      (testing "ancestor-only keys are merged underneath the child's"
        (is (= 7999 (get-in m [:manifest/shadow :nrepl-port])))
        (is (= [:workspace] (manifest/build-ids m))))
      (testing "both levels are recorded, nearest first"
        (is (= [(.getPath (io/file proj "hive-cljs.edn"))
                (.getPath (io/file ws "hive-cljs.edn"))]
               (:manifest/sources m)))))))

(deftest sources-are-ordered-highest-precedence-first-across-levels
  (let [ws   (tmp-root "precedence")
        proj (mkdir-under ws "proj")]
    (spit-edn ws ".hive-project.edn"
              {:project-id "ws"
               :hive.cljs {:shadow {:host "ws-host"}
                           :builds {:workspace {:http-port 8999}}}})
    (spit-edn ws "hive-cljs.edn"
              {:hive.cljs/shadow {:port 9700 :nrepl-port 7999}})
    (spit-edn proj ".hive-project.edn"
              {:project-id "proj"
               :hive.cljs {:shadow {:port 9999}
                           :builds {:app {:http-port 8280}}}})
    (spit-edn proj "hive-cljs.edn"
              {:hive.cljs/inherit true
               :hive.cljs/shadow  {:port 9633}})
    (let [res (boundary/load-manifest proj)
          m   (:ok res)]
      (is (r/ok? res))
      (testing "nearest level before its ancestor, dedicated file before descriptor"
        (is (= [(.getPath (io/file proj "hive-cljs.edn"))
                (.getPath (io/file proj ".hive-project.edn"))
                (.getPath (io/file ws "hive-cljs.edn"))
                (.getPath (io/file ws ".hive-project.edn"))]
               (:manifest/sources m))))
      (testing "the winning value comes from the first source that sets it"
        (is (= 9633 (get-in m [:manifest/shadow :port])))
        (is (= 7999 (get-in m [:manifest/shadow :nrepl-port])))
        (is (= "ws-host" (get-in m [:manifest/shadow :host]))))
      (testing "and every level's builds survive"
        (is (= #{:app :workspace} (set (manifest/build-ids m))))))))

(def ^:private absent-env-var
  "A variable name no process is expected to export."
  "HIVE_CLJS_CONFIG_TEST_ABSENT_VAR")

(defn- port-shaped-env-vars
  "Names of inherited environment variables whose value reads as a port-range
   integer and whose name is a legal EDN symbol. Sorted, so the pick is stable."
  []
  (->> (into {} (System/getenv))
       (keep (fn [[k v]]
               (when (re-matches #"[A-Za-z_][A-Za-z0-9_]*" (str k))
                 (when-let [n (when (re-matches #"\d+" (str v)) (parse-long (str v)))]
                   (when (<= 1 n 65535) (str k))))))
       sort
       vec))

(deftest env-tag-reads-the-environment-with-a-fallback
  (let [root (tmp-root "env")]
    (testing "preconditions the assertions below rest on"
      (is (not (str/blank? (System/getenv "PATH"))))
      (is (str/blank? (System/getenv absent-env-var))))
    (spit (io/file root "hive-cljs.edn")
          (str "{:hive.cljs/e2e    {:artifacts-dir #hive/env PATH}\n"
               " :hive.cljs/shadow {:nrepl-port #hive/env [" absent-env-var " 7891]}}\n"))
    (let [res (boundary/load-manifest root)
          m   (:ok res)]
      (is (r/ok? res))
      (testing "a bare tag reads the variable"
        (is (= (System/getenv "PATH") (get-in m [:manifest/e2e :artifacts-dir]))))
      (testing "a vector tag falls back when the variable is unset or blank"
        (is (= 7891 (get-in m [:manifest/shadow :nrepl-port])))))))

(deftest env-tag-coerces-an-integer-valued-variable
  (let [candidates (port-shaped-env-vars)]
    (if-let [var-name (first candidates)]
      (let [root (tmp-root "env-int")]
        (spit (io/file root "hive-cljs.edn")
              (str "{:hive.cljs/shadow {:nrepl-port #hive/env " var-name "}}\n"))
        (let [res  (boundary/load-manifest root)
              port (get-in (:ok res) [:manifest/shadow :nrepl-port])]
          (is (r/ok? res))
          (testing "the string value becomes a long, not a string"
            (is (int? port))
            (is (= (parse-long (System/getenv var-name)) port)))))
      (is (empty? candidates)
          "no inherited variable reads as an integer, so coercion is unobservable here"))))

(deftest not-found-points-at-descendants-that-do-author-config
  (let [ws   (tmp-root "candidates")
        app  (mkdir-under ws "app")
        lib  (mkdir-under ws "lib")
        docs (mkdir-under ws "docs")]
    (spit-edn app "hive-cljs.edn" {:hive.cljs/builds {:app {:http-port 8280}}})
    (spit-edn lib ".hive-project.edn" {:project-id "lib"
                                       :hive.cljs {:builds {:lib {:http-port 8281}}}})
    (let [res (boundary/load-manifest ws)]
      (is (r/err? res))
      (is (= :manifest/not-found (:error res)))
      (testing "both authoring children are offered, whichever file authored"
        (is (= [app lib] (:candidates res))))
      (testing "a directory that authors nothing is not offered"
        (is (not (contains? (set (:candidates res)) docs)))))))
