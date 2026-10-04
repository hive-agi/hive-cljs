(ns hive-cljs.artifact-observe-test
  "Compiles hive-cljs did not run, seen through the output they leave.

   An external `vite --watch` tells nobody it compiled; its bundle on disk is
   the witness. Nothing here touches a disk or a wall clock: the filesystem is
   a map behind `IFileStamps` and time is a number behind `IClock`, so every
   write, every pause and every half-written bundle is staged exactly."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.build.artifacts :as artifacts]
            [hive-cljs.build.observe :as observe]
            [hive-cljs.build.process :as process]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.ports :as ports]
            [hive-cljs.toolchain :as toolchain]
            [hive-cljs.watch :as watch]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Fakes
;; =============================================================================

(defrecord FakeFs [files]
  ;; files: atom of {"dist/app.js" {:modified n :size n :content s}}
  ports/IFileStamps
  (file-stamps [_ _root paths opts]
    (into {}
          (for [[f {:keys [modified size content]}] @files
                :when (some #(or (= f %) (str/starts-with? f (str % "/"))) paths)]
            [f (cond-> {:modified modified :size size}
                 (:hash? opts) (assoc :hash (hash content)))]))))

(defrecord FakeClock [t]
  ports/IClock
  (now-ms [_] @t))

(defn- world []
  (let [files (atom {}) t (atom 1000)]
    {:files files :t t :fs (->FakeFs files) :clock (->FakeClock t)}))

(defn- write! [{:keys [files t]} path content]
  (swap! files assoc path {:modified @t :size (count content) :content content}))

(defn- delete! [{:keys [files]} path] (swap! files dissoc path))

(defn- advance! [{:keys [t]} ms] (swap! t + ms))

(defn- observer
  ([w specs] (observer w specs (atom [])))
  ([w specs seen]
   [(observe/observer "/app" specs #(swap! seen conj %)
                      {:stamps (:fs w) :clock (:clock w)})
    seen]))

(def ^:private dist {:app {:outputs ["dist"] :quiet-ms 100}})

;; =============================================================================
;; The pure decision
;; =============================================================================

(deftest the-first-observation-is-a-baseline-not-a-compile
  ;; Output already on disk when watching began is not a compile we saw happen.
  (let [{:keys [changed state]} (artifacts/step artifacts/initial-state
                                                {:outputs ["dist"]} 0
                                                {"dist/a.js" {:modified 5 :size 3}})]
    (is (nil? changed))
    (is (:primed? state))))

(deftest a-change-is-reported-only-after-it-holds-still
  (let [spec {:outputs ["dist"] :quiet-ms 100}
        s0   (:state (artifacts/step artifacts/initial-state spec 0 {"dist/a.js" {:modified 1 :size 1}}))
        new  {"dist/a.js" {:modified 2 :size 9}}
        r1   (artifacts/step s0 spec 10 new)
        r2   (artifacts/step (:state r1) spec 60 new)
        r3   (artifacts/step (:state r2) spec 110 new)]
    (is (nil? (:changed r1)) "seen, not yet settled")
    (is (nil? (:changed r2)) "inside the quiet window")
    (is (= ["dist/a.js"] (:changed r3)))
    (testing "and once reported, the same output is the new baseline"
      (is (nil? (:changed (artifacts/step (:state r3) spec 500 new)))))))

(deftest a-ready-marker-reports-at-once-and-alone
  (let [spec {:outputs ["dist"] :ready-marker ".built" :quiet-ms 10000}]
    (is (= [".built"] (artifacts/watched-paths spec)))
    (let [s0 (:state (artifacts/step artifacts/initial-state spec 0 {".built" {:modified 1 :size 0}}))
          r  (artifacts/step s0 spec 1 {".built" {:modified 2 :size 0}})]
      (is (= [".built"] (:changed r)) "the tool said it was done; no quiet window"))))

(deftest output-that-vanished-is-a-build-in-progress
  (let [spec {:outputs ["dist"] :quiet-ms 0}
        s0   (:state (artifacts/step artifacts/initial-state spec 0 {"dist/a.js" {:modified 1 :size 1}}))
        r    (artifacts/step s0 spec 50 {})]
    (is (nil? (:changed r)))
    (is (= {"dist/a.js" [1 1]} (:baseline (:state r))) "the baseline survives the gap")))

(deftest content-hashing-ignores-a-touch-that-changed-nothing
  (let [spec {:outputs ["dist"] :quiet-ms 0}
        s0   (:state (artifacts/step artifacts/initial-state spec 0
                                     {"dist/a.js" {:modified 1 :size 3 :hash "h"}}))
        r    (artifacts/step s0 spec 50 {"dist/a.js" {:modified 99 :size 3 :hash "h"}})]
    (is (nil? (:changed r)))
    (is (nil? (:pending (:state r))))))

(deftest an-observed-compile-has-the-shape-of-any-other
  (let [e (artifacts/event-of :app ["dist/a.js"] 42)]
    (is (= :app (:event/build e)))
    (is (= 42 (:event/at e)))
    (is (= :completed (get-in e [:event/status :build/state])))
    (is (= [{:file/path "dist/a.js"}] (get-in e [:event/status :build/files])))))

;; =============================================================================
;; The observer over fake ports
;; =============================================================================

(deftest an-external-compile-emits-one-event-once-the-bundle-settles
  (let [w         (world)
        _         (write! w "dist/app.js" "v1")
        [obs seen] (observer w dist)]
    (observe/tick! obs)
    (is (empty? @seen) "baseline")
    (testing "a bundler writing over time is one compile, not several"
      (advance! w 10) (write! w "dist/app.js" "v2-partial") (observe/tick! obs)
      (advance! w 30) (write! w "dist/app.css" "css") (observe/tick! obs)
      (advance! w 50) (observe/tick! obs)
      (is (empty? @seen))
      (advance! w 60) (observe/tick! obs)
      (is (= 1 (count @seen)))
      (is (= ["dist/app.css" "dist/app.js"]
             (mapv :file/path (get-in (first @seen) [:event/status :build/files])))))
    (testing "nothing more while nothing changes"
      (advance! w 1000) (observe/tick! obs) (observe/tick! obs)
      (is (= 1 (count @seen))))))

(deftest files-outside-the-declared-outputs-are-not-witnesses
  (let [w (world)
        _ (write! w "dist/app.js" "v1")
        [obs seen] (observer w dist)]
    (observe/tick! obs)
    (write! w "src/Main.elm" "edit")
    (advance! w 500) (observe/tick! obs) (advance! w 500) (observe/tick! obs)
    (is (empty? @seen))))

(deftest a-suspended-build-is-not-reported-and-resumes-from-what-it-wrote
  ;; hive's own compile reports its verdict from the exit code; the output it
  ;; wrote must not be reported again.
  (let [w (world)
        _ (write! w "dist/app.js" "v1")
        [obs seen] (observer w dist)]
    (observe/tick! obs)
    (observe/suspend! obs :app)
    (write! w "dist/app.js" "v2")
    (advance! w 500) (observe/tick! obs)
    (observe/resume! obs :app)
    (advance! w 500) (observe/tick! obs)
    (is (empty? @seen))))

;; =============================================================================
;; Through the build tool and the watcher
;; =============================================================================

(defn- process-tool [w commands specs exec-fn]
  (process/build-tool "/app" commands exec-fn
                      {:artifacts specs :stamps (:fs w) :clock (:clock w)}))

(deftest an-observed-compile-reaches-subscribers-and-the-status-read
  (let [w    (world)
        _    (write! w "dist/app.js" "v1")
        bt   (process-tool w {} dist (fn [_ _] (r/ok {:exit 0 :out "" :err ""})))
        seen (atom [])
        obs  (:observer bt)]
    (try
      (is (= [:app] (:ok (ports/builds bt))) "an observed build is a known build")
      (ports/subscribe! bt :w #(swap! seen conj %))
      (is (observe/running? obs) "sampling starts with the first subscriber")
      ;; drive the observer directly; the scheduled thread has nothing to see
      ;; beyond what the fake world shows it, and events are de-duplicated
      ;; by the state both share
      (observe/stop! obs)
      (observe/tick! obs)
      (advance! w 10) (write! w "dist/app.js" "v2") (observe/tick! obs)
      (advance! w 200) (observe/tick! obs)
      (is (= 1 (count @seen)))
      (is (= :completed (:build/state (:ok (ports/build-status bt :app)))))
      (testing "the last unsubscribe stops sampling"
        (observe/start! obs)
        (ports/unsubscribe! bt :w)
        (is (not (observe/running? obs))))
      (finally (process/close! bt)))))

(deftest hives-own-compile-is-reported-once
  (let [w    (world)
        _    (write! w "dist/app.js" "v1")
        bt   (process-tool w {:app ["vite" "build"]} dist
                           (fn [_ _] (write! w "dist/app.js" "v2")
                             (r/ok {:exit 0 :out "" :err ""})))
        obs  (:observer bt)
        seen (atom [])]
    (ports/subscribe! bt :w #(swap! seen conj %))
    (observe/stop! obs)
    (observe/tick! obs)
    (ports/compile-once! bt :app)
    (advance! w 500) (observe/tick! obs)
    (is (= 1 (count @seen)) "the exit code's event, not a second one from the output")
    (process/close! bt)))

(deftest the-watcher-cannot-tell-an-observed-compile-from-its-own
  (let [m (manifest/normalize
           {:hive.cljs/toolchain :browser
            :hive.cljs/builds    {:app {:artifacts ["dist"]}}
            :hive.cljs/e2e       {:base-url "http://localhost:5173"
                                  :scenarios [{:id :smoke :steps [[:visit "/"]]}]}
            :hive.cljs/watch     {:on-build-success [[:run-e2e]] :debounce-ms 0}}
           "/app")
        e (artifacts/event-of :app ["dist/a.js"] 5000)]
    (is (= [:smoke] (watch/runnable (watch/decide m e nil))))))

;; =============================================================================
;; Config and mounting
;; =============================================================================

(deftest artifacts-normalize-from-shorthand-and-map
  (is (= {:outputs ["dist"]} (manifest/normalize-artifacts "dist")))
  (is (= {:outputs ["dist" "public/app.js"]}
         (manifest/normalize-artifacts ["dist" "public/app.js"])))
  (is (= {:ready-marker ".hive-built" :quiet-ms 50 :hash? true}
         (manifest/normalize-artifacts {:ready-marker ".hive-built" :quiet-ms 50
                                        :hash? true :bogus 1})))
  (is (nil? (manifest/normalize-artifacts {})) "nothing observable declared")
  (is (nil? (manifest/normalize-artifacts nil))))

(deftest a-build-with-artifacts-only-is-observed-not-run
  (let [tc  (:ok (toolchain/resolve-toolchain :browser))
        m   (manifest/normalize {:hive.cljs/builds {:app {:artifacts ["dist"]}}} "/tmp/app")
        res (ports/open-build-tool tc m)]
    (is (= {:app {:outputs ["dist"]}} (manifest/build-artifacts m)))
    (is (r/ok? res))
    (is (= [:app] (:ok (ports/builds (:ok res)))))
    (is (= :build/no-command (:error (ports/compile-once! (:ok res) :app)))
        "observing is not running")
    (is (nil? (ports/close-build-tool! tc (:ok res))))))

(deftest neither-command-nor-artifacts-names-both
  (let [tc  (:ok (toolchain/resolve-toolchain :browser))
        m   (manifest/normalize {:hive.cljs/builds {:app {:http-port 8280}}} "/tmp/app")
        res (ports/open-build-tool tc m)]
    (is (= :build-tool/not-supervised (:error res)))
    (is (re-find #":artifacts" (:hint res)))))

(deftest the-real-filesystem-port-reads-relative-stamps
  (let [dir (doto (java.io.File/createTempFile "hive-obs" "") (.delete) (.mkdirs))
        _   (doto (java.io.File. dir "dist") (.mkdirs))
        _   (spit (java.io.File. dir "dist/a.js") "abc")
        st  (ports/file-stamps (observe/fs-stamps) (str dir) ["dist" "missing"] {:hash? true})]
    (is (= #{"dist/a.js"} (set (keys st))))
    (is (= 3 (get-in st ["dist/a.js" :size])))
    (is (string? (get-in st ["dist/a.js" :hash])))))
