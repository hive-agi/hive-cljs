(ns hive-cljs.http
  "The HARNESS's HTTP channel, the pure half: an out-of-band actor a scenario
   plays without leaving the page it drives.

   `[:http {...}]` makes a request (pay, mine a block, move a clock) and
   `[:expect-http {...}]` judges the LAST response. The request is executed by
   the harness through `ports/IHttpChannel`, never by the browser, so no
   `:goto` to another origin discards the pinned runtime.

   Everything here is data in, data out: the allowlist check the plan runs, the
   request the adapter receives, the decoding of what it answered and the
   verdict of an expectation. The one effect lives behind the port
   (`hive-cljs.http.jdk`).

   The allowlist is the escape-hatch control. A plan may only address the
   `host:port` authorities its manifest lists under `:hive.cljs/e2e
   :http-allow`; anything else fails the PLAN, before a browser opens."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [malli.core :as m]
            [hive-cljs.schema :as s]
            #?(:clj [clojure.data.json :as json])))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; URLs and the allowlist
;; =============================================================================

(def ^:private url-re
  ;; scheme://host[:port][/path?query#fragment] — userinfo and IPv6 literals
  ;; are refused: neither belongs in a scenario's out-of-band call.
  #"^(?i)(https?)://([A-Za-z0-9.\-]+)(?::([0-9]{1,5}))?(?:[/?#].*)?$")

(def default-ports {"http" 80 "https" 443})

(defn authority
  "`{:scheme :host :port :authority}` of an absolute http(s) URL, the
   authority always carrying its port (explicit or the scheme's default), or
   nil when the URL is not one this channel will address."
  [url]
  (when (string? url)
    (when-let [[_ scheme host port] (re-matches url-re url)]
      (let [scheme (str/lower-case scheme)
            host   (str/lower-case host)
            port   (if port
                     #?(:clj (Long/parseLong port) :cljs (js/parseInt port 10))
                     (get default-ports scheme))]
        {:scheme scheme :host host :port port :authority (str host ":" port)}))))

(defn normalize-allow
  "The allowlist as a set of lower-cased `host:port` strings."
  [allow]
  (into #{} (map str/lower-case) allow))

(defn allowed?
  "True when `url` is an http(s) URL whose `host:port` is on `allow`."
  [allow url]
  (boolean
   (when-let [a (authority url)]
     (contains? (normalize-allow allow) (:authority a)))))

(defn refusal
  "Why `url` may not be addressed under `allow`, or nil when it may."
  [allow url]
  (let [a (authority url)]
    (cond
      (nil? a)
      {:url url
       :problem "not an absolute http(s) URL with a plain host (no userinfo, no IPv6 literal)"}

      (not (contains? (normalize-allow allow) (:authority a)))
      {:url       url
       :authority (:authority a)
       :allowed   (vec (sort (normalize-allow allow)))
       :problem   (if (empty? allow)
                    "the manifest declares no :http-allow, so no :http step may run"
                    (str (:authority a) " is not on :http-allow"))
       :hint      (str "add \"" (:authority a) "\" to :hive.cljs/e2e :http-allow")})))

;; =============================================================================
;; Plan-time checks
;; =============================================================================

(defn http-op?
  "True for an op routed to the harness's HTTP channel."
  [op]
  (= :http (:op/channel op)))

(defn check-ops
  "Result of `ops` when every http op is admissible under `allow`, else the
   first problem, tagged with the op's `:index`:

   - `:http/host-not-allowed` a request addresses a host:port the manifest
     does not list (or the URL is not one the channel addresses at all)
   - `:http/no-request-yet`   an assertion judges the last response before any
     request was made, which can only ever fail

   `assertion-op?` answers whether an op asserts (`step/assertion-op?`), so the
   check reads the op's semantics rather than a kind list."
  [allow ops assertion-op?]
  (loop [[[idx op] & more] (map-indexed vector ops)
         requested?         false]
    (cond
      (nil? op) (r/ok ops)

      (not (http-op? op)) (recur more requested?)

      (assertion-op? op)
      (if requested?
        (recur more requested?)
        (r/err :http/no-request-yet
               {:index idx :kind (:op/kind op)
                :hint "an :expect-http judges the LAST :http response; make the request first"}))

      :else
      (if-let [why (refusal allow (get-in op [:op/args 0 :url]))]
        (r/err :http/host-not-allowed (assoc why :index idx :kind (:op/kind op)))
        (recur more true)))))

;; =============================================================================
;; Request building — what the adapter receives
;; =============================================================================

(defn- write-json [x]
  #?(:clj (json/write-str x) :cljs (js/JSON.stringify (clj->js x))))

(defn- read-json [text]
  #?(:clj (json/read-str text :key-fn keyword)
     :cljs (js->clj (js/JSON.parse text) :keywordize-keys true)))

(defn- header-name [k] (str/lower-case (if (keyword? k) (name k) (str k))))

(defn build-request
  "The `schema/HttpWire` an adapter sends, from an op's authored request.

   A string body goes as written; any other body is encoded as JSON, with a
   `content-type: application/json` unless the author set one. Header names
   are lower-cased. `default-timeout-ms` applies when the step names none."
  [{:keys [method url headers body timeout-ms]} default-timeout-ms]
  (let [headers (into {} (map (fn [[k v]] [(header-name k) (str v)])) headers)
        encoded (cond
                  (nil? body)    nil
                  (string? body) body
                  :else          (write-json body))]
    (cond-> {:method     (or method :get)
             :url        url
             :headers    (cond-> headers
                           (and (some? body) (not (string? body))
                                (not (contains? headers "content-type")))
                           (assoc "content-type" "application/json"))
             :timeout-ms (or timeout-ms default-timeout-ms 15000)}
      encoded (assoc :body encoded))))

(defn decode-response
  "Result of the response a scenario judges: the adapter's raw
   `{:status :headers :body text}` with `:text` kept verbatim and `:body`
   decoded as the request asked (`:as :json` parses it to EDN with keyword
   keys; `:as :text`, the default, keeps the string)."
  [as raw]
  (let [text (or (:body raw) "")
        base {:status  (:status raw)
              :headers (into {} (map (fn [[k v]] [(header-name k) v])) (:headers raw))
              :text    text}]
    (if (= :json as)
      (try
        (r/ok (assoc base :body (when-not (str/blank? text) (read-json text))))
        (catch #?(:clj Exception :cljs :default) e
          (r/err :http/not-json {:status (:status raw)
                                 :text   (subs text 0 (min 200 (count text)))
                                 :cause  (ex-message e)})))
      (r/ok (assoc base :body text)))))

;; =============================================================================
;; Expectations — judged against the LAST response
;; =============================================================================

(defn- same? [e a]
  (if (and (number? e) (number? a)) (== e a) (= e a)))

(defn mismatches
  "Every place `actual` fails to include `expected`, as `{:path :expected
   :actual}` maps. A map includes another when every expected key is present
   and its value is included in turn (extra keys are fine); a vector must have
   the same length and include element by element; anything else compares by
   value, numbers numerically."
  ([expected actual] (mismatches expected actual []))
  ([expected actual path]
   (cond
     (map? expected)
     (if (map? actual)
       (into []
             (mapcat (fn [[k v]]
                       (if (contains? actual k)
                         (mismatches v (get actual k) (conj path k))
                         [{:path (conj path k) :expected v :actual :hive-cljs.http/absent}])))
             expected)
       [{:path path :expected expected :actual actual}])

     (sequential? expected)
     (if (and (sequential? actual) (= (count expected) (count actual)))
       (into [] (mapcat (fn [i e a] (mismatches e a (conj path i)))
                        (range) expected actual))
       [{:path path :expected expected :actual actual}])

     :else
     (if (same? expected actual) [] [{:path path :expected expected :actual actual}]))))

(defn- status-ok? [expected status]
  (if (set? expected) (contains? expected status) (= expected status)))

(defn judge
  "Outcome of an `:expect-http` expectation against the last decoded response
   (`nil` when no request has been made). `:pass` reports the status, so a
   green step still says what it saw."
  [{:keys [status body-includes body-contains headers] :as expect} response]
  (if (nil? response)
    {:state :error :detail "no :http response to judge — make the request first"}
    (let [body     (:body response)
          problems
          (cond-> []
            (and (contains? expect :status) (not (status-ok? status (:status response))))
            (conj (str "status " (:status response) ", expected " (pr-str status)))

            (and body-contains (not (str/includes? (:text response) body-contains)))
            (conj (str "body does not contain " (pr-str body-contains)))

            (contains? expect :body-includes)
            (into (if (and (string? body) (coll? body-includes))
                    ["body is text — ask the request for :as :json to compare it as data"]
                    (map #(str "body at " (pr-str (:path %)) ": expected " (pr-str (:expected %))
                               ", got " (pr-str (:actual %)))
                         (mismatches body-includes body))))

            headers
            (into (keep (fn [[k v]]
                          (let [got (get (:headers response) (header-name k))]
                            (when-not (= (str v) got)
                              (str "header " (header-name k) " is " (pr-str got)
                                   ", expected " (pr-str (str v))))))
                        headers)))]
      (if (empty? problems)
        {:state :pass :detail (str "status " (:status response))}
        {:state :fail :detail (str/join "; " problems)}))))

;; =============================================================================
;; Contracts
;; =============================================================================

(m/=> authority [:=> [:cat :any] [:maybe [:map [:authority :string]]]])
(m/=> allowed? [:=> [:cat [:sequential :string] :any] :boolean])
(m/=> build-request [:=> [:cat s/HttpRequest [:maybe s/Millis]] s/HttpWire])
(m/=> judge [:=> [:cat s/HttpExpect [:maybe :map]] [:map [:state :keyword]]])
