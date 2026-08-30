(ns hive-cljs.browser.page-eval
  "A runtime channel backed by the BROWSER — the stack-agnostic half of the
   subsystem.

   Evaluation happens in the very page the scenario drives, so this channel is
   in-band by construction: there is no runtime to choose and therefore nothing
   to pin. It implements neither `IRuntimeAffinity` nor `IRuntimeInventory`,
   because the whole pinning apparatus exists to answer a question — *which
   runtime am I asserting about* — that cannot arise here.

   It also implements no `IRuntimeIntrospection`: reading a page's state is not
   the same power as rewriting an application's handler registry, so an
   `:app-db-schema` invariant and `e2e mutate --auto` report `:incomplete`
   rather than pretending."
  (:require [hive-cljs.dialect.js :as js]
            [hive-cljs.ports :as ports]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defrecord PageEval [driver session]
  ports/ICljsEval
  (eval-cljs [_ _build-id source]
    (cond
      (not (ports/page-eval? driver))
      (r/err :page-eval/driver-cannot-evaluate
             {:hint "this browser adapter cannot evaluate in the page it drives"})

      (nil? session)
      (r/err :page-eval/no-session
             {:hint (str "a browser-backed runtime step needs a page: put a "
                         ":goto before it, so there is an application to ask")})

      :else
      (ports/eval-in-page driver session source)))

  (runtime-available? [_ _] (some? session))

  ports/ISessionBound
  (with-session [this s] (assoc this :session s))
  (bootstrap-source [_] @js/installer)

  ports/IRuntimeDialect
  (assertion-source [_ op] (js/assertion-source op))
  (probe-source [_ op] (js/probe-source op)))

(defn channel
  "An unbound channel over `driver`. The run binds it to its session."
  [driver]
  (->PageEval driver nil))
