(ns hive-cljs.browser.toolchain
  "The stack-agnostic toolchain: the page is the runtime, and the build is a
   command rather than a server.

   This is what an Elm, React, Svelte, Vue or hand-written application looks
   like to hive-cljs. Every browser step and every `-js` / `-state` runtime step
   works exactly as it does for ClojureScript. Build supervision appears when a
   build declares a `:command` and is an explained absence when none does —
   these toolchains have no long-lived server to ask, so there is nothing to
   connect to and nothing to poll."
  (:require [hive-cljs.browser.factory :as factory]
            [hive-cljs.browser.page-eval :as page-eval]
            [hive-cljs.build.process :as process]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.ports :as ports]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defrecord BrowserToolchain []
  ports/IToolchain

  (open-build-tool [_ manifest]
    (if-let [commands (seq (manifest/build-commands manifest))]
      (r/ok (process/build-tool (:manifest/root manifest) (into {} commands)))
      (r/err :build-tool/not-supervised
             {:hint (str "no build declares a :command — add one under "
                         ":hive.cljs/builds (e.g. [\"npx\" \"vite\" \"build\"]), "
                         "or run the build yourself")})))

  (open-runtime [_ _manifest]
    ;; Resolved here rather than handed down from the composition root: the
    ;; channel only ever touches the page handle inside the session it is bound
    ;; to, so which driver VALUE performs the evaluation does not matter.
    (r/bind (factory/driver) (fn [d] (r/ok (page-eval/channel d)))))

  ;; Nothing to release. The build tool owns no connection — it spawns a process
  ;; per compile and waits for it — and the session the runtime borrows is
  ;; closed by the run that opened it.
  (close-build-tool! [_ _] nil)
  (close-runtime! [_ _] nil))

(defn toolchain
  "Constructor `hive-cljs.toolchain` resolves for :browser."
  []
  (->BrowserToolchain))
