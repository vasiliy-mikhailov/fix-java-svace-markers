package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SHELL IS WRITTEN BY SOMEBODY WHO HAS NOT READ THIS CODE.
 *
 * <p>This tool is one of several — {@code bump-java-version}, {@code improve-java-tests} — that a
 * developer reaches from one UI. The shell that composes them is a separate repository, written in a
 * separate session, by somebody who cannot ask this code a question.
 *
 * <p>So everything it needs is SERVED and versioned rather than agreed: a manifest it fetches, a
 * health check it polls, a badge count it reads without knowing what a finding is. An agreement
 * nobody can query goes stale on the first deploy and reports success while doing so.
 *
 * <p>What is held here is the part that is expensive to get wrong later. Components can be extracted
 * at leisure; a base path baked into a hundred links cannot, and a manifest field renamed under a
 * shell nobody redeployed is a portal with a hole in it.
 */
class MountedBySomebodyElseTest {

    @Test
    @DisplayName("standalone is the default, so nothing about the current deployment changes")
    void standalone() {
        // BASE_PATH unset in this JVM. The tool serves at / exactly as it does today; a mount
        // contract that changed the unmounted behaviour would be a migration, not an addition.
        assertEquals("", Zone.basePath());
        assertTrue(Zone.manifest().contains("\"basePath\": \"\""), Zone.manifest());
    }

    @Test
    @DisplayName("the manifest carries every field the contract promises")
    void everyPromisedField() {
        String manifest = Zone.manifest();
        for (String field : new String[] {"id", "name", "description", "version", "basePath",
                "assetPrefix", "api", "health", "nav", "badges"}) {
            assertTrue(manifest.contains("\"" + field + "\""),
                    "spec/17 promises `" + field + "` and a shell will read it. Removing a field is "
                            + "how a portal breaks on a deploy nobody thought was breaking: "
                            + manifest);
        }
        assertTrue(manifest.contains("\"" + Zone.ID + "\""),
                "the id is the key a shell stores against and must not drift");
    }

    @Test
    @DisplayName("the manifest is valid JSON, because something else parses it")
    void parses() {
        String manifest = Zone.manifest();
        // Cheap structural check: this is hand-built with a text block, so the failure mode is a
        // stray comma or an unescaped value, and the reader is a program in another repository.
        assertEquals(count(manifest, '{'), count(manifest, '}'), "unbalanced braces: " + manifest);
        assertEquals(count(manifest, '['), count(manifest, ']'), "unbalanced brackets: " + manifest);
        assertFalse(manifest.contains(",\n}"), "trailing comma before }: " + manifest);
        assertFalse(manifest.contains(",]"), "trailing comma before ]: " + manifest);
    }

    @Test
    @DisplayName("nav paths are relative, because the shell owns the URL bar")
    void navIsRelative() {
        String manifest = Zone.manifest();
        int nav = manifest.indexOf("\"nav\"");
        String navBlock = manifest.substring(nav, manifest.indexOf(']', nav));
        assertFalse(navBlock.contains(Zone.ID),
                "a nav path with the mount prefix already in it is prefixed twice by the shell, and "
                        + "the link 404s in a way that reads as the shell's bug: " + navBlock);
    }

    @Test
    @DisplayName("an unknown version says so rather than being blank")
    void versionIsNeverSilentlyEmpty() {
        // FSM_VERSION is unset here, which is what a local build looks like.
        assertEquals("unknown", Zone.version(),
                "a blank version renders as a gap in the shell's About box and nobody chases it; "
                        + "`unknown` is a fact somebody can act on");
    }

    @Test
    @DisplayName("health reports on the record, not on the model")
    void healthIsAboutTheRecord(@TempDir Path results) {
        assertNull(Zone.unhealthy(results),
                "a readable results directory is a servable tool, whatever the inference endpoint "
                        + "is doing — the record is most of what anybody comes for");
        String why = Zone.unhealthy(results.resolve("not-there"));
        assertNotNull(why, "a missing results directory is the one thing that makes this unservable");
        assertTrue(why.contains("not there"), why);
    }

    @Test
    @DisplayName("badges count what stands, and exclude what the critic threw out")
    void badges(@TempDir Path results) throws Exception {
        Files.writeString(results.resolve("overwatch.jsonl"),
                "{\"finding\":\"a\",\"verdict\":\"holds\"}\n"
                        + "{\"finding\":\"b\",\"verdict\":\"refuted\"}\n"
                        + "{\"finding\":\"c\"}\n");
        String badges = Zone.badges(results);
        assertTrue(badges.contains("\"findings\":2"),
                "holds counts, and a finding with no verdict counts because nobody reached it — "
                        + "refuted does not, or the number only ever grows and stops being read: "
                        + badges);
    }

    @Test
    @DisplayName("badges survive a run that has not started")
    void badgesOnNothing(@TempDir Path results) {
        assertEquals("{\"findings\":0,\"proving\":0}", Zone.badges(results),
                "a shell polls this every few seconds from the moment it is mounted, including "
                        + "before anything has run");
    }

    private static int count(String s, char c) {
        return (int) s.chars().filter(ch -> ch == c).count();
    }
}
