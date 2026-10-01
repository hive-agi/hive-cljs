(ns hive-cljs.http-step-test
  "The harness's out-of-band HTTP channel: `[:http {...}]` makes a request,
   `[:expect-http {...}]` judges the last response, and the manifest's
   `:http-allow` decides at PLAN time which hosts may be addressed.

   The channel is a fake injected through `ports/IHttpChannel`; only the last
   test touches a real socket, an in-process server on an ephemeral port."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [hive-cljs.boundary :as boundary]
            [hive-cljs.fixtures :as fix]
            [hive-cljs.http :as http]
            [hive-cljs.http.jdk :as jdk]
            [hive-cljs.manifest :as manifest]
            [hive-cljs.plan :as plan]
            [hive-cljs.ports :as ports]
            [hive-cljs.schema :as s]
            [hive-cljs.step :as step]
            [hive-cljs.stub.ports :as stub]
            [hive-dsl.result :as r]
            [malli.core :as m])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; A fake channel, injected through the port
;; =============================================================================

(defn- fake-channel
  "An IHttpChannel recording every wire request into `sent` and answering
   with `(respond req)`."
  [sent respond]
  (reify ports/IHttpChannel
    (request! [_ req]
      (swap! sent conj req)
      (respond req))))

(defn- answering [status body]
  (fn [_] (r/ok {:status status :headers {"Content-Type" "application/json"} :body body})))

(defn- manifest-with [allow steps]
  (:ok (manifest/parse (-> fix/raw
                           (assoc-in [:hive.cljs/e2e :http-allow] allow)
                           (assoc-in [:hive.cljs/e2e :scenarios]
                                     [{:id :pay :build :app :steps steps}]))
                       "/tmp/hive-cljs-http")))

