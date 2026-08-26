package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE GUARD THAT IS A GENERATOR RATHER THAN A PROMPT.
 *
 * <p>An agent told "write an empty stub" writes {@code return true;} on the day the build will not
 * go green otherwise. A critic catches most of those; a generator catches all of them, costs no
 * model call, and cannot be talked round. The model hands back names, kinds and signatures — there
 * is no field through which a body can arrive.
 *
 * <p>The four cases below are not hypothetical. Each is a way an adversary reading the first draft
 * of this specification found to reach a green without writing anything true.
 */
class AStubMayNotHaveABodyTest {

    private static Fabricate.Declaration of(String fqn, Fabricate.Declaration.Kind kind,
                                            List<String> supers, List<Fabricate.Member> members) {
        return new Fabricate.Declaration(fqn, kind, supers, members, List.of(), "the build named it");
    }

    @Test
    @DisplayName("a class method throws, and says whose fabrication it is")
    void aBodyThatThrowsIsAFact(@TempDir Path dir) throws Exception {
        Fabricate.Written written = Fabricate.write(dir, "", List.of(
                of("ru.nsd.a.Thing", Fabricate.Declaration.Kind.CLASS, List.of(),
                        List.of(new Fabricate.Member("go", "boolean", List.of("java.lang.String"), false)))),
                Baseline.NONE);

        assertEquals(1, written.files().size(), written.refused().toString());
        String java = Files.readString(written.files().get(0));
        assertTrue(java.contains("throw new UnsupportedOperationException"), java);
        assertTrue(java.contains("ru.nsd.a.Thing#go"),
                "a test that CALLS a stand-in must fail with the fabrication's own name, in the "
                        + "surefire output and in the trace: " + java);
        // AND IT CANNOT RETURN. `return true` is the one edit that turns a stand-in into a passing
        // assertion, and there is no channel for it.
        assertFalse(java.contains("return "), java);
    }

    @Test
    @DisplayName("an interface method has no body at all, which cannot lie")
    void anInterfaceIsHonestByConstruction(@TempDir Path dir) throws Exception {
        Fabricate.Written written = Fabricate.write(dir, "", List.of(
                of("ru.nsd.core.wrauthclient.service.WRAuthService",
                        Fabricate.Declaration.Kind.INTERFACE, List.of(), List.of())),
                Baseline.NONE);
        String java = Files.readString(written.files().get(0));
        // THE ca2_gateway CASE, and the reason "empty is honest" is true THERE: the only use in
        // that tree is a class literal in @MockBean, so nothing dispatches on it.
        assertTrue(java.contains("public interface WRAuthService"), java);
        // `throw new`, not `throw`: the file's own header says "Every method throws", which is
        // prose about the generator rather than code in the stand-in.
        assertFalse(java.contains("throw new"), "nothing to throw from: " + java);
        assertTrue(written.fabricatedValues().isEmpty(),
                "an empty interface nobody dispatches on fabricates no values at all");
    }

    @Test
    @DisplayName("inheritance is a body somebody else wrote, so it is refused")
    void aStubMayNotInheritABody(@TempDir Path dir) throws Exception {
        Fabricate.Written written = Fabricate.write(dir, "", List.of(
                of("ru.nsd.a.Repo", Fabricate.Declaration.Kind.CLASS,
                        List.of("ru.nsd.a.MessageRepositoryBase"), List.of())),
                Baseline.NONE);

        assertTrue(written.files().isEmpty());
        assertEquals(1, written.refused().size());
        assertTrue(written.refused().get(0).because().contains("behaviour nobody declared"),
                "`extends MessageRepositoryBase` gives every call site a real implementation, "
                        + "nothing throws, and a ledger counting members honestly reports zero: "
                        + written.refused());
    }

    @Test
    @DisplayName("extending a throwable is allowed and counted, because it is what makes assertThrows pass")
    void aThrowableIsAFabricatedDecision(@TempDir Path dir) throws Exception {
        Fabricate.Written written = Fabricate.write(dir, "", List.of(
                of("ru.nsd.a.Boom", Fabricate.Declaration.Kind.CLASS,
                        List.of("java.lang.RuntimeException"), List.of())),
                Baseline.NONE);

        assertEquals(1, written.files().size(), written.refused().toString());
        assertTrue(written.fabricatedValues().stream()
                        .anyMatch(v -> v.contains("fabricated inheritance")),
                "allowed, because a run that refused it would be useless — and counted, because "
                        + "`extends RuntimeException` is exactly what makes an assertThrows pass "
                        + "over a type nobody wrote: " + written.fabricatedValues());
    }

