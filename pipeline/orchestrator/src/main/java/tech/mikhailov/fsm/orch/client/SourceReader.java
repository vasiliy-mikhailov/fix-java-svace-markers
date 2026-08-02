package tech.mikhailov.fsm.orch.client;

import java.util.Map;

/**
 * ONE FILE OUT OF THE RUNNER'S READ-ONLY CHECKOUT — the {@code /fs/read_file} half of the runner, as a
 * seam.
 *
 * <p>WHY IT IS AN INTERFACE AND NOT AN HTTP CALL INLINE. The runner serves two things: it proves markers
 * and it serves the source a reviewer is shown. Those move together — when the prove runs in this
 * process, the read has to as well. It used to be an {@code HttpTransport} call written straight into
 * {@code SourceWindowService}, which independently re-derived the runner's base URL from the same
 * property. That is a SECOND copy of the address, in a class whose failure mode is silent: a source
 * window pointed at a container that is not in the stack renders "source unavailable" on every marker,
 * with nothing red anywhere and every verdict still correct. So the choice is made once, in
 * {@code ClientConfig}, and both halves are chosen together.
 *
 * <p>IT MUST BE THE SAME CHECKOUT THE PROVE RAN IN, which is the whole reason this reads from the runner
 * rather than from GitHub: the window shows the code that was actually JUDGED, at the tree the test was
 * anchored against, and it costs no API rate limit.
 */
public interface SourceReader {

    /**
     * Read one file.
     *
     * @param body {@code {repo, branch, path}} — the runner's own request shape, passed through so the
     *             two implementations cannot drift in what they ask for
     * @return the runner's reply document in the engine's value shape. A path that escapes the repo, a
     *         file that is not there and a clone that failed all come back as {@code {"error": …}} —
     *         they are ANSWERS about this marker, and the caller renders them in the tab.
     * @throws Exception only when the read could not be attempted at all: the runner is unreachable, the
     *                   socket died, the reply was not JSON. Broad on purpose — the caller turns every
     *                   one of them into the same "source unavailable" line rather than branching, and a
     *                   narrower signature would make the in-process implementation lie about which
     *                   failures it can have.
     */
    Object read(Map<String, Object> body) throws Exception;

    /**
     * Where the read goes, for the "source unavailable — …" line and the start-up log.
     *
     * <p>Never a credential: this string reaches a browser and a log an operator pastes into a ticket.
     */
    String describe();
}
