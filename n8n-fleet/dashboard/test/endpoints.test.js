'use strict';
/**
 * Every endpoint the page calls must exist on the server.
 *
 * Origin (2026-07-28): the Node port dropped /api/bug. The investigation modal adds its Reproducer /
 * Fixer / PR maker tabs only when that call returns a row, so every marker — including 50 proven ones
 * with a drafted PR, a verified test and a diff — opened showing "not proven yet".
 *
 * Nothing failed. jget() catches the fetch error and returns null, which is indistinguishable from
 * "this marker has no artifact yet". The page rendered, the server logged nothing, and the only
 * symptom was a tab quietly missing. That is the same shape as the two blank-page bugs: a UI that
 * degrades silently when its data does not arrive.
 */
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const APP = fs.readFileSync(path.join(__dirname, '..', 'public', 'app.js'), 'utf8');
const SERVER = fs.readFileSync(path.join(__dirname, '..', 'src', 'server.js'), 'utf8');

test('the server answers every api path the page fetches', () => {
  // jget('api/bug?key=..') / fetch('api/state') -> the path before any query string
  const called = new Set(
    [...APP.matchAll(/['"`](api\/[a-z/]+)/g)].map(m => '/' + m[1]));
  assert.ok(called.size >= 3, 'expected the page to call several endpoints, found ' + called.size);

  const handled = new Set(
    [...SERVER.matchAll(/urlPath === '(\/[a-z/]+)'/g)].map(m => m[1]));

  const missing = [...called].filter(p => !handled.has(p)).sort();
  assert.deepEqual(missing, [],
    'the page calls these but the server does not handle them, and jget() will swallow the 404: '
    + missing.join(', '));
});

test('the endpoints the investigation modal depends on are present', () => {
  // named explicitly: these are the ones whose absence degrades silently rather than visibly
  for (const p of ['/api/state', '/api/bug', '/api/source']) {
    assert.match(SERVER, new RegExp(`urlPath === '${p.replace('/', '\\/')}'`),
      `${p} must be handled — without it the UI quietly renders less`);
  }
});

test('the marker columns are joined in one place, not duplicated', () => {
  // both /api/state and /api/bug enrich artifacts with the Svace row; two literal lists would drift
  const lists = SERVER.match(/'svace_severity', 'svace_checker'/g) || [];
  assert.equal(lists.length, 1, 'MARKER_COLS should have a single definition');
  assert.match(SERVER, /MARKER_COLS/);
});
