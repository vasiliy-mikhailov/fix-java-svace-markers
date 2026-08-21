/**
 * THE THREE STATES OF A THING BEING READ, AND THIS FILE IS NOW THE LIBRARY'S.
 *
 * It went to `ratchet-ui` in 0.3.0 unchanged: same props, same order of tests, same sentences. Both
 * dashboards had written it and the two versions differed in nothing but the name of one private
 * padding constant — which is the narrowest case for sharing there is, and the reason this one costs
 * no call site anything.
 *
 * FAILURE IS TESTED BEFORE EMPTINESS, THERE AS HERE. A read that failed and a read that returned
 * nothing are different facts, and a component that checks emptiness first reports every failure as
 * "there is nothing here" — which is the one sentence guaranteed to stop a reader looking.
 */
export { Loaded, type LoadedProps } from 'ratchet-ui/components'
