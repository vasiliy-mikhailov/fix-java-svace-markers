package tech.mikhailov.fsm.lib;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tech.mikhailov.fsm.lib.TestRealness.Result;

/**
 * {@link TestRealness} — does the proof actually exercise the code it claims to?
 *
 * <p>A red-&gt;green flip only says something about the source if the test EXERCISED the source. A test
 * that mocks the class under test, or never touches it, can be driven red and then green entirely by
 * its own stubbing — the execution record is byte-identical to a real proof and establishes nothing.
 *
 * <p>Mock-heaviness is deliberately NOT the signal. The fixtures below include a real test the
 * pipeline wrote for a resource-leak marker that mocks Connection, Statement and ResultSet and is
 * completely sound: the object under test is real, the real method runs, and the verify() asserts the
 * real object's behaviour. Penalising that would punish the best proofs.
 *
 * <p>The score is an ORDERING over sound proofs, and the pipeline reads it as a number, so most tests
 * here pin the exact score and the exact {@code reasons} list. Asserting only {@code sound == true}
 * leaves every term of the arithmetic — the +20, the +15, the -10, the +10 — free to be wrong.
 */
class TestRealnessTest {

    /** Real pipeline output, for a HANDLE_LEAK marker. Mock-heavy and SOUND. */
    private static final String SOUND_MOCKY = """
            package org.owasp.webgoat.lessons.sqlinjection.introduction;
            import static org.mockito.Mockito.*;
            public class SqlInjectionLesson10FsmProofTest {
              @Test void statementShouldBeClosedOnAllPaths() throws Exception {
                LessonDataSource ds = mock(LessonDataSource.class);
                Connection conn = mock(Connection.class);
                Statement stmt = mock(Statement.class);
                when(ds.getConnection()).thenReturn(conn);
                when(stmt.executeQuery(anyString())).thenThrow(new SQLException("boom"));
                SqlInjectionLesson10 lesson = new SqlInjectionLesson10(ds);
                lesson.injectableQueryAvailability("test");
                verify(stmt, atLeastOnce()).close();
              }
            }""";

    /** Real pipeline output: a static call checked against the JDK's own MessageDigest, no mocks. */
    private static final String SOUND_STATIC = """
            public class MD5FsmProofTest {
              @Test void testLargeInputBitCountOverflow() throws Exception {
                byte[] expected = MessageDigest.getInstance("MD5").digest(data);
                byte[] actual = MD5.getHash(data);
                assertArrayEquals(expected, actual, "hash should match");
              }
            }""";

    private static final String STUB_MOCK_REASON =
            " stub/mock setup(s) for collaborators (legitimate when the real ones need a DB/network)";
    private static final String NO_STUBS_REASON =
            "no stubbing at all — drives the real objects end to end";
    private static final String INTERACTION_ONLY_REASON =
            "asserts only on interactions (verify), not on returned values/state";

    @Test
    void mockHeavyButDrivesTheRealClassIsSound() {
        Result r = TestRealness.testRealness(SOUND_MOCKY, "SqlInjectionLesson10");
        assertTrue(r.sound(), () -> String.join("; ", r.reasons()));
        assertFalse(r.mocksSubject());
        assertTrue(r.touchesReal());
        // 55 for driving the real class, +20 for constructing it, -10 because the only check is a
        // verify(). The five stubs cost nothing — that is the whole point of the fixture, and
        // `score >= 60` alone would still pass if the stub count silently earned or lost points.
        assertEquals(65, r.score(), () -> "three mocks of collaborators must not be punished: "
                + r.score());
        assertEquals(List.of(
                "instantiates the real SqlInjectionLesson10",
                INTERACTION_ONLY_REASON,
                "5" + STUB_MOCK_REASON), r.reasons(),
                "a sound proof must collect neither the \"mocked/spied\" nor the \"never constructs\" "
                        + "reason");
    }

