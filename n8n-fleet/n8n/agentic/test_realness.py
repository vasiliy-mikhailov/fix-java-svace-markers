#!/usr/bin/env python3
"""Tests for the test-realness scorer.

A red->green flip only says something about the source if the test EXERCISED the source. A test that
mocks the class under test, or never touches it, can be driven red and then green entirely by its own
stubbing — the execution evidence is byte-identical to a real proof and means nothing.

The scorer must separate that from legitimate mocking. Mock-heaviness is NOT the signal: the fixtures
below include a real test the pipeline wrote for a resource-leak marker that mocks Connection,
Statement and ResultSet and is completely sound, because the object under test is real and the
verify() is against the real object's behaviour.

    python3 test_realness.py
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tables  # noqa: E402
tables.SUSPICIONS_TABLE = tables.BUGS_TABLE = "test0000000000"
tables.SCAN_FILES_TABLE = tables.METHOD_RUNS_TABLE = "test0000000000"
import gen_prover  # noqa: E402


def score(src, cls):
    js = """
%s
const r = testRealness(%s, %s);
process.stdout.write(JSON.stringify(r));
""" % (gen_prover.TEST_REALNESS_FN, json.dumps(src), json.dumps(cls))
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(js)
        p = f.name
    try:
        r = subprocess.run(["node", p], capture_output=True, text=True)
        if r.returncode != 0:
            raise AssertionError("scorer threw:\n" + r.stderr[-1500:])
        return json.loads(r.stdout)
    finally:
        os.unlink(p)


FAILED = []


def check(label, cond, detail=""):
    if cond:
        print("  ok   %s" % label)
    else:
        print("  FAIL %s %s" % (label, detail))
        FAILED.append(label)


# --- REAL fixture: written by the pipeline for a HANDLE_LEAK marker. Mock-heavy and SOUND. ---------
SOUND_MOCKY = '''
package org.owasp.webgoat.lessons.sqlinjection.introduction;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
public class SqlInjectionLesson10FsmProofTest {
  @Test void statementShouldBeClosedOnAllPaths() throws Exception {
    LessonDataSource ds = mock(LessonDataSource.class);
    Connection conn = mock(Connection.class);
    Statement stmt = mock(Statement.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.createStatement(1, 2)).thenReturn(stmt);
    when(stmt.executeQuery(anyString())).thenThrow(new SQLException("boom"));
    SqlInjectionLesson10 lesson = new SqlInjectionLesson10(ds);
    lesson.injectableQueryAvailability("test");
    verify(stmt, atLeastOnce()).close();
  }
}
'''

# --- REAL fixture: static call against a real JDK oracle, no mocks at all. -------------------------
SOUND_STATIC = '''
package org.owasp.webgoat.lessons.challenges.challenge7;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
public class MD5FsmProofTest {
  @Test void testLargeInputBitCountOverflow() throws Exception {
    byte[] data = new byte[256 * 1024 * 1024];
    byte[] expected = MessageDigest.getInstance("MD5").digest(data);
    byte[] actual = MD5.getHash(data);
    assertArrayEquals(expected, actual, "hash should match");
  }
}
'''

# --- The failure mode being guarded against: the SUBJECT itself is mocked. -------------------------
MOCKS_SUBJECT = '''
import static org.mockito.Mockito.*;
public class ServersFsmProofTest {
  @Test void t() {
    Servers servers = mock(Servers.class);
    when(servers.sort("id")).thenReturn("safe");
    verify(servers, atLeastOnce()).sort("id");
  }
}
'''

# --- The other failure mode: the class under test is never touched at all. -------------------------
NEVER_TOUCHES = '''
import static org.mockito.Mockito.*;
public class ServersFsmProofTest {
  @Test void t() throws Exception {
    Connection conn = mock(Connection.class);
    Statement stmt = mock(Statement.class);
    when(conn.createStatement()).thenReturn(stmt);
    verify(conn, never()).close();
  }
}
'''

# A comment mentioning the class must not count as exercising it.
COMMENT_ONLY = '''
public class ServersFsmProofTest {
  // this test is about Servers.sort() and new Servers(ds)
  @Test void t() { assertTrue(true); }
}
'''


def main():
    print("sound: mock-heavy but drives the real class (real pipeline output)")
    r = score(SOUND_MOCKY, "SqlInjectionLesson10")
    check("sound", r["sound"] is True, str(r["reasons"]))
    check("subject not mocked", r["mocks_subject"] is False)
    check("scores well despite 3 mocks", r["score"] >= 60, str(r["score"]))

    print("\nsound: static call, no mocks (real pipeline output)")
    r2 = score(SOUND_STATIC, "MD5")
    check("sound via static call", r2["sound"] is True, str(r2["reasons"]))
    check("no-stub test scores highest", r2["score"] > r["score"],
          "static=%s mocky=%s" % (r2["score"], r["score"]))

    print("\nunsound: the class under test is itself mocked")
    r3 = score(MOCKS_SUBJECT, "Servers")
    check("flagged as mocking the subject", r3["mocks_subject"] is True)
    check("not sound", r3["sound"] is False)
    check("score is zero", r3["score"] == 0)
    check("reason names the class", any("Servers" in x for x in r3["reasons"]), str(r3["reasons"]))

    print("\nunsound: never touches the class under test")
    r4 = score(NEVER_TOUCHES, "Servers")
    check("not sound", r4["sound"] is False, str(r4["reasons"]))
    check("score is zero", r4["score"] == 0)

    print("\nunsound: only mentioned in a comment")
    r5 = score(COMMENT_ONLY, "Servers")
    check("comment does not count as usage", r5["sound"] is False, str(r5["reasons"]))

    print("\nranking: value assertions outrank interaction-only")
    val = score('public class T { @Test void t(){ Foo f = new Foo(); assertEquals(2, f.calc()); } }', "Foo")
    ver = score('import static org.mockito.Mockito.*;\npublic class T { @Test void t(){ Bar b = mock(Bar.class);'
                ' Foo f = new Foo(b); f.run(); verify(b).close(); } }', "Foo")
    check("both sound", val["sound"] and ver["sound"])
    check("value-asserting test ranks higher", val["score"] > ver["score"],
          "value=%s interaction=%s" % (val["score"], ver["score"]))

    print("\ndegenerate input")
    check("empty source is unsound", score("", "Foo")["sound"] is False)
    check("empty class name is unsound", score(SOUND_STATIC, "")["sound"] is False)

    print()
    if FAILED:
        raise SystemExit("%d check(s) FAILED: %s" % (len(FAILED), ", ".join(FAILED)))
    print("all checks passed")


if __name__ == "__main__":
    main()
