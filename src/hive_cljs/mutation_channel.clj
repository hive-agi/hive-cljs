(ns hive-cljs.mutation-channel
  "Which runtime channel a fault rides, decided BEFORE anything runs.

   A fault is injected as one more runtime op, so it can only take effect on a
   vocabulary the connected channel renders. That is asked of the channel
   itself, through the same `IRuntimeDialect` port `boundary/perform-runtime!`
   uses for a scenario's own steps: a `:browser` page-eval channel renders
   `:eval-js` and nothing ClojureScript, a shadow channel renders both.

   A fault the channel cannot carry is refused up front with a typed error
   naming the toolchain and the channel. Sending it anyway is how every fault
   came back `:unapplied` and the suite was reported as scoring 0.0."
  (:require [hive-cljs.mutation :as mutation]
            [hive-cljs.ports :as ports]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn renders?
  "Predicate over an op: true when runtime channel `ce` renders it."
  [ce]
  (fn [op]
    (boolean (and ce (ports/runtime-dialect? ce) (ports/assertion-source ce op)))))

(defn bind-faults
  "Result of `faults` bound to the op kind the runtime channel in `deps`
   carries them on, or `:mutation/fault-channel-mismatch` naming the toolchain
   (`:toolchain-id` in deps), what its channel can carry (`:channel`, empty
   when nothing), and the faults it cannot (`:faults`)."
  [deps faults]
  (let [{:keys [bound refused carriers]}
        (mutation/bind-faults (renders? (:cljs-eval deps)) faults)
        tc (or (:toolchain-id deps) "connected")]
    (if (empty? refused)
      (r/ok bound)
      (r/err :mutation/fault-channel-mismatch
             {:toolchain (:toolchain-id deps)
              :channel   carriers
              :faults    refused
              :hint      (if (empty? carriers)
                           (str "the " tc " toolchain has no runtime channel that "
                                "can evaluate a fault")
                           (str "the " tc " toolchain's runtime channel carries "
                                "faults only as " carriers " - write these faults "
                                "as source strings in that language"))}))))
