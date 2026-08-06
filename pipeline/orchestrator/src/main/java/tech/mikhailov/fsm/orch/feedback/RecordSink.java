package tech.mikhailov.fsm.orch.feedback;

import java.nio.file.Path;
import java.util.Map;

/**
 * Somewhere a diagnostic record can be appended, and an answer about THAT record.
 *
 * <p>WHY THIS EXISTS AT ALL, since {@link FeedbackStore} is the only implementation that ships. It is
 * the seam the concurrency defect needed and did not have. {@code CommentJournal.write} used to decide
 * what to tell a person by reading the store's shared failure COUNTER either side of the call:
 *
 * <pre>
 *   long before = store.failures();
 *   store.append(event);
 *   return store.failures() == before ? WRITTEN : FAILED;
 * </pre>
 *
 * <p>That is not an answer about the caller's record, it is an answer about the process. Two comments
 * landing together, one of them failing, each read the other's increment: the person whose comment was
 * LOST got the green tick and the person whose comment was SAVED was told it failed. The fix is one
 * line — {@code append} answers per call — but nothing could TEST it, because {@code FeedbackStore} is
 * final, writes to a real file, and fails only when the filesystem does. A test cannot ask a real disk
 * to fail one caller and serve the next.
 *
 * <p>So the journal depends on this instead, and a test supplies a sink that fails exactly the records
 * it chooses. That is the whole justification: not "an interface is good design", but that the defect
 * being fixed was unobservable without one — a fix nothing can prove is a fix nobody can keep.
 */
public interface RecordSink {

    /** Whether anything is being recorded at all; {@code false} is a configuration, not a failure. */
    boolean enabled();

    /**
     * Where the records go, so an answer can NAME the file and an operator can go and read it.
     *
     * <p>On the seam because a caller told "not recorded" needs somewhere to look, and a path in the
     * response is the difference between a report and an investigation.
     */
    Path path();

    /**
     * Append one record.
     *
     * @return whether THIS record reached the journal. Never throws — a diagnostic must not strand
     *     the marker it is describing, which is why the answer is a return value and not an exception.
     */
    boolean append(Map<String, Object> record);
}
