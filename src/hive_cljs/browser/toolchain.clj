(ns hive-cljs.browser.toolchain
  "The stack-agnostic toolchain: no build supervision, and a runtime channel
   that evaluates in the page itself.

   This is what an Elm, React, Svelte, Vue or hand-written application looks
   like to hive-cljs. It supervises no build because those toolchains have no
   long-lived server to ask — `cljs status` and the build→e2e watcher stay dark
   until a build adapter is configured — but every browser step and every
   `-js` runtime step works exactly as it does for ClojureScript."
  (:require [hive-cljs.browser.factory :as factory]
            [hive-cljs.browser.page-eval :as page-eval]
            [hive-cljs.ports :as ports]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defrecord BrowserToolchain []
  ports/IToolchain

  (open-build-tool [_ _manifest]
    (r/err :build-tool/not-supervised
           {:hint (str "the :browser toolchain watches no build — register an "
                       "IToolchain that opens one, or run the build yourself")}))

  (open-runtime [_ _manifest]
    ;; Resolved here rather than handed down from the composition root: the
    ;; channel only ever touches the page handle inside the session it is bound
    ;; to, so which driver VALUE performs the evaluation does not matter.
    (r/bind (factory/driver) (fn [d] (r/ok (page-eval/channel d)))))

  (close-build-tool! [_ _] nil)

  ;; Nothing to release: the channel owns no connection, and the session it
  ;; borrows is closed by the run that opened it.
  (close-runtime! [_ _] nil))

(defn toolchain
  "Constructor `hive-cljs.toolchain` resolves for :browser."
  []
  (->BrowserToolchain))
