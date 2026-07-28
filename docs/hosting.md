# Mounting hive-cljs in a host

hive-cljs is an IAddon. A host that mounts it gains the `cljs` subdomain on its
consolidated `code` tool — `code cljs status`, `code cljs e2e run`, and so on.

## Wiring it into hive-mcp

One line in the coordinator's untracked `local.deps.edn`:

```clojure
io.github.hive-agi/hive-cljs #:local{:root "../hive-cljs"}
```

Restart. That is the whole host-side change.

**Batteries included.** Every vendor hive-cljs needs — shadow relay transport,
nREPL client, and the Playwright browser driver — rides in on its own `:deps`.
The host never declares an addon's vendors.

This is why the browser driver is a base dep rather than an `:browser` alias:
**tools.deps aliases do not propagate to a consumer.** A host that mounts the
library would never see an aliased dep, so the browser channel could only work if
the host itself declared Playwright — exactly the coupling an addon exists to
avoid. Cost of the choice: ~195M in `~/.m2` (`driver-bundle` ships node plus the
driver). Nothing is extracted or launched until a scenario opens a session.

## What it registers

At `initialize!` the addon calls the host's command-contribution seam:

```clojure
(contribute-commands! "code" :hive-cljs
  {"cljs" {:handler … :description …}})
```

and contributes its parameter schema via `(schema-extensions [_] {"code" …})`,
minus the params the code tool already owns. `shutdown!` retracts by the
`:hive-cljs` key, so it removes only its own contributions.

The host is resolved **softly** — `requiring-resolve` behind a `try`, never a
`:require`. There is no compile-time coupling to hive-mcp, and the library loads,
tests and reports health with no host present at all.

## Verifying the mount

```clojure
code {command: "help"}          ; `cljs` should appear alongside `carto`
code {command: "cljs help"}     ; 10 subcommands
code {command: "cljs doctor", directory: "/path/to/a/cljs/project"}
```

If `cljs` appears in `code help` but calls render **empty**, the handler is
returning the wrong shape. A contributed handler must return the host content
map:

```clojure
{:type "text" :text "…"}        ; or {:type "text" :text "…" :isError true}
```

Diagnose by comparing against a known-good contributor:

```clojure
(let [c (get @@(requiring-resolve 'hive-mcp.extensions.registry/command-contributions) "code")]
  {:mine  (keys ((get-in c ["cljs" :handler])  {:command "cljs help"}))
   :carto (keys ((get-in c ["carto" :handler]) {:command "carto help"}))})
```

`(:ok)` on the left and `(:type :text)` on the right is the whole diagnosis.

## Why a subdomain and not its own tool

A novel addon tool name becomes its own root — but `hive-mcp.tools.registry`
then applies a visibility gate: any tool absent from
`config.edn [:tool-roots :visible]` is marked `:deprecated`, which keeps it
callable while **hiding it from `tools/list`**. A standalone `cljs` root would
have mounted, reported healthy, and been invisible until someone edited that
allowlist.

Contributing to `code` inherits `code`'s visibility, so no host config change is
needed. The trade is namespacing: every command is prefixed, and the handler
strips its own `cljs ` prefix before dispatching.

## Using it without a host

Nothing about the library requires a host. From a REPL or a test suite:

```clojure
(require '[hive-cljs.test-api :as cljs-e2e])
(cljs-e2e/run-scenario! "/path/to/project" :login)
```

See [setup.md](setup.md#7-in-a-test-suite).
