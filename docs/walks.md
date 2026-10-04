# Generative walks

A scenario is one hand-written step vector. A **walk spec** instead declares a
step *alphabet*, and `hive-cljs.walk` lets
[test.check](https://github.com/clojure/test.check) generate step vectors from
it, run them, and — when one fails — shrink it to the shortest walk that still
fails.

## The alphabet

```clojure
(require '[clojure.test.check.generators :as gen]
         '[hive-cljs.walk :as walk])

(def cart
  {:init       {:page nil :items 0}   ; abstract model the templates reason over
   :min-length 1                      ; default 1
   :max-length 12                     ; default 20
   :alphabet
   [{:id :visit :gen (gen/elements [[:goto "/a"] [:goto "/b"]])
     :next (fn [m [_ url]] (assoc m :page url))}
    {:id :add :step [:click "#add"]
     :pre  #(= "/a" (:page %))
     :next (fn [m _] (update m :items inc))}
    {:id :del :step [:click "#del"]
     :pre  #(pos? (:items %))
     :next (fn [m _] (update m :items dec))}]})
```

Each template has:

| key | meaning |
|---|---|
| `:id` | keyword, unique in the alphabet |
| `:step` / `:gen` | exactly one: a fixed step vector, a generator of steps, or `(fn [model] generator)` |
| `:pre` | `(fn [model])` — may this template be chosen now? (default: always) |
| `:valid?` | `(fn [model step])` — is this exact step still legal? Re-checked while shrinking |
| `:next` | `(fn [model step])` — the model after the step (default: unchanged) |

Steps are ordinary [scenario steps](steps.md); the model is yours and never
reaches the browser. `walk/validate-spec` refuses a malformed spec with
`:walk/malformed`.

## Generating

```clojure
(walk/walk-gen cart)          ; test.check generator of step vectors
(walk/sample-walk cart 20 42) ; one walk, size 20, seed 42 — reproducible
```

Every generated walk — and every walk it shrinks to — replays legally from
`:init`. A shrunk repro is never a sequence the app could not have been put
through. Generation stops early when no template is enabled.

## Checking

`walk/check` takes a **runner**: any fn of a step vector to a RunReport, a
Result of one, or a boolean.

```clojure
(def res (walk/check cart run-fn {:num-tests 100 :seed 7}))
(walk/explain res)
;; => "walk: failed (seed 7) — shrunk 9 steps to 4: [[:goto \"/a\"] [:click \"#add\"] ...] — ..."
```

The result carries `:walk/pass?`, `:walk/seed`, `:walk/num-tests`, and on
failure `:walk/failing` (as first found), `:walk/smallest` (the minimal
repro), `:walk/shrinks` and the smallest walk's `:walk/report` or
`:walk/error`. Pass the same `:seed` to replay a failure exactly.

Shrinking removes contiguous chunks (halves first, down to single steps — so a
dependent pair like add-then-delete disappears together) and shrinks each
step's own generator. A final deterministic pass (`:minimize? true`, default)
makes the repro 1-minimal; turn it off when each run is an expensive browser
session.

### Through the real scenario boundary

`walk/plan-runner` plans each walk as a scenario against a manifest and runs it
through the injected ports:

```clojure
(walk/check cart
            (fn [steps]
              ;; fresh ports per walk = a fresh session per walk
              ((walk/plan-runner deps manifest {:id :walk :build :app}) steps))
            {:num-tests 50})
```

Any other fn of steps → RunReport (e.g. a headless reduce over event
handlers) is an equally valid runner — the tests in
`test/hive_cljs/walk_test.clj` use stub runners and stub ports, no browser.

In a suite, `walk/property` gives the raw test.check property for `defspec`.