    @Test
    @DisplayName("an annotation gets runtime retention, because an invisible one silently does nothing")
    void anEmptyAnnotationIsAMissingAspect(@TempDir Path dir) throws Exception {
        Fabricate.Written written = Fabricate.write(dir, "", List.of(
                of("ru.nsd.a.HasPermission", Fabricate.Declaration.Kind.ANNOTATION,
                        List.of(), List.of())),
                Baseline.NONE);
        String java = Files.readString(written.files().get(0));

        // A GENERATED @interface WITH NO @Retention DEFAULTS TO CLASS — invisible to reflection. A
        // fabricated Jackson or JAXB annotation then compiles and does nothing, and a round-trip
        // test passes because a field was quietly dropped.
        assertTrue(java.contains("@Retention(RetentionPolicy.RUNTIME)"), java);
        assertTrue(written.fabricatedValues().stream().anyMatch(v -> v.contains("@Retention")),
                "and it is COUNTED, because a fabricated @HasPermission makes every permission "
                        + "check silently vanish and the module look better for losing its "
                        + "security: " + written.fabricatedValues());
    }

    @Test
    @DisplayName("an enum's constants are its branches, so every one is counted")
    void theConstantSetIsTheBranchSet(@TempDir Path dir) throws Exception {
        Fabricate.Written written = Fabricate.write(dir, "", List.of(
                new Fabricate.Declaration("ru.nsd.a.State", Fabricate.Declaration.Kind.ENUM,
                        List.of(), List.of(), List.of("CREATED", "SENT"), "a switch names them")),
                Baseline.NONE);
        String java = Files.readString(written.files().get(0));

        assertTrue(java.contains("CREATED, SENT;"), java);
        assertEquals(2, written.fabricatedValues().size(),
                "`switch (t) { case CREATED … }` does not compile without the constant and takes a "
                        + "different branch with it: " + written.fabricatedValues());
    }

    @Test
    @DisplayName("a static field is its type's default, and there is no way to choose otherwise")
    void thereIsNoChannelForAChosenValue(@TempDir Path dir) throws Exception {
        Fabricate.Written written = Fabricate.write(dir, "", List.of(
                of("ru.nsd.a.Consts", Fabricate.Declaration.Kind.CLASS, List.of(),
                        List.of(new Fabricate.Member("CA_FORM", "java.lang.String", null, true),
                                new Fabricate.Member("LIMIT", "int", null, true)))),
                Baseline.NONE);
        String java = Files.readString(written.files().get(0));

        assertTrue(java.contains("public static final java.lang.String CA_FORM = null;"), java);
        assertTrue(java.contains("public static final int LIMIT = 0;"), java);
        assertEquals(2, written.fabricatedValues().size(),
                "a constant nobody chose is still a constant somebody's branch reads");
    }

    @Test
    @DisplayName("a type the tree already defines may not be replaced by a stand-in")
    void aStandInMayNotReplaceRealCode(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/ru/nsd/a"));
        Files.writeString(dir.resolve("src/main/java/ru/nsd/a/Real.java"),
                "package ru.nsd.a; public class Real { public boolean go() { return false; } }\n");

        Fabricate.Written written = Fabricate.write(dir, "", List.of(
                of("ru.nsd.a.Real", Fabricate.Declaration.Kind.CLASS, List.of(), List.of())),
                Baseline.NONE);

        assertTrue(written.files().isEmpty());
        assertTrue(written.refused().get(0).because().contains("already defined"),
                "this is how a stand-in stops being a stand-in and becomes a replacement for the "
                        + "code under analysis: " + written.refused());
    }

    @Test
    @DisplayName("stand-ins go in main sources, where the production code can see them")
    void mainAndNotTest(@TempDir Path dir) throws Exception {
        Fabricate.Written written = Fabricate.write(dir, "ca2-events", List.of(
                of("ru.nsd.a.Thing", Fabricate.Declaration.Kind.INTERFACE, List.of(), List.of())),
                Baseline.NONE);

        assertEquals(dir.resolve("ca2-events/src/main/java/ru/nsd/a/Thing.java"),
                written.files().get(0),
                "under src/test/java it would be invisible to the main compile — and it would sit "
                        + "in the one tree guard A watches, where every write is reverted");
    }
}
