'use strict';
/**
 * Differential harness — the comparison, for the JSON/reply-parsing family.
 *
 * Reports total cases, how many are IDENTICAL, and every divergence with the input that triggered it.
 * Divergences are grouped by the OUTPUT FIELD the two sides first disagree about, because that is the
 * divergence CLASS: 27 rows that all differ in the realness log line are one finding, not 27.
 *
 * Nothing is normalised away. The only thing the tagging forgives is the difference between a value
 * and its wire rendering (an undefined member dropped, a non-finite number written as null), because
 * that rendering is what the next stage actually receives — and a harness that quietly forgave
 * anything else would be worth less than no harness at all.
 */
const fs = require('fs');

const path = require('path');
const OUT = path.join(__dirname, 'out');
const read = (f) => JSON.parse(fs.readFileSync(path.join(OUT, 'json-family-' + f), 'utf8'));
const cases = read('cases.json');
const js = read('js-results.json');
const java = read('java-results.json');
const byId = (rows) => Object.fromEntries(rows.map((r) => [r.id, r]));
const J = byId(js);
const V = byId(java);

const stats = {};
const diffs = [];
for (const c of cases) {
  const a = J[c.id] || {};
  const b = V[c.id] || {};
  const s = stats[c.suite] || (stats[c.suite] = { total: 0, same: 0, diff: 0 });
  s.total++;
  const same = a.threw ? (b.threw ? 'both-threw' : false)
    : (b.threw ? false : (a.out === b.out && (a.log || '') === (b.log || '')));
  if (same === true || same === 'both-threw') {
    s.same++;
  } else {
    s.diff++;
    diffs.push({ c, a, b });
  }
}

let report = '';
for (const [suite, s] of Object.entries(stats)) {
  report += `${suite}: ${s.total} cases, ${s.same} identical, ${s.diff} divergent\n`;
}
const t = Object.values(stats).reduce((x, y) => ({ total: x.total + y.total, same: x.same + y.same,
  diff: x.diff + y.diff }), { total: 0, same: 0, diff: 0 });
report += `TOTAL: ${t.total} cases, ${t.same} identical, ${t.diff} divergent\n`;

// group divergences by their signature so the report names classes, not 400 rows
const groups = new Map();
for (const d of diffs) {
  const sig = d.c.suite + ' | ' + (d.a.threw ? 'JS THREW ' + d.a.threw.split(':')[0] : 'ok')
    + ' | ' + (d.b.threw ? 'JAVA THREW ' + d.b.threw.split(':')[0] : 'ok')
    + ' | field=' + fieldAt(d.a.out || '', d.b.out || '');
  if (!groups.has(sig)) groups.set(sig, []);
  groups.get(sig).push(d);
}
/** The output field the two sides first disagree about — the divergence CLASS, not the case. */
function fieldAt(x, y) {
  if (x === y) return 'log line only';
  let i = 0;
  while (i < x.length && i < y.length && x[i] === y[i]) i++;
  const before = x.slice(0, i);
  const keys = [...before.matchAll(/"([a-z_0-9]+)":/g)];
  return (keys.length ? keys[keys.length - 1][1] : '?')
    + ' [js ' + kindOf(x.slice(i)) + ' vs java ' + kindOf(y.slice(i)) + ']';
}
function kindOf(s) {
  const m = /^(str:|num:|bool:|null|\[|\{|")/.exec(s);
  return m ? m[1] : s.slice(0, 6);
}
function firstDiff(x, y) {
  if (x === y) return 'logs differ';
  let i = 0;
  while (i < x.length && i < y.length && x[i] === y[i]) i++;
  return 'at ' + i + ': js=' + JSON.stringify(x.slice(i, i + 70))
    + ' java=' + JSON.stringify(y.slice(i, i + 70));
}

report += `\n${groups.size} divergence class(es)\n`;
let n = 0;
for (const [sig, rows] of groups) {
  n++;
  const d = rows[0];
  report += `\n--- class ${n}: ${rows.length} case(s) --- ${d.c.suite} :: ${d.c.note}\n`;
  report += 'input : ' + JSON.stringify(d.c).slice(0, 600) + '\n';
  report += 'js    : ' + (d.a.threw ? 'THREW ' + d.a.threw : d.a.out).slice(0, 600) + '\n';
  report += 'java  : ' + (d.b.threw ? 'THREW ' + d.b.threw : d.b.out).slice(0, 600) + '\n';
  if (!d.a.threw && !d.b.threw) report += 'first : ' + firstDiff(d.a.out, d.b.out) + '\n';
  if ((d.a.log || '') !== (d.b.log || '')) {
    report += 'jslog : ' + JSON.stringify(d.a.log || '') + '\njavalog: ' + JSON.stringify(d.b.log || '') + '\n';
  }
  report += 'notes : ' + rows.slice(0, 8).map((r) => r.c.note).join(' | ') + '\n';
}
fs.writeFileSync(path.join(OUT, 'json-family-report.txt'), report);
console.log(report.slice(0, 20000));
