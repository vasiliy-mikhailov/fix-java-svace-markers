/**
 * THE THREE THINGS THIS SYSTEM DOES. ONE SUBPACKAGE EACH, AND NOTHING ELSE AT THIS LEVEL.
 *
 * <ul>
 *   <li>{@code try_prove} — take one claimed marker, ask the engine whether the defect is real, and
 *       write down what came of it. Thirteen types: one interactor, three steps the framework drives
 *       at its own seams, four output boundaries, three ports and two values.</li>
 *   <li>{@code collect_feedback} — keep what a prove was given and what it produced, so a later pass
 *       has something to learn from. One port here; the adapters and the human half are named in that
 *       package, with the reason they stay outside.</li>
 *   <li>{@code train_prompts} — read the labelled trials and improve the instructions that produced
 *       them. EMPTY. That package states the conditions it needs before it can hold anything.</li>
 * </ul>
 *
 * <p>THE COUNT IS THE POINT. A reader opening this directory sees the three use cases and can name
 * them without opening a file. Everything else is one of their supporting casts, and which use case a
 * type serves is the directory it is in.
 *
 * <p>NO FRAMEWORK REACHES IN. Together with {@code orch.domain} this is the inner circle: these
 * packages see the JDK, the pipeline's state vocabularies, each other, and nothing else. That is what
 * makes every rule in them reachable from a JUnit table with no Spring context, no datasource and no
 * clock — and it is a rule with a test behind it rather than an intention, in
 * {@code TheInnerCirclesDependOnNoFrameworkTest}, which walks this whole tree.
 */
package tech.mikhailov.fsm.orch.usecase;