    @Test
    void aStaticCallAgainstARealOracleScoresHighest() {
        Result stat = TestRealness.testRealness(SOUND_STATIC, "MD5");
        Result mocky = TestRealness.testRealness(SOUND_MOCKY, "SqlInjectionLesson10");
        assertTrue(stat.sound());
        assertTrue(stat.score() > mocky.score(), "no stubbing at all is the strongest evidence");
        // 55 + 15 for the value assertion + 10 for stubbing nothing. The +20 is NOT earned: nothing
        // is constructed. Ranking alone cannot see that — a constructor bonus handed to every test
        // would keep this fixture on top at 100 — so the exact 80 is what pins it.
        assertEquals(80, stat.score());
        assertEquals(List.of(
                "exercises MD5 through static calls",
                "1 value/state assertion(s)",
                NO_STUBS_REASON), stat.reasons());
    }

    static Stream<Arguments> subjectMockingForms() {
        return Stream.of(
                Arguments.of("mock(X.class)",
                        "Servers servers = mock(Servers.class); verify(servers).sort(\"id\");"),
                Arguments.of("@Mock field",
                        "@Mock Servers servers;\n  @Test void t(){ servers.sort(\"id\"); }"),
                Arguments.of("@InjectMocks",
                        "@InjectMocks Servers servers;\n  @Test void t(){ servers.sort(\"id\"); }"),
                Arguments.of("@Spy",
                        "@Spy Servers servers;\n  @Test void t(){ servers.sort(\"id\"); }"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subjectMockingForms")
    void mockingTheSubjectIsTheCardinalSin(String name, String body) {
        Result r = TestRealness.testRealness(
                "import static org.mockito.Mockito.*;\npublic class T { " + body + " }", "Servers");
        assertTrue(r.mocksSubject(), name);
        assertFalse(r.touchesReal());
        assertFalse(r.sound(), "everything it observes is stubbing");
        assertEquals(0, r.score());
        // Both reasons this verdict can carry name the class, so `contains("Servers")` alone does not
        // establish that the DIAGNOSIS was reported — the reader must be told the subject is the mock.
        assertTrue(r.reasons().stream()
                        .anyMatch(x -> x.contains("mocked/spied") && x.contains("Servers")),
                () -> "the reason must say the subject itself is the mock, and name it: "
                        + String.join("; ", r.reasons()));
    }

    @Test
    void neverTouchingTheClassUnderTestIsUnsound() {
        Result r = TestRealness.testRealness("""
                import static org.mockito.Mockito.*;
                public class T { @Test void t(){ Connection c = mock(Connection.class);
                  verify(c, never()).close(); } }""", "Servers");
        assertFalse(r.sound());
        assertEquals(0, r.score());
        assertFalse(r.touchesReal());
        // Exactly one complaint: it never touched Servers. Nothing here is mocked as the SUBJECT, so
        // the cardinal-sin reason must not also be reported — a wrong diagnosis sends the pipeline
        // rewriting a test that has no mocking problem.
        assertEquals(List.of("the test never constructs Servers and never calls a static method on it"),
                r.reasons());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "public class T { // about new Servers(ds) and Servers.sort()\n }",
        "public class T { /* new Servers(ds); */ }",
        "public class T { @Test void t(){ log(\"new Servers(ds)\"); } }",
    })
    void aMentionInACommentOrAStringIsNotAUsage(String src) {
        assertFalse(TestRealness.testRealness(src, "Servers").sound());
    }

    /**
     * The @Mock-field rule is a PROXIMITY rule: the annotation must be within 80 characters of the
     * declaration to be read as annotating it. That distance is only meaningful if masking a comment
     * leaves the surrounding offsets untouched — a mask that collapsed the comment to a shorter string
     * would drag an unrelated @Mock collaborator inside the window and condemn a sound proof. Both
     * fixtures below put &gt;80 characters of comment between {@code @Mock DataSource} and the subject.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "/* the pool is not available in a unit test, so the factory is stubbed out */",
        "// the pool is not available in a unit test, so the factory is stubbed out",
    })
    void maskingACommentPreservesOffsets(String comment) {
        String src = """
                import static org.mockito.Mockito.*;
                public class ServersPoolTest {
                  @Mock DataSource ds; %s
                  @Test void t() { Servers servers = new Servers(ds);
                    assertEquals(2, servers.count()); }
                }""".formatted(comment);
        Result r = TestRealness.testRealness(src, "Servers");
        assertFalse(r.mocksSubject(),
                "the @Mock annotates DataSource, not Servers — the comment must not shrink the gap");
        assertTrue(r.sound(), () -> String.join("; ", r.reasons()));
        assertEquals(90, r.score(),
                "55 + 20 constructed + 15 asserted, one legitimate @Mock collaborator");
    }

