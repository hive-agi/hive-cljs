(ns hive-cljs.shadow.toolchain
  "shadow-cljs as an `IToolchain` — the websocket remote-relay for builds, the
   cljs nREPL for the runtime.

   This is the only namespace that names both shadow channels; the composition
   root reaches them through `hive-cljs.toolchain`."
  (:require [hive-cljs.ports :as ports]
            [hive-cljs.shadow.nrepl :as nrepl]
            [hive-cljs.shadow.relay :as relay]
            [hive-dsl.result :as r]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private handshake-timeout-ms 5000)

(defrecord ShadowToolchain []
  ports/IToolchain

  (open-build-tool [_ manifest]
    (let [conn (:manifest/shadow manifest)]
      (r/bind (relay/connect! conn)
              #(relay/await-ready! % handshake-timeout-ms))))

  (open-runtime [_ manifest]
    (let [conn (:manifest/shadow manifest)]
      (if (nrepl/blank-port? conn)
        (r/err :cljs-eval/no-nrepl-port {})
        (nrepl/connect! conn))))

  (close-build-tool! [_ build-tool]
    (try (relay/disconnect! build-tool) (catch Exception _ nil))
    nil)

  (close-runtime! [_ runtime]
    (try (nrepl/disconnect! runtime) (catch Exception _ nil))
    nil))

(defn toolchain
  "Constructor `hive-cljs.toolchain` resolves for :shadow-cljs."
  []
  (->ShadowToolchain))
