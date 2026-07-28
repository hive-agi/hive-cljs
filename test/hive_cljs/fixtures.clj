(ns hive-cljs.fixtures
  "Shared manifest fixtures."
  (:require [hive-cljs.manifest :as manifest]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def raw
  {:hive.cljs/shadow {:port 9630 :nrepl-port 7889}
   :hive.cljs/builds {:app {:http-port 8280}}
   :hive.cljs/e2e
   {:scenarios
    [{:id :login :build :app :tags [:smoke]
      :steps [[:goto "/login"]
              [:fill "#user" "pedro"]
              [:click "#go"]
              [:expect-text "#hi" "Hello"]
              [:expect-sub [:current-user] 'some?]]}
     {:id :dashboard :build :app :tags [:slow]
      :steps [[:goto "/dash"] [:expect-visible "#chart"]]}]}
   :hive.cljs/watch {:on-build-success [[:run-e2e {:tags [:smoke]}]]
                     :debounce-ms 500}})

(def manifest (:ok (manifest/parse raw "/tmp/hive-cljs-fixture")))

(defn completed-status
  ([] (completed-status :app))
  ([build-id]
   {:build/id build-id :build/state :completed
    :build/warnings [] :build/errors [] :build/files []
    :build/compiled 1}))

(defn failed-status
  ([] (failed-status :app))
  ([build-id]
   {:build/id build-id :build/state :failed
    :build/warnings [] :build/errors [{:msg "boom"}] :build/files []}))