    @Test
    void valueAssertionsOutrankInteractionOnlyChecks() {
        Result value = TestRealness.testRealness(
                "public class T { @Test void t(){ Foo f = new Foo(); assertEquals(2, f.calc()); } }",
                "Foo");
        Result inter = TestRealness.testRealness("""
                import static org.mockito.Mockito.*;
                public class T { @Test void t(){ Bar b = mock(Bar.class); Foo f = new Foo(b);
                  f.run(); verify(b).close(); } }""", "Foo");
        // A proof that does both is a value proof: the -10 is for having NOTHING but an interaction,
        // so asserting a value must not be taxed just because a verify() stands next to it.
        Result both = TestRealness.testRealness("""
                import static org.mockito.Mockito.*;
                public class T { @Test void t(){ Bar b = mock(Bar.class); Foo f = new Foo(b);
                  assertEquals(2, f.flush()); verify(b).close(); } }""", "Foo");
        assertTrue(value.sound());
        assertTrue(inter.sound(), "interaction-only is legitimate for \"must call close()\" claims");
        assertTrue(value.score() > inter.score(), "but it is weaker evidence and must rank below");
        assertEquals(100, value.score());
        assertEquals(65, inter.score());
        assertEquals(90, both.score(),
                "value + interaction is 55 + 20 + 15, with no interaction-only penalty");
        assertFalse(both.reasons().contains(INTERACTION_ONLY_REASON),
                () -> String.join("; ", both.reasons()));
        assertTrue(inter.reasons().contains(INTERACTION_ONLY_REASON),
                "and the penalty must be explained");
    }

    @Test
    void aTestThatChecksNothingIsNotChargedTheInteractionOnlyPenalty() {
        // `verifyChecksum()` is a domain method, not a Mockito `verify(...)`. If the scan counted it,
        // this silent smoke test would be charged the -10 reserved for proofs whose only evidence is
        // an interaction — a penalty for a verify() it never performed.
        Result r = TestRealness.testRealness(
                "public class T { @Test void t(){ Servers s = new Servers(); s.verifyChecksum(); } }",
                "Servers");
        assertEquals(85, r.score(), "55 + 20 constructed + 10 for stubbing nothing; no verify()");
        assertEquals(List.of("instantiates the real Servers", NO_STUBS_REASON), r.reasons());
    }

    @Test
    void aMethodWhoseNameMerelyStartsWithFailIsNotAnAssertion() {
        // Same trap on the assertion side: `failedAttempts()` merely begins with `fail`. Counting it
        // would turn this verify-only proof into a "value assertion" one and hand it 25 points it
        // never earned.
        Result r = TestRealness.testRealness("""
                import static org.mockito.Mockito.*;
                public class T { @Test void t(){ Audit a = mock(Audit.class); Servers s = new Servers(a);
                  s.retry(s.failedAttempts()); verify(a).record(); } }""", "Servers");
        assertEquals(65, r.score(), "55 + 20 constructed - 10 interaction-only");
        assertEquals(List.of("instantiates the real Servers", INTERACTION_ONLY_REASON,
                "1" + STUB_MOCK_REASON), r.reasons());
    }

    @Test
    void assertionsCountWithOrWithoutASpaceBeforeTheParen() {
        // JUnit sources in the wild put whitespace between the call and its paren, and a bare
        // `fail(...)` in a try/catch is the classic expected-exception idiom. Miss either and a real
        // value proof is graded as if it asserted nothing.
        Result r = TestRealness.testRealness("""
                public class ServersEdgeTest {
                  @Test void t() {
                    Servers s = new Servers();
                    assertEquals (2, s.count());
                    try { s.boom(); fail("should have thrown"); } catch (IllegalStateException e) { }
                    assertTrue(s.isEmpty());
                  }
                }""", "Servers");
        assertEquals(100, r.score(), "the ceiling: real class, constructed, asserted, nothing stubbed");
        assertEquals(List.of("instantiates the real Servers", "3 value/state assertion(s)",
                NO_STUBS_REASON), r.reasons());
    }

