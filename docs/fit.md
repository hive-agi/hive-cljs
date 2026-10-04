# Fit and derive

Two pure libraries that ship alongside the addon. Neither needs a running build,
a browser or a REPL, and both load on every host a `.cljc` file loads on.

| Namespace | What it is |
|---|---|
| `hive-cljs.fit` | the overflow judge: one judge, many measurers, two evidence rungs |
| `hive-cljs.fit.test` | `deffit`, which generates the `clojure.test` vars that gate one document's fit |
| `hive-cljs.ports` | `IFitSource` and `IFitUniverse`, the two things a consumer implements |
| `hive-cljs.derive` | forward chaining over tuple facts, with provenance and stratified aggregation |

The worked example of both fit protocols and `deffit` is plato's gate:
`gate/plato/fit_gate_test.clj` in the plato repository.

---

## Fit

Something laid out against a fixed box (a slide, a card, a print page, a fixed
viewport) loses whatever overflows that box. Nobody scrolls to it. `hive-cljs.fit`
reads measurements of such subjects and decides whether they fit. It does not
care how they were measured.

### The two rules that are easy to get wrong

If a consumer misses either of these, it gets a gate that passes for the wrong
reason.

**1. A source declares its own rung, and an estimated finding inside the
source's stated margin never fails a build.**

There are two rungs (`schema/FitRung`), and they do not carry the same weight:

| Rung | Produced by | May |
|---|---|---|
| `:measured` | reading a laid-out page through a layout engine | fail a build on any finding past tolerance |
| `:estimated` | running a box model over the document value | warn; fail only when a finding is larger than its own `:fit/margin` |

The source picks the rung from what it actually did (`ports/fit-rung`), not from
how confident it is. A box model reports `:estimated` even when its numbers look
right. Every estimated measurement states `:fit/margin`, the number of pixels its
model could be wrong by. Overflow inside that band is not evidence, so it warns
and does not fail. The same 60px of overflow is a failure when `:measured`
reports it and a warning when an estimator with a 100px margin reports it.
`:fit/strict? true` turns every warning into a failure. Set it once the estimator
has been calibrated against a measured rung.

**2. A coverage universe must come from the document model, not from the source
being checked.**

