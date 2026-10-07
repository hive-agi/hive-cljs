(ns hive-cljs.browser.playwright
  "IBrowserDriver adapter over playwright-java.

   Loading this namespace requires the optional `:browser` alias. Resolve it
   through `hive-cljs.browser.factory`, never with a direct require from a
   layer that must work without a browser.

   `perform-op` is a multimethod keyed on :op/kind — a new browser step kind is
   a new defmethod."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-cljs.dialect.probe :as probe]
            [hive-cljs.ports :as ports]
            [hive-cljs.selector :as selector]
            [hive-dsl.result :as r])
  (:import [com.microsoft.playwright Playwright Browser BrowserType$LaunchOptions
            BrowserContext Page Page$ScreenshotOptions Locator Frame ElementHandle
            ConsoleMessage]
           [java.util.function Consumer]
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

(defn selector-string
  "The selector string a Playwright call takes. A plan has already compiled
   every selector — step arguments in `hive-cljs.step`, `:iframe` in
   `hive-cljs.plan` — so this is the identity on what a run hands it. It still
   compiles selector DATA (and rejects a malformed one with the selector's own
   message) so an op or session built by hand, outside a plan, cannot reach
   Playwright as a non-string and fail as an interop error."
  ^String [sel]
  (selector/css sel))

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
  (if-let [authored (:iframe session)]
    (let [sel (selector-string authored)
          ^ElementHandle handle (.querySelector (page-of session) sel)]
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
  [session {[authored] :op/args}]
  (let [sel (selector-string authored)]
    (.click (dom-root session) sel)
    (pass sel)))

(defmethod perform-op :fill
  [session {[authored value] :op/args}]
  (let [sel (selector-string authored)]
    (.fill (dom-root session) sel (str value))
    (pass sel)))

(defmethod perform-op :select
  [session {[authored value] :op/args}]
  (let [sel (selector-string authored)]
    (.selectOption (dom-root session) sel (str value))
    (pass sel)))

(defmethod perform-op :check
  [session {[authored] :op/args}]
  (let [sel (selector-string authored)]
    (.check (dom-root session) sel)
    (pass sel)))

(defmethod perform-op :press
  [session {[authored k] :op/args}]
  (let [sel (selector-string authored)]
    (.press (dom-root session) sel (str k))
    (pass (str sel " " k))))

(defmethod perform-op :hover
  [session {[authored] :op/args}]
  (let [sel (selector-string authored)]
    (.hover (dom-root session) sel)
    (pass sel)))

(defmethod perform-op :wait-for
  [session {[authored] :op/args}]
  (let [sel (selector-string authored)]
    (.waitForSelector (dom-root session) sel)
    (pass sel)))

(defmethod perform-op :wait-ms
  [session {[ms] :op/args}]
  (.waitForTimeout (page-of session) (double ms))
  (pass (str ms "ms")))

(defmethod perform-op :expect-text
  [session {[authored expected] :op/args}]
  (let [sel    (selector-string authored)
        actual (.textContent (dom-root session) sel)]
    (if (and actual (str/includes? actual (str expected)))
      (pass)
      (fail (str "expected " (pr-str expected) " in " sel
                 ", got " (pr-str actual))))))

(defmethod perform-op :expect-value
  [session {[authored expected] :op/args}]
  (let [sel    (selector-string authored)
        actual (.inputValue (dom-root session) sel)]
    (if (= (str expected) (str actual))
      (pass)
      (fail (str "expected value " (pr-str expected) " in " sel
                 ", got " (pr-str actual))))))

(defmethod perform-op :expect-visible
  [session {[authored] :op/args}]
  (let [sel (selector-string authored)]
    (if (.isVisible (dom-root session) sel)
      (pass)
      (fail (str sel " is not visible")))))

(defmethod perform-op :expect-hidden
  [session {[authored] :op/args}]
  (let [sel (selector-string authored)]
    (if (.isHidden (dom-root session) sel)
      (pass)
      (fail (str sel " is visible")))))

(defmethod perform-op :expect-count
  [session {[authored expected] :op/args}]
  (let [sel    (selector-string authored)
        ^Locator loc (.locator (dom-root session) sel)
        actual (.count loc)]
    (if (= (long expected) (long actual))
      (pass)
      (fail (str "expected " expected " of " sel ", got " actual)))))

(defmethod perform-op :expect-attr
  [session {[authored attr expected] :op/args}]
  (let [sel    (selector-string authored)
        actual (.getAttribute (dom-root session) sel (name attr))]
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

(defmethod perform-op :hive-cljs/at-origin
  [session {[origin] :op/args}]
  (let [actual (.url (page-of session))]
    (if (str/starts-with? (str actual) (str origin))
      (pass actual)
      (fail (str "page is not on the app origin " (pr-str origin) ", it is on "
                 (pr-str actual) " — a fault applied here would break nothing")))))

;; =============================================================================
;; Page errors
;; =============================================================================

(defn page-errors
  "Console errors and uncaught page errors recorded on `session` so far, as
   `[{:error/source :console|:pageerror :error/text \"…\"} …]`."
  [session]
  (some-> (:errors session) deref))

(defn unexpected-errors
  "Recorded `errors` an `:expect-no-errors` options map does not excuse.

   `opts` is data: `:sources` (a set of `:console`/`:pageerror`, default both)
   and `:ignore` (substrings whose presence excuses an error)."
  [errors {:keys [sources ignore]}]
  (let [sources (set (or sources [:console :pageerror]))]
    (vec (remove (fn [{:error/keys [source text]}]
                   (or (not (contains? sources source))
                       (some #(str/includes? (str text) (str %)) ignore)))
                 errors))))

(defmethod perform-op :expect-no-errors
  [session {[opts] :op/args}]
  (let [bad (unexpected-errors (page-errors session) (or opts {}))]
    (if (empty? bad)
      (pass "no console or page errors")
      (fail (str (count bad) " console/page error(s): "
                 (str/join " | " (map #(str (name (:error/source %)) ": " (:error/text %))
                                      (take 5 bad))))))))

(defn- record-errors!
  "Listen for console errors and uncaught page errors on `page`, appending them
   to `errors`."
  [^Page page errors]
  (.onConsoleMessage page
                     (reify Consumer
                       (accept [_ m]
                         (let [^ConsoleMessage m m]
                           (when (= "error" (.type m))
                             (swap! errors conj {:error/source :console
                                                 :error/text   (.text m)}))))))
  (.onPageError page
                (reify Consumer
                  (accept [_ e]
                    (swap! errors conj {:error/source :pageerror
                                        :error/text   (str e)})))))

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

(def default-window-class
  "X11 WM_CLASS a headed browser window carries unless the manifest names one."
  "hive-cljs-headed")

(defn window-args
  "Launch arguments that stamp `window-class` onto a HEADED window's WM_CLASS,
   so a window manager can place it. Chromium and Firefox (GTK) take
   `--class=NAME`; headless launches and WebKit get none."
  [engine headless window-class]
  (if (or headless (= :webkit engine) (empty? window-class))
    []
    [(str "--class=" window-class)]))

(defn launch-options
  "Compose explicit process switches with the headed window-class switch."
  ^BrowserType$LaunchOptions [engine headless window-class launch-args]
  (let [args (into (vec launch-args) (window-args engine headless window-class))]
    (cond-> (-> (BrowserType$LaunchOptions.) (.setHeadless (boolean headless)))
      (seq args) (.setArgs ^java.util.List args))))

(defn- launch-browser
  ^Browser [^Playwright pw engine headless window-class launch-args]
  (let [opts (launch-options engine headless window-class launch-args)]
    (case engine
      :firefox (.launch (.firefox pw) opts)
      :webkit  (.launch (.webkit pw) opts)
      (.launch (.chromium pw) opts))))

(defn token-script
  "JS source stamping `token` onto the document as `window.__hiveCljsToken`."
  [token]
  (probe/->js (list 'set! 'js/window.__hiveCljsToken (str token))))

(defrecord PlaywrightDriver [pw-atom]
  ports/IBrowserDriver
  (open-session! [_ {:keys [browser headless timeout-ms artifacts-dir ignore-https-errors
                            viewport user-agent is-mobile has-touch device-scale-factor iframe
                            window-class launch-args]}]
    (try
      (let [^Playwright pw (Playwright/create)
            br  (launch-browser pw (or browser :chromium) (if (nil? headless) true headless)
                                (or window-class default-window-class) launch-args)
            ctx-opts (cond-> (Browser$NewContextOptions.)
                       ignore-https-errors (.setIgnoreHTTPSErrors true)
                       viewport (.setViewportSize (int (:width viewport)) (int (:height viewport)))
                       user-agent (.setUserAgent (str user-agent))
                       (some? is-mobile) (.setIsMobile (boolean is-mobile))
                       (some? has-touch) (.setHasTouch (boolean has-touch))
                       device-scale-factor (.setDeviceScaleFactor (double device-scale-factor)))
            ^BrowserContext ctx (.newContext br ctx-opts)
            ^Page page (.newPage ctx)]
        (when timeout-ms
          (.setDefaultTimeout page (double timeout-ms)))
        (let [errors  (atom [])
              _       (record-errors! page errors)
              session (cond-> {:pw pw :browser br :context ctx :page page :errors errors
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
