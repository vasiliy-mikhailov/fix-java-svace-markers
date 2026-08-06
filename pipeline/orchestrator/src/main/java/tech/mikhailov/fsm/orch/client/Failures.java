package tech.mikhailov.fsm.orch.client;

import java.time.Duration;

/**
 * THE TWO LINES EVERY CLIENT IN THIS PACKAGE HAD ITS OWN COPY OF.
 *
 * <p>{@link #cause} was byte-identical in FOUR files ({@code HttpRunnerClient},
 * {@code HttpLlmClient}, {@code GithubSourceClient}, {@code CheckoutSourceClient}),
 * {@link #NOTHING_TO_SAY} verbatim in two and {@link #positive} in two. They are here rather than in
 * each file because what they produce is not private detail: {@code cause} writes the text of an
 * {@code InfraFailure}, and that text is what {@code infra_reason} carries on the row, what the
 * recovery queries match on with {@code LIKE}, and what a human triaging at 3 a.m. reads. Four copies
 * of it is four chances for one client's failures to start reading differently from the others'
 * without anybody choosing that.
 *
 * <p>WHAT DELIBERATELY DID NOT MOVE HERE. The three {@code pause()} methods look like a fourth
 * duplicate and are not: each names its own wait in the message it throws ("between connect attempts
 * to …", "between retries", "between attempts at …") and reads its own delay, and those messages are
 * the only record of WHICH wait was interrupted. Nor did the {@code retrying()} log lines, whose
 * prefixes ({@code [runner]}, {@code [source]}, {@code [llm]}) are what an operator greps. Sameness of
 * shape is not sameness of meaning, and this package is where that distinction is expensive.
 */
final class Failures {

    /**
     * What a socket failure reads as when it carries no message of its own — because a throw with
     * nothing quotable still has to say something a human can act on.
     */
    static final String NOTHING_TO_SAY = "the call failed with no message";

    private Failures() {
    }

    /**
     * A throwable as one line of {@code infra_reason}.
     *
     * <p>THE CLASS NAME IS ALWAYS THERE, and that is the decision this method carries. A bare
     * {@code getMessage()} on a {@code ConnectException} is {@code "Connection refused"} with nothing
     * saying to what or of what kind, and half the failures on this path — connect timeouts, resets,
     * TLS handshakes — are distinguished only by their type. A null or blank message is the shape that
     * writes an empty reason onto a marker, which reads as a marker nobody could explain.
     */
    static String cause(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Is this a duration a caller actually asked for?
     *
     * <p>Null, zero and negative all mean "not configured" and all fall back to the client's own
     * default. Zero especially: it is what an unset {@code fsm.runner.timeout} binds to, and a zero
     * wall clock on a prove would fail every build at the moment it started.
     */
    static boolean positive(Duration d) {
        return d != null && !d.isZero() && !d.isNegative();
    }
}
