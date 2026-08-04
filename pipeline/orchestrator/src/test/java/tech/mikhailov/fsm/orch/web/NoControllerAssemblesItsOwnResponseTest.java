package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A CONTROLLER ROUTES; IT DOES NOT DESCRIBE. As a test, because a layering rule nobody can check is a
 * comment — which is the argument {@code TheInnerCirclesDependOnNoFrameworkTest} makes for the write
 * path, applied to the read path's other half.
 *
 * <p>WHAT THIS IS WORTH, CONCRETELY. Before it, {@code JobsController} built its bodies with 31
 * inline {@code put()} calls, {@code CommentController} with 40 and {@code DashboardController} with
 * 5. Nothing was wrong with any of them. What was wrong was that the shape of a response could only be
 * learned by reading the method that returned it: the reply to an ingest that DISCARDS 282 markers was
 * assembled across three call frames, and the header block every comment answer carries — the one the
 * FORM IS GENERATED FROM — was a private method on a controller. A payload with a contract that is
 * described nowhere is one that drifts from its consumer without anybody choosing to change it, which
 * is the failure {@code ThePageAndTheApiAgreeOnEveryFieldNameTest} was written after.
 *
 * <p>SO THE WIRE NAMES LIVE IN THE PRESENTERS. {@link DashboardPresenter}, {@link JobsPresenter} and
 * {@code CommentPresenter} are the three files a reader opens to see the shape of every response this
 * service can give, and there is no fourth. What the controllers keep is what only they can decide:
 * status codes, cache headers, which parameter falls back rather than 400-ing, and which exceptions
 * become an answer instead of Boot's error page.
 *
 * <p>IT READS THE SOURCES, comments stripped, for the same reason the dependency rule does: these
 * files argue for their own decisions at length and quote the very keys they no longer build, so a
 * naive search would fail on every explanation of why the rule exists.
 */
class NoControllerAssemblesItsOwnResponseTest {

    /**
     * The classes this rule governs, and the presenter each one must be routing through.
     *
     * <p>{@link LivePublisher} IS ONE OF THEM, and it was missed on the first pass — which is why
     * {@link #everyControllerIsOnTheListAbove} exists. It is a {@code @Controller} with a
     * {@code @MessageMapping} rather than a {@code @RestController}, so a reader listing "the
     * controllers" from memory does not think of it; it was quietly building the two socket payloads
     * — one of them pushed on EVERY marker transition, the busiest document this service sends —
     * while a test called "no controller assembles its own response" passed.
     */
    private static final Map<String, String> CONTROLLERS = Map.of(
            "tech/mikhailov/fsm/orch/web/DashboardController.java", "DashboardPresenter",
            "tech/mikhailov/fsm/orch/web/JobsController.java", "JobsPresenter",
            "tech/mikhailov/fsm/orch/web/LivePublisher.java", "DashboardPresenter",
            "tech/mikhailov/fsm/orch/comment/CommentController.java", "CommentPresenter");

    /**
     * {@code out.put("dedup_key", …)} — a wire name being written into a body.
     *
     * <p>Quoted first argument only, deliberately: {@code map.put(column, value)} inside a loop over a
     * named list is the OPPOSITE of the defect — it is a shape derived from one definition — and
     * {@link MarkerColumns} is built exactly that way.
     */
    private static final Pattern NAMES_A_KEY = Pattern.compile("\\.put\\(\\s*\"([^\"]+)\"");

    /** {@code Map.of("errors", List.of())} — the same thing, spelled shorter. */
    private static final Pattern LITERAL_MAP = Pattern.compile("Map\\.of\\(\\s*\"([^\"]+)\"");

