(ns hive-cljs.watch-test
  "The watcher's decision table (pure) and its supervision loop (stubbed ports)."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.fixtures :as fix]
            [hive-cljs.stub.ports :as stub]
            [hive-cljs.watch :as watch]
            [hive-cljs.watch.supervisor :as supervisor]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- event
  ([status] (event status 10000))
  ([status at] {:event/build (:build/id status) :event/status status :event/at at}))

;; =============================================================================
;; Pure decisions
;; =============================================================================

(deftest success-triggers-tagged-scenarios
  (let [[d] (watch/decide fix/manifest (event (fix/completed-status)) nil)]
    (is (= :run-e2e (:decision/kind d)))
    (is (= [:login] (:decision/scenarios d)))))

(deftest debounce-window-suppresses-a-rerun
  (testing "inside the window nothing runs"
    (let [[d] (watch/decide fix/manifest (event (fix/completed-status) 10000) 9800)]
      (is (= :ignore (:decision/kind d)))))
  (testing "outside the window it runs again"
    (let [[d] (watch/decide fix/manifest (event (fix/completed-status) 10000) 9000)]
      (is (= :run-e2e (:decision/kind d))))))

(deftest failed-build-reports-instead-of-running
  (let [[d] (watch/decide fix/manifest (event (fix/failed-status)) nil)]
    (is (= :report (:decision/kind d)))
    (is (= "build failed" (:decision/reason d)))))

(deftest unwatched-build-is-ignored
  (let [m (assoc-in fix/manifest [:manifest/watch :builds] #{:other})
        [d] (watch/decide m (event (fix/completed-status)) nil)]
    (is (= :ignore (:decision/kind d)))
    (is (= "build not watched by policy" (:decision/reason d)))))

(deftest compiling-state-triggers-nothing
  (let [status (assoc (fix/completed-status) :build/state :compiling)
        [d]    (watch/decide fix/manifest (event status) nil)]
    (is (= :ignore (:decision/kind d)))))

;; =============================================================================
;; Supervision loop
;; =============================================================================

(deftest a-successful-build-runs-the-smoke-scenario-unprompted
  (let [bt   (stub/build-tool)
        drv  (stub/driver)
        ce   (stub/cljs-eval (constantly true))
        deps {:build-tool bt :driver drv :cljs-eval ce}
        sup  (:ok (supervisor/start! fix/manifest deps))]
    (is (some? sup))
    (testing "nothing has run yet"
      (is (zero? (stub/sessions-opened drv))))

    (stub/emit-build! bt (fix/completed-status) 10000)

    (testing "the build event drove a browser session and a runtime assertion"
      (is (= 1 (stub/sessions-opened drv)))
      (is (= [:goto :fill :click :expect-text] (mapv :op/kind (stub/performed-ops drv))))
      (is (= 1 (count (stub/evals ce)))))

    (testing "a second build inside the debounce window does not re-run"
      (stub/emit-build! bt (fix/completed-status) 10200)
      (is (= 1 (stub/sessions-opened drv))))

    (testing "a later build does re-run"
      (stub/emit-build! bt (fix/completed-status) 11000)
      (is (= 2 (stub/sessions-opened drv))))

    (testing "status reports the run log"
      (let [st (:ok (supervisor/status sup))]
        (is (true? (:running? st)))
        (is (= 500 (:debounce-ms st)))
        (is (seq (:log st)))))

    (testing "stop unsubscribes — further builds are inert"
      (supervisor/stop! sup)
      (stub/emit-build! bt (fix/completed-status) 20000)
      (is (= 2 (stub/sessions-opened drv))))))

(deftest supervisor-refuses-without-a-build-tool
  (is (= :watch/no-build-tool
         (:error (supervisor/start! fix/manifest {:driver (stub/driver)})))))

(deftest failed-build-does-not-run-scenarios
  (let [bt   (stub/build-tool)
        drv  (stub/driver)
        sup  (:ok (supervisor/start! fix/manifest {:build-tool bt :driver drv
                                                   :cljs-eval (stub/cljs-eval)}))]
    (stub/emit-build! bt (fix/failed-status) 10000)
    (is (zero? (stub/sessions-opened drv)))
    (supervisor/stop! sup)))
