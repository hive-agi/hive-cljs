(ns hive-cljs.contrast-test
  "What `hive-cljs.contrast` promises about colour.

   The numbers here are not this implementation's output recorded back. They
   are the ratios WCAG's own worked examples give, plus values a second
   implementation would have to agree with: black on white is exactly 21, a
   colour on itself is exactly 1."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-cljs.contrast :as contrast]
            [hive-cljs.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:private white {:r 255 :g 255 :b 255 :a 1.0})
(def ^:private black {:r 0 :g 0 :b 0 :a 1.0})

(defn- close-to?
  ([a b] (close-to? a b 0.01))
  ([a b tolerance] (< (Math/abs (- (double a) (double b))) tolerance)))

;; =============================================================================
;; Parsing
;; =============================================================================

(deftest a-colour-is-read-in-every-spelling-a-browser-emits
  (testing "hex, in each length"
    (is (= {:r 255 :g 0 :b 0 :a 1.0} (contrast/parse-color "#f00")))
    (is (= {:r 255 :g 0 :b 0 :a 1.0} (contrast/parse-color "#FF0000")))
    (is (= {:r 18 :g 52 :b 86 :a 1.0} (contrast/parse-color "#123456"))))

  (testing "hex with alpha"
    (is (= {:r 255 :g 0 :b 0 :a 1.0} (contrast/parse-color "#ff0000ff")))
    (is (close-to? 0.0 (:a (contrast/parse-color "#ff000000")))))

  (testing "the functional forms, which is what getComputedStyle returns"
    (is (= {:r 1 :g 2 :b 3 :a 1.0} (contrast/parse-color "rgb(1, 2, 3)")))
    (is (= {:r 1 :g 2 :b 3 :a 0.5} (contrast/parse-color "rgba(1, 2, 3, 0.5)")))
    (is (= {:r 1 :g 2 :b 3 :a 1.0} (contrast/parse-color "rgb(1 2 3)"))
        "CSS Color 4 space-separated form")
    (is (= {:r 1 :g 2 :b 3 :a 0.5} (contrast/parse-color "rgb(1 2 3 / 0.5)"))
        "and its slash-alpha form, which modern browsers do emit")
    (is (= {:r 1 :g 2 :b 3 :a 0.5} (contrast/parse-color "rgb(1 2 3 / 50%)"))))

  (testing "percentage channels"
    (is (= {:r 255 :g 0 :b 128 :a 1.0}
           (contrast/parse-color "rgb(100%, 0%, 50.2%)"))))

  (testing "whitespace and case are not meaning"
    (is (= (contrast/parse-color "#ABCDEF")
           (contrast/parse-color "  #abcdef  "))))

  (testing "a colour this layer cannot resolve is UNKNOWN, never black"
    (doseq [unresolvable ["transparent" "currentColor" "inherit" "red"
                          "var(--line)" "" "   " "#12" "#1234567"
                          "rgb(1, 2)" "nonsense" nil 42]]
      (is (nil? (contrast/parse-color unresolvable))
          (str (pr-str unresolvable)
               " must not be read as a colour, because guessing one invents "
               "a measurement")))))

;; =============================================================================
;; Luminance and ratio, against WCAG's own numbers
;; =============================================================================

(deftest the-ratio-agrees-with-wcag-worked-examples
  (testing "the extremes are exact"
    (is (close-to? 21.0 (contrast/ratio black white) 0.0001))
    (is (close-to? 1.0 (contrast/ratio white white) 0.0001))
    (is (close-to? 1.0 (contrast/ratio black black) 0.0001)))

  (testing "luminance of the endpoints"
    (is (close-to? 0.0 (contrast/luminance black) 0.0001))
    (is (close-to? 1.0 (contrast/luminance white) 0.0001)))

  (testing "it is symmetric: contrast has no direction"
    (let [a {:r 18 :g 52 :b 86 :a 1.0}
          b {:r 200 :g 190 :b 180 :a 1.0}]
      (is (close-to? (contrast/ratio a b) (contrast/ratio b a) 0.0000001))))

  (testing "mid grey on white, a value any implementation must agree on"
    ;; #767676 is the canonical 'smallest grey that passes AA on white'.
    (is (>= (contrast/ratio (contrast/parse-color "#767676") white) 4.5))
    (is (< (contrast/ratio (contrast/parse-color "#777777") white) 4.5)
        "and one step lighter does not")))

;; =============================================================================
;; Alpha
;; =============================================================================

(deftest a-translucent-colour-is-composited-before-it-is-measured
  (testing "half-opacity black over white is the grey a browser paints"
    (let [composited (contrast/over {:r 0 :g 0 :b 0 :a 0.5} white)]
      (is (= {:r 128 :g 128 :b 128 :a 1.0} composited))))

  (testing "a fully transparent foreground IS its backdrop, and so is 1:1"
    (is (close-to? 1.0 (contrast/contrast "rgba(0,0,0,0)" "#ffffff") 0.0001)))

  (testing "an opaque foreground is unchanged by compositing"
    (is (= black (contrast/over black white))))

  (testing "measuring alpha WITHOUT compositing would report 21:1 here"
    ;; The trap: luminance ignores alpha, so a naive implementation reads
    ;; rgba(0,0,0,0.1) on white as pure black and calls a near-invisible
    ;; border perfect.
    (let [naive (contrast/ratio {:r 0 :g 0 :b 0 :a 0.1} white)
          real  (contrast/contrast "rgba(0,0,0,0.1)" "#ffffff")]
      (is (close-to? 21.0 naive 0.0001))
      (is (< real 1.3)
          "the honest answer for a 10% black line on white")))

  (testing "a background that is itself translucent is not measurable here"
    (is (nil? (contrast/contrast "#000000" "rgba(255,255,255,0.5)"))
        "it has its own backdrop this layer was not given")))

;; =============================================================================
;; Which bar applies
;; =============================================================================

(deftest large-text-clears-a-lower-bar-and-only-when-it-really-is-large
  (testing "18pt is 24px"
    (is (contrast/large-text? {:font-size-px 24}))
    (is (not (contrast/large-text? {:font-size-px 23.9}))))

  (testing "14pt BOLD is 18.66px, and the boldness is required"
    (is (contrast/large-text? {:font-size-px 19 :bold? true}))
    (is (not (contrast/large-text? {:font-size-px 19 :bold? false})))
    (is (not (contrast/large-text? {:font-size-px 19}))))

  (testing "a sample that says nothing about its size is ordinary text"
    (is (not (contrast/large-text? {})))
    (is (= :text (contrast/role-of {})))))

(deftest each-role-has-the-bar-wcag-gives-it
  (testing "AA"
    (is (= 4.5 (contrast/required-ratio :aa :text)))
    (is (= 3.0 (contrast/required-ratio :aa :large-text)))
    (is (= 3.0 (contrast/required-ratio :aa :non-text))))

  (testing "AAA raises text, and leaves non-text alone because WCAG does"
    (is (= 7.0 (contrast/required-ratio :aaa :text)))
    (is (= 4.5 (contrast/required-ratio :aaa :large-text)))
    (is (= 3.0 (contrast/required-ratio :aaa :non-text))))

  (testing "an unnamed level defaults to AA rather than to nothing"
    (is (= 4.5 (contrast/required-ratio nil :text))))

  (testing "a role nobody declared has no bar, and so cannot silently pass"
    (is (nil? (contrast/required-ratio :aa :decorative)))))

;; =============================================================================
;; Judgement
;; =============================================================================

(deftest a-sample-is-judged-against-its-own-bar
  (testing "text that clears 4.5 passes"
    (let [f (contrast/evaluate {:id :body
                                :foreground "#767676"
                                :background "#ffffff"})]
      (is (= :pass (:contrast/state f)))
      (is (= :text (:contrast/role f)))
      (is (= 4.5 (:contrast/required f)))))

  (testing "the SAME colours fail as text and pass as a control edge"
    (let [colours {:foreground "#949494" :background "#ffffff"}]
      (is (= :fail (:contrast/state (contrast/evaluate colours))))
      (is (= :pass (:contrast/state
                    (contrast/evaluate (assoc colours :role :non-text))))
          "WCAG 1.4.11 asks 3:1 of a control boundary, not 4.5:1")))

  (testing "a colour that could not be resolved is UNKNOWN, not a pass"
    (doseq [sample [{:foreground "currentColor" :background "#ffffff"}
                    {:foreground "#000000" :background "transparent"}
                    {:foreground nil :background "#ffffff"}]]
      (let [f (contrast/evaluate sample)]
        (is (= :unknown (:contrast/state f)) (pr-str sample))
        (is (nil? (:contrast/measured f))
            "an unknown finding must carry no measurement at all"))))

  (testing "every finding is the shape the schema declares"
    (doseq [sample [{:id :a :foreground "#000" :background "#fff"}
                    {:id :b :foreground "currentColor" :background "#fff"}]]
      (is (m/validate s/ContrastFinding (contrast/evaluate sample))
          (pr-str (contrast/evaluate sample))))))

(deftest a-report-says-what-failed-and-what-was-never-measured
  (let [report (contrast/report
                [{:id :ok :foreground "#000000" :background "#ffffff"}
                 {:id :bad :foreground "#eeeeee" :background "#ffffff"}
                 {:id :missing :foreground "currentColor" :background "#ffffff"}])]
    (is (= 3 (count (:contrast/findings report))))
    (is (= [:bad] (mapv :contrast/id (:contrast/failures report))))
    (is (= [:missing] (mapv :contrast/id (:contrast/unknown report))))
    (is (= :fail (:contrast/state report)))
    (is (m/validate s/ContrastReport report) (pr-str report)))

  (testing "unknowns alone are :incomplete, which is not a pass"
    (let [report (contrast/report
                  [{:id :ok :foreground "#000" :background "#fff"}
                   {:id :missing :foreground "inherit" :background "#fff"}])]
      (is (= :incomplete (:contrast/state report)))
      (is (empty? (:contrast/failures report)))))

  (testing "all clear is a pass, and the worst measurement is reported"
    (let [report (contrast/report
                  [{:id :a :foreground "#000000" :background "#ffffff"}
                   {:id :b :foreground "#767676" :background "#ffffff"}])]
      (is (= :pass (:contrast/state report)))
      (is (close-to? 4.54 (:contrast/worst report) 0.05)
          "the worst is the one nearest its bar, not the average")))

  (testing "nothing to judge is a pass with nothing measured"
    (let [report (contrast/report [])]
      (is (= :pass (:contrast/state report)))
      (is (nil? (:contrast/worst report)))
      (is (m/validate s/ContrastReport report)))))

;; =============================================================================
;; The declared rung
;; =============================================================================

(def ^:private stylesheet
  ":root {\n  --ink: #f3f5ef;\n  --ground: #0b0e0d;\n  /* a comment */\n  --line: #26302c;\n}\n
   @media (prefers-contrast: more) {\n  :root { --line: #8fa39a; }\n}\n")

(deftest tokens-are-read-out-of-the-stylesheet-that-ships
  (let [props (contrast/custom-properties stylesheet)]
    (is (= "#f3f5ef" (get props "ink")))
    (is (= "#0b0e0d" (get props "ground")))

    (testing "a conditional block does NOT overwrite the theme that ships"
      (is (= "#26302c" (get props "line"))
          "the @media value applies only when its condition holds, and this
           layer is not told which conditions hold: reporting it would audit a
           theme most readers never see"))

    (testing "a stylesheet that is not a string yields nothing, not a throw"
      (is (nil? (contrast/custom-properties nil)))
      (is (nil? (contrast/custom-properties 42))))))

(deftest the-declared-rung-measures-what-a-default-reader-actually-sees
  (testing "a dark-scheme block cannot mask the light theme's failure"
    (let [props (contrast/custom-properties
                 (str ":root { --fg: #949494; --bg: #ffffff; }\n"
                      "@media (prefers-color-scheme: dark) {\n"
                      "  :root { --fg: #eeeeee; --bg: #111111; }\n}\n"))
          report (contrast/report
                  (contrast/token-samples props [{:foreground "fg"
                                                  :background "bg"}]))]
      (is (= "#949494" (get props "fg")))
      (is (= :fail (:contrast/state report))
          "3.03:1 on screen must not be reported as the dark theme's 21:1")))

  (testing "a commented-out old value does not beat the live one"
    (let [props (contrast/custom-properties
                 ":root { --fg: #949494; }\n/* --fg: #000000; */\n")]
      (is (= "#949494" (get props "fg"))
          "commenting a token out while trying something else is the commonest
           edit there is, and the dead value has no effect on any reader")))

  (testing "a declaration with no trailing semicolon is still a declaration"
    (is (= "#ffffff" (get (contrast/custom-properties ":root { --ink: #ffffff }")
                          "ink"))))

  (testing "a value does not run past its own block"
    (is (= "#ffffff"
           (get (contrast/custom-properties
                 ":root { --ink: #ffffff }\n.x { color: red; }")
                "ink"))))

  (testing "an at-rule with no block does not swallow what follows it"
    (is (= "#ffffff"
           (get (contrast/custom-properties
                 "@import \"other.css\";\n:root { --ink: #ffffff; }")
                "ink")))))

(deftest a-pair-naming-a-token-that-vanished-fails-loudly
  (let [props   (contrast/custom-properties stylesheet)
        samples (contrast/token-samples
                 props
                 [{:foreground "ink" :background "ground"}
                  {:foreground "ink" :background "renamed-away"}])
        report  (contrast/report samples)]
    (is (= :incomplete (:contrast/state report))
        "a renamed token must not read as a silent pass")
    (is (not= :pass (:contrast/state report)))
    (is (= 1 (count (:contrast/unknown report))))
    (is (= :pass (:contrast/state (first (:contrast/findings report))))))

  (testing "each sample is named after the pair, so a failure says which"
    (let [[sample] (contrast/token-samples {"a" "#000" "b" "#fff"}
                                           [{:foreground "a" :background "b"}])]
      (is (= :a-on-b (:id sample)))
      (is (m/validate s/ContrastSample sample)))))

;; =============================================================================
;; The computed rung
;; =============================================================================

(deftest rows-a-page-reported-become-samples
  (testing "a text row carries what decides which bar it clears"
    (let [[sample] (contrast/rows->samples
                    [["body-0" "rgb(20, 20, 20)" ["rgb(255, 255, 255)"]
                      nil 24.0 true]])]
      (is (= :body-0 (:id sample)))
      (is (= "rgb(20, 20, 20)" (:foreground sample)))
      (is (= 24.0 (:font-size-px sample)))
      (is (true? (:bold? sample)))
      (is (m/validate s/ContrastSample sample))))

  (testing "a control edge is judged as non-text, at 3:1"
    (let [[sample] (contrast/rows->samples
                    [["edge-0" "rgb(90, 112, 101)" ["rgb(13, 17, 15)"]
                      "non-text" nil false]])]
      (is (= :non-text (:role sample)))
      (is (= :non-text (:contrast/role (contrast/evaluate sample))))
      (is (= 3.0 (:contrast/required (contrast/evaluate sample))))))

  (testing "a role the page invented is not honoured"
    (let [[sample] (contrast/rows->samples [["x" "#000" ["#fff"] "decorative"]])]
      (is (not (contains? sample :role))
          "an unrecognised role must fall back to being judged by size")
      (is (= :text (:contrast/role (contrast/evaluate sample))))))

  (testing "junk from the page is dropped, not promoted to a false finding"
    (is (= [] (contrast/rows->samples nil)))
    (is (= [] (contrast/rows->samples [])))
    (is (= [] (contrast/rows->samples ["not-a-row" 42 nil ["too" "short"]]))))

  (testing "a row with no readable id is dropped: a finding must be nameable"
    (is (= [] (contrast/rows->samples [[nil "#000" ["#fff"]]
                                       ["" "#000" ["#fff"]]
                                       ["   " "#000" ["#fff"]]
                                       [{:a 1} "#000" ["#fff"]]])))
    (testing "and one that is readable survives being printed and read back"
      (let [[sample] (contrast/rows->samples [["a b" "#000" ["#fff"]]])]
        (is (= :a-b (:id sample)))
        (is (= sample (read-string (pr-str sample))))))))

(deftest a-heading-is-judged-at-the-bar-its-own-size-earns
  (testing "the page does not get to raise a heading's bar by calling it text"
    (let [[sample] (contrast/rows->samples
                    [["h1-0" "rgb(120,120,120)" ["rgb(255,255,255)"]
                      nil 32.0 true]])
          finding  (contrast/evaluate sample)]
      (is (contrast/large-text? sample))
      (is (= :large-text (:contrast/role finding)))
      (is (= 3.0 (:contrast/required finding)))
      (is (= :pass (:contrast/state finding))
          "4.41:1 clears the 3:1 large-text bar it is actually held to")))

  (testing "nor lower a bar by calling small copy large"
    (let [[sample] (contrast/rows->samples
                    [["fine-0" "rgb(138,138,138)" ["rgb(255,255,255)"]
                      "large-text" 10.0 false]])
          finding  (contrast/evaluate sample)]
      (is (= :text (:contrast/role finding))
          "10px is not large text, whatever the page says")
      (is (= :fail (:contrast/state finding)))))

  (testing "only :non-text may be asserted, because size cannot imply it"
    (let [[sample] (contrast/rows->samples
                    [["edge-0" "rgb(90,112,101)" ["rgb(13,17,15)"]
                      "non-text" nil false]])]
      (is (= :non-text (:contrast/role (contrast/evaluate sample)))))))

(deftest what-the-page-painted-is-what-gets-measured
  (testing "element opacity fades the text, and a computed colour omits it"
    (let [[sample] (contrast/rows->samples
                    [["p-0" "rgb(0, 0, 0)" ["rgb(255, 255, 255)"]
                      nil 16.0 false 0.15]])
          finding  (contrast/evaluate sample)]
      (is (= :fail (:contrast/state finding))
          "15% black on white is 1.41:1, not the 21:1 an unfaded read gives")
      (is (< (:contrast/measured finding) 1.5))))

  (testing "a translucent layer is composited, not walked past"
    (let [[sample] (contrast/rows->samples
                    [["p-0" "#888888"
                      ["rgba(255, 255, 255, 0.5)" "rgb(0, 0, 0)"]
                      nil 16.0 false]])
          finding  (contrast/evaluate sample)]
      (is (= "rgb(128, 128, 128)" (:background sample))
          "50% white over black is what the reader sees")
      (is (= :fail (:contrast/state finding))
          "1.11:1 against the painted grey, not 5.92:1 against the black
           underneath it")))

  (testing "a stack with nothing opaque in it is unknown, not assumed"
    (let [[sample] (contrast/rows->samples
                    [["p-0" "#000" ["rgba(0,0,0,0)"] nil 16.0 false]])]
      (is (nil? (:background sample)))
      (is (= :unknown (:contrast/state (contrast/evaluate sample))))))

  (testing "one colour, not a stack, is still read"
    (let [[sample] (contrast/rows->samples
                    [["p-0" "#000" "rgb(255,255,255)" nil 16.0 false]])]
      (is (= "rgb(255, 255, 255)" (:background sample))))))

(deftest a-colour-out-of-range-or-not-a-number-is-not-a-measurement
  (testing "channels are clamped, so no ratio escapes 1.0 to 21.0"
    (let [c (contrast/parse-color "rgb(300, -40, 20)")]
      (is (= {:r 255 :g 0 :b 20 :a 1.0} c))
      (is (m/validate s/Rgba c))))

  (testing "a NaN alpha is UNKNOWN, never an opaque black"
    (is (nil? (contrast/parse-color "rgba(255, 255, 255, NaN)")))
    (is (nil? (contrast/contrast "rgba(255,255,255,NaN)" "#ffffff"))))

  (testing "a channel that is not a number does not parse to one"
    (doseq [bad ["rgb(1px, 2, 3)" "rgb(1.9, 2, 3)" "rgb(NaN, 0, 0)"
                 "rgb(Infinity, 0, 0)"]]
      (is (nil? (contrast/parse-color bad)) bad)))

  (testing "every parsed colour satisfies the schema it declares"
    (doseq [v ["#fff" "#ffffff" "#ffffffff" "rgb(0 0 0)" "rgb(1 2 3 / 50%)"
               "rgb(100%, 0%, 50%)"]]
      (is (m/validate s/Rgba (contrast/parse-color v)) v))))

(deftest a-level-nobody-implements-does-not-produce-a-contradictory-finding
  (let [finding (contrast/evaluate {:id :x :foreground "#000" :background "#fff"}
                                   :wcag3)]
    (is (= :aa (:contrast/level finding))
        "an unrecognised level falls back rather than escaping the enum")
    (is (= :pass (:contrast/state finding)))
    (is (m/validate s/ContrastFinding finding))
    (is (m/validate s/ContrastReport
                    (contrast/report [{:id :x :foreground "#000"
                                       :background "#fff"}]
                                     :wcag3))))

  (testing "and no finding ever reports :unknown while carrying a measurement"
    (doseq [sample [{:id :a :foreground "currentColor" :background "#fff"}
                    {:id :b :foreground "#000" :background "transparent"}]]
      (let [f (contrast/evaluate sample)]
        (is (= :unknown (:contrast/state f)))
        (is (not (contains? f :contrast/measured)))))))

(deftest a-page-sample-flows-through-to-a-report
  (let [report (contrast/report
                (contrast/rows->samples
                 [["ok-0" "rgb(0,0,0)" "rgb(255,255,255)" "text" 16.0 false]
                  ["faint-0" "rgb(238,238,238)" "rgb(255,255,255)" "text" 16.0 false]
                  ["edge-0" "rgba(0,0,0,0)" "rgb(255,255,255)" "non-text" nil false]]))]
    (is (= [:faint-0 :edge-0] (mapv :contrast/id (:contrast/failures report)))
        "an invisible border is a real finding: it composites to its backdrop")
    (is (= :fail (:contrast/state report)))
    (is (m/validate s/ContrastReport report))))
