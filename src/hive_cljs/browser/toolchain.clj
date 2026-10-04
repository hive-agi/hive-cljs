(ns hive-cljs.browser.toolchain
  "The stack-agnostic toolchain: the page is the runtime, and the build is a
   command rather than a server.

   This is what an Elm, React, Svelte, Vue or hand-written application looks
   like to hive-cljs. Every browser step and every `-js` / `-state` runtime step
   works exactly as it does for ClojureScript. Build supervision appears when a
   build declares a `:command` (hive runs it) or `:artifacts` (hive observes the
   output an external watcher writes), and is an explained absence when none does —
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
    (let [commands  (manifest/build-commands manifest)
          artifacts (manifest/build-artifacts manifest)]
      (if (or (seq commands) (seq artifacts))
        (r/ok (process/build-tool (:manifest/root manifest) commands process/exec!
                                  {:artifacts artifacts}))
        (r/err :build-tool/not-supervised
               {:hint (str "no build declares a :command or :artifacts — add "
                           ":command [\"npx\" \"vite\" \"build\"] for hive to run "
                           "the build, or :artifacts [\"dist\"] for hive to observe "
                           "an external `vite --watch`, under :hive.cljs/builds")}))))

  (open-runtime [_ _manifest]
    ;; Resolved here rather than handed down from the composition root: the
    ;; channel only ever touches the page handle inside the session it is bound
    ;; to, so which driver VALUE performs the evaluation does not matter.
    (r/bind (factory/driver) (fn [d] (r/ok (page-eval/channel d)))))

  ;; The build tool owns no connection — it spawns a process per compile and
  ;; waits for it — but an output observer may be sampling, and stops here. The
  ;; session the runtime borrows is closed by the run that opened it.
  (close-build-tool! [_ bt] (process/close! bt))
  (close-runtime! [_ _] nil))

(defn toolchain
  "Constructor `hive-cljs.toolchain` resolves for :browser."
  []
  (->BrowserToolchain))
