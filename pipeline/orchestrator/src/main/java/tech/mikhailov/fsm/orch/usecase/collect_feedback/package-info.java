/**
 * KEEP WHAT A PROVE WAS GIVEN AND WHAT IT PRODUCED, PLUS WHAT A HUMAN SAID ABOUT IT — the record
 * {@code train_prompts} is meant to read.
 *
 * <p>ONE TYPE OF THIS USE CASE IS IN THE INNER CIRCLE, and it is
 * {@link tech.mikhailov.fsm.orch.usecase.collect_feedback.FeedbackJournal}: the port
 * {@code try_prove} appends a settled prove's trace through. It is a port rather than a static because
 * whether a run records anything is a DEPLOYMENT fact — a flag and a path — and a deployment fact
 * reached through a static is one no test can vary.
 *
 * <p>THE REST OF THIS USE CASE IS OUTER-CIRCLE AND BELONGS THERE. Every remaining piece holds a file,
 * a datasource or an HTTP endpoint, so bringing any of it in here would break the rule that makes this
 * circle testable at all:
 * <ul>
 *   <li>{@code orch.feedback.FeedbackStore} — the adapter. One locked, fsynced, newline-terminated
 *       append per settled prove, to a file that accumulates ACROSS runs and outlives the database.</li>
 *   <li>{@code orch.feedback.CritiqueIndex} — the read path, which folds that file into the counts the
 *       dashboard shows without re-parsing a line twice.</li>
 *   <li>{@code orch.feedback.RecordSink} — the seam that lets a test make one append fail and the next
 *       succeed, so an answer about a record cannot be inferred from a process-wide counter.</li>
 *   <li>{@code orch.comment} — the HUMAN half: the endpoint, the row, the journal and the read model
 *       for a reviewer's verdict on a marker. That verdict is the LABEL; without it the file is a
 *       transcript and not training data.</li>
 * </ul>
 *
 * <p>SO IT IS NOT SCATTERED, WITH ONE EXCEPTION THAT IS WORTH NAMING. The split above is the
 * dependency rule doing its job: one port in, adapters out. The exception is that this use case has NO
 * INTERACTOR. The machine half needs none — appending IS the whole policy, and the port carries it.
 * The human half has one, and it is {@code orch.comment.CommentService}: the three rules that decide
 * whether a verdict is accepted at all — nothing unbounded is stored, a comment on a marker that does
 * not exist is refused, an empty comment is not a comment — are policy, and they sit in a Spring bean
 * where they cannot be exercised without a context. Moving them here is a real refactor with real
 * behaviour at stake. It is not a rename, so it is not done here.
 */
package tech.mikhailov.fsm.orch.usecase.collect_feedback;
