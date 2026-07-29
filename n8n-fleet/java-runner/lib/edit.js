'use strict';
/**
 * Applying the fixer's search/replace edits, and refusing the ones that would game the proof.
 *
 * Two separate jobs live here because both are safety rules, not conveniences:
 *
 *   fixTarget  — resolves a path inside the workspace and REFUSES anything under a test tree or the
 *                proof test itself. Enforced server-side so the fixer/test independence never depends
 *                on the caller getting it right.
 *   applyEdit  — exact unique match first; falls back to a whitespace-insensitive match so a correct
 *                fix is not thrown away over line-wrapping.
 */
const path = require('path');

/** Collapse every whitespace run to one space; index_map[i] is the offset in `s` that produced it. */
function wsNorm(s) {
  const out = [];
  const idx = [];
  let prevWs = false;
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (/\s/.test(ch)) {
      if (!prevWs) { out.push(' '); idx.push(i); prevWs = true; }
    } else {
      out.push(ch); idx.push(i); prevWs = false;
    }
  }
  return { norm: out.join(''), idx };
}

/**
 * Apply one edit. Returns { text } or { error }.
 *
 * The exact unique match is the contract — it is what makes an edit unambiguous. The fallback exists
 * because WebGoat is google-java-format'ed, so a wrapped expression puts the operator at the START of
 * each continuation line while the model reliably quotes it back at the END of the previous one. The
 * tokens are identical and only the wrapping differs, so collapsing whitespace makes them agree.
 * Without it a correct parameterised-query fix for a genuinely reproduced bug was discarded as
 * "old_str not found" — the defect proven and the patch thrown away.
 *
 * The fallback still demands exactly ONE candidate, so it can never choose between them.
 */
function applyEdit(cur, old, replacement) {
  const n = cur.split(old).length - 1;
  if (n === 1) return { text: cur.replace(old, replacement), note: '' };
  if (n > 1) return { error: `old_str matches ${n}x (not unique)` };

  const { norm: ncur, idx } = wsNorm(cur);
  const nold = wsNorm(old).norm.trim();
  if (!nold) return { error: 'old_str is empty' };
  const hits = ncur.split(nold).length - 1;
  if (hits === 0) return { error: 'old_str not found' };
  if (hits > 1) {
    return { error: `old_str not found exactly, and the whitespace-insensitive match is ambiguous (${hits} candidates)` };
  }
  const j = ncur.indexOf(nold);
  const start = idx[j];
  const end = idx[j + nold.length - 1] + 1;
  // the span starts at the first non-whitespace character, so the file's own indentation survives and
  // the replacement is trimmed to suit
  return { text: cur.slice(0, start) + replacement.trim() + cur.slice(end),
    note: 'matched ignoring whitespace/line-wrapping' };
}

/**
 * Resolve an edit path inside `ws`. Refuses workspace escapes and ANY edit under a test tree.
 *
 * This is the invariant that stops a fix grading its own exam: the fixer was handed a test it did not
 * write, and if it could weaken that test a green run would prove nothing.
 */
function fixTarget(ws, p, testPath) {
  const full = path.resolve(ws, String(p || '').replace(/^\/+/, ''));
  if (!full.startsWith(path.resolve(ws) + path.sep) && full !== path.resolve(ws)) {
    return { error: 'path escapes workspace' };
  }
  const rel = path.relative(ws, full).split(path.sep).join('/');
  const tp = String(testPath || '').split(path.sep).join('/');
  if (rel.toLowerCase().includes('/src/test/') || rel.toLowerCase().startsWith('src/test/')
      || (tp && rel === tp)) {
    return { error: 'refuses to edit a test file (fixer may not touch the test)' };
  }
  return { path: full };
}

module.exports = { applyEdit, fixTarget, wsNorm };
