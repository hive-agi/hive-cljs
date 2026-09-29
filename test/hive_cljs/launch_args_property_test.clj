(ns hive-cljs.launch-args-property-test
  "Which browser switches a scenario is launched with, synthesized from
   `hive-cljs.schema/LaunchArgs`."
  (:require [hive-cljs.plan]
            [hive-cljs.schema :as s]
            [hive-schemas.test :as ht]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(ht/deftrifecta-from-schema launch-args
  hive-cljs.plan/launch-args
  {:in  [:cat [:maybe s/LaunchArgs] [:map [:launch-args {:optional true} s/LaunchArgs]]]
   :out [:maybe s/LaunchArgs]
   :rel (fn [[margs scenario] out]
          (= out (if (contains? scenario :launch-args) (:launch-args scenario) margs)))
   :num-tests 200})
