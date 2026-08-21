/**
 * A PARAGRAPH EXPLAINING WHAT A CONTROL DOES — NOW THE LIBRARY'S, AND REPAINTED.
 *
 * THE NAME, THE SHAPE AND `quiet` ARE OURS; EVERY NUMBER IS THE SIBLING'S. We had fourteen call
 * sites of a component called `Account`; the sibling had the same paragraph typed out inline four
 * times and had never named it. Under the rule this release runs on, naming it is our contribution
 * and the metrics are theirs — 13px against our 15.2px, a 1.6 line against 1.7, a 72ch measure
 * against 52em, and no colour at all against secondary.
 *
 * SO THIS IS THE ONE ADOPTION THAT REPAINTS A PAGE, and the page is settings. It is not reversible
 * by a prop: if these metrics are wrong for us the answer is one line in the shared file, argued
 * upstream. It is step two of the prescribed order for exactly that reason — one commit to revert.
 *
 * The prose still stays in the component tree and never goes on the wire.
 */
export { ACCOUNT, ACCOUNT_QUIET, Account, type AccountProps } from 'ratchet-ui/components'
