/**
 * READ THE LABELLED TRIALS AND IMPROVE THE INSTRUCTIONS THAT PRODUCED THEM. THIS PACKAGE IS EMPTY.
 *
 * <p>IT IS EMPTY ON PURPOSE, AND THE EMPTINESS IS THE HONEST PART. The owner names three use cases;
 * two of them run. This one is named in the design, is named by
 * {@code tech.mikhailov.fsm.trial.Trial.Labelled} as the shape it produces examples for, and has no
 * code anywhere in the reactor. A directory that says so is worth more than a package that quietly
 * does not appear in the listing, and far more than an optimiser written before the three conditions
 * below are met.
 *
 * <p>WHAT IT NEEDS BEFORE IT CAN HOLD ANYTHING. None of the three is code that belongs in here; all
 * three are facts the rest of the system has to start carrying.
 *
 * <ul>
 *   <li>A RECOVERABLE TEMPLATE. A training pass rewrites the TEMPLATE, and the record keeps the
 *       RESOLVED prompt beside the marker's own contribution to it. The template itself is a file on
 *       the deployment's disk, and the only thing a trial carries about which wording produced it is a
 *       stage stamp from {@code orch.Versions} — hand-written prose a person must remember to bump. Two
 *       trials can therefore carry the same stamp and have run against different files, so "the
 *       template as it stood for this trial" is not reconstructable from the record. A content hash of
 *       the resolved template, stamped at the point the prompt is built, is what closes this.</li>
 *   <li>A DECLARED REWARD SIGNAL. Nothing in the reactor names one. There are scores
 *       ({@code realness_score}, {@code test_score}), machine critique kinds, and human comments — three
 *       different opinions about a prove, on three different scales, none of them declared as the thing
 *       an optimiser maximises. Picking one silently inside an optimiser is how a loop comes to agree
 *       with itself: the objective has to be written down, and argued, before anything hill-climbs it.</li>
 *   <li>A HELD-CONSTANT ENVIRONMENT. Two runs of one marker reach live source hosting, live model
 *       endpoints and a real build; a score that moves between them says nothing about the wording that
 *       changed in between. The engine's own stages are pinned as pure functions against recorded
 *       catalogues, which is exactly the property a training pass needs and exactly the property a
 *       whole prove lacks. Until a candidate prompt can be scored against held-constant inputs, a
 *       measured improvement and an afternoon of flaky infrastructure are the same number.</li>
 * </ul>
 *
 * <p>UNTIL ALL THREE HOLD, an optimiser placed here would be measuring noise against a template it
 * cannot identify, using an objective nobody declared.
 */
package tech.mikhailov.fsm.orch.usecase.train_prompts;
