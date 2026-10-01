(ns hive-cljs.window-class-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [hive-cljs.browser.playwright :as pw]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.schema :as s]))

(deftest a-headed-window-is-stamped-with-its-class
  (testing "chromium and firefox take --class=NAME when headed"
    (is (= ["--class=hive-cljs-headed"] (pw/window-args :chromium false "hive-cljs-headed")))
    (is (= ["--class=tests"] (pw/window-args :firefox false "tests"))))
  (testing "nothing is added when there is no window to place"
    (is (= [] (pw/window-args :chromium true "hive-cljs-headed")) "headless")
    (is (= [] (pw/window-args :webkit false "hive-cljs-headed")) "webkit")
    (is (= [] (pw/window-args :chromium false nil)) "no class")))

(deftest the-default-class-is-the-one-a-window-manager-rule-matches
  (is (= "hive-cljs-headed" pw/default-window-class)))

(deftest the-manifest-carries-a-window-class-through-to-the-e2e-config
  (let [e2e (manifest/normalize-e2e {:base-url "http://localhost:8080"
                                     :window-class "my-tests"}
                                    {} "/tmp/app")]
    (is (= "my-tests" (:window-class e2e)))
    (is (m/validate s/E2eConfig e2e) "E2eConfig accepts :window-class")
    (is (not (m/validate s/E2eConfig (assoc e2e :window-class "")))
        "an empty class is refused"))
  (testing "absent, the e2e config has no class and the driver falls back to the default"
    (is (nil? (:window-class (manifest/normalize-e2e {:base-url "http://localhost:8080"}
                                                     {} "/tmp/app"))))))
