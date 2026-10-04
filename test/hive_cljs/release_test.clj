(ns hive-cljs.release-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.release :as release]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def tags ["v0.2.24" "v0.2.23" "v0.2.9" "v0.1.40"])

(deftest a-hand-set-minor-ahead-of-the-tags-ships-verbatim
  (is (= {:version "0.3.0" :mode :verbatim :from "0.3.0" :latest "0.2.24"}
         (release/decide tags "0.3.0")))
  (testing "a hand-set patch ahead of the tag is honoured too"
    (is (= "0.2.30" (release/next-version tags "0.2.30"))))
  (testing "a hand-set major"
    (is (= "1.0.0" (release/next-version tags "1.0.0\n")))))

(deftest a-version-equal-to-the-tag-patch-bumps
  (is (= {:version "0.2.25" :mode :bump :from "0.2.24" :latest "0.2.24"}
         (release/decide tags "0.2.24"))))

(deftest a-version-behind-the-tag-bumps-from-the-tag
  (testing "VERSION 0.2.23 left behind by an unmerged release commit"
    (is (= {:version "0.2.25" :mode :bump :from "0.2.24" :latest "0.2.24"}
           (release/decide tags "0.2.23")))))

(deftest tags-compare-by-semver-not-by-string
  (is (= [0 2 24] (release/latest-tag ["v0.2.9" "v0.2.24" "v0.2.10"])))
  (is (= "0.2.25" (release/next-version ["v0.2.9" "v0.2.24"] "0.2.10"))))

(deftest non-semver-tags-are-ignored
  (is (= [0 2 24] (release/latest-tag ["v0.2.24" "vnext" "v1.0" "release-9"])))
  (is (nil? (release/latest-tag ["vnext"]))))

(deftest no-tags-means-the-first-release-ships-verbatim
  (is (= {:version "0.1.0" :mode :verbatim :from "0.1.0" :latest nil}
         (release/decide [] "0.1.0"))))

(deftest level-applies-only-when-bumping
  (is (= "0.3.0" (release/next-version tags "0.2.24" :minor)))
  (is (= "1.0.0" (release/next-version tags "0.2.20" :major)))
  (is (= "0.3.0" (release/next-version tags "0.3.0" :major))))

(deftest a-non-semver-version-is-refused
  (is (thrown? clojure.lang.ExceptionInfo (release/decide tags "0.3")))
  (is (thrown? clojure.lang.ExceptionInfo (release/decide tags nil))))
