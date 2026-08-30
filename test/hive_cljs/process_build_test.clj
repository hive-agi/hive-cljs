(ns hive-cljs.process-build-test
  "A build whose verdict is an exit code.

   `elm make`, `vite build`, `tsc`, an npm script — the toolchains that have no
   server to ask. No process is spawned here: the executor is injected, so what
   is under test is the orchestration and the verdict, not the shell."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.build.process :as process]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.ports :as ports]
            [hive-cljs.toolchain :as toolchain]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private commands {:app ["elm" "make" "src/Main.elm"]})

(defn- exec-returning
  "An executor that records its calls and answers `result`."
  [calls result]
  (fn [argv cwd]
    (swap! calls conj [argv cwd])
    (if (r/err? result) result (r/ok result))))

(defn- tool
  ([result] (tool result (atom [])))
  ([result calls]
   [(process/build-tool "/tmp/app" commands (exec-returning calls result)) calls]))

;; =============================================================================
;; Verdicts
;; =============================================================================

(deftest a-build-nobody-compiled-is-unknown-not-an-error
  ;; Not having asked is not the same as having asked and failed.
  (let [[bt _] (tool {:exit 0 :out "" :err ""})
        st     (:ok (ports/build-status bt :app))]
    (is (= :unknown (:build/state st)))
    (is (empty? (:build/errors st)))))

(deftest a-zero-exit-is-a-completed-build
  (let [[bt calls] (tool {:exit 0 :out "Success!" :err ""})
        st         (:ok (ports/compile-once! bt :app))]
    (is (= :completed (:build/state st)))
    (is (empty? (:build/errors st)))
    (testing "run as declared, with the project root as cwd"
      (is (= [[["elm" "make" "src/Main.elm"] "/tmp/app"]] @calls)))))

(deftest a-non-zero-exit-is-a-failed-build-carrying-its-diagnostics
  (let [[bt _] (tool {:exit 1 :out "" :err "-- TYPE MISMATCH --\n\nThe 2nd argument"})
        st     (:ok (ports/compile-once! bt :app))]
    (is (= :failed (:build/state st)))
    (is (= ["-- TYPE MISMATCH --" "The 2nd argument"] (:build/errors st)))))

(deftest diagnostics-come-from-both-streams
  ;; elm writes errors to stderr, tsc to stdout. Reading one would call half the
  ;; failures in the world silent.
  (let [[bt _] (tool {:exit 2 :out "error TS2345: nope" :err ""})
        st     (:ok (ports/compile-once! bt :app))]
    (is (= :failed (:build/state st)))
    (is (= ["error TS2345: nope"] (:build/errors st)))))

(deftest a-verdict-is-remembered-for-the-next-status-read
  (let [[bt _] (tool {:exit 1 :out "" :err "boom"})]
    (ports/compile-once! bt :app)
    (is (= :failed (:build/state (:ok (ports/build-status bt :app)))))))

(deftest an-undeclared-build-names-the-ones-that-are
  (let [[bt calls] (tool {:exit 0 :out "" :err ""})
        res        (ports/compile-once! bt :ghost)]
    (is (r/err? res))
    (is (= :build/no-command (:error res)))
    (is (= [:app] (:declared res)))
    (is (empty? @calls) "nothing was run")))

(deftest an-executor-failure-stays-an-error-rather-than-a-red-build
  ;; A missing binary is not the application failing to compile, and reporting
  ;; it as :failed would send the reader into their own source.
  (let [[bt _] (tool (r/err :build/exec-failed {:cause "No such file"}))
        res    (ports/compile-once! bt :app)]
    (is (r/err? res))
    (is (= :build/exec-failed (:error res)))))

;; =============================================================================
;; Events
;; =============================================================================

(deftest a-finished-compile-reaches-every-subscriber
  (let [[bt _] (tool {:exit 0 :out "" :err ""})
        seen   (atom [])]
    (ports/subscribe! bt :watcher #(swap! seen conj %))
    (ports/compile-once! bt :app)
    (is (= 1 (count @seen)))
    (is (= :app (:event/build (first @seen))))
    (is (= :completed (get-in (first @seen) [:event/status :build/state])))
    (testing "and stops after unsubscribing"
      (ports/unsubscribe! bt :watcher)
      (ports/compile-once! bt :app)
      (is (= 1 (count @seen))))))

(deftest one-subscriber-throwing-does-not-cost-the-others-their-event
  (let [[bt _] (tool {:exit 0 :out "" :err ""})
        seen   (atom [])]
    (ports/subscribe! bt :bad (fn [_] (throw (ex-info "boom" {}))))
    (ports/subscribe! bt :good #(swap! seen conj %))
    (is (r/ok? (ports/compile-once! bt :app)))
    (is (= 1 (count @seen)))))

;; =============================================================================
;; Config and mounting
;; =============================================================================

(deftest a-command-declared-on-a-build-survives-normalization
  (let [m (manifest/normalize
           {:hive.cljs/builds {:app {:http-port 8280
                                     :command ["npx" "vite" "build"]}}}
           "/tmp/app")]
    (is (= {:app ["npx" "vite" "build"]} (manifest/build-commands m)))))

(deftest a-project-declaring-no-command-supervises-no-build
  (let [m (manifest/normalize {:hive.cljs/builds {:app {:http-port 8280}}} "/tmp/app")]
    (is (empty? (manifest/build-commands m)))))

(deftest the-browser-toolchain-supervises-a-build-once-one-is-declared
  (let [tc (:ok (toolchain/resolve-toolchain :browser))
        m  (manifest/normalize
            {:hive.cljs/builds {:app {:command ["elm" "make" "src/Main.elm"]}}}
            "/tmp/app")
        res (ports/open-build-tool tc m)]
    (is (r/ok? res))
    (is (ports/build-tool? (:ok res)))
    (is (= [:app] (:ok (ports/builds (:ok res)))))))

(deftest without-a-command-the-browser-toolchain-explains-the-absence
  (let [tc  (:ok (toolchain/resolve-toolchain :browser))
        m   (manifest/normalize {:hive.cljs/builds {:app {:http-port 8280}}} "/tmp/app")
        res (ports/open-build-tool tc m)]
    (is (r/err? res))
    (is (= :build-tool/not-supervised (:error res)))
    (is (re-find #":command" (:hint res)))))
