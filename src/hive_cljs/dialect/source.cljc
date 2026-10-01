(ns hive-cljs.dialect.source
  "Printing an authored form as the source text a runtime will evaluate.

   A manifest is EDN, so a step argument may be a FORM rather than a string of
   source. Turning that form back into text is `pr-str` — but `pr-str` obeys
   the CALLER's printer vars. A REPL with `*print-length*` set truncates a long
   vector into `(1 2 3 ...)`, `*print-namespace-maps*` rewrites `{:a/x 1}` as
   `#:a{:x 1}`, `*print-meta*` prepends `^{...}`, and `*print-readably*` false
   drops the quotes off every string. Each of those changes what the app is
   asked, and none of them is visible in the manifest.

   So every form crosses into source under PINNED printer bindings: the same
   form yields the same source on every thread, whatever its bindings.")

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn pr-source
  "`pr-str` under pinned printer vars — thread-independent source text."
  [x]
  (binding [*print-namespace-maps* false
            *print-length*         nil
            *print-level*          nil
            *print-meta*           false
            *print-readably*       true]
    (pr-str x)))

(defn form->string
  "Source text for an authored argument: a string passes through verbatim (the
   escape hatch for reader macros EDN cannot carry, `#(…)` and `#\"…\"`), any
   other value is printed as a form under pinned printer vars."
  [x]
  (if (string? x) x (pr-source x)))

;; =============================================================================
;; Splicing authored source text into a built form
;; =============================================================================

;; A builder composes FORMS, but an authored argument may be a string — the
;; escape hatch for `#(…)` and `#"…"`, which have no EDN form. Printing that
;; string as a form would quote it, so it rides inside the form as a Verbatim
;; leaf the pinned printer writes as the text itself. The form is still printed
;; ONCE, at the eval edge; nothing is concatenated.

(deftype Verbatim [text]
  #?@(:clj  [Object
             (equals [_ o] (and (instance? Verbatim o) (= text (.-text ^Verbatim o))))
             (hashCode [_] (hash text))
             (toString [_] text)]
      :cljs [IEquiv
             (-equiv [_ o] (and (instance? Verbatim o) (= text (.-text o))))
             IHash
             (-hash [_] (hash text))
             IPrintWithWriter
             (-pr-writer [_ w _] (-write w text))]))

#?(:clj
   (defmethod print-method Verbatim [^Verbatim v ^java.io.Writer w]
     (.write w ^String (.-text v))))

(defn verbatim
  "A form leaf printing as `text` exactly — authored source spliced unquoted."
  [text]
  (->Verbatim text))

(defn verbatim?
  [x]
  (instance? Verbatim x))

(defn arg
  "An authored argument as a form leaf: a string becomes a Verbatim (its source
   is spliced as written), any other value is already a form."
  [x]
  (if (string? x) (verbatim x) x))
