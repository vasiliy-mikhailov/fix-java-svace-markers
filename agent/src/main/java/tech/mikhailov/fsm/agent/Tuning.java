package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WHAT THE MODEL IS ASKED FOR, AS DATA THE NEXT PROVE READS.
 *
 * <p>The model name, the endpoint and the two bounds were environment variables and Java constants,
 * so changing any of them meant recreating a container or building an image — and both of those
 * kill the pool, which orphans every claim in flight. A prove is a fresh process per marker, so a
 * file read at construction takes effect on the next marker and disturbs nothing running.
 *
 * <p>THE KEY IS NOT HERE, and that is not an oversight. Everything else on this list is a parameter;
 * a credential is not, and a page that displays one is a page that leaks one to anybody who gets a
 * look at the screen. It stays in the environment, and the settings page reports only whether one is
 * set.
 *
 * <p>Every value is CLAMPED on the way out rather than on the way in, so a file edited by hand or
 * left behind by an older version cannot put the pipeline somewhere the code does not expect.
 */
final class Tuning {

    /** Beside the results, like the prompts and the pool width. */
    static final Path WHERE =
            Path.of(System.getenv().getOrDefault("TUNING", "/results/model"));

    /**
     * Temperature zero, because these agents CERTIFY.
     *
     * <p>A judge that answers differently on the same evidence twice is not a judge, and every
     * loopback in this chain replays a decision. Editable because a run that produces the same
     * wrong answer every time is sometimes worth shaking, but that is a diagnostic and not a
     * setting to leave changed.
     */
    static final double TEMPERATURE = 0.0;

    /** No token cap: the streaming client bounds silence instead. Zero means unset. */
    static final int MAX_TOKENS = 0;

    private Tuning() {
    }

    /** The endpoint to call. Blank falls back to the environment, which is where deploys set it. */
    static String baseUrl() {
        return read("base_url", System.getenv().getOrDefault("QWEN_BASE_URL", ""));
    }

    /** The model to ask. */
    static String model() {
        return read("model", System.getenv().getOrDefault("QWEN_MODEL", ""));
    }

    /** 0 to 2, the range every OpenAI-shaped endpoint accepts. */
    static double temperature() {
        return Math.max(0, Math.min(2, number("temperature", TEMPERATURE)));
    }

    /** How much of one answer to allow, or 0 for no cap. Capped output truncates reasoning. */
    static int maxTokens() {
        return (int) Math.max(0, Math.min(200_000, number("max_tokens", MAX_TOKENS)));
    }

    /**
     * How long a call may go with NOTHING on the wire before the endpoint is called dead.
     *
     * <p>Not how long an answer may take — that is {@link #ceiling()}. Confusing the two is what
     * killed eighty-six live proves once, so they are separate values with separate names and the
     * page says which is which.
     */
    static Duration patience() {
        return Duration.ofMinutes((long) Math.max(1, Math.min(120, number("patience_minutes", 4))));
    }

    /** How long a call may go on ANSWERING before it is a generation that will not stop. */
    static Duration ceiling() {
        return Duration.ofMinutes((long) Math.max(1, Math.min(1440, number("ceiling_minutes", 240))));
    }

    /** Whether a key is set, which is all a page may say about one. */
    static boolean keyed() {
        return !System.getenv().getOrDefault("QWEN_API_KEY", "").isBlank();
    }

    /** Everything editable, in the order the page shows it. */
    static Map<String, String> all() {
        Map<String, String> now = new LinkedHashMap<>();
        now.put("model", model());
        now.put("base_url", baseUrl());
        now.put("temperature", trim(temperature()));
        now.put("max_tokens", String.valueOf(maxTokens()));
        now.put("patience_minutes", String.valueOf(patience().toMinutes()));
        now.put("ceiling_minutes", String.valueOf(ceiling().toMinutes()));
        return now;
    }

    /** Writes the settings a form submitted, keeping any it did not mention. */
    static void save(Map<String, String> given) throws IOException {
        Map<String, String> now = new LinkedHashMap<>(stored());
        for (String name : all().keySet()) {
            String value = given.get(name);
            if (value != null) {
                now.put(name, value.strip());
            }
        }
        StringBuilder b = new StringBuilder();
        now.forEach((k, v) -> b.append(k).append('=').append(v.replace("\n", " ")).append('\n'));
        Files.createDirectories(WHERE.getParent() == null ? WHERE : WHERE.getParent());
        Files.writeString(WHERE, b.toString());
    }

    /** Puts every value back to the environment's and the code's. */
    static void revert() throws IOException {
        Files.deleteIfExists(WHERE);
    }

    static boolean edited() {
        return Files.exists(WHERE);
    }

    private static Map<String, String> stored() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            if (!Files.isReadable(WHERE)) {
                return out;
            }
            for (String line : Files.readAllLines(WHERE)) {
                int at = line.indexOf('=');
                if (at > 0) {
                    out.put(line.substring(0, at).strip(), line.substring(at + 1).strip());
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // A SETTING THAT CANNOT BE READ IS NOT AN EMPTY SETTING. Every caller falls back to the
            // environment or the constant, so an unreadable file leaves the pipeline exactly as it
            // was rather than pointing it at nothing.
            return Map.of();
        }
        return out;
    }

    private static String read(String name, String fallback) {
        String value = stored().getOrDefault(name, "");
        return value.isBlank() ? fallback : value;
    }

    private static double number(String name, double fallback) {
        try {
            String value = stored().getOrDefault(name, "");
            return value.isBlank() ? fallback : Double.parseDouble(value);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** 0.0 rather than 0.0000001, and 0.7 rather than 0.7000000000000001. */
    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
