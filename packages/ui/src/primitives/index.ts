/**
 * THE PRIMITIVES: everything the dashboard is built from that does not know what a marker is.
 *
 * A `Pill` takes a tone, not a disposition. A `TabRow` takes hrefs, not a marker key. That line is
 * the reason these are testable at all — the Java's equivalents each reached for one particular
 * page's URLs or one particular payload's vocabulary, so the same markup was written three times
 * and the three copies drifted (`tab()` ended with zero callers while `supervisorTabs()` and
 * `settingsTabs()` hand-rolled their own anchors, and only one of the three got its lit-tab bug).
 *
 * Anything that has to know what `by-design` means lives in `../domain`.
 */

export { Account, type AccountProps } from './Account'
export { CodeBlock, type CodeBlockProps } from './CodeBlock'
export { DiffBlock, type DiffBlockProps } from './DiffBlock'
export { Disclosure, type DisclosureProps } from './Disclosure'
export { EmptyNote, type EmptyNoteProps } from './EmptyNote'
export { Loaded, type LoadedProps } from './Loaded'
export { LabeledField, type LabeledFieldProps } from './LabeledField'
export { Pill, type PillProps, type PillTone } from './Pill'
export { Prose, type ProseProps } from './Prose'
export { ProgressBar, type ProgressBarProps } from './ProgressBar'
export { RelativeTime, type RelativeTimeProps } from './RelativeTime'
export { SaveRow, type SaveRowProps } from './SaveRow'
export { SecretField, type SecretFieldProps } from './SecretField'
// THE BAR AT THE TOP OF A PAGE, AND IT IS NOT THE SIBLING'S `TabRow` — that name belongs there
// to an underline row INSIDE a page. Two roles, one name, and only the page-top bar had a pair,
// so the shared one is `SectionTabs` and ours is renamed with it. `items`/`on` become
// `tabs`/`current`, `label` is new and required, and it is the `aria-label` a page with more
// than one nav in it needs.
export { SectionTabs, type SectionTab, type SectionTabsProps } from 'ratchet-ui/components'
// A POST, A LANDING, AND FOUR STATES INSTEAD OF TWO. Not a component — it is here because the
// line these entry points draw is React, not JSX.
export { NO_REASON, REQUEST_FAILED, useAsk, type Ask, type AskHow, type Landing } from 'ratchet-ui/components'
export { Tally, type TallyProps } from './Tally'
export { TextFold, type TextFoldProps } from './TextFold'
export type { Style } from './style'
