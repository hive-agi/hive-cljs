(ns hive-cljs.http.jdk
  "`ports/IHttpChannel` over the JDK's own `java.net.http.HttpClient`.

   The boundary of the harness's HTTP channel and nothing more: it receives a
   `schema/HttpWire` the plan already admitted and built, sends it, and hands
   back status, headers and body text. No dependency beyond the JDK, so the
   channel is always available."
  (:require [hive-cljs.ports :as ports]
            [hive-dsl.result :as r]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers)
           (java.time Duration)))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- ->request
  ^HttpRequest [{:keys [method url headers body timeout-ms]}]
  (let [publisher (if body
                    (HttpRequest$BodyPublishers/ofString ^String body)
                    (HttpRequest$BodyPublishers/noBody))
        b         (-> (HttpRequest/newBuilder (URI/create url))
                      (.timeout (Duration/ofMillis (long timeout-ms)))
                      (.method (.toUpperCase (name method)) publisher))]
    (doseq [[k v] headers]
      (.header b ^String k ^String v))
    (.build b)))

(defn- ->response
  [^HttpResponse resp]
  {:status  (.statusCode resp)
   :headers (into {}
                  (map (fn [[k vs]] [(str/lower-case k) (str/join "," vs)]))
                  (.map (.headers resp)))
   :body    (str (.body resp))})

(defrecord JdkHttpChannel [^HttpClient client]
  ports/IHttpChannel
  (request! [_ req]
    (try
      (r/ok (->response (.send client (->request req) (HttpResponse$BodyHandlers/ofString))))
      (catch InterruptedException e
        (.interrupt (Thread/currentThread))
        (r/err :http/interrupted {:url (:url req) :cause (ex-message e)}))
      (catch Exception e
        (r/err :http/request-failed {:url    (:url req)
                                     :method (:method req)
                                     :class  (.getName (class e))
                                     :cause  (ex-message e)})))))

(defn channel
  "An `IHttpChannel` on a fresh JDK client. Redirects are NOT followed: a
   redirect to a host off the allowlist would otherwise be followed silently,
   so the scenario sees the 3xx and judges it."
  []
  (->JdkHttpChannel (-> (HttpClient/newBuilder)
                        (.followRedirects HttpClient$Redirect/NEVER)
                        (.connectTimeout (Duration/ofSeconds 10))
                        (.build))))
