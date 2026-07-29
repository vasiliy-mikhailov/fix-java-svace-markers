'use strict';
/**
 * Differential harness — the comparison.
 *
 * Reads the two type-tagged result files and reports: total cases, how many are IDENTICAL, and every
 * divergence with the case that triggered it and the two values side by side. Nothing is normalised
 * away except one thing, spelled out below, because a harness that quietly forgives a difference is
 * worth less than no harness at all.
 */
const fs = require('fs');
const path = require('path');

const OUT = path.join(__dirname, 'out');
// Optional file prefix, so each slice's run can leave its own evidence in place instead of
// overwriting the previous slice's: `node harness/compare.cjs input-family-`. Empty is slice 2.
const PREFIX = process.argv[2] || '';
const read = (name) => JSON.parse(fs.readFileSync(path.join(OUT, PREFIX + name), 'utf8'));
const js = read('js-results.json');
const java = read('java-results.json');
const cases = read('cases.json');

/**
 * The ONE normalisation: the name of the exception a node body threw.
 *
 * Reading `.choices` off a value that is not an object is a TypeError in JS and a
 * NullPointerException in Java; `.slice` on a number is a TypeError in JS and the port's NotSliceable;
 * a malformed reply is a SyntaxError in JS and a JsonException in Java. Those are the same EVENT — the
 * node threw where the JS threw — and the harness compares the event. The message text is not compared
 * here and is reported separately where it reaches a row (verdict_confidence, skeptic_reason).
 */
const THROWN = {
  TypeError: 'TypeError', NullPointerException: 'TypeError', NotSliceable: 'TypeError',
  SyntaxError: 'SyntaxError', JsonException: 'SyntaxError',
};
const normThrew = (t) => (typeof t === 'string' && t.startsWith('s:'))
  ? 's:' + (THROWN[t.slice(2)] || t.slice(2)) : t;

/** Walk two tagged trees together, collecting the paths at which they differ. */
function diff(a, b, at, into) {
  if (typeof a === 'string' || typeof b === 'string') {
    if (a !== b) into.push({ at, js: a, java: b });
    return;
  }
  if (!Array.isArray(a) || !Array.isArray(b)) {
    if (JSON.stringify(a) !== JSON.stringify(b)) into.push({ at, js: a, java: b });
    return;
  }
  if (a[0] !== b[0]) { into.push({ at, js: a[0], java: b[0] }); return; }
  if (a[0] === 'a') {
    const n = Math.max(a.length, b.length);
    for (let i = 1; i < n; i++) diff(a[i] ?? '(missing)', b[i] ?? '(missing)', at + '[' + (i - 1) + ']', into);
    return;
  }
  // an object: key ORDER is part of the value, because n8n items become table columns in that order
  const ka = a.slice(1).map((e) => e[0]);
  const kb = b.slice(1).map((e) => e[0]);
  if (ka.join(',') !== kb.join(',')) {
    into.push({ at: at + ' (keys)', js: ka.join(','), java: kb.join(',') });
  }
  const seen = new Set();
  for (const [k, v] of a.slice(1)) {
    seen.add(k);
    const other = b.slice(1).find((e) => e[0] === k);
    diff(v, other ? other[1] : '(missing)', at + '.' + k, into);
  }
  for (const [k, v] of b.slice(1)) {
    if (!seen.has(k)) diff('(missing)', v, at + '.' + k, into);
  }
}

let identical = 0;
const byNode = {};
const divergences = [];
for (let i = 0; i < js.length; i++) {
  const node = cases[i].node;
  byNode[node] = byNode[node] || { total: 0, same: 0 };
  byNode[node].total++;
  const found = [];
  diff(js[i].calls, java[i].calls, 'calls', found);
  diff(js[i].logs, java[i].logs, 'logs', found);
  diff(js[i].out, java[i].out, 'out', found);
  diff(normThrew(js[i].threw), normThrew(java[i].threw), 'threw', found);
  if (found.length === 0) { identical++; byNode[node].same++; } else {
    divergences.push({ id: js[i].id, found });
  }
}

const show = (v) => {
  const s = typeof v === 'string' ? v : JSON.stringify(v);
  return s.length > 220 ? s.slice(0, 220) + `…(${s.length})` : s;
};

console.log(`CASES ${js.length}   IDENTICAL ${identical}   DIVERGENT ${js.length - identical}`);
for (const [node, s] of Object.entries(byNode)) {
  console.log(`  ${node}: ${s.same}/${s.total} identical`);
}

// Group divergences by their SHAPE (the path plus the pair of values), so 400 instances of one rule
// are one finding with a count rather than 400 lines nobody reads.
const groups = new Map();
for (const d of divergences) {
  for (const f of d.found) {
    const key = f.at.replace(/\[\d+\]/g, '[i]') + ' | ' + show(f.js) + ' | ' + show(f.java);
    if (!groups.has(key)) groups.set(key, { at: f.at, js: f.js, java: f.java, n: 0, first: d.id });
    groups.get(key).n++;
  }
}
console.log(`\nDIVERGENCE CLASSES: ${groups.size}`);
let k = 0;
for (const g of [...groups.values()].sort((x, y) => y.n - x.n)) {
  console.log(`\n[${++k}] ${g.n} case(s) at ${g.at}\n    first: ${g.first}\n    JS   : ${show(g.js)}\n    JAVA : ${show(g.java)}`);
}
