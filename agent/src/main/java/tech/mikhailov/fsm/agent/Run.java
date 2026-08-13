package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WHAT IS TRUE ABOUT THE RUN, ONCE, FOR BOTH THINGS THAT ASK.
 *
 * <p>The dashboard is being replaced by a React zone reading JSON. For as long as both exist — which
 * is the whole port, not a moment of it — there are two readers of the same files, and a rule copied
 * into the second one drifts from the first without either failing.
 *
 * <p>The rule that matters most here is that THE STATE ON SCREEN IS NOT THE STATE IN THE FILE. A
 * settlement row saying {@code proving} only means a prove once started: the row outlives a container
 * replaced under it, and every interrupted marker then reads as busy forever. The claim directory is
 * the fact — a prover takes one before it works — so:
 *
 * <ul>
 *   <li>{@code proving} with no claim is {@code interrupted}, and nobody should wait on it;
 *   <li>queued WITH a claim is {@code proving}: taken seconds ago, before the first stage reported;
 *   <li>everything else is what the last non-{@code proving} settlement row says.
 * </ul>
 *
 * <p>A client shown the raw state would be wrong about both ends of that, so this resolves it here
 * and the API sends the answer rather than the ingredients.
 */
final class Run {

    private Run() {
    }

    /** One marker, as both the old page and the new API see it. */
    record Row(String key, String state, boolean hasSettlement) {
    }

    /**
     * Every marker the run was given, in the order it was given them.
     *
     * <p>ORDER IS LOAD-BEARING. The queue is a file somebody wrote and diffs against; a table sorted
     * by state throws that away and cannot be compared with the run before it. Queue order first,
     * then any settled key the queue has never heard of — results already recorded may name markers
     * a replaced queue does not.
     */
    static Map<String, Row> rows(Path settlements, List<String> queued) {
        Map<String, String> latest = lastSettlementByMarker(settlements);
        Set<String> claimed = claims(settlements);

        Map<String, Row> all = new LinkedHashMap<>();
        for (String marker : queued) {
            String key = marker.trim();
            if (!key.isEmpty()) {
                all.put(key, new Row(key, "queued", false));
            }
        }
        latest.forEach((key, state) -> all.put(key,
                new Row(key, resolve(state, claimed.contains(Supervisor.slug(key))), true)));
        // Claimed but no row yet: taken seconds ago, before the first stage reported.
        for (String marker : queued) {
            String key = marker.trim();
            Row row = all.get(key);
            if (row != null && row.state().equals("queued") && claimed.contains(Supervisor.slug(key))) {
                all.put(key, new Row(key, "proving", row.hasSettlement()));
            }
        }
        return all;
    }

    /** The rule, in one place, so the page and the API cannot disagree about it. */
    static String resolve(String state, boolean isClaimed) {
        if (state.equals("proving")) {
            return isClaimed ? "proving" : "interrupted";
        }
        return state;
    }

    /** Settled means DECIDED. Three states are not an answer; `infra` counts, because a prove
     *  that threw has stopped even though it decided nothing. */
    static boolean isSettled(String state) {
        return !state.equals("proving") && !state.equals("queued") && !state.equals("interrupted");
    }

    static int settled(Map<String, Row> rows) {
        return (int) rows.values().stream().filter(r -> isSettled(r.state())).count();
    }

    /** What the run is currently working on, which is what a claim directory means. */
    static Set<String> claims(Path settlements) {
        Set<String> claimed = new HashSet<>();
        Path claims = settlements.resolveSibling("claims");
        if (Files.isDirectory(claims)) {
            try (var c = Files.list(claims)) {
                c.forEach(p -> claimed.add(p.getFileName().toString()));
            } catch (IOException none) {
                // No claims directory is a run that has not started, not an error.
            }
        }
        return claimed;
    }

    /**
     * The last state each marker settled on, ignoring `proving` rows after it.
     *
     * <p>A lane writes a row per stage boundary, so the file holds `proving` several times before it
     * holds an answer; the answer is the last row that is not `proving`.
     */
    private static Map<String, String> lastSettlementByMarker(Path settlements) {
        Map<String, String> latest = new LinkedHashMap<>();
        for (String line : Dashboard.lines(settlements)) {
            String key = Dashboard.field(line, "suspicion_key");
            String state = Dashboard.field(line, "state");
            if (key.isEmpty() || state.isEmpty()) {
                continue;
            }
            if (state.equals("proving") && latest.containsKey(key)) {
                continue;
            }
            latest.put(key, state);
        }
        return latest;
    }
}
