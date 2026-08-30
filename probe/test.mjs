globalThis.window = globalThis;
const { expose, exposeAll, pushed, clear, exposed } = await import('./index.js');

let ok = 0, bad = 0;
const t = (label, actual, expected) => {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a === e) { ok++; } else { bad++; console.log(`FAIL ${label}\n  got      ${a}\n  expected ${e}`); }
};
const throws = (label, f, needle) => {
  try { f(); bad++; console.log(`FAIL ${label}: did not throw`); }
  catch (e) { if (e.message.includes(needle)) ok++;
              else { bad++; console.log(`FAIL ${label}: ${e.message}`); } }
};

let state = { user: { name: 'pedro' }, items: [1,2,3], ready: true, count: 0 };
expose('model', () => state);

t('whole source',   window.__hive__.read(['model','user','name']), 'pedro');
t('array index',    window.__hive__.read(['model','items',1]), 2);
t('array length',   window.__hive__.read(['model','items','length']), 3);
t('boolean',        window.__hive__.read(['model','ready']), true);
t('falsy zero',     window.__hive__.read(['model','count']), 0);
t('missing path',   window.__hive__.read(['model','nope','deeper']), null);
t('getter is live', (state = {ready:false}, window.__hive__.read(['model','ready'])), false);

throws('unknown source', () => window.__hive__.read(['ghost']), 'nothing is exposed under "ghost"');
throws('empty path',     () => window.__hive__.read([]), 'needs a path');
throws('getter throws',  () => { expose('boom', () => { throw new Error('nope'); });
                                 window.__hive__.read(['boom']); }, 'threw: nope');

// non-clonable values are described, not fatal
expose('weird', () => ({ fn: () => 1, when: new Date('2026-08-29T00:00:00Z'), node: undefined }));
t('function',  window.__hive__.read(['weird','fn']), '#function');
t('date',      window.__hive__.read(['weird','when']), '2026-08-29T00:00:00.000Z');

const cyc = {}; cyc.self = cyc; cyc.n = 1;
expose('cyc', () => cyc);
t('cycle survives', window.__hive__.read(['cyc']), { self: '#cycle', n: 1 });

// deep nesting is bounded rather than hanging
let deep = {}; let cur = deep;
for (let i = 0; i < 40; i++) { cur.next = {}; cur = cur.next; }
expose('deep', () => deep);
const d = window.__hive__.read(['deep']);
t('depth bounded', JSON.stringify(d).includes('#depth-limit'), true);

// Map / Set
expose('coll', () => ({ m: new Map([['a',1]]), s: new Set([1,2]) }));
t('map', window.__hive__.read(['coll','m']), { a: 1 });
t('set', window.__hive__.read(['coll','s']), [1,2]);

// push-style (Elm ports)
const send = pushed('elm');
t('pushed empty', window.__hive__.read(['elm']), null);
send({ route: '/inbox' });
t('pushed value', window.__hive__.read(['elm','route']), '/inbox');

exposeAll({ a: () => 1, b: () => 2 });
t('exposeAll', [window.__hive__.read(['a']), window.__hive__.read(['b'])], [1,2]);

clear('a');
t('clear', exposed().includes('a'), false);
t('names sorted', exposed(), exposed().slice().sort());

console.log(`\n${ok} passed, ${bad} failed`);
process.exit(bad ? 1 : 0);
