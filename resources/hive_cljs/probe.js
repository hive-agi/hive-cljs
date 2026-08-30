// hive-cljs probe — injected into every document the driven session loads,
// before any page script, so an application's own startup can call expose().
//
// The application authors ONE optional-chained line and depends on nothing:
//
//   window.__hive__?.expose('model', () => store.getState())
//   app.ports.hiveState.subscribe(window.__hive__?.pushed('model'))   // Elm
//
// In production nothing injects this, `?.` short-circuits, and the line is a
// no-op — which is why there is no build flag to guard.
//
// SPDX-License-Identifier: MIT
(() => {
  if (window.__hive__) return;

  const MAX_DEPTH = 12;

  // Everything read crosses a structured-clone boundary. A value that cannot
  // cross it would fail with an error about serialization rather than about the
  // assertion, so unclonable things are DESCRIBED instead of thrown or dropped:
  // a silent {} would read as "the state is empty", which is a lie.
  const clean = (v, d, seen) => {
    if (v === null || v === undefined) return null;

    const t = typeof v;
    if (t === 'function' || t === 'symbol') return '#' + t;
    if (t === 'bigint') return String(v);
    if (t === 'number') return Number.isFinite(v) ? v : String(v);
    if (t !== 'object') return v;

    if (v instanceof Date) return v.toISOString();
    if (v instanceof Error) return { name: v.name, message: v.message };
    if (typeof Node !== 'undefined' && v instanceof Node) {
      return '#node:' + v.nodeName.toLowerCase();
    }

    // Bounded rather than trusted: a getter may hand back a store graph far
    // larger than the value under test, and an unbounded walk is a hang, not a
    // failure.
    if (d >= MAX_DEPTH) return '#depth-limit';
    if (seen.has(v)) return '#cycle';

    seen.add(v);
    try {
      if (Array.isArray(v)) return v.map((x) => clean(x, d + 1, seen));
      if (v instanceof Set) return [...v].map((x) => clean(x, d + 1, seen));
      const entries = v instanceof Map ? [...v] : Object.entries(v);
      return Object.fromEntries(
        entries.map(([k, x]) => [String(k), clean(x, d + 1, seen)])
      );
    } finally {
      seen.delete(v);
    }
  };

  const sources = Object.create(null);
  const names = () => Object.keys(sources).sort();

  window.__hive__ = {
    version: 1,
    names,

    // Getters run at READ time, once per assertion, so they report what the
    // application holds now rather than what it held at wiring time.
    expose: (name, getter) => { sources[String(name)] = getter; },

    // For state that arrives by PUSH — an Elm port, a websocket. Returns the
    // subscriber to hand to the producer.
    pushed: (name) => {
      let latest = null;
      sources[String(name)] = () => latest;
      return (value) => { latest = value; };
    },

    // A path running off the end of the data reads null: that is an ordinary
    // assertion failure and the scenario reports it as one. A path naming a
    // source nobody exposed THROWS — that is a wiring mistake, and no assertion
    // about it would mean anything.
    read: (path) => {
      const segs = Array.isArray(path) ? path : [path];
      const name = String(segs[0]);
      const getter = sources[name];
      if (!getter) {
        throw new Error(
          'hive probe: nothing exposed under "' + name + '" — exposed: [' +
            names().join(', ') + ']. Did the app call ' +
            'window.__hive__?.expose("' + name + '", () => …) at startup?'
        );
      }

      let v;
      try {
        v = getter();
      } catch (e) {
        throw new Error(
          'hive probe: the getter for "' + name + '" threw: ' + e.message
        );
      }

      for (let i = 1; i < segs.length; i++) {
        if (v === null || v === undefined) return null;
        v = v[segs[i]];
      }
      return clean(v, 0, new Set());
    },
  };
})();
