package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * THE CONVERSATION WITH THE SUPERVISOR AS JSON, FOR A CLIENT THAT RENDERS IT SOMEWHERE ELSE.
 *
 * <p>The page this replaces is {@code Dashboard.chat}. It reads the same three things — the turns,
 * the flag, and the partial answer being written right now — and the shape here follows the one
 * {@link Api} set: a manual StringBuilder, {@code Settlement.escape} on every string, and facts
 * rather than the sentences the page made out of them.
 *
 * <p>WHAT IS NOT SENT is the point of most of it. {@code Dashboard.ago} turns a timestamp into "4m
 * ago" and the panel's own rule turns the same fact into "quiet 4m"; both are recomputed on every
 * render because the conversation is read while it moves. A client sent "4m ago" has a clock that
 * has stopped, so this sends the epoch millis and {@code serverNow} to measure them against — the
 * one resolution the browser cannot do for itself, because its own clock may be minutes out.
 *
 * <p>Nor is the text cut down. The page shows the last {@code LIVE_TAIL} characters of a partial
 * answer and links marker names inside a recorded one; both are decisions about drawing, and a
 * client sent the trimmed text cannot undo them.
 */
final class ApiChat {

    private ApiChat() {
    }

    /**
     * {@code GET /api/chat} — everything said, whether an answer is coming, and the partial one.
     *
     * <p>Every turn, oldest first, in the order the file was appended in. Not the last
     * {@link Chat#KEEP}: that cut belongs to the prompt, which drops the oldest turns because they
     * are about markers that have since settled. The page is not the prompt, and a reader scrolling
     * back for what they asked half an hour ago must find it.
     */
    static String chat(Path results) {
        // ANSWERING IS READ FIRST AND ONCE. It is a process-wide flag another thread owns, and
        // Chat.unanswered reads it again a moment later; between the two reads an answer can land.
        // The page could only ever show one tail because it asked in an if/else — over JSON both
        // booleans travel, so the client must resolve them in the same order: answering wins, and
        // unanswered is the question only when nothing is being written.
        boolean answering = Chat.answering();
        boolean unanswered = Chat.unanswered(results);

        StringBuilder b = new StringBuilder("{\"turns\":[");
        boolean first = true;
        for (Chat.Turn turn : Chat.turns(results)) {
            if (!first) {
                b.append(',');
            }
            first = false;
            // 0 IS A RECORD WITH NO TIMESTAMP, AND IT IS NOT NOW. The page omits the whole "· 4m
            // ago" span for it rather than falling back to the clock; a client that renders it as a
            // date draws 1970, and one that substitutes now dates an old turn to this second.
            b.append("{\"at\":").append(turn.at());
            // WHO AS RECORDED, not the side it renders on. `mine` is not a stored fact — it is
            // who.equals("you") — and anything else, including a value nobody planned for, is the
            // supervisor. Deciding that here would put it in Java, where it was never written down.
            b.append(",\"who\":").append(quote(turn.who()));
            // The whole text, with the author's own line breaks inside it. It is rendered pre-wrap
            // and Chat.answer already stripped the ends, so what is left is the model's.
            b.append(",\"text\":").append(quote(turn.text()));
            b.append('}');
        }
        b.append(']');
        b.append(",\"answering\":").append(answering);
        // THE STATE THAT LOOKS LIKE THE OTHER ONE. A question with nothing under it and nothing
        // running means the dashboard was restarted mid-answer, which happens on every deploy. Sent
        // as its own flag because a client that folds it into "answering" waits forever for a reply
        // that is not coming.
        b.append(",\"unanswered\":").append(unanswered);
        // The server's clock, so elapsed can be computed without trusting the browser's.
        b.append(",\"serverNow\":").append(System.currentTimeMillis());
        b.append(",\"live\":").append(live(Chat.live(results)));
        return b.append('}').toString();
    }

    /**
     * The partial answer as the streaming client writes it: first line the agent, second the epoch
     * millis of the last write, the rest the text.
     *
     * <p>NULL RATHER THAN A GUESS, and the parse is all-or-nothing on purpose. The file is being
     * rewritten while it is read, so a read can land mid-write and see one line where three are
     * coming; that is a stream that has not got there yet, not an error, and the next poll is
     * seconds away. Absent is the same answer — a run that started thirty seconds ago has no file.
     *
     * <p>Present does not mean live. Chat.answer deletes this when it records the answer properly,
     * so a partial left behind is one an interrupted dashboard never finished — which is why the
     * client draws it only while {@code answering}, and reads {@code unanswered} otherwise.
     */
    private static String live(Path file) {
        if (!Files.isReadable(file)) {
            return "null";
        }
        String agent;
        long at;
        String text;
        try {
            String all = Files.readString(file);
            int one = all.indexOf('\n');
            int two = one < 0 ? -1 : all.indexOf('\n', one + 1);
            if (two <= 0) {
                return "null";
            }
            agent = all.substring(0, one);
            at = num(all.substring(one + 1, two));
            text = all.substring(two + 1);
        } catch (IOException | RuntimeException unreadable) {
            // Being rewritten as we read it. The next poll is seconds away.
            return "null";
        }
        // THE WHOLE PARTIAL, not the last four thousand characters the page shows. Taking the end
        // is right — a reasoning turn runs to tens of thousands of characters and opening on the
        // beginning shows the same paragraph for four minutes — but it is a decision about how much
        // fits on a screen, and the screen is the only thing that knows.
        return "{\"agent\":" + quote(agent) + ",\"at\":" + at + ",\"text\":" + quote(text) + "}";
    }

    /** A field that should be a number, or 0 — a malformed one must not take the document down. */
    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    /**
     * A JSON string, escaped by the one escaper this program has.
     *
     * <p>EVERY STRING GOES THROUGH THIS ONE. A turn is a whole question or a whole reply and the
     * partial is raw model output: quotes, newlines and tabs are the normal contents, not the edge
     * case. One unescaped value does not corrupt a field — it ends the document early, and the
     * screen is blank.
     */
    private static String quote(String s) {
        return "\"" + Settlement.escape(s == null ? "" : s) + "\"";
    }
}
