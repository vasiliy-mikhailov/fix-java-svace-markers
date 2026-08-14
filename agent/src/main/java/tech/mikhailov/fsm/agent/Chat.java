package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ASKING THE SUPERVISOR SOMETHING, AND BEING ANSWERED BY THE DASHBOARD.
 *
 * <p>The watcher is a loop in another process: it wakes on a timer, reads the whole run, reports
 * what it finds and sleeps again. Posting a question into that loop would mean waiting up to a
 * quarter of an hour for a reply, and a conversation with a fifteen-minute floor is not one.
 *
 * <p>So the answer is produced HERE, in the dashboard's own process, by an agent built from the same
 * prompt over the same digest with the same read-only tools. Nothing is shared with the watcher but
 * the results directory, which is what both of them are actually looking at. The watcher is
 * untouched — it does not know this exists, and a question cannot make it miss a pass.
 *
 * <p>ONE AT A TIME. Not for correctness — two answers would interleave in nothing, since each
 * appends a whole line — but because the second question would be asked without the first one's
 * answer in front of it, and a conversation where the replies arrive out of order reads as a model
 * that has lost the thread. A person who asks again while one is in flight is told to wait.
 *
 * <p>THE QUESTION IS WRITTEN DOWN BEFORE THE ANSWER IS ATTEMPTED, so a dashboard that dies mid-reply
 * leaves a record of what was asked rather than nothing at all. The page can then see a question
 * with no answer under it and say so, which is the honest state.
 */
final class Chat {

    /**
     * How many earlier turns go back to the model with a new question.
     *
     * <p>The digest is already the large part of this prompt — three hundred markers, several tens
     * of kilobytes — and it is rebuilt fresh every time because the run moves. Twenty turns is a
     * long conversation about one run; beyond that the oldest of it is about markers that have since
     * settled, which is worse than absent because it reads as current.
     */
    static final int KEEP = 20;

    /** True while an answer is being written. Process-wide: there is one dashboard. */
    private static final AtomicBoolean ASKING = new AtomicBoolean();

    private Chat() {
    }

    /** One thing said, by one side. */
    record Turn(long at, String who, String text) {

        boolean mine() {
            return "you".equals(who);
        }
    }

    /** Where the conversation lives, beside the run it is about. */
    static Path where(Path results) {
        return results.resolve("chat.jsonl");
    }

    /** The live partial answer, written by the streaming client as tokens arrive. */
    static Path live(Path results) {
        return results.resolve("chat-trace.jsonl.live");
    }

    static boolean answering() {
        return ASKING.get();
    }

