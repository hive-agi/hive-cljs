(ns hive-cljs.release
  "Which version a release publishes. Pure: a tag list and ./VERSION in, a
   decision out. The effects (reading git, writing VERSION) live in
   ./build.clj; this namespace only decides. It sits under build/, which is
   on the :build and :test classpaths and never in the published jar.

   The rule:

     VERSION ahead of the newest v* tag (by semver)  -> publish VERSION verbatim
     otherwise                                        -> bump max(VERSION, tag)

   So a hand-set minor (VERSION 0.3.0 over v0.2.24) ships as 0.3.0 instead of
   being patch-bumped to 0.3.1, and a VERSION that fell behind the tags (a
   release commit on main never merged back) bumps from the tag, so it never
   re-mints a coordinate that already exists.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn parse
  "`text` (optionally v-prefixed) as [major minor patch], or nil when it is
   not MAJOR.MINOR.PATCH."
  [text]
  (when (string? text)
    (when-let [[_ a b c] (re-matches #"v?(\d+)\.(\d+)\.(\d+)"
                                     (.trim ^String text))]
      (mapv parse-long [a b c]))))

(defn render
  "[major minor patch] as MAJOR.MINOR.PATCH."
  [[a b c]]
  (str a "." b "." c))

(defn bump
  "`v` advanced at `level`; lower components reset to zero."
  [[a b c] level]
  (case level
    :major [(inc a) 0 0]
    :minor [a (inc b) 0]
    :patch [a b (inc c)]
    (throw (ex-info "level must be :major, :minor or :patch" {:level level}))))

(defn latest-tag
  "The greatest semver among `tags` (v-prefixed strings), as [major minor
   patch], or nil when none parses. Non-semver tags are ignored."
  [tags]
  (let [vs (keep parse tags)]
    (when (seq vs) (last (sort vs)))))

(defn decide
  "The release decision for `tags` (every v* tag) and `version` (./VERSION).

   Returns {:version \"x.y.z\" :mode :verbatim|:bump :from \"x.y.z\"
            :latest \"x.y.z\"|nil}.
   :verbatim when VERSION is strictly ahead of the newest tag (or no tag
   exists yet); otherwise :bump at `level` from whichever of VERSION and the
   newest tag is greater. Throws when VERSION is not a semantic version: a
   release must never guess at its own coordinate."
  ([tags version] (decide tags version :patch))
  ([tags version level]
   (let [current (or (parse version)
                     (throw (ex-info "VERSION is not MAJOR.MINOR.PATCH"
                                     {:version version})))
         latest  (latest-tag tags)]
     (if (or (nil? latest) (pos? (compare current latest)))
       {:version (render current) :mode :verbatim
        :from (render current) :latest (some-> latest render)}
       (let [base (if (neg? (compare current latest)) latest current)]
         {:version (render (bump base level)) :mode :bump
          :from (render base) :latest (render latest)})))))

(defn next-version
  "Just the version string `decide` answers."
  ([tags version] (next-version tags version :patch))
  ([tags version level] (:version (decide tags version level))))
