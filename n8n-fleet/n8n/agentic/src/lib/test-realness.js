'use strict';
/**
 * Is the proof about the REAL code, or about a mock?
 *
 * A red->green flip only says something about the source if the test EXERCISED the source. A test
 * that mocks the class under test, or never touches it, can be driven red and then green entirely by
 * its own stubbing — the execution record is identical to a real proof and means nothing.
 *
 * Mock-heaviness is deliberately NOT the signal. The pipeline's best resource-leak proof mocks
 * Connection, Statement and ResultSet and is completely sound, because the object under test is real
 * and the verify() asserts the real object's behaviour. Mocking a collaborator you cannot stand up is
 * good practice; mocking the subject is the defect.
 */
function testRealness(src, cls) {
  const out = { sound: false, score: 0, reasons: [], mocks_subject: false, touches_real: false };
  const name = String(cls || '').replace(/[^A-Za-z0-9_$]/g, '');
  if (!src || !name) { out.reasons.push('no test source or no class name'); return out; }
  // mask comments and string literals so a class name inside a comment cannot count as a usage
  const s = src
    .replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, m => ' '.repeat(m.length))
    .replace(/"(\\.|[^"\\\n])*"/g, '""');
  const has = (re) => re.test(s);
  const count = (re) => (s.match(re) || []).length;
  const N = name.replace(/\$/g, '\\$');

  // THE CARDINAL SIN: the subject itself is a mock, so every observed behaviour is stubbed.
  out.mocks_subject = has(new RegExp('mock\\s*\\(\\s*' + N + '\\s*\\.\\s*class'))
                   || has(new RegExp('@Mock[\\s\\S]{0,80}?\\b' + N + '\\s+\\w'))
                   || has(new RegExp('@(?:Spy|InjectMocks)[\\s\\S]{0,80}?\\b' + N + '\\s+\\w'));

  const constructs = count(new RegExp('new\\s+' + N + '\\s*\\(', 'g'));
  const statics    = count(new RegExp('\\b' + N + '\\s*\\.\\s*[a-z]\\w*\\s*\\(', 'g'));
  out.touches_real = (constructs > 0 || statics > 0) && !out.mocks_subject;

  const asserts = count(/\bassert[A-Z]\w*\s*\(|\bassertThat\s*\(|\bfail\s*\(/g);
  const verifies = count(/\bverify\s*\(/g);
  const stubs = count(/\bwhen\s*\(|\bmock\s*\(|\bgiven\s*\(|@Mock\b/g);

  if (out.mocks_subject) {
    out.reasons.push('the class under test is itself mocked/spied — the test observes stubs, not ' + name);
  }
  if (!constructs && !statics) {
    out.reasons.push('the test never constructs ' + name + ' and never calls a static method on it');
  }
  out.sound = out.touches_real;
  if (!out.sound) return out;

  // Preference ordering among SOUND tests.
  let score = 55;                                   // it does drive the real class
  if (constructs > 0) { score += 20; out.reasons.push('instantiates the real ' + name); }
  else { out.reasons.push('exercises ' + name + ' through static calls'); }
  if (asserts > 0) { score += 15; out.reasons.push(asserts + ' value/state assertion(s)'); }
  if (asserts === 0 && verifies > 0) {
    // Interaction-only is legitimate for "must call close()" style claims, but it is weaker evidence
    // than asserting a value, so it ranks below — it is not penalised into unsoundness.
    score -= 10; out.reasons.push('asserts only on interactions (verify), not on returned values/state');
  }
  if (stubs === 0) { score += 10; out.reasons.push('no stubbing at all — drives the real objects end to end'); }
  else { out.reasons.push(stubs + ' stub/mock setup(s) for collaborators (legitimate when the real ones need a DB/network)'); }
  out.score = Math.max(0, Math.min(100, score));
  return out;
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { testRealness };
