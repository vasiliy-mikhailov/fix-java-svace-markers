package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TREE IS THE AGENT'S WHOLE WORLD, and this holds the two halves of that.
 *
 * <p>The flagged file arrives at its package path — javac requires it and an agent asked to edit
 * the class should find it where its package says it is. And a collaborator that has NOT been
 * written comes back as a compile error naming the missing symbol, which is not a failure of the
 * mechanism but the whole of it: that message is how an agent learns which stand-in it still owes.
 *
 * <p>A run that quietly resolved the real collaborator from the subject's tree would be the failure
 * this exists to prevent — the test would then pass or fail for reasons three packages away, which
 * is the thing isolation is for.
 */
class NothingAroundTheClassIsReachableTest {

    @TempDir
    Path tmp;

    private Path checkout;

    private void given(String body) throws IOException {
        checkout = tmp.resolve("checkout");
        Path pkg = checkout.resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(checkout.resolve("pom.xml"), "<project/>");
        Files.writeString(pkg.resolve("Flagged.java"), body);
        // A collaborator that EXISTS in the subject and must not follow the file across.
        Files.writeString(pkg.resolve("Helper.java"),
                "package com.example;\npublic class Helper { public static int help() { return 1; } }\n");
    }

    @Test
    @DisplayName("the flagged file lands at its package path and its neighbour does not follow")
    void seeded() throws IOException {
        given("package com.example;\npublic class Flagged { }\n");
        Path into = tmp.resolve("isolated");
        Isolation.seed(checkout, "src/main/java/com/example/Flagged.java", into);

        assertTrue(Files.isRegularFile(into.resolve("src/com/example/Flagged.java")),
                "the class is not where its package says it is");
        assertFalse(Files.exists(into.resolve("src/com/example/Helper.java")),
                "a collaborator followed the class across; nothing around it may be reachable");
    }

    @Test
    @DisplayName("a collaborator that was not written is a compile error that names it")
    void namesWhatIsMissing() throws IOException {
        // The flagged class calls its neighbour, which is deliberately NOT carried over.
        given("package com.example;\npublic class Flagged {\n"
                + "  public int go() { return Helper.help(); }\n}\n");
        Path into = tmp.resolve("isolated");
        Isolation.seed(checkout, "src/main/java/com/example/Flagged.java", into);

        Runner.Result r = new Isolation(into).run("red", "com.example.FlaggedTest");

        assertTrue(r.infra(), "a tree that cannot compile is never evidence, in either phase");
        assertFalse(r.passed());
        // THE MESSAGE IS THE INSTRUCTION. Without the symbol in it, an agent is told only that
        // something is wrong and has to guess which stand-in to write.
        assertTrue(r.summary().contains("Helper") || r.summary().contains("symbol"),
                "the compile error does not name what is missing: " + r.summary());
    }

    @Test
    @DisplayName("no test class named is infra, not an empty pass")
    void unnamed() throws IOException {
        given("package com.example;\npublic class Flagged { }\n");
        Path into = tmp.resolve("isolated");
        Isolation.seed(checkout, "src/main/java/com/example/Flagged.java", into);

        Runner.Result r = new Isolation(into).run("red", "  ");
        assertTrue(r.infra());
        assertFalse(r.passed(), "a run that named no test must never read as a pass");
    }
}
