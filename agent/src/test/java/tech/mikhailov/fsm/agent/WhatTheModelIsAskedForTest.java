package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TWO BOUNDS THAT MEAN DIFFERENT THINGS, AND CONFUSING THEM KILLED EIGHTY-SIX LIVE PROVES.
 *
 * <p>One is how long the wire may carry NOTHING; the other is how long an answer may go on
 * ARRIVING. A single "timeout" that measures the second while reading like the first reported a
 * healthy endpoint as dead, eighty-six times, and each one cost a marker that was fine.
 *
 * <p>They are separate settings with separate names here, and every value is clamped on the way out
 * rather than on the way in — a file edited by hand or left behind by an older version must not be
 * able to put the pipeline somewhere the code does not expect.
 */
class WhatTheModelIsAskedForTest {

    @AfterEach
    void clean() throws Exception {
        Tuning.revert();
    }

    private static void set(String... pairs) throws Exception {
        Map<String, String> given = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            given.put(pairs[i], pairs[i + 1]);
        }
        Tuning.save(given);
    }

    @Test
    @DisplayName("with nothing set, the pipeline runs on the environment and the code")
    void defaults() {
        assertEquals(Tuning.TEMPERATURE, Tuning.temperature(),
                "a pipeline nobody has tuned behaves exactly as it did before there was a page");
        assertEquals(0, Tuning.maxTokens(), "no cap is the setting, not a large number");
        assertFalse(Tuning.edited());
    }

    @Test
    @DisplayName("the two bounds are separate and neither is the other")
    void twoBounds() throws Exception {
        set("patience_minutes", "7", "ceiling_minutes", "300");
        assertEquals(7, Tuning.patience().toMinutes(), "silence on the wire");
        assertEquals(300, Tuning.ceiling().toMinutes(), "an answer that will not finish");
    }

    @Test
    @DisplayName("absurd values are clamped rather than obeyed")
    void clamped() throws Exception {
        set("temperature", "99", "patience_minutes", "0", "ceiling_minutes", "999999",
                "max_tokens", "-4");
        assertEquals(2, Tuning.temperature(), "no endpoint accepts more");
        assertEquals(1, Tuning.patience().toMinutes(), "zero would fail every call instantly");
        assertEquals(1440, Tuning.ceiling().toMinutes(), "a day is already generous");
        assertEquals(0, Tuning.maxTokens(), "negative is no cap, not a broken request");
    }

    @Test
    @DisplayName("junk leaves the value where it was rather than zeroing it")
    void junk() throws Exception {
        set("temperature", "warm", "patience_minutes", "soon");
        assertEquals(Tuning.TEMPERATURE, Tuning.temperature(),
                "a typo in a settings file must not silently change how every agent answers");
        assertEquals(4, Tuning.patience().toMinutes());
    }

    @Test
    @DisplayName("a blank value falls back rather than pointing the pipeline at nothing")
    void blank() throws Exception {
        set("model", "  ", "base_url", "");
        assertEquals(System.getenv().getOrDefault("QWEN_MODEL", ""), Tuning.model(),
                "an empty model name is not a model name");
        assertEquals(System.getenv().getOrDefault("QWEN_BASE_URL", ""), Tuning.baseUrl());
    }

    @Test
    @DisplayName("saving one setting keeps the others")
    void partial() throws Exception {
        set("temperature", "0.7", "model", "some-model");
        set("temperature", "0.2");
        assertEquals("some-model", Tuning.model(),
                "a form that posts one field must not blank the rest");
        assertEquals(0.2, Tuning.temperature());
    }

    @Test
    @DisplayName("reverting puts the environment back")
    void revert() throws Exception {
        set("temperature", "1.5");
        assertEquals(1.5, Tuning.temperature());
        Tuning.revert();
        assertEquals(Tuning.TEMPERATURE, Tuning.temperature());
        assertFalse(Tuning.edited());
    }

    @Test
    @DisplayName("an unreadable settings file leaves the pipeline exactly as it was")
    void unreadable() throws Exception {
        Files.createDirectories(Tuning.WHERE);
        try {
            assertEquals(Tuning.TEMPERATURE, Tuning.temperature(),
                    "every caller falls back, so an unreadable file cannot point the pipeline at "
                            + "nothing");
            assertEquals(4, Tuning.patience().toMinutes());
        } finally {
            Files.deleteIfExists(Tuning.WHERE);
        }
    }

    @Test
    @DisplayName("the key is not a setting and never appears among them")
    void noKey() {
        assertFalse(Tuning.all().containsKey("api_key"), "a credential is not a parameter");
        assertTrue(Tuning.all().keySet().stream().noneMatch(k -> k.contains("key")),
                "and nothing key-shaped is on the list either: " + Tuning.all().keySet());
    }
}