    /** Everything said so far, oldest first. */
    static List<Turn> turns(Path results) {
        List<Turn> said = new ArrayList<>();
        Path file = where(results);
        if (!Files.isReadable(file)) {
            return said;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                String who = Json.field(line, "who");
                if (who.isBlank()) {
                    continue;
                }
                said.add(new Turn(num(Json.field(line, "at")), who, Json.field(line, "text")));
            }
        } catch (IOException | RuntimeException unreadable) {
            // A conversation that cannot be read is shown as empty rather than as an error page.
            // The file is a record, not a mechanism: nothing downstream depends on it.
            return said;
        }
        return said;
    }

    /**
     * Whether the last thing said was a question nobody answered.
     *
     * <p>Which happens when the dashboard was restarted mid-reply — the container is redeployed
     * often and an answer takes minutes. Distinguishing it from "still thinking" is the difference
     * between a page that says wait and a page that waits forever.
     */
    static boolean unanswered(Path results) {
        List<Turn> said = turns(results);
        return !said.isEmpty() && said.get(said.size() - 1).mine() && !answering();
    }

    /**
     * Records the question and starts answering it.
     *
     * @return what to tell the person now, in the words they will read
     */
    static String ask(Path results, String question) {
        String asked = question == null ? "" : question.strip();
        if (asked.isEmpty()) {
            return "";
        }
        if (!ASKING.compareAndSet(false, true)) {
            return "still answering the last one";
        }
        try {
            append(results, "you", asked);
        } catch (IOException notRecorded) {
            ASKING.set(false);
            return "could not write the question down: " + notRecorded.getMessage();
        }
        // A DAEMON, so a reply in flight cannot keep the container up when the dashboard is told to
        // stop. What is lost is one answer, and the question it belongs to is already on disk.
        Thread answering = new Thread(() -> answer(results, asked), "chat");
        answering.setDaemon(true);
        answering.start();
        return "";
    }

    /**
     * THE ANSWER, AND EVERY WAY IT CAN FAIL WRITTEN DOWN AS THE ANSWER.
     *
     * <p>A thrown exception here would leave a question with nothing under it and a flag stuck on,
     * so the page would say "still answering" for the rest of the process's life. Every outcome
     * becomes a turn, including the ones that are somebody's configuration being wrong — which is
     * the case a person most needs to see, because it is the one they can fix.
     */
    /**
     * A FAILURE SAID TO A PERSON, WITH THE STACK KEPT WHERE STACKS BELONG.
     *
     * <p>This used to be {@code "could not answer: " + failed}, so a question got back
     * {@code could not answer: java.lang.IllegalArgumentException: text cannot be null or blank} —
     * a sentence with no subject, naming a class the reader does not have and a parameter called
     * "text" that belongs to neither their question nor this program. It told them nothing they could
     * do, and it hid what had actually gone wrong: a tool had read an empty file.
     *
     * <p>The class name is not deleted, it is MOVED. The reader gets a sentence and is told where the
     * detail is; {@code chat-trace.jsonl} gets the exception, which is the file somebody debugging
     * would open anyway. The two audiences are different and were being served one string.
     */
    private static String broke(Path results, Throwable failed) {
        try {
            new JsonlTrace(results.resolve("chat-trace.jsonl"),
                    results.resolve("chat-settlements.jsonl"), "chat").failed("chat", failed);
        } catch (RuntimeException | Error notRecorded) {
            // The reply below is still owed to the person waiting for it.
        }
        return "I could not answer that one — something failed while I was working on it, and the "
                + "question is still on the record above. Asking again is worth a try; if it keeps "
                + "failing, the reason is in chat-trace.jsonl.";
    }

    private static void answer(Path results, String question) {
        String reply;
        try {
            JsonlTrace trace = new JsonlTrace(results.resolve("chat-trace.jsonl"),
                    results.resolve("chat-settlements.jsonl"), "chat");
            // NO CHECKOUT AND NOTHING THAT BUILDS, for the reason the supervisor has neither: an
            // agent that can run the tests can manufacture the evidence it is describing.
            Agents agents = new Agents(results, trace, (phase, test) -> new Runner.Result(true, false,
                    "the supervisor does not build; it reads what the provers built"));
            reply = agents.chat(results).run(prompt(results, question));
            // STRIPPED, because the answer is rendered pre-wrap so the model's own line breaks
            // survive — and this one opens with two of them, which renders as a reply that starts
            // an inch below its own name. Inside the text they are the author's; at the ends they
            // are an artefact of how it was generated.
            reply = reply == null ? "" : reply.strip();
            if (reply.isEmpty()) {
                reply = "(nothing came back — the model answered with silence)";
            }
        } catch (RuntimeException | Error failed) {
            // Error too: an OutOfMemoryError from a large digest would otherwise take the flag with
            // it and wedge the page.
            reply = broke(results, failed);
        }
        try {
            append(results, "supervisor", reply);
        } catch (IOException notRecorded) {
            // Nothing left to tell them with. The flag still has to come off.
        } finally {
            ASKING.set(false);
            // The partial is a copy of what is now recorded properly, and leaving it makes the page
            // show the last answer as though it were still arriving.
            try {
                Files.deleteIfExists(live(results));
            } catch (IOException stale) {
                // It is only a display artefact; the recorded turn is the truth.
            }
        }
    }

    /** The run, then the conversation, then what was just asked. */
    private static String prompt(Path results, String question) {
        String digest = new Overwatch(results, null, null).digest();
        StringBuilder b = new StringBuilder();
        b.append(digest.isBlank()
                ? "Nothing has run yet — there are no markers to report on.\n"
                : "The run as it stands:\n\n" + digest + "\n");
        List<Turn> said = turns(results);
        // The question just recorded is the last turn; it is asked below rather than twice.
        List<Turn> earlier = said.subList(Math.max(0, said.size() - 1 - KEEP),
                Math.max(0, said.size() - 1));
        if (!earlier.isEmpty()) {
            b.append("\n---\n\nWhat has been said so far:\n\n");
            for (Turn turn : earlier) {
                b.append(turn.mine() ? "THEY ASKED: " : "YOU ANSWERED: ").append(turn.text())
                        .append("\n\n");
            }
        }
        return b.append("\n---\n\nTHEY ASK: ").append(question).toString();
    }

    private static void append(Path results, String who, String text) throws IOException {
        String line = "{\"at\":\"" + System.currentTimeMillis() + "\",\"who\":\"" + who
                + "\",\"text\":\"" + Settlement.escape(text) + "\"}\n";
        Files.writeString(where(results), line, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