    @Test
    void noControllerNamesAFieldOfTheResponseItReturns() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String controller : CONTROLLERS.keySet()) {
            String code = stripComments(read(controller));
            for (String key : keysNamedIn(code)) {
                violations.add(controller + " builds `" + key + "` inline");
            }
        }

        assertThat(violations)
                .as("a controller is assembling its own response body. Every key below is part of a "
                        + "contract with static/app.js or with an operator's curl, and it is now "
                        + "described in a place no reader would look for it — move it to the "
                        + "presenter for that controller, where the whole shape can be read at once "
                        + "and checked against the page")
                .isEmpty();
    }

    /**
     * …AND THE PRESENTERS ARE ACTUALLY THE ROUTE, which is what stops the rule above from being
     * satisfied by a controller that answers nothing.
     *
     * <p>A layering rule is trivially passed by deleting the layer. This is the same guard
     * {@code TheInnerCirclesDependOnNoFrameworkTest} puts under its own dependency rule for the same
     * reason: assert the outer class is WIRED to the inner one rather than merely quiet.
     */
    @Test
    void everyControllerAnswersThroughItsPresenter() throws Exception {
        List<String> unwired = new ArrayList<>();
        for (Map.Entry<String, String> pair : CONTROLLERS.entrySet()) {
            if (!stripComments(read(pair.getKey())).contains(pair.getValue() + ".")) {
                unwired.add(pair.getKey() + " never calls " + pair.getValue());
            }
        }

        assertThat(unwired)
                .as("this controller neither builds a body nor asks a presenter for one, so the rule "
                        + "above is passing over a class that answers nothing")
                .isEmpty();
    }

    /**
     * AND THE SHAPES DID NOT SIMPLY EVAPORATE. The presenters have to be carrying the names the
     * controllers stopped carrying — otherwise both checks above are satisfied by a refactor that
     * deleted the payloads instead of moving them.
     */
    @Test
    void thePresentersAreWhereTheNamesWent() throws Exception {
        Map<String, Integer> atLeast = Map.of(
                "tech/mikhailov/fsm/orch/web/DashboardPresenter.java", 20,
                "tech/mikhailov/fsm/orch/web/JobsPresenter.java", 20,
                "tech/mikhailov/fsm/orch/comment/CommentPresenter.java", 25);

        List<String> thin = new ArrayList<>();
        for (Map.Entry<String, Integer> pair : atLeast.entrySet()) {
            int named = keysNamedIn(stripComments(read(pair.getKey()))).size();
            if (named < pair.getValue()) {
                thin.add(pair.getKey() + " names " + named + " keys, expected at least "
                        + pair.getValue());
            }
        }

        assertThat(thin)
                .as("a presenter has lost the response shape it was created to hold. The two checks "
                        + "above would both pass over an API that had stopped answering, so this is "
                        + "the one that says the payloads are still somewhere")
                .isEmpty();
    }

    /**
     * …AND THE LIST ABOVE IS EVERY CONTROLLER THERE IS, which is the check that would have caught
     * {@link LivePublisher} instead of shipping a rule with a hole in it.
     *
     * <p>THE DEFECT THIS CLOSES IS THE ONE THE RULE ITSELF HAD. Three files were named by hand, the
     * fourth was a {@code @Controller} nobody listed, and the three checks above all passed while it
     * went on assembling {@code dedupKey}, {@code from}, {@code to}, {@code note} and {@code event}
     * inline — on the topic pushed at every marker transition. A rule that governs a hand-written list
     * only governs what somebody remembered, and "somebody remembered" is not a property a build can
     * hold. So the list is checked against the annotations instead: a new controller is a build failure
     * until it is either routed through a presenter or explicitly named here.
     */
    @Test
    void everyControllerIsOnTheListAbove() throws Exception {
        List<Path> annotated = new ArrayList<>();
        try (java.util.stream.Stream<Path> tree = Files.walk(mainSources())) {
            for (Path source : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(source, StandardCharsets.UTF_8));
                if (code.contains("@RestController") || code.contains("@Controller")) {
                    annotated.add(mainSources().relativize(source));
                }
            }
        }

        assertThat(annotated)
                .as("no @Controller was found in src/main/java at all, so this check is passing by "
                        + "reading nothing and the rule above governs whatever list it was given")
                .hasSizeGreaterThanOrEqualTo(CONTROLLERS.size());

        List<String> unlisted = new ArrayList<>();
        for (Path controller : annotated) {
            String relative = controller.toString().replace(java.io.File.separatorChar, '/');
            if (!CONTROLLERS.containsKey(relative)) {
                unlisted.add(relative);
            }
        }

        assertThat(unlisted)
                .as("this class is annotated as a controller and the rule above does not govern it, so "
                        + "it may assemble any response it likes with nothing failing — which is "
                        + "exactly how LivePublisher kept building the live payloads after this test "
                        + "was written. Route it through a presenter and add it to CONTROLLERS")
                .isEmpty();
    }

    // ---- reading the sources -----------------------------------------------------------------------

    private static List<String> keysNamedIn(String code) {
        List<String> keys = new ArrayList<>();
        for (Pattern pattern : List.of(NAMES_A_KEY, LITERAL_MAP)) {
            Matcher m = pattern.matcher(code);
            while (m.find()) {
                keys.add(m.group(1));
            }
        }
        return keys;
    }

    private static String read(String relative) throws Exception {
        Path source = mainSources().resolve(relative);
        assertThat(source)
                .as("%s is a file this rule governs and it is not there; a rule with nothing to read "
                        + "passes for the wrong reason", relative)
                .isRegularFile();
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    /**
     * Comments out, so the check reads the CODE.
     *
     * <p>Load-bearing in both directions here. {@link JobsPresenter} and the controllers document the
     * exact JSON they exchange — {@code {"ok":false,"error":"unknown_marker", …}} — and a search that
     * did not strip those would report every documented contract as a violation of itself.
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else {
                out.append(source.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** {@code src/main/java}, derived from where this class was loaded from. */
    private static Path mainSources() throws URISyntaxException, IOException {
        Path testClasses = Path.of(NoControllerAssemblesItsOwnResponseTest.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        // target/test-classes -> target -> the module.
        Path module = testClasses.getParent().getParent();
        return module.resolve("src").resolve("main").resolve("java");
    }
}
