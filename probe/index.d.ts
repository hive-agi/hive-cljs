// Type declarations for @hive-agi/probe.
// SPDX-License-Identifier: MIT

/** JSON-shaped value, which is all that survives the browser boundary. */
export type Exposed =
  | null
  | boolean
  | number
  | string
  | Exposed[]
  | { [key: string]: Exposed };

/**
 * Expose a named slice of application state.
 *
 * The getter runs at read time, once per assertion, so it reports what the
 * application currently holds rather than what it held at wiring time.
 */
export function expose<T>(name: string, getter: () => T): () => T;

/** Expose several sources at once. */
export function exposeAll(map: Record<string, () => unknown>): Record<string, () => unknown>;

/**
 * Expose a value that arrives by push rather than by pull — an Elm port, a
 * websocket, an event stream. Returns the subscriber to hand to the producer.
 */
export function pushed<T>(name: string): (value: T) => void;

/** Stop exposing a source. */
export function clear(name: string): void;

/** Names currently exposed, sorted. */
export function exposed(): string[];

declare global {
  interface Window {
    __hive__?: {
      version: number;
      names(): string[];
      read(path: (string | number)[] | string): Exposed;
    };
  }
}

declare const _default: {
  expose: typeof expose;
  exposeAll: typeof exposeAll;
  pushed: typeof pushed;
  clear: typeof clear;
  exposed: typeof exposed;
};
export default _default;
