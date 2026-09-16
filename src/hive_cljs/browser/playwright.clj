(ns hive-cljs.browser.playwright
  "IBrowserDriver adapter over playwright-java.

   Loading this namespace requires the optional `:browser` alias. Resolve it
   through `hive-cljs.browser.factory`, never with a direct require from a
   layer that must work without a browser.

   `perform-op` is a multimethod keyed on :op/kind — a new browser step kind is
   a new defmethod."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-cljs.ports :as ports]
            [hive-dsl.result :as r])
  (:import [com.microsoft.playwright Playwright Browser BrowserType$LaunchOptions
            BrowserContext Page Page$ScreenshotOptions Locator Frame ElementHandle]
           [java.nio.file Paths]
[com.microsoft.playwright Browser$NewContextOptions]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Outcomes
;; =============================================================================

(defn pass
  ([] (pass nil))
  ([detail] (cond-> {:state :pass} detail (assoc :detail detail))))

(defn fail
  [detail]
  {:state :fail :detail detail})

(defn js->data
  "Host values from the page as Clojure data.

   A JavaScript array must read back as a VECTOR: a polled probe returns
   `[truthy? value]`, and a java.util.List would fall through the caller's
   vector check and be mistaken for a bare value. Object keys stay STRINGS —
   they are the page's own names, not this library's keywords."
  [x]
  (cond
    (instance? java.util.List x) (mapv js->data x)
    (instance? java.util.Map x)  (into {} (map (fn [[k v]] [(str k) (js->data v)])) x)
    :else x))

;; =============================================================================
;; Op interpreter
;; =============================================================================

(defmulti perform-op
  "Execute one browser op against a session. Returns an outcome map."
  (fn [_session op] (:op/kind op)))

(defmethod perform-op :default
  [_ op]
  {:state :error :detail (str "no browser handler for " (:op/kind op))})

(defn- ^Page page-of [session] (:page session))

(defn dom-root
  "What selector ops and page expressions address: the page itself, or the
   document inside the session's `:iframe`.

   A composition host (a slide player, a preview pane, an embedded editor)
   renders the application under test in a CHILD document, so `document` in the
   top page belongs to the host and every selector written against it silently
   addresses the wrong page. Playwright's css engine pierces open shadow roots,
   so one selector reaches a player that wraps its iframe in a custom element.

   A `Frame` rather than a `FrameLocator`, because Frame mirrors Page's whole
   selector API: the same `click`/`fill`/`textContent`/`locator`/`evaluate`
   calls work against either, which is what lets ONE option scope both
   channels instead of only the JavaScript one.

   A configured iframe that cannot be resolved THROWS. It must never fall back
   to the top page: falling back would answer against the host's document,
   which is exactly the confusion the option exists to remove, and it would do
   so while reporting a pass."
  [session]
  (if-let [sel (:iframe session)]
    (let [^ElementHandle handle (.querySelector (page-of session) sel)]
      (when-not handle
        (throw (ex-info (str "iframe " (pr-str sel) " matches nothing on this page")
                        {:selector sel})))
      (or (.contentFrame handle)
          (throw (ex-info (str "iframe " (pr-str sel) " is not a frame")
                          {:selector sel}))))
    (page-of session)))

(defn eval-target
  "`dom-root` as a Result, for the eval channel, which reports an
   unresolvable iframe as a typed error rather than as a thrown step."
  [session]
  (try
    (r/ok (dom-root session))
    (catch Throwable e
      (r/err :browser/iframe-unresolved
             {:selector (:iframe session) :cause (.getMessage e)}))))

(defmethod perform-op :goto
  [session {[url] :op/args}]
  (.navigate (page-of session) url)
  (pass url))

(defmethod perform-op :back
  [session _]
  (.goBack (page-of session))
  (pass))

(defmethod perform-op :reload
  [session _]
  (.reload (page-of session))
  (pass))

(defmethod perform-op :click
  [session {[sel] :op/args}]
  (.click (dom-root session) sel)
  (pass sel))

(defmethod perform-op :fill
  [session {[sel value] :op/args}]
  (.fill (dom-root session) sel (str value))
  (pass sel))

(defmethod perform-op :select
  [session {[sel value] :op/args}]
  (.selectOption (dom-root session) sel (str value))
  (pass sel))

(defmethod perform-op :check
  [session {[sel] :op/args}]
  (.check (dom-root session) sel)
  (pass sel))

(defmethod perform-op :press
  [session {[sel k] :op/args}]
  (.press (dom-root session) sel (str k))
  (pass (str sel " " k)))

(defmethod perform-op :hover
  [session {[sel] :op/args}]
  (.hover (dom-root session) sel)
  (pass sel))

(defmethod perform-op :wait-for
  [session {[sel] :op/args}]
  (.waitForSelector (dom-root session) sel)
  (pass sel))

(defmethod perform-op :wait-ms
  [session {[ms] :op/args}]
  (.waitForTimeout (page-of session) (double ms))
  (pass (str ms "ms")))

(defmethod perform-op :expect-text
  [session {[sel expected] :op/args}]
  (let [actual (.textContent (dom-root session) sel)]
    (if (and actual (str/includes? actual (str expected)))
      (pass)
      (fail (str "expected " (pr-str expected) " in " sel
                 ", got " (pr-str actual))))))

(defmethod perform-op :expect-value
  [session {[sel expected] :op/args}]
  (let [actual (.inputValue (dom-root session) sel)]
    (if (= (str expected) (str actual))
      (pass)
      (fail (str "expected value " (pr-str expected) " in " sel
                 ", got " (pr-str actual))))))

(defmethod perform-op :expect-visible
  [session {[sel] :op/args}]
  (if (.isVisible (dom-root session) sel)
    (pass)
    (fail (str sel " is not visible"))))

(defmethod perform-op :expect-hidden
  [session {[sel] :op/args}]
  (if (.isHidden (dom-root session) sel)
    (pass)
    (fail (str sel " is visible"))))

(defmethod perform-op :expect-count
  [session {[sel expected] :op/args}]
  (let [^Locator loc (.locator (dom-root session) sel)
        actual (.count loc)]
    (if (= (long expected) (long actual))
      (pass)
      (fail (str "expected " expected " of " sel ", got " actual)))))

(defmethod perform-op :expect-attr
  [session {[sel attr expected] :op/args}]
  (let [actual (.getAttribute (dom-root session) sel (name attr))]
    (if (= (str expected) (str actual))
      (pass)
      ;; An ABSENT attribute and one holding the wrong value are different
      ;; mistakes, and the report has to say which: `nil` is a selector or a
      ;; spelling to fix, a wrong value is the application to fix.
      (fail (str "expected " (name attr) "=" (pr-str expected) " on " sel
                 ", got " (if (nil? actual) "no such attribute" (pr-str actual)))))))

(defmethod perform-op :expect-url
  [session {[expected] :op/args}]
  (let [actual (.url (page-of session))]
    (if (str/includes? (str actual) (str expected))
      (pass)
      (fail (str "expected url to contain " (pr-str expected) ", got " (pr-str actual))))))

(defmethod perform-op :screenshot
  [session {[label] :op/args}]
  (let [dir  (:artifacts-dir session)
        path (str (str/replace (str dir) #"/$" "") "/" (name label) ".png")]
    (io/make-parents path)
    (.screenshot (page-of session)
                 (-> (Page$ScreenshotOptions.)
                     (.setPath (Paths/get path (into-array String [])))))
    (assoc (pass path) :artifacts [path])))

;; =============================================================================
;; Session lifecycle
;; =============================================================================

(defn- launch-browser
  ^Browser [^Playwright pw engine headless]
  (let [opts (-> (BrowserType$LaunchOptions.) (.setHeadless (boolean headless)))]
    (case engine
      :firefox (.launch (.firefox pw) opts)
      :webkit  (.launch (.webkit pw) opts)
      (.launch (.chromium pw) opts))))

(defn token-script
  "JS source stamping `token` onto the document as `window.__hiveCljsToken`."
  [token]
  (str "window.__hiveCljsToken = " (pr-str (str token)) ";"))

(defrecord PlaywrightDriver [pw-atom]
  ports/IBrowserDriver
  (open-session! [_ {:keys [browser headless timeout-ms artifacts-dir ignore-https-errors
                            viewport iframe]}]
    (try
      (let [^Playwright pw (Playwright/create)
            br  (launch-browser pw (or browser :chromium) (if (nil? headless) true headless))
            ctx-opts (cond-> (Browser$NewContextOptions.)
                       ignore-https-errors (.setIgnoreHTTPSErrors true)
                       viewport (.setViewportSize (int (:width viewport)) (int (:height viewport))))
            ^BrowserContext ctx (.newContext br ctx-opts)
            ^Page page (.newPage ctx)]
        (when timeout-ms
          (.setDefaultTimeout page (double timeout-ms)))
        (let [session (cond-> {:pw pw :browser br :context ctx :page page
                               :artifacts-dir (or artifacts-dir ".hive-cljs/artifacts")}
                        iframe (assoc :iframe iframe))]
          (swap! pw-atom conj session)
          (r/ok session)))
      (catch Throwable e
        (r/err :browser/launch-failed {:cause (.getMessage e)}))))

  (perform! [_ session op]
    (let [started (System/currentTimeMillis)]
      (try
        (let [outcome (perform-op session op)]
          (r/ok (assoc outcome :elapsed-ms (- (System/currentTimeMillis) started))))
        (catch Throwable e
          (r/ok {:state :error
                 :detail (str (.getSimpleName (class e)) ": " (.getMessage e))
                 :elapsed-ms (- (System/currentTimeMillis) started)})))))

  (close-session! [_ session]
    (try
      (some-> ^BrowserContext (:context session) .close)
      (some-> ^Browser (:browser session) .close)
      (some-> ^Playwright (:pw session) .close)
      (swap! pw-atom disj session)
      (r/ok nil)
      (catch Throwable e
        (r/err :browser/close-failed {:cause (.getMessage e)}))))

  ports/IPageMarker
  (mark-session! [_ session token]
    (try
      (let [script (token-script token)]
        (.addInitScript ^BrowserContext (:context session) script)
        (.evaluate (page-of session) script)
        (r/ok token))
      (catch Throwable e
        (r/err :browser/mark-failed {:cause (.getMessage e)}))))

  ports/IPageEval
  (eval-in-page [_ session source]
    (try
      (let [target (eval-target session)]
        (if (r/err? target)
          target
          (let [v (js->data (.evaluate (:ok target) source))]
            (r/ok {:value v :printed (pr-str v)}))))
      (catch Throwable e
        (r/err :browser/eval-failed
               {:cause (.getMessage e) :source source})))))

(extend-type PlaywrightDriver
  ports/IPageBootstrap
  (bootstrap! [_ session source]
    ;; An init script on the CONTEXT, not an evaluate on the current page: the
    ;; page is blank at this point and the application does not exist yet. The
    ;; script has to be waiting when the first :goto loads it, because what it
    ;; installs is what the app's own startup calls into.
    (try
      (.addInitScript ^BrowserContext (:context session) ^String source)
      (r/ok :installed)
      (catch Throwable e
        (r/err :browser/bootstrap-failed {:cause (.getMessage e)})))))

(defn driver
  "Construct the Playwright-backed IBrowserDriver."
  []
  (->PlaywrightDriver (atom #{})))

(defn handled-kinds
  "Browser op kinds this adapter implements."
  []
  (vec (remove #{:default} (keys (methods perform-op)))))
