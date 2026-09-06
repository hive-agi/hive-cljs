(ns hive-cljs.contrast
  "PURE layer for colour contrast.

   Colours a page declares or computes become WCAG contrast ratios, and a set
   of samples becomes a `schema/ContrastReport`. No IO: reading a stylesheet
   and asking a live page for its computed styles live in the boundary.

   Two rungs. The DECLARED rung reads the custom properties a stylesheet
   defines. The COMPUTED rung reads what a browser painted, including the
   background layers and opacity the declared rung cannot see."
  (:require [clojure.string :as str]
            [hive-cljs.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;; =============================================================================
;; Colour
;; =============================================================================

(def ^:private hex-pattern #"(?i)^#([0-9a-f]{3,8})$")
(def ^:private functional-pattern #"(?i)^rgba?\(([^)]*)\)$")

(defn- parse-long* [s]
  (when (re-matches #"[+-]?\d+" s)
    #?(:clj (try (Long/parseLong s) (catch Exception _ nil))
       :cljs (let [n (js/parseInt s 10)] (when-not (js/isNaN n) n)))))

(defn- parse-double* [s]
  (when (re-matches #"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?" s)
    #?(:clj (try (Double/parseDouble s) (catch Exception _ nil))
       :cljs (let [n (js/parseFloat s)] (when-not (js/isNaN n) n)))))

(defn- hex-pair [s]
  #?(:clj (try (Long/parseLong s 16) (catch Exception _ nil))
     :cljs (let [n (js/parseInt s 16)] (when-not (js/isNaN n) n))))

(defn- finite?
  "Whether `x` is a number a ratio can be computed from."
  [x]
  (and (number? x)
       #?(:clj (let [d (double x)]
                 (and (not (Double/isNaN d)) (not (Double/isInfinite d))))
          :cljs (js/isFinite x))))

(defn- clamped
  "A parsed colour with channels and alpha brought into range.
   => nil when any component is not a finite number."
  [{:keys [r g b a]}]
  (when (every? finite? [r g b a])
    {:r (-> (long r) (max 0) (min 255))
     :g (-> (long g) (max 0) (min 255))
     :b (-> (long b) (max 0) (min 255))
     :a (-> (double a) (max 0.0) (min 1.0))}))

(defn- from-hex [digits]
  (let [expand (fn [c] (hex-pair (str c c)))
        pair   (fn [i] (hex-pair (subs digits i (+ i 2))))]
    (case (count digits)
      3 {:r (expand (nth digits 0)) :g (expand (nth digits 1))
         :b (expand (nth digits 2)) :a 1.0}
      4 {:r (expand (nth digits 0)) :g (expand (nth digits 1))
         :b (expand (nth digits 2))
         :a (/ (double (expand (nth digits 3))) 255.0)}
      6 {:r (pair 0) :g (pair 2) :b (pair 4) :a 1.0}
      8 {:r (pair 0) :g (pair 2) :b (pair 4)
         :a (/ (double (pair 6)) 255.0)}
      nil)))

(defn- channel-value
  "One `rgb()` channel argument: a byte, or a percentage of 255."
  [token]
  (if (str/ends-with? token "%")
    (some-> (parse-double* (subs token 0 (dec (count token))))
            (* 2.55) (Math/round) long)
    (parse-long* token)))

(defn- alpha-value
  "An alpha argument: 0-1, or a percentage of 1."
  [token]
  (if (str/ends-with? token "%")
    (some-> (parse-double* (subs token 0 (dec (count token)))) (/ 100.0))
    (parse-double* token)))

(defn- from-functional
  "An `rgb()`/`rgba()` body as a colour, or nil.

   An alpha that is PRESENT and unreadable yields nil, where an absent one
   yields 1.0: `rgba(255,255,255,NaN)` is a colour this layer cannot resolve,
   not an opaque white."
  [body]
  (let [[channels slash-alpha] (str/split body #"/" 2)
        parts       (remove str/blank? (str/split (str/trim channels) #"[,\s]+"))
        alpha-token (or (some-> slash-alpha str/trim not-empty)
                        (nth parts 3 nil))
        alpha       (if alpha-token (alpha-value alpha-token) 1.0)
        [r g b]     (map channel-value (take 3 parts))]
    (when (and r g b alpha)
      {:r r :g g :b b :a (double alpha)})))

(defn parse-color
  "A CSS colour string as `{:r :g :b :a}`, clamped to range.

   => nil when `value` names no colour this layer resolves: a keyword, a
   `var()`, a colour space it does not implement, or a component that is not a
   finite number. Nil means UNKNOWN and is never treated as a measurement."
  [value]
  (when (string? value)
    (let [v (str/trim value)]
      (some-> (cond
                (str/blank? v) nil
                (re-matches hex-pattern v)
                (from-hex (str/lower-case (second (re-matches hex-pattern v))))

                (re-matches functional-pattern v)
                (from-functional (second (re-matches functional-pattern v)))

                :else nil)
              clamped))))

(defn opaque?
  "Whether `color` hides whatever is behind it."
  [color]
  (boolean (and color (>= (double (:a color 1.0)) 1.0))))

(defn over
  "`color` composited onto the opaque `backdrop`, as an opaque colour.

   Source-over compositing in sRGB byte space. An opaque `color` is returned
   unchanged; a fully transparent one yields the backdrop."
  [color backdrop]
  (cond
    (nil? color) nil
    (opaque? color) color
    (nil? backdrop) nil
    :else
    (let [a   (max 0.0 (min 1.0 (double (:a color 1.0))))
          mix (fn [k]
                (long (Math/round (+ (* a (double (get color k)))
                                     (* (- 1.0 a) (double (get backdrop k)))))))]
      {:r (mix :r) :g (mix :g) :b (mix :b) :a 1.0})))

(defn rgb-string
  "An opaque colour as the `rgb(r, g, b)` a browser would report."
  [{:keys [r g b]}]
  (str "rgb(" r ", " g ", " b ")"))

(defn- as-color [value]
  (if (string? value) (parse-color value) value))

(defn faded
  "`color` with `opacity` folded into its alpha, as an `rgba()` string.

   => `color` unchanged when `opacity` is absent or 1, nil when `color` is
   unknown. The CSS `opacity` property does not appear in a computed
   `color`, so a caller that has one has to fold it in before measuring."
  [color opacity]
  (if (and (finite? opacity) (< (double opacity) 1.0))
    (when-let [c (as-color color)]
      (str "rgba(" (:r c) ", " (:g c) ", " (:b c) ", "
           (* (:a c) (max 0.0 (double opacity))) ")"))
    color))

(defn flatten-background
  "Background layers, INNERMOST FIRST, as the one opaque colour painted.

   The layers a browser reports for an element and its ancestors: the last is
   the first opaque one, and every earlier layer sits on top of it.
   => an `rgb()` string, or nil when a layer is unknown or none is opaque.

   A single string is read as a one-layer stack."
  [layers]
  (let [layers (if (string? layers) [layers] layers)
        parsed (mapv as-color layers)]
    (when (and (seq parsed)
               (every? some? parsed)
               (opaque? (peek parsed)))
      (rgb-string (reduce (fn [base layer] (over layer base))
                          (peek parsed)
                          (reverse (pop parsed)))))))

;; =============================================================================
;; WCAG luminance and ratio
;; =============================================================================

(def ^:private srgb-threshold 0.03928)

(defn- linearize [byte-value]
  (let [c (/ (double byte-value) 255.0)]
    (if (<= c srgb-threshold)
      (/ c 12.92)
      (Math/pow (/ (+ c 0.055) 1.055) 2.4))))

(defn luminance
  "WCAG relative luminance of an OPAQUE colour, 0.0 to 1.0.
   Alpha is not consulted: composite with `over` first."
  [{:keys [r g b]}]
  (+ (* 0.2126 (linearize r))
     (* 0.7152 (linearize g))
     (* 0.0722 (linearize b))))

(defn ratio
  "WCAG contrast ratio between two OPAQUE colours. 1.0 to 21.0, symmetric."
  [a b]
  (let [la (luminance a)
        lb (luminance b)]
    (/ (+ 0.05 (max la lb))
       (+ 0.05 (min la lb)))))

(defn contrast
  "Contrast of `foreground` seen against `background`.

   Either may be a CSS colour string or a parsed colour. `background` must be
   opaque: a translucent one has its own backdrop this layer was not given,
   and `flatten-background` is what resolves a stack into one.
   => a ratio, or nil when a colour is unknown or the background is not opaque."
  [foreground background]
  (let [bg (as-color background)]
    (when (opaque? bg)
      (some-> (as-color foreground) (over bg) (ratio bg)))))

;; =============================================================================
;; What a sample has to clear
;; =============================================================================

(def large-text-px
  "Font size at which WCAG calls text large: 18pt."
  24.0)

(def large-bold-px
  "Font size at which BOLD text is large: 14pt."
  18.66)

(defn large-text?
  "Whether WCAG reads this text as large."
  [{:keys [font-size-px bold?]}]
  (let [px (if (finite? font-size-px) (double font-size-px) 0.0)]
    (or (>= px large-text-px)
        (and (boolean bold?) (>= px large-bold-px)))))

(def thresholds
  "Ratio a sample must clear, by conformance level and role.
   `:non-text` is WCAG 1.4.11, which declares no AAA tier."
  {:aa  {:text 4.5 :large-text 3.0 :non-text 3.0}
   :aaa {:text 7.0 :large-text 4.5 :non-text 3.0}})

(def default-level
  "Level a sample is judged at when none is named, or one is not recognised."
  :aa)

(defn level-of
  "`level` when this layer knows it, else `default-level`."
  [level]
  (if (contains? thresholds level) level default-level))

(defn required-ratio
  "What `role` has to clear at `level`, or nil for a role nobody declared."
  [level role]
  (get-in thresholds [(level-of level) role]))

(defn role-of
  "The role a sample is judged as.

   An explicit role may only narrow to `:non-text`, which is the one role a
   font size cannot imply. Text is sized by its own font, so a page that
   stamps `text` on a heading does not thereby raise the heading's bar."
  [{:keys [role] :as sample}]
  (if (= :non-text role)
    :non-text
    (if (large-text? sample) :large-text :text)))

;; =============================================================================
;; Judgement
;; =============================================================================

(defn evaluate
  "One sample judged. => `schema/ContrastFinding`.

   A sample whose colours cannot be resolved is `:unknown` and carries no
   measurement."
  ([sample] (evaluate sample default-level))
  ([{:keys [id foreground background] :as sample} level]
   (let [level    (level-of level)
         role     (role-of sample)
         required (required-ratio level role)
         measured (contrast foreground background)
         state    (cond
                    (nil? measured) :unknown
                    (nil? required) :unknown
                    (>= measured required) :pass
                    :else :fail)]
     (cond-> #:contrast{:id id
                        :role role
                        :level level
                        :required (some-> required double)
                        :foreground (when (string? foreground) foreground)
                        :background (when (string? background) background)
                        :state state}
       (not= :unknown state) (assoc :contrast/measured measured)))))

(defn report
  "Every sample judged, with the failures and the worst measurement to hand.
   => `schema/ContrastReport`."
  ([samples] (report samples default-level))
  ([samples level]
   (let [findings (mapv #(evaluate % level) samples)
         failures (filterv #(= :fail (:contrast/state %)) findings)
         unknown  (filterv #(= :unknown (:contrast/state %)) findings)
         measured (keep :contrast/measured findings)]
     #:contrast{:level (level-of level)
                :findings findings
                :failures failures
                :unknown unknown
                :worst (when (seq measured) (reduce min measured))
                :state (cond
                         (seq failures) :fail
                         (seq unknown) :incomplete
                         :else :pass)})))

;; =============================================================================
;; The declared rung: custom properties
;; =============================================================================

(def ^:private declaration-pattern
  #"--([a-zA-Z0-9_-]+)\s*:\s*([^;}]+)")

(defn- strip-comments [css]
  (str/replace css #"(?s)/\*.*?\*/" ""))

(defn- at-rule-spans
  "`[start end)` of every at-rule in `css`, braces matched."
  [css]
  (let [n (count css)]
    (loop [i 0, depth 0, start nil, spans []]
      (if (>= i n)
        (if start (conj spans [start n]) spans)
        (let [c (nth css i)]
          (cond
            (nil? start)     (recur (inc i) 0 (when (= \@ c) i) spans)
            (= \{ c)         (recur (inc i) (inc depth) start spans)
            (and (= \} c)
                 (= 1 depth)) (recur (inc i) 0 nil (conj spans [start (inc i)]))
            (= \} c)         (recur (inc i) (dec depth) start spans)
            (and (= \; c)
                 (zero? depth)) (recur (inc i) 0 nil (conj spans [start (inc i)]))
            :else            (recur (inc i) depth start spans)))))))

(defn- without-at-rules [css]
  (let [spans (at-rule-spans css)]
    (if (empty? spans)
      css
      (let [{:keys [out cursor]}
            (reduce (fn [{:keys [out cursor]} [a b]]
                      {:out (str out (subs css cursor a)) :cursor b})
                    {:out "" :cursor 0}
                    spans)]
        (str out (subs css cursor))))))

(defn custom-properties
  "Custom properties `css` declares UNCONDITIONALLY, as {\"name\" \"value\"}.

   Comments are stripped, and every at-rule block is removed before scanning:
   a declaration inside `@media`, `@supports` or `@container` applies only
   when its condition holds, and this layer is not told which conditions hold.
   Reading them all and letting the last win reports a theme the reader may
   never see. Later unconditional declarations still win over earlier ones.

   Values are returned verbatim; resolving `var()` needs the cascade, which is
   the computed rung's job."
  [css]
  (when (string? css)
    (reduce (fn [acc [_ k v]] (assoc acc k (str/trim v)))
            {}
            (re-seq declaration-pattern
                    (without-at-rules (strip-comments css))))))

(defn token-samples
  "Samples pairing each foreground token with each background token.

   `pairs` is a seq of `{:foreground token :background token}` plus whatever
   else a sample carries. A pair naming a token `properties` does not hold
   becomes an unknown sample rather than being dropped."
  [properties pairs]
  (mapv (fn [{:keys [foreground background] :as pair}]
          (merge pair
                 {:id (or (:id pair) (keyword (str foreground "-on-" background)))
                  :foreground (get properties foreground)
                  :background (get properties background)}))
        pairs))

;; =============================================================================
;; The computed rung: rows a page reported
;; =============================================================================

(def role-names
  "The role vocabulary as a page spells it, projected from the schema so a role
   added there cannot be silently dropped here."
  (into #{} (map name) (rest s/ContrastRole)))

(defn- row-id
  "A row's id as a readable keyword, or nil when it is not one."
  [id]
  (when (and (string? id) (not (str/blank? id)))
    (keyword (str/replace (str/trim id) #"\s+" "-"))))

(defn rows->samples
  "Positional rows a page collected, promoted to samples.

   A row is `[id foreground background role font-size-px bold? opacity]`, where
   `background` is either one colour or the layer stack innermost-first, and
   `opacity` is the accumulated CSS opacity between the text and that stack.
   Positional because it crosses a host boundary intact.

   A row without a readable id, or with fewer than three slots, is DROPPED: it
   would otherwise become an unmeasurable finding indistinguishable from a real
   one."
  [rows]
  (into []
        (comp (filter #(and (sequential? %) (>= (count %) 3)))
              (keep (fn [[id foreground background role font-size-px bold? opacity]]
                      (when-let [id (row-id id)]
                        (cond-> {:id id
                                 :foreground (faded (when (string? foreground)
                                                      foreground)
                                                    opacity)
                                 :background (flatten-background background)}
                          (contains? role-names (str role))
                          (assoc :role (keyword (str role)))

                          (finite? font-size-px)
                          (assoc :font-size-px font-size-px)

                          (some? bold?)
                          (assoc :bold? (boolean bold?)))))))
        rows))

(m/=> parse-color [:=> [:cat :any] [:maybe s/Rgba]])
(m/=> evaluate [:function
                [:=> [:cat s/ContrastSample] s/ContrastFinding]
                [:=> [:cat s/ContrastSample :any] s/ContrastFinding]])
(m/=> report [:function
              [:=> [:cat [:sequential s/ContrastSample]] s/ContrastReport]
              [:=> [:cat [:sequential s/ContrastSample] :any] s/ContrastReport]])
(m/=> rows->samples [:=> [:cat [:maybe [:sequential :any]]]
                     [:vector s/ContrastSample]])