Coverage asks whether every kind of content the document model admits was
actually measured by some subject. If the universe is derived from the source
(the estimator's own table of arms, say), the assertion becomes `X ⊆ X`. That is
true for every X, including the X that lacks the arm you built the gate for. The
universe has to come from somewhere independent: a content registry, a
multimethod's dispatch table, a schema. That is why `IFitUniverse` is a separate
protocol from `IFitSource`. A consumer that cannot answer it independently
should not claim coverage, and should leave `:universe` out.

### A measurement

`schema/FitMeasurement` is a closed map:

```clojure
#:fit{:id       "agenda"                    ; required, non-blank
      :rung     :estimated                  ; required, :measured | :estimated
      :box      {:w 960.0 :h 700.0}         ; required, the area it was given
      :extent   {:w 960.0 :h 760.0}         ; the area it actually takes
      :margin   100.0                       ; the source's own uncertainty, px
      :policy   :shrink                     ; optional author waiver, :allow | :shrink
      :clipped  [#:fit{:tag "pre" :class "code" :over-w 0.0 :over-h 90.0}]
      :kinds    #{:text :code}              ; what it drew on from the document model
      :modelled? true}                      ; false = the source could not model it
```

Sizes are doubles in CSS pixels. If a source cannot model a subject, it must
still report it with `:fit/modelled? false`. A subject that is left out looks
exactly like one that fits.

### Judging

```clojure
(require '[hive-cljs.fit :as fit])

(def box {:w 960.0 :h 700.0})

(fit/evaluate #:fit{:id "welcome" :rung :measured :box box :extent {:w 960.0 :h 820.0}})
;; => #:fit{:id "welcome" :rung :measured :state :overflows :severity :fail
;;          :findings [#:fit{:kind :taller-than-box :by 120.0}] :margin 0.0}

(def agenda #:fit{:id "agenda" :rung :estimated :box box
                  :extent {:w 960.0 :h 760.0} :margin 100.0})

(fit/evaluate agenda)                         ; 60px over, inside a 100px margin
;; => #:fit{... :state :overflows :severity :warn ...}

(fit/evaluate (assoc agenda :fit/extent {:w 960.0 :h 900.0}))   ; 200px over
;; => #:fit{... :state :overflows :severity :fail ...}

(fit/evaluate agenda #:fit{:strict? true})
;; => #:fit{... :severity :fail ...}

(fit/evaluate #:fit{:id "chart" :rung :measured :box box
                    :extent {:w 960.0 :h 875.0} :policy :shrink})
;; => #:fit{... :state :shrunk :severity :pass :scale 0.8 ...}
```

`fit/report` judges a sequence of measurements, and `fit/explain` turns a report
into prose (it returns nil when everything fits):

```clojure
(println (fit/explain (fit/report [agenda])))
;; 1 of 1 subjects have something to answer for, at the estimated rung:
;;   warn  agenda -- is 60px taller than its box (estimated, within the source's own 100px margin)

(fit/report [])
;; => #:fit{:rung :estimated :verdicts [] :failures [] :warnings [] :tally {}
;;          :state :unavailable}
```

A report over no measurements is `:unavailable`, never `:pass`. A gate that
judged nothing has proved nothing.

**States** (`schema/FitState`) and what each one is worth:

| State | Means | Severity |
|---|---|---|
| `:ok` | fits, declares no policy | pass |
| `:waived` | overflows, and declared `:allow` | pass |
| `:shrunk` | overflows, declared `:shrink`, fits at `:fit/scale` ≥ `:fit/min-scale` | pass |
| `:overflows` | overflows with no policy, or has a clipped descendant (shrinking scales the clip too) | fail at `:measured`; at `:estimated`, fail only when a finding is larger than `:fit/margin` |
| `:too-small` | declared `:shrink`, but fitting would go below `:fit/min-scale` | same as `:overflows` |
| `:stale-waiver` | declares a policy but now fits, so drop the policy | warn |
| `:unknown` | `:fit/modelled? false` | warn |

**Policy** (`fit/default-policy`, merged with what you pass):

| Key | Default | |
|---|---|---|
| `:fit/tolerance` | `2.0` | px of overflow to ignore, because scroll sizes are rounded up from fractional layout |
| `:fit/min-scale` | `0.6` | below this, shrinking is no longer a remedy |
| `:fit/strict?` | `false` | turn every warning into a failure |
| `:fit/exempt` | — | `{kind "reason"}`, read by `deffit`'s coverage check |

### Coverage

```clojure
(fit/coverage #{:image :code :table}            ; universe, from the document model
              (fit/reached measurements)         ; union of every :fit/kinds
              {:table "no deck ships one yet"})  ; exemptions, each with its reason
;; with reached = #{:image}:
;; => #:fit{:universe #{:image :code :table} :reached #{:image} :missing [:code]
;;          :exempt {:table "no deck ships one yet"} :stale-exemptions []
;;          :state :fail}
```

- An empty universe is `:unavailable`, not a pass.
- An exemption must carry a reason string. Without one it is just a skip list,
  and nobody would know when it stopped being justified.
- An exemption for a kind that *is* reached now fails in its own right
  (`:stale-exemptions`), the same way a stale waiver does.

`fit/explain-coverage` gives the prose, or nil when coverage passes.

### The ports

```clojure
(defprotocol IFitSource
  (fit-rung [this])           ; the rung this source can honestly claim
  (fit-measurements [this]))  ; [schema/FitMeasurement ...], unmodelled subjects included

(defprotocol IFitUniverse
  (fit-universe [this]))      ; #{kind ...} the document model admits
```

A consumer usually ships two sources: one that reads a laid-out page through a
browser (`:measured`) and one that estimates from the document value
(`:estimated`). Both answer the same protocol and are judged by the same code.
`fit/source` wraps measurements you already have:
`(fit/source :estimated measurements)`.

### `deffit`: the gate as tests

```clojure
(ns my.deck.fit-gate-test
  (:require [hive-cljs.fit :as fit]
            [hive-cljs.fit.test :refer [deffit]]
            [hive-cljs.ports :as ports]))

(def content-model
  ;; from the document model's own registry, NOT from the estimator
  (reify ports/IFitUniverse
    (fit-universe [_] #{:text :image :code})))

(deffit deck
  {:source   (fit/source :estimated
                         [#:fit{:id "intro" :rung :estimated
                                :box {:w 960.0 :h 700.0} :extent {:w 900.0 :h 760.0}
                                :margin 100.0 :kinds #{:text :image}}])
   :universe content-model
   :policy   {:fit/exempt {:code "no deck ships a code slide yet"}}
   :doc      "Every slide, judged without a browser."})
```

Each option is a form, evaluated once when the tests first run. `:source` is
required. `:universe`, `:policy` and `:doc` are optional, and any other key is a
macroexpansion error. The generated vars share a single run of the source:

| Var | Asserts |
|---|---|
| `deck-was-measured` | the source produced at least one measurement; an empty source fails instead of passing |
| `deck-rung-holds` | every measurement uses the same rung, and that rung is the one the source claims; a source may not report above its rung |
| `deck-fits` | the report is not `:fail`; warnings are printed, not asserted |
| `deck-covers-model` | coverage passes (only emitted when `:universe` is given) |

The example above passes all four tests and prints:

```
fit warnings (estimated rung, not gating):
  warn  intro -- is 60px taller than its box (estimated, within the source's own 100px margin)
```

Without `:universe`, no coverage is claimed. That is different from claiming
coverage over nothing, which would be `:unavailable` and fail.
`hive-cljs.fit.test/run` is the function the vars call. Use it directly when you
want the report itself, for example to print a tally, as plato's `^:report` test
does.

---

## Derive

`hive-cljs.derive` is a small forward-chaining engine. Facts are tuples. A rule is
a conjunction of tuple patterns, plus a function from the bindings those patterns
matched to the facts that follow. `run` applies the rules until nothing new
appears (a fixpoint). Every derived fact records the rule that produced it and
the premises it used, so you can ask why a value came out the way it did.

It is meant for relational questions about layout. For example, "this heading is
illegible because the render scale is 0.37, its declared size is 40px, and the
floor is 16px." The output is a build artifact (generated CSS, a token file), so
the engine is plain `.cljc` with no dependencies. It covers conjunctive matching
over ground tuples, with no negation. Anything larger belongs in a real reasoner.

### Patterns

- `?x` is a logic variable. A variable already bound must match the same value
  again, which is what makes a conjunction a join.
- `_` matches anything and binds nothing.
- Anything else must be equal. Arity must agree.

### Rules and `run`

```clojure
(require '[hive-cljs.derive :as d])

(def family #{[:parent :ana :bo] [:parent :bo :cy]})

;; (d/rule id doc when-patterns then & [guard])
(def base (d/rule :ancestor/base "A parent is an ancestor."
                  '[[:parent ?a ?b]]
                  (fn [{:syms [?a ?b]}] [:ancestor ?a ?b])))

(def step (d/rule :ancestor/step "Ancestry is transitive."
                  '[[:ancestor ?a ?b] [:ancestor ?b ?c]]
                  (fn [{:syms [?a ?c]}] [:ancestor ?a ?c])))

(def fx (d/run [base step] family))
;; => {:facts #{...asserted and derived...}
;;     :derived #{[:ancestor :ana :bo] [:ancestor :bo :cy] [:ancestor :ana :cy]}
;;     :why {fact {:rule/id … :premises […] :bindings {…}}}
;;     :iterations 3}
```

- `:then` returns one fact, a vector of facts, or nil (derive nothing).
- `:then` must depend only on its bindings. The fixpoint assumes the rules are
  monotone and stops at the first round that adds no fact.
- The optional guard `(fn [bindings] boolean)` covers arithmetic that a pattern
  cannot express:

```clojure
(d/run [(d/rule :illegible "Rendered below the 16px floor."
                '[[:size ?el ?px] [:scale :slide ?s]]
                (fn [{:syms [?el]}] [:illegible ?el])
                (fn [{:syms [?px ?s]}] (< (* ?px ?s) 16)))]
       #{[:size :h1 40] [:size :body 18] [:scale :slide 0.5]})
;; => {... :derived #{[:illegible :body]} ...}
```

- Patterns are matched left to right and the engine does not reorder them, so
  put the most selective pattern first.
- A rule set that keeps inventing values throws `:derive/no-fixpoint` after
  `d/max-iterations` (64) rounds instead of looping forever. Pass
  `{:max-rounds n}` as the third argument to `run` to change the limit.

### Reading the answer

```clojure
(d/query fx '[[:ancestor :ana ?x]])          ; => [{?x :cy} {?x :bo}]
(d/facts-matching fx '[:ancestor :ana _])    ; => #{[:ancestor :ana :bo] [:ancestor :ana :cy]}

(d/why fx [:ancestor :ana :cy])
;; => {:rule/id :ancestor/step
;;     :premises [[:ancestor :ana :bo] [:ancestor :bo :cy]]
;;     :bindings {?a :ana ?b :bo ?c :cy}}

(d/why fx [:parent :ana :bo])                ; => nil: asserted, not derived

(d/explain fx [:ancestor :ana :cy])
;; => "[:ancestor :ana :cy] by :ancestor/step from [:ancestor :ana :bo], [:ancestor :bo :cy]"

(d/trace fx [:ancestor :ana :cy])            ; every derivation step back to asserted facts
```

`why` returns nil for an asserted fact. That is the correct answer, because an
asserted fact has no derivation. It does not mean the lookup failed. The first
derivation of a fact keeps its provenance, and later derivations do not
overwrite it.

### Stratified aggregation

"The largest scale any token demands" is a statement about a set of facts. A
monotone rule cannot express it, because it would need "and no larger one
exists", which is negation. `run-strata` handles this by running rule sets in
order. Between them, a stratum's `:assert` function receives the finished
fixpoint and can compute an aggregate from it:

```clojure
(def fx3
  (d/run-strata
   [{:rules  [base step]
     :assert (fn [fx]
               (let [as (d/facts-matching fx '[:ancestor _ _])]
                 {[:ancestor-count (count as)] (vec as)}))}]   ; fact -> premises
   family))

(d/why fx3 [:ancestor-count 3])
;; => {:rule/id :stratum/aggregate :premises [...the three :ancestor facts...]}
```

`:assert` can return either a plain collection of facts or a map of
fact → premises. Prefer the map. With a plain collection, the provenance chain
stops at the aggregate. With the map, `trace` follows the aggregate back to the
asserted facts it was computed from.
