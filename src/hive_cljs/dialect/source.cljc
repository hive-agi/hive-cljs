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
