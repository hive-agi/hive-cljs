(ns build
  "hive-cljs release tasks: hive-build's canonical tasks, with one override.

   `bump` honours a hand-set VERSION. When ./VERSION is already ahead of the
   newest v* tag it is published verbatim; otherwise it is bumped at :level
   from the newer of VERSION and that tag. The decision is the pure
   `hive-cljs.release/decide` (build/hive_cljs/release.clj, unit-tested in
   test/hive_cljs/release_test.clj); this file only reads git and writes
   VERSION.

   Every other task delegates to hive-build.api unchanged."
  (:require [clojure.string :as str]
            [hive-build.api :as api]
            [hive-build.collect.git :as git]
            [hive-cljs.release :as release]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn bump
  "Rewrite ./VERSION to the version this release publishes and print it.

   :level :patch (default) | :minor | :major — used only when VERSION is not
   already ahead of the newest v* tag. Does not commit, tag, or deploy."
  [{:keys [level] :or {level :patch}}]
  (let [current (or (some-> (slurp "VERSION") str/trim not-empty)
                    (throw (ex-info "No ./VERSION file to bump"
                                    {:cwd (System/getProperty "user.dir")})))
        tags (git/release-tags)
        {:keys [version mode latest]} (release/decide tags current level)]
    (spit "VERSION" (str version "\n"))
    (println (format "VERSION %s -> %s (%s; latest tag %s)"
                     current version
                     (if (= mode :verbatim) "verbatim, ahead of the tags" (name level))
                     (or latest "none")))
    version))

(defn clean [opts] (api/clean opts))
(defn jar [opts] (api/jar opts))
(defn jar-aot [opts] (api/jar-aot opts))
(defn install [opts] (api/install opts))
(defn kondo [opts] (api/kondo opts))
(defn verify-license [opts] (api/verify-license opts))
(defn audit-opacity [opts] (api/audit-opacity opts))
(defn freeze-check [opts] (api/freeze-check opts))
(defn readme-examples [opts] (api/readme-examples opts))
(defn changelog [opts] (api/changelog opts))
(defn deploy [opts] (api/deploy opts))
