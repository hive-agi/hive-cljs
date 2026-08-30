(ns hive-cljs.toolchain-test
  "The composition root's DIP seam.

   The claim under test is not that a protocol exists but that the extension
   point can actually be TAKEN: a toolchain this library has never heard of,
   registered at runtime, must be the thing `system/open!` gets its ports from.
   That was false before the registry existed — `open!` named shadow directly,
   so the extension point the docs advertised could not be reached."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.ports :as ports]
            [hive-cljs.stub.ports :as stub]
            [hive-cljs.system :as system]
            [hive-cljs.toolchain :as toolchain]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private test-id :stub/toolchain)

(use-fixtures :each
  (fn [f]
    (try (f)
         (finally
           (system/close-all!)
           (toolchain/unregister! test-id)
           (toolchain/unregister! :shadow-cljs)))))

(defn- tmp-project!
  "A temp project root carrying `raw` as its manifest. No real project path is
   touched, and nothing is listening anywhere — the stub toolchain connects
   nothing."
  [label raw]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "hive-cljs-toolchain-" label "-" (System/nanoTime)))]
    (.mkdirs d)
    (spit (io/file d manifest/manifest-filename) (pr-str raw))
    (.getAbsolutePath d)))

(defn- raw-manifest
  [toolchain-id]
  (cond-> {:hive.cljs/builds {:app {:http-port 8280}}}
    toolchain-id (assoc :hive.cljs/toolchain toolchain-id)))

;; =============================================================================
;; Registry
;; =============================================================================

(deftest the-shipped-clojurescript-adapter-resolves
  ;; Soft resolution has to actually work, not merely be declared: the symbol is
  ;; only dereferenced when someone opens a session, so a typo in it would
  ;; otherwise surface for the first time in a user's project.
  (let [res (toolchain/resolve-toolchain :shadow-cljs)]
    (is (r/ok? res))
    (is (ports/toolchain? (:ok res)))))

(deftest an-unknown-toolchain-is-a-typed-error-naming-what-is-known
  (let [res (toolchain/resolve-toolchain :no-such-stack)]
    (is (r/err? res))
    (is (= :toolchain/unknown (:error res)))
    (is (contains? (set (:known res)) :shadow-cljs)
        "the error has to tell the author what they could have written")))

(deftest a-constructor-returning-a-non-toolchain-is-a-typed-error
  ;; The symbol resolves and the call succeeds — it just does not yield an
  ;; IToolchain. Distinguished from :unavailable so the author is told the
  ;; registration is wrong rather than the vendor missing.
  (toolchain/register! test-id 'clojure.core/rand)
  (let [res (toolchain/resolve-toolchain test-id)]
    (is (r/err? res))
    (is (= :toolchain/not-a-toolchain (:error res)))))

(deftest a-constructor-that-throws-is-a-typed-error
  ;; Registering a symbol that is not a 0-arity constructor must degrade the
  ;; way a missing vendor does, not throw out of the composition root.
  (toolchain/register! test-id 'clojure.core/identity)
  (let [res (toolchain/resolve-toolchain test-id)]
    (is (r/err? res))
    (is (= :toolchain/unavailable (:error res)))))

(deftest a-registered-value-that-is-neither-is-a-typed-error
  (toolchain/register! test-id {:not "a toolchain"})
  (let [res (toolchain/resolve-toolchain test-id)]
    (is (r/err? res))
    (is (= :toolchain/not-a-toolchain (:error res)))))

(deftest an-unresolvable-symbol-is-a-typed-error
  (toolchain/register! test-id 'no.such.namespace/toolchain)
  (let [res (toolchain/resolve-toolchain test-id)]
    (is (r/err? res))
    (is (= :toolchain/unavailable (:error res)))))

(deftest a-registration-replaces-a-shipped-adapter-of-the-same-id
  ;; A project must be able to substitute the ClojureScript wiring without
  ;; unregistering first — otherwise the swap is order-dependent.
  (let [stubbed (stub/toolchain)]
    (toolchain/register! :shadow-cljs stubbed)
    (is (identical? stubbed (:ok (toolchain/resolve-toolchain :shadow-cljs))))))

(deftest registered-lists-shipped-and-registered-ids-together
  (toolchain/register! test-id (stub/toolchain))
  (let [ids (toolchain/registered)]
    (is (contains? ids :shadow-cljs))
    (is (contains? ids test-id))))

;; =============================================================================
;; Manifest
;; =============================================================================

(deftest a-project-declaring-no-toolchain-keeps-the-clojurescript-default
  (is (= :shadow-cljs
         (:manifest/toolchain (manifest/normalize (raw-manifest nil) "/tmp/x")))))

(deftest a-declared-toolchain-wins
  (is (= test-id
         (:manifest/toolchain (manifest/normalize (raw-manifest test-id) "/tmp/x")))))

;; =============================================================================
;; The seam itself
;; =============================================================================

(deftest the-composition-root-opens-its-ports-through-the-declared-toolchain
  (let [tc   (stub/toolchain)
        _    (toolchain/register! test-id tc)
        root (tmp-project! "seam" (raw-manifest test-id))
        res  (system/open! root)]
    (is (r/ok? res))
    (let [s (:ok res)]
      (testing "both channels came from the registered toolchain"
        (is (= [:build-tool :runtime] (mapv first (stub/opened tc))))
        (is (ports/build-tool? (:build-tool s)))
        (is (ports/cljs-eval? (:cljs-eval s))))
      (testing "the session records which toolchain answered"
        (is (= test-id (:toolchain-id s)))
        (is (identical? tc (:toolchain s))))
      (testing "health reports the toolchain as a channel of its own"
        (is (= :ok (:toolchain (system/health s))))))
    (testing "teardown goes back through the same toolchain"
      (system/close! root)
      (is (= #{:build-tool :runtime} (set (stub/released tc)))))))

(deftest an-unresolvable-toolchain-explains-both-dead-channels
  ;; Two unexplained absences would send the reader hunting for a shadow server
  ;; that was never the problem.
  (let [root (tmp-project! "unknown" (raw-manifest :no-such-stack))
        res  (system/open! root)]
    (is (r/ok? res) "an unknown toolchain degrades, it does not abort the session")
    (let [s (:ok res)]
      (is (nil? (:build-tool s)))
      (is (nil? (:cljs-eval s)))
      (is (= :toolchain/unknown (get-in s [:errors :toolchain :error])))
      (is (= :toolchain/unknown (get-in s [:errors :build-tool :error])))
      (is (= :toolchain/unknown (get-in s [:errors :cljs-eval :error])))
      (is (= :down (:toolchain (system/health s)))))))

(deftest a-channel-that-refuses-to-connect-leaves-the-other-one-up
  (let [tc   (stub/toolchain {:runtime-result (r/err :runtime/nope {})})
        _    (toolchain/register! test-id tc)
        root (tmp-project! "partial" (raw-manifest test-id))
        s    (:ok (system/open! root))]
    (is (ports/build-tool? (:build-tool s)))
    (is (nil? (:cljs-eval s)))
    (is (= :runtime/nope (get-in s [:errors :cljs-eval :error])))
    (is (= :ok (:build-tool (system/health s))))
    (is (= :down (:cljs-eval (system/health s))))))

(deftest doctor-names-the-toolchain-in-play
  (toolchain/register! test-id (stub/toolchain))
  (let [root (tmp-project! "doctor" (raw-manifest test-id))
        rep  (:ok (system/doctor root))]
    (is (= test-id (:toolchain rep)))))
