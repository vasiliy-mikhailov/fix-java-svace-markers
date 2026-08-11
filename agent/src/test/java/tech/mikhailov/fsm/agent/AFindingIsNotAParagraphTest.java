package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE CRITIC REFUTED "**Examples:**" TWICE, AND IT WAS RIGHT TO.
 *
 * <p>The watcher's first real report named four markers whose tests pass on unfixed code and traced
 * the cause to a prompt — a genuine finding, and the one thing a person reading a finished run had
 * previously had to find by hand. It arrived as a heading, a count, a list of examples and an
 * explanation. Splitting on blank lines turned that into four claims, none of which contained the
 * claim, and the critic correctly refuted a list of file names for asserting nothing.
 *
 * <p>A finding is bounded by a heading the watcher was told to write, which is checkable. A paragraph
 * break is a guess about how a model formats prose.
 */
class AFindingIsNotAParagraphTest {

    @SuppressWarnings("unchecked")
    private static List<String> split(String report) throws Exception {
        Method m = Overwatch.class.getDeclaredMethod("split", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, report);
    }

    /** The shape the watcher actually produced, abbreviated. */
    private static final String REPORT = """
            # Pipeline Report

            ## Finding: Reproducer writes assertThrows tests that PASS on unfixed code

            **Count:** 4 markers

            **Examples:**
            - `DOMCrossSiteScripting.java_40_DEREF_OF_NULL.RET`
            - `LessonTemplateResolver.java_61_DEREF_AFTER_NULL`
            - `ProfileUploadBase.java_86_DEREF_OF_NULL.RET.LIB`

            **What's wrong:** A reproducer test must fail on unfixed code and pass on fixed code.

            ## Finding: estimator prices a patch on markers where no fixer ran

            **Count:** 2 markers, both settled by the verdict agent with no fix stage at all.
            """;

    @Test
    @DisplayName("two findings, each whole")
    void whole() throws Exception {
        List<String> findings = split(REPORT);
        assertEquals(2, findings.size(), "one per heading, not one per paragraph: " + findings);
        assertTrue(findings.get(0).contains("assertThrows"), "the claim is in the first");
        assertTrue(findings.get(0).contains("DOMCrossSiteScripting"),
                "and so are its examples — a critic given the claim without them cannot check it");
        assertTrue(findings.get(0).contains("must fail on unfixed code"),
                "and the explanation, which used to become a finding of its own");
        assertTrue(findings.get(1).contains("estimator"), "the second is its own claim");
    }

    @Test
    @DisplayName("the preamble is not a finding")
    void preamble() throws Exception {
        for (String finding : split(REPORT)) {
            assertTrue(finding.toLowerCase().startsWith("## finding"),
                    "'# Pipeline Report' asserts nothing and would be refuted for it: " + finding);
        }
    }

    @Test
    @DisplayName("a report with no headings is one finding, not none")
    void unheaded() throws Exception {
        String prose = "The reproducer keeps writing tests that pass before any patch. I checked "
                + "four markers and all four recorded a RED build that passed, which settles nothing.";
        List<String> findings = split(prose);
        assertEquals(1, findings.size(), "better one whole claim judged once than dropped");
        assertTrue(findings.get(0).contains("settles nothing"), findings.get(0));
    }

    @Test
    @DisplayName("a clean run reports nothing to judge")
    void clean() throws Exception {
        List<String> findings = split("I checked the run and found no patterns worth reporting.");
        assertEquals(1, findings.size(), "the watcher saying it is clean is itself the answer");
    }

    @Test
    @DisplayName("a heading with nothing under it does not become a judgement")
    void empty() throws Exception {
        List<String> findings = split("## Finding: x\n\n## Finding: the estimator prices work that "
                + "did not happen, on two markers where no fixer ran at all, EncDec and Ping.");
        assertEquals(1, findings.size(), "a one-line heading asserts nothing: " + findings);
        assertTrue(findings.get(0).contains("estimator"), findings.get(0));
    }
}
