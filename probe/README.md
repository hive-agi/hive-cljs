# @hive-agi/probe

One line in your app, and a [hive-cljs](https://github.com/hive-agi/hive-cljs)
scenario can assert on what your application *believes* — not just on what it
rendered.

Works with anything that runs in a browser: Elm, React, Svelte, Vue, Solid,
vanilla JS. No framework integration, no devtools hook, no build plugin.

```bash
npm i -D @hive-agi/probe
```

## Wire it

```js
import { expose } from '@hive-agi/probe'

expose('model', () => store.getState())
```

Then, in a scenario:

```clojure
[[:goto "/inbox"]
 [:click "#refresh"]
 [:wait-for-state ["model" "loading"] "v === false"]
 [:expect-state  ["model" "items" "length"] "v === 3"]
 [:expect-text   "#count" "3 messages"]]
```

The first segment of the path names the source; the rest indexes into it. `v` in
the predicate is the value that was read.

`:expect-text` red while `:expect-state` green localises a bug to rendering
rather than to state — which is the whole reason to expose state at all.

## Per stack

**Redux / Zustand / any store with a getter**

```js
expose('model', () => store.getState())
```

**Svelte**

```js
import { get } from 'svelte/store'
expose('model', () => ({ user: get(user), items: get(items) }))
```

**React** — expose the value, not the hook:

```js
function App() {
  const state = useAppState()
  useEffect(() => { expose('model', () => state) })
  return <Main />
}
```

**Elm** — Elm keeps no state in JS, so push it out through a port:

```elm
port hiveState : Encode.Value -> Cmd msg

update msg model =
    ( newModel, hiveState (encodeModel newModel) )
```

```js
import { pushed } from '@hive-agi/probe'
const app = Elm.Main.init({ node: document.getElementById('root') })
app.ports.hiveState.subscribe(pushed('model'))
```

`pushed(name)` returns a subscriber and exposes the latest value it received —
the same shape for a websocket or any other push source.

## Ship it, or don't

The probe is a few hundred bytes and reads only what you hand it, so leaving it
in a production bundle is defensible. To keep it out, guard the wiring:

```js
if (import.meta.env.DEV) expose('model', () => store.getState())
```

A scenario against a build with no probe fails with a message naming the fix,
rather than silently reading `undefined`:

```
hive probe not installed on this page — `npm i -D @hive-agi/probe`, then
`expose("model", () => yourState)` where the app starts up
```

## What comes back

Everything crosses a structured-clone boundary, so values are projected to
JSON shapes first. Functions become `"#function"`, DOM nodes `"#node:div"`,
`Date`s ISO strings, `Map`s objects, `Set`s arrays. Cycles become `"#cycle"` and
nesting stops at depth 12 — a store graph much larger than the value under test
would otherwise hang the assertion rather than fail it.

A path that runs off the end of the data reads `null`: that is an ordinary
assertion failure and the scenario reports it as one. A path naming a source
nobody exposed throws, listing what *is* exposed — that is a wiring mistake, and
no assertion about it would mean anything.

Getters run at read time, once per assertion, so they always report what the app
currently holds rather than what it held at wiring time.

## API

| | |
|---|---|
| `expose(name, getter)` | expose a named slice of state |
| `exposeAll({name: getter, …})` | several at once |
| `pushed(name)` | for values that arrive by push; returns the subscriber |
| `clear(name)` | stop exposing one |
| `exposed()` | names currently exposed, sorted |

Everything lands on `window.__hive__`, which is what hive-cljs reads.

## License

MIT © Pedro Gomes Branquinho (BuddhiLW)
