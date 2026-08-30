// @hive-agi/probe — expose your application's state to a hive-cljs scenario.
//
// Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
// SPDX-License-Identifier: MIT

const KEY = '__hive__';
const VERSION = 1;

// Depth is bounded rather than trusted. A getter may hand back a store whose
// graph is far larger than the value under test, and everything returned has to
// cross a structured-clone boundary — an unbounded walk there is a hang, not an
// error.
const MAX_DEPTH = 12;

function root() {
  if (typeof window === 'undefined') return null;
  if (!window[KEY]) {
    window[KEY] = {
      version: VERSION,
      sources: Object.create(null),
      names: function () { return Object.keys(this.sources).sort(); },
      read: read,
    };
  }
  return window[KEY];
}

// Only what survives a structured clone may be returned: a function, a DOM node
// or a cycle would fail at the boundary with an error about serialization
// rather than about the assertion, so they are replaced by a description here.
function sanitize(value, depth, seen) {
  if (value === null || value === undefined) return null;

  const t = typeof value;
  if (t === 'string' || t === 'boolean') return value;
  if (t === 'number') return Number.isFinite(value) ? value : String(value);
  if (t === 'bigint') return String(value);
  if (t === 'function') return '#function';
  if (t === 'symbol') return String(value);

  if (value instanceof Date) return value.toISOString();
  if (value instanceof Error) return { name: value.name, message: value.message };
  if (typeof Node !== 'undefined' && value instanceof Node) {
    return '#node:' + (value.nodeName || '').toLowerCase();
  }

  if (depth >= MAX_DEPTH) return '#depth-limit';
  if (seen.has(value)) return '#cycle';
  seen.add(value);

  try {
    if (Array.isArray(value)) {
      return value.map(function (v) { return sanitize(v, depth + 1, seen); });
    }
    if (value instanceof Map) {
      const out = {};
      value.forEach(function (v, k) { out[String(k)] = sanitize(v, depth + 1, seen); });
      return out;
    }
    if (value instanceof Set) {
      return Array.from(value).map(function (v) { return sanitize(v, depth + 1, seen); });
    }
    const out = {};
    for (const k of Object.keys(value)) {
      out[k] = sanitize(value[k], depth + 1, seen);
    }
    return out;
  } finally {
    seen.delete(value);
  }
}

// A path that runs off the end of the data is null, not a throw: "the value is
// not there" is an ordinary assertion failure and the scenario should report it
// as one. A path naming a source nobody exposed IS a throw, because that is a
// wiring mistake and no assertion about it means anything.
function read(path) {
  const segments = Array.isArray(path) ? path : [path];
  if (segments.length === 0) {
    throw new Error('hive probe: read() needs a path whose first segment names a source');
  }

  const name = String(segments[0]);
  const getter = root().sources[name];
  if (!getter) {
    throw new Error(
      'hive probe: nothing is exposed under "' + name + '" — exposed: [' +
        root().names().join(', ') + ']'
    );
  }

  let value;
  try {
    value = getter();
  } catch (e) {
    throw new Error('hive probe: the getter for "' + name + '" threw: ' + e.message);
  }

  for (let i = 1; i < segments.length; i++) {
    if (value === null || value === undefined) return null;
    value = value[segments[i]];
  }
  return sanitize(value, 0, new Set());
}

/**
 * Expose a named slice of application state.
 *
 * The getter is called at READ time, once per assertion, so it always reports
 * what the application currently holds rather than what it held at wiring time.
 */
export function expose(name, getter) {
  if (typeof getter !== 'function') {
    throw new TypeError('hive probe: expose(name, getter) needs a function');
  }
  const r = root();
  if (r) r.sources[String(name)] = getter;
  return getter;
}

/** Expose several sources at once: exposeAll({ model: () => …, route: () => … }). */
export function exposeAll(map) {
  for (const name of Object.keys(map)) expose(name, map[name]);
  return map;
}

/**
 * Expose a value that arrives by PUSH rather than by pull — an Elm port, a
 * websocket, an event stream. Returns the subscriber to hand to the producer.
 *
 *   app.ports.hiveState.subscribe(pushed('model'))
 */
export function pushed(name) {
  let latest = null;
  expose(name, function () { return latest; });
  return function (value) { latest = value; };
}

/** Stop exposing a source. */
export function clear(name) {
  const r = root();
  if (r) delete r.sources[String(name)];
}

/** Names currently exposed, sorted. */
export function exposed() {
  const r = root();
  return r ? r.names() : [];
}

export default { expose, exposeAll, pushed, clear, exposed };