    @Test
    void everyStubbingFormIsCountedSpacedOrNot() {
        // when()/mock()/given() are the three stubbing forms, and each is counted whether or not a
        // space separates it from its paren. The count is reported to the reviewer, so an undercount
        // understates how much of the fixture is stubbed.
        Result r = TestRealness.testRealness("""
                import static org.mockito.BDDMockito.*;
                public class ServersStubTest {
                  @Test void t() {
                    Repo a = mock(Repo.class);
                    Repo b = mock (Repo.class);
                    when(a.find(1)).thenReturn(ROW);
                    when (b.find(1)).thenReturn(ROW);
                    given(a.all()).willReturn(ROWS);
                    given (b.all()).willReturn(ROWS);
                    Servers s = new Servers(a, b);
                    assertEquals(2, s.count());
                  }
                }""", "Servers");
        assertEquals(90, r.score(), "stubbing collaborators costs only the +10 for stubbing nothing");
        assertEquals(List.of("instantiates the real Servers", "1 value/state assertion(s)",
                "6" + STUB_MOCK_REASON), r.reasons());
    }

    @Test
    void theScoreStaysInside0To100() {
        List<String[]> cases = List.of(
                new String[] {SOUND_STATIC, "MD5"},
                new String[] {SOUND_MOCKY, "SqlInjectionLesson10"},
                new String[] {"public class T {}", "Foo"},
                new String[] {"", "Foo"});
        for (String[] c : cases) {
            Result r = TestRealness.testRealness(c[0], c[1]);
            assertTrue(r.score() >= 0 && r.score() <= 100, () -> r.score() + " out of range");
        }
    }

    @Test
    void degenerateInputIsUnsoundRatherThanThrowing() {
        List<String[]> cases = List.of(
                new String[] {"", "Foo"},
                new String[] {null, "Foo"},
                new String[] {SOUND_STATIC, ""},
                new String[] {SOUND_STATIC, null},
                new String[] {"public class T {}", null});
        for (String[] c : cases) {
            Result r = assertDoesNotThrow(() -> TestRealness.testRealness(c[0], c[1]));
            assertFalse(r.sound());
            assertEquals(0, r.score());
            // This is the one path that returns before the two flags are computed, so it is the only
            // place their defaults are observable — and a default of `true` would tell the caller
            // that a source nobody could even scan both mocks its subject and drives it for real.
            assertFalse(r.mocksSubject());
            assertFalse(r.touchesReal());
            // The reason has to be the RIGHT one. "never constructs Foo" is also a plausible-looking
            // complaint for an empty source, and the reproducer acts on this text: told that, it
            // rewrites a test that does not exist instead of being told there was no source at all.
            assertEquals(List.of("no test source or no class name"), r.reasons());
        }
    }

    @Test
    void mockingTheSubjectOutranksConstructingIt() {
        // The two signals can both be present — a test that stubs the subject and ALSO news up a real
        // one observes the stub wherever it matters. The cardinal-sin rule has to win, or the
        // constructor alone would certify the proof as sound and a red->green flip produced entirely
        // by stubbing would be recorded as evidence about the file.
        Result r = TestRealness.testRealness("""
                import static org.mockito.Mockito.*;
                public class ServersTest {
                  @Mock Servers servers;
                  @Test void t() { Servers real = new Servers(); servers.sort("id");
                    assertEquals(2, real.count()); }
                }""", "Servers");
        assertTrue(r.mocksSubject());
        assertFalse(r.touchesReal(), "constructing it does not undo mocking it");
        assertFalse(r.sound());
        assertEquals(0, r.score());
    }

    @Test
    void aClassNameWithRegexMetacharactersDoesNotBreakTheScan() {
        Result r = TestRealness.testRealness(
                "public class T { @Test void t(){ Foo$Inner x = new Foo$Inner(); x.go(); } }",
                "Foo$Inner");
        assertTrue(r.sound(), "$ must be quoted, not treated as an anchor");
    }
}
