/**
 * THE PROGRESS BAR, FROM `ratchet-ui`.
 *
 * Identical markup, identical ARIA down to `aria-label="settled"`, identical deliberate
 * non-clamping. The entire difference between the two versions was the gradient's two token names,
 * and `--state-progress-from` / `--state-progress-to` in `domain.css` restore them — so the call
 * site changes by zero characters and the bar renders the same colours it always did.
 */
export { ProgressBar, type ProgressBarProps } from 'ratchet-ui/components'
