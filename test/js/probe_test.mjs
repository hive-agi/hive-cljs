import { readFileSync } from 'fs';
globalThis.window = globalThis;
eval(readFileSync(new URL('../../resources/hive_cljs/probe.js', import.meta.url), 'utf8'));

let ok = 0, bad = 0;
const t = (l, a, e) => { const A=JSON.stringify(a), E=JSON.stringify(e);
  if (A===E) ok++; else { bad++; console.log(`FAIL ${l}\n  got      ${A}\n  expected ${E}`);} };
const throws = (l, f, n) => { try { f(); bad++; console.log(`FAIL ${l}: no throw`); }
  catch (e) { e.message.includes(n) ? ok++ : (bad++, console.log(`FAIL ${l}: ${e.message}`)); } };

const H = window.__hive__;
let state = { user:{name:'pedro'}, items:[1,2,3], ready:true, count:0 };
H.expose('model', () => state);

t('nested',        H.read(['model','user','name']), 'pedro');
t('index',         H.read(['model','items',1]), 2);
t('length',        H.read(['model','items','length']), 3);
t('falsy zero',    H.read(['model','count']), 0);
t('off the end',   H.read(['model','nope','deeper']), null);
t('read at read-time', (state={ready:false}, H.read(['model','ready'])), false);
t('bare path',     (H.expose('flat', () => 42), H.read('flat')), 42);

throws('unexposed', () => H.read(['ghost']), 'nothing exposed under "ghost"');
throws('names the fix', () => H.read(['ghost']), 'window.__hive__?.expose');
throws('getter throws', () => { H.expose('boom', () => { throw new Error('nope'); }); H.read(['boom']); }, 'threw: nope');

H.expose('odd', () => ({ fn: () => 1, when: new Date('2026-08-30T00:00:00Z'),
                         big: 10n, nan: NaN, err: new TypeError('bad'), sym: Symbol('s') }));
t('function', H.read(['odd','fn']),   '#function');
t('date',     H.read(['odd','when']), '2026-08-30T00:00:00.000Z');
t('bigint',   H.read(['odd','big']),  '10');
t('NaN',      H.read(['odd','nan']),  'NaN');
t('error',    H.read(['odd','err']),  {name:'TypeError', message:'bad'});
t('symbol',   H.read(['odd','sym']),  '#symbol');

const cyc = { n:1 }; cyc.self = cyc;
H.expose('cyc', () => cyc);
t('cycle', H.read(['cyc']), { n:1, self:'#cycle' });

let deep = {}, cur = deep;
for (let i=0;i<40;i++){ cur.next={}; cur=cur.next; }
H.expose('deep', () => deep);
t('depth bounded', JSON.stringify(H.read(['deep'])).includes('#depth-limit'), true);

H.expose('coll', () => ({ m: new Map([['a',1]]), s: new Set([1,2]) }));
t('map', H.read(['coll','m']), {a:1});
t('set', H.read(['coll','s']), [1,2]);

const send = H.pushed('elm');
t('pushed empty', H.read(['elm']), null);
send({ route:'/inbox' });
t('pushed value', H.read(['elm','route']), '/inbox');

t('idempotent install', (eval(readFileSync(new URL('../../resources/hive_cljs/probe.js', import.meta.url),'utf8')), H.read(['model','ready'])), false);
t('names sorted', H.names(), H.names().slice().sort());

// everything read must survive a structured clone
for (const n of H.names().filter(n => n !== 'boom')) { try { structuredClone(H.read([n])); } catch (e) {
  bad++; console.log(`FAIL clonable(${n}): ${e.message}`); continue; } ok++; }

console.log(`\n${ok} passed, ${bad} failed`);
process.exit(bad?1:0);
