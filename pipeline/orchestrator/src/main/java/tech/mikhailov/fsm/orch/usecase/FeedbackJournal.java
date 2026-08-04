package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.orch.domain.ProveTrace;

/**
 * Where a run's prompts and replies accumulate, when a deployment asked for them.
 *
 * <p>A PORT, because whether a run records critiques is a DEPLOYMENT fact — a flag and a path — and a
 * deployment fact reached through a static is one no test can vary. It is also the reason the method
 * cannot throw: every state a marker can reach must still reach a settled row, and a diagnostic is
 * never allowed to strand one. An implementation that cannot write swallows and says so in the log.
 */
public interface FeedbackJournal {

    /** Keep this prove's record, or do nothing when the trace is empty because nothing was kept. */
    void append(ProveTrace trace);
}