(def ^:private pay-steps
  [[:goto "/checkout"]
   [:expect-sub [:order :status] 'some?]
   [:http {:method :post :url "http://localhost:12345/pay"
           :headers {:x-test "1"} :body {:invoice "inv-1" :amount 5} :as :json}]
   [:expect-http {:status 200 :body-includes {:paid true}}]
   [:expect-sub [:order :status] 'some?]])

;; =============================================================================
;; Compilation — shape and declared semantics
;; =============================================================================

(deftest http-steps-compile-to-the-http-channel-with-declared-semantics
  (let [req (:ok (step/compile-step [:http {:method :post :url "http://localhost:1/x"}]))
        exp (:ok (step/compile-step [:expect-http {:status 200}]))]
    (is (m/validate s/Op req))
    (is (= :http (:op/channel req) (:op/channel exp)))
    (testing ":http is an action that may change the world"
      (is (= {:op/assert? false :op/poll? false :op/read-only? false}
             (select-keys req step/semantic-flags))))
    (testing ":expect-http is an OPSEM-declared assertion that only observes"
      (is (step/assertion-op? exp))
      (is (step/read-only-op? exp))
      (is (not (contains? step/assertion-kinds :expect-http))
          "declared on the op, not added to a fallback set"))))

(deftest malformed-http-steps-fail-the-plan
  (is (= :step/malformed (:error (step/compile-step [:http]))))
  (is (= :step/malformed (:error (step/compile-step [:http {:method :post}]))) "no url")
  (is (= :step/malformed (:error (step/compile-step [:http {:url "http://h:1" :method :teleport}]))))
  (is (= :step/malformed (:error (step/compile-step [:http {:url "http://h:1" :exec "rm -rf"}])))
      "closed map — nothing rides along")
  (is (= :step/malformed (:error (step/compile-step [:expect-http {}]))) "expects nothing")
  (is (= :step/malformed (:error (step/compile-step [:expect-http {:status 999}])))))

;; =============================================================================
;; The allowlist — a plan-time decision
;; =============================================================================

(deftest a-listed-host-plans
  (let [res (plan/plan-for-id (manifest-with ["localhost:12345"] pay-steps) :pay)]
    (is (r/ok? res) (pr-str res))
    (is (m/validate s/RunPlan (:ok res)))
    (is (= #{:browser :runtime :http} (plan/channels-used (:ok res))))))

(deftest an-unlisted-host-is-a-plan-error-not-a-runtime-surprise
  (testing "a different port of an allowed host is a different authority"
    (let [res (plan/plan-for-id (manifest-with ["localhost:9999"] pay-steps) :pay)]
      (is (= :http/host-not-allowed (:error res)))
      (is (= 2 (:index res)))
      (is (= "localhost:12345" (:authority res)))
      (is (= :pay (:scenario res)))))
  (testing "no allowlist at all → no :http step plans"
    (let [m   (:ok (manifest/parse (assoc-in fix/raw [:hive.cljs/e2e :scenarios]
                                             [{:id :pay :build :app :steps pay-steps}])
                                   "/tmp/x"))
          res (plan/plan-for-id m :pay)]
      (is (= :http/host-not-allowed (:error res)))
      (is (re-find #"no :http-allow" (:problem res)))))
  (testing "scheme default ports are spelled out, so http://h means h:80"
    (is (http/allowed? ["example.test:80"] "http://example.test/x"))
    (is (http/allowed? ["example.test:443"] "https://EXAMPLE.test/x"))
    (is (not (http/allowed? ["example.test:80"] "https://example.test/x"))))
  (testing "URLs the channel will not address at all"
    (doseq [u ["/relative" "ftp://localhost:1/x" "http://user@localhost:1/x" "http://[::1]:1/"]]
      (is (= :http/host-not-allowed
             (:error (http/check-ops ["localhost:1"]
                                     [(:ok (step/compile-step [:http {:url u}]))]
                                     step/assertion-op?)))
          u))))

(deftest an-expectation-before-any-request-fails-the-plan
  (let [res (plan/plan-for-id (manifest-with ["localhost:12345"]
                                             [[:expect-http {:status 200}]
                                              [:http {:url "http://localhost:12345/x"}]])
                              :pay)]
    (is (= :http/no-request-yet (:error res)))
    (is (= 0 (:index res)))))

(deftest the-manifest-allowlist-is-validated
  (is (r/ok? (manifest/parse (assoc-in fix/raw [:hive.cljs/e2e :http-allow] ["localhost:1"]) "/tmp/x")))
  (is (= :manifest/invalid
         (:error (manifest/parse (assoc-in fix/raw [:hive.cljs/e2e :http-allow] ["localhost"]) "/tmp/x")))
      "a port is always written")
  (is (= :manifest/invalid
         (:error (manifest/parse (assoc-in fix/raw [:hive.cljs/e2e :http-allow] ["http://localhost:1"]) "/tmp/x")))))

;; =============================================================================
;; Execution — through the fake channel
;; =============================================================================

(defn- run [allow steps channel]
  (let [d {:driver (stub/driver stub/always-pass)
           :cljs-eval (stub/cljs-eval (constantly true))
           :http channel}]
    [d (boundary/run-scenario! d (manifest-with allow steps) :pay)]))

(deftest the-harness-pays-without-leaving-the-page
  (let [sent    (atom [])
        [d res] (run ["localhost:12345"] pay-steps
                     (fake-channel sent (answering 200 "{\"paid\":true,\"tx\":\"abc\"}")))
        report  (:ok res)]
    (is (= :pass (:run/state report)) (pr-str report))
    (testing "the request reached the channel as built data"
      (is (= [{:method :post :url "http://localhost:12345/pay"
               :headers {"x-test" "1" "content-type" "application/json"}
               :body (json/write-str {:invoice "inv-1" :amount 5})
               :timeout-ms 15000}]
             @sent))
      (is (every? #(m/validate s/HttpWire %) @sent)))
    (testing "the browser never saw the http ops, so no :goto left the page"
      (is (= [:goto] (mapv :op/kind (stub/performed-ops (:driver d))))))
    (testing "the runtime stayed pinned: one bind, and :expect-sub still answers AFTER the call"
      (is (= 1 (count (stub/binds (:cljs-eval d)))))
      (is (= :pass (:step/state (last (:run/steps report))))))))

(deftest expect-http-fails-on-the-wrong-answer-and-halts
  (let [[_ res] (run ["localhost:12345"] pay-steps
                     (fake-channel (atom []) (answering 402 "{\"paid\":false}")))
        steps   (:run/steps (:ok res))]
    (is (= :fail (:run/state (:ok res))))
    (is (= :pass (:step/state (nth steps 2))) "any status is a request that happened")
    (is (= :fail (:step/state (nth steps 3))))
    (is (re-find #"status 402, expected 200" (:step/detail (nth steps 3))))
    (is (re-find #"\[:paid\]" (:step/detail (nth steps 3))))
    (is (= :skipped (:step/state (nth steps 4))))))

(deftest a-transport-failure-is-an-error-and-no-channel-is-incomplete
  (let [[_ res] (run ["localhost:12345"] pay-steps
                     (fake-channel (atom []) (fn [_] (r/err :http/request-failed {:cause "refused"}))))]
    (is (= :error (:step/state (nth (:run/steps (:ok res)) 2)))))
  (let [[_ res] (run ["localhost:12345"] (subvec pay-steps 0 3) nil)]
    (is (= :incomplete (:run/state (:ok res))))))

(deftest a-json-request-answered-with-text-is-an-error-not-a-pass
  (let [[_ res] (run ["localhost:12345"] pay-steps
                     (fake-channel (atom []) (answering 200 "<html>oops")))]
    (is (= :error (:step/state (nth (:run/steps (:ok res)) 2))))))

(deftest judge-reads-the-last-response
  (let [resp {:status 201 :headers {"x-id" "7"} :text "{\"a\":{\"b\":[1,2]}}"
              :body {:a {:b [1 2] :c 3}}}]
    (is (= :pass (:state (http/judge {:status #{200 201}} resp))))
    (is (= :pass (:state (http/judge {:body-includes {:a {:b [1 2]}}} resp))))
    (is (= :fail (:state (http/judge {:body-includes {:a {:b [1]}}} resp))))
    (is (= :pass (:state (http/judge {:body-contains "\"b\"" :headers {:x-id 7}} resp))))
    (is (= :fail (:state (http/judge {:headers {"x-id" "8"}} resp))))
    (is (= :error (:state (http/judge {:status 200} nil))))))

;; =============================================================================
;; The JDK adapter, against a real in-process server on an ephemeral port
;; =============================================================================

(deftest the-jdk-adapter-talks-to-a-real-server
  (let [seen   (atom nil)
        server (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
                 (.createContext
                  "/"
                  (reify HttpHandler
                    (handle [_ ex]
                      (let [^HttpExchange ex ex
                            body (slurp (.getRequestBody ex))
                            out  (.getBytes "{\"ok\":true}" "UTF-8")]
                        (reset! seen {:method (.getRequestMethod ex)
                                      :path   (.getPath (.getRequestURI ex))
                                      :ctype  (.getFirst (.getRequestHeaders ex) "Content-Type")
                                      :body   body})
                        (.add (.getResponseHeaders ex) "X-Reply" "yes")
                        (.sendResponseHeaders ex 202 (alength out))
                        (with-open [os (.getResponseBody ex)] (.write os out))))))
                 (.start))
        port   (.getPort (.getAddress server))]
    (try
      (let [url  (str "http://127.0.0.1:" port "/mine")
            ch   (jdk/channel)
            wire (http/build-request {:method :post :url url :body {:blocks 1}} 5000)
            res  (ports/request! ch wire)]
        (is (r/ok? res) (pr-str res))
        (is (= 202 (:status (:ok res))))
        (is (= "yes" (get-in res [:ok :headers "x-reply"])))
        (is (= {:ok true} (:body (:ok (http/decode-response :json (:ok res))))))
        (is (= {:method "POST" :path "/mine" :ctype "application/json"
                :body "{\"blocks\":1}"}
               @seen))
        (testing "an unreachable host is an err Result, never a throw"
          (.stop server 0)
          (is (= :http/request-failed
                 (:error (ports/request! ch (assoc wire :timeout-ms 2000)))))))
      (finally (.stop server 0)))))
