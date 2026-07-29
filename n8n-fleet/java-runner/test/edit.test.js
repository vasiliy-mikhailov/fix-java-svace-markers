'use strict';
/**
 * Applying the fixer's edits, and refusing the ones that would game the proof.
 *
 * Origin: the reproducer PROVED the TAINTED_PTR marker in Assignment5.java — a test that compiled,
 * ran and failed on the unpatched code — and the fixer produced a correct parameterised-query patch
 * that was thrown away with "old_str not found". WebGoat is google-java-format'ed, so the wrapped
 * concatenation puts `+` at the START of each continuation line; the model quoted it back with `+` at
 * the END of the previous one. Identical tokens, different wrapping, no byte-exact match, and a
 * demonstrated defect left unfixed.
 */
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { applyEdit, fixTarget } = require('../lib/edit');

// verbatim from the container: operators lead the continuation lines
const SRC = "    try (var connection = dataSource.getConnection()) {\n      PreparedStatement statement =\n          connection.prepareStatement(\n              \"select password from challenge_users where userid = '\"\n                  + username_login\n                  + \"' and password = '\"\n                  + password_login\n                  + \"'\");\n      ResultSet resultSet = statement.executeQuery();\n    }\n";
// what the fixer actually sent: operators trail the previous lines
const OLD = "      PreparedStatement statement =\n          connection.prepareStatement(\n              \"select password from challenge_users where userid = '\" +\n                  username_login +\n                  \"' and password = '\" +\n                  password_login +\n                  \"'\");\n      ResultSet resultSet = statement.executeQuery();";
const NEW = "      PreparedStatement statement =\n          connection.prepareStatement(\n              \"select password from challenge_users where userid = ? and password = ?\");\n      statement.setString(1, username_login);\n      statement.setString(2, password_login);\n      ResultSet resultSet = statement.executeQuery();";

test('the real e2e failure: wrapping differs, the fix still applies', () => {
  assert.equal(SRC.split(OLD).length - 1, 0, 'byte-exact matching genuinely cannot see it');
  const r = applyEdit(SRC, OLD, NEW);
  assert.ok(!r.error, r.error);
  assert.match(r.note, /whitespace/, 'the caller must be told the match was fuzzy');
  assert.ok(!r.text.includes('+ username_login'), 'the concatenation is gone');
  assert.ok(r.text.includes('userid = ? and password = ?'), 'placeholders are in');
  assert.equal(r.text.split('statement.setString').length - 1, 2);
  assert.ok(r.text.startsWith('    try (var connection'), 'surrounding code untouched');
  assert.ok(!r.text.includes('\n            PreparedStatement'), 'no double indentation');
});

test('an exact match is preferred and reported as clean', () => {
  const r = applyEdit('a\n  b\nc\n', '  b', '  B');
  assert.equal(r.text, 'a\n  B\nc\n');
  assert.equal(r.note, '');
});

test('ambiguity is refused, never guessed', async (t) => {
  await t.test('duplicate exact match', () => {
    assert.match(applyEdit('x = 1;\nx = 1;\n', 'x = 1;', 'x = 2;').error, /not unique/);
  });
  await t.test('two candidates that both differ only in whitespace', () => {
    const amb = 'f(a,\n b);\nf(a,  b);\n';
    assert.equal(amb.split('f(a, b);').length - 1, 0);
    assert.match(applyEdit(amb, 'f(a, b);', 'g();').error, /ambiguous/);
  });
});

test('a genuinely absent string stays absent', () => {
  assert.match(applyEdit(SRC, 'totallyUnrelated(123);', 'x').error, /not found/);
});

test('tabs and runs collapse', () => {
  assert.ok(applyEdit('int  x =\t1;\n', 'int x = 1;', 'int x = 2;').text.includes('int x = 2;'));
});

test('the fixer may never touch a test', async (t) => {
  const ws = '/ws';
  for (const [name, p] of Object.entries({
    'the proof test itself': 'src/test/java/a/BFsmProofTest.java',
    'any other test': 'src/test/java/a/OtherTest.java',
    'a nested test tree': 'mod/src/test/java/a/T.java',
  })) {
    await t.test(name, () => {
      assert.match(fixTarget(ws, p, 'src/test/java/a/BFsmProofTest.java').error, /refuses to edit a test/);
    });
  }
});

test('a path escaping the workspace is refused', () => {
  assert.match(fixTarget('/ws', '../../etc/passwd', '').error, /escapes workspace/);
});

test('the suspected source file is allowed', () => {
  const r = fixTarget('/ws', 'src/main/java/a/B.java', 'src/test/java/a/BTest.java');
  assert.ok(!r.error);
  assert.equal(r.path, path.join('/ws', 'src/main/java/a/B.java'));
});
