(ns hive-cljs.staleness-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.schema :as s]
            [hive-cljs.staleness :as staleness]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- stamp
  "A conforming `schema/SourceStamp` for `path` at `modified` (size defaults to 64)."
  ([path modified] (stamp path modified 64))
  ([path modified size]
   {:source/path path :source/exists? true :source/modified modified :source/size size}))

(def descriptor (stamp "/p/hive-cljs.edn" 1000))
(def descriptor' (stamp "/p/hive-cljs.edn" 2000))
(def project (stamp "/p/.hive-project.edn" 500 32))

(deftest a-stamp-fixture-conforms
  (is (m/validate s/SourceStamp descriptor) (pr-str (m/explain s/SourceStamp descriptor)))
  (is (m/validate s/SourceStamp project)))

;; =============================================================================
;; sources-changed?
;; =============================================================================

(deftest change-needs-two-witnesses
  (testing "an empty cached side records nothing, so nothing can have changed"
    (is (false? (staleness/sources-changed? [] [descriptor])))
    (is (false? (staleness/sources-changed? nil [descriptor project]))))

  (testing "an empty current side is equally mute"
    (is (false? (staleness/sources-changed? [descriptor] [])))
    (is (false? (staleness/sources-changed? [descriptor project] nil))))

  (testing "both empty is not a change"
    (is (false? (staleness/sources-changed? [] [])))
    (is (false? (staleness/sources-changed? nil nil)))))

(deftest equal-stamps-are-not-a-change
  (is (false? (staleness/sources-changed? [descriptor] [descriptor])))
  (is (false? (staleness/sources-changed? [descriptor project] [descriptor project])))

  (testing "a seq and a vector with the same contents compare equal"
    (is (false? (staleness/sources-changed? (list descriptor project) [descriptor project])))
    (is (false? (staleness/sources-changed? [descriptor project] (list descriptor project))))
    (is (false? (staleness/sources-changed? (map identity [descriptor project])
                                            [descriptor project])))))

(deftest differing-stamps-are-a-change
  (testing "the same file, touched"
    (is (true? (staleness/sources-changed? [descriptor] [descriptor']))))

  (testing "a source appeared"
    (is (true? (staleness/sources-changed? [descriptor] [descriptor project]))))

  (testing "a source disappeared"
    (is (true? (staleness/sources-changed? [descriptor project] [project]))))

  (testing "order is part of the identity — :manifest/sources is precedence-ordered"
    (is (true? (staleness/sources-changed? [descriptor project] [project descriptor])))))

;; =============================================================================
;; freshness
;; =============================================================================

(deftest freshness-maps-change-to-stale-and-everything-else-to-fresh
  (is (= :stale (staleness/freshness [descriptor] [descriptor'])))
  (is (= :stale (staleness/freshness [descriptor project] [project descriptor])))
  (is (= :fresh (staleness/freshness [descriptor] [descriptor])))
  (is (= :fresh (staleness/freshness (list descriptor project) [descriptor project])))

  (testing "an unrecorded side is fresh, never stale"
    (is (= :fresh (staleness/freshness [] [descriptor])))
    (is (= :fresh (staleness/freshness [descriptor] [])))
    (is (= :fresh (staleness/freshness nil nil))))

  (testing "the verdict conforms"
    (is (m/validate s/ManifestFreshness (staleness/freshness [descriptor] [descriptor'])))
    (is (m/validate s/ManifestFreshness (staleness/freshness nil nil)))))

;; =============================================================================
;; server-match
;; =============================================================================

(deftest server-match-needs-both-sides-to-claim-anything
  (testing "no declared builds"
    (is (= :unknown (staleness/server-match [] [:app])))
    (is (= :unknown (staleness/server-match nil [:app]))))

  (testing "no reported builds"
    (is (= :unknown (staleness/server-match [:app] [])))
    (is (= :unknown (staleness/server-match [:app] nil))))

  (testing "neither side"
    (is (= :unknown (staleness/server-match [] [])))
    (is (= :unknown (staleness/server-match nil nil)))))

(deftest server-match-is-decided-by-intersection
  (testing "an exact overlap is ok"
    (is (= :ok (staleness/server-match [:app] [:app])))
    (is (= :ok (staleness/server-match [:app :admin] [:app :admin]))))

  (testing "a partial overlap is ok — one shared build is enough"
    (is (= :ok (staleness/server-match [:app :admin] [:app :other])))
    (is (= :ok (staleness/server-match [:app] [:other :app :extra]))))

  (testing "set semantics — order and duplicates do not decide"
    (is (= :ok (staleness/server-match [:admin :app] [:app :admin])))
    (is (= :ok (staleness/server-match [:app :app] [:app]))))

  (testing "disjoint non-empty sets are a mismatch"
    (is (= :mismatch (staleness/server-match [:app] [:other])))
    (is (= :mismatch (staleness/server-match [:app :admin] [:x :y]))))

  (testing "the verdict conforms"
    (is (m/validate s/ServerMatch (staleness/server-match [:app] [:app])))
    (is (m/validate s/ServerMatch (staleness/server-match [:app] [:other])))
    (is (m/validate s/ServerMatch (staleness/server-match [] [])))))

;; =============================================================================
;; wrong-server?
;; =============================================================================

(deftest only-a-mismatch-reads-as-wrong
  (testing "a mismatch"
    (is (true? (staleness/wrong-server? [:app] [:other])))
    (is (true? (staleness/wrong-server? [:app :admin] [:x :y]))))

  (testing "an overlap is not wrong"
    (is (false? (staleness/wrong-server? [:app] [:app])))
    (is (false? (staleness/wrong-server? [:app :admin] [:app :other]))))

  (testing "an unknown must never read as wrong"
    (is (false? (staleness/wrong-server? [] [:app])))
    (is (false? (staleness/wrong-server? [:app] [])))
    (is (false? (staleness/wrong-server? nil [:app])))
    (is (false? (staleness/wrong-server? [:app] nil)))
    (is (false? (staleness/wrong-server? nil nil)))))

;; =============================================================================
;; report
;; =============================================================================

(def report-keys
  #{:staleness/manifest :staleness/sources :staleness/server
    :staleness/declared-builds :staleness/reported-builds})

(deftest an-empty-report-is-fresh-and-unknown
  (let [rep (staleness/report {})]
    (is (= report-keys (set (keys rep))))
    (is (m/validate s/StalenessReport rep) (pr-str (m/explain s/StalenessReport rep)))
    (is (= {:staleness/manifest        :fresh
            :staleness/sources         []
            :staleness/server          :unknown
            :staleness/declared-builds []
            :staleness/reported-builds []}
           rep))))

(deftest a-populated-report-derives-each-value-from-its-own-input
  (let [rep (staleness/report {:cached-sources  [descriptor project]
                               :current-sources [descriptor' project]
                               :declared-builds [:app :admin]
                               :reported-builds [:other]})]
    (is (= report-keys (set (keys rep))))
    (is (m/validate s/StalenessReport rep) (pr-str (m/explain s/StalenessReport rep)))

    (testing "the manifest axis compares cached against current"
      (is (= :stale (:staleness/manifest rep))))

    (testing ":staleness/sources reports the CURRENT stamps, not the cached ones"
      (is (= [descriptor' project] (:staleness/sources rep))))

    (testing "the server axis compares declared against reported"
      (is (= :mismatch (:staleness/server rep)))
      (is (= [:app :admin] (:staleness/declared-builds rep)))
      (is (= [:other] (:staleness/reported-builds rep))))))

(deftest the-two-report-axes-are-independent
  (testing "fresh sources with a wrong server"
    (let [rep (staleness/report {:cached-sources  [descriptor]
                                 :current-sources [descriptor]
                                 :declared-builds [:app]
                                 :reported-builds [:other]})]
      (is (= :fresh (:staleness/manifest rep)))
      (is (= :mismatch (:staleness/server rep)))))

  (testing "stale sources with the right server"
    (let [rep (staleness/report {:cached-sources  [descriptor]
                                 :current-sources [descriptor']
                                 :declared-builds [:app]
                                 :reported-builds [:app]})]
      (is (= :stale (:staleness/manifest rep)))
      (is (= :ok (:staleness/server rep))))))

(deftest report-vectorizes-whatever-sequential-it-is-given
  (let [rep (staleness/report {:cached-sources  (list descriptor)
                               :current-sources (map identity [descriptor project])
                               :declared-builds (list :app)
                               :reported-builds (map identity [:app])})]
    (is (vector? (:staleness/sources rep)))
    (is (vector? (:staleness/declared-builds rep)))
    (is (vector? (:staleness/reported-builds rep)))
    (is (m/validate s/StalenessReport rep) (pr-str (m/explain s/StalenessReport rep)))
    (is (= :stale (:staleness/manifest rep)))
    (is (= :ok (:staleness/server rep)))))
