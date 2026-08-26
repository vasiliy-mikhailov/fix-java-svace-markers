/**
 * THE DOMAIN TIER: everything that knows what a marker is.
 *
 * The line between this barrel and `../primitives` is the one the primitives' own note draws — a
 * `Pill` takes a tone, a `StateBadge` takes a `MarkerState`. Anything here may name a state, a
 * verdict, a checker or an agent; nothing in `../primitives` may.
 *
 * WRITTEN LAST, AND DELIBERATELY SO. Seven passes wrote these components at once, and a barrel is
 * the one file they would all have had to edit — so it was left until every component existed
 * rather than merged seven ways. That is also why the exports are named rather than `export *`:
 * an ambiguous star re-export is dropped silently under ESM, so the one real name collision in the
 * tier (see `FlaggedSourceRecord` below) would have removed BOTH names from the public surface and
 * the only symptom would have been an import failing in some other package.
 */

export { AgentAnswer, type AgentAnswerProps } from './AgentAnswer'
export {
  AgentGroupHeading,
  groupOf,
  type AgentGroup,
  type AgentGroupHeadingProps,
} from './AgentGroupHeading'
export { AgentPending, type AgentPendingProps } from './AgentPending'
export { AgentPromptEditor, type AgentPromptEditorProps } from './AgentPromptEditor'
export { AskBox, type AskBoxProps } from './AskBox'
export { AskNotice, type AskNoticeProps } from './AskNotice'
export { BuildOutcomes, type BuildOutcome, type BuildOutcomesProps } from './BuildOutcomes'
export {
  AgentChip,
  ChainStage,
  ChainStrip,
  STAGES,
  type AgentChipProps,
  type ChainAgent,
  type ChainStageProps,
  type ChainStripProps,
  type Stage,
  type StageName,
} from './ChainStrip'
export { ChatTranscript, type ChatTranscriptProps } from './ChatTranscript'
export { ChatTurn, type ChatTurnData, type ChatTurnProps } from './ChatTurn'
export { ClaimCard, type ClaimCardProps } from './ClaimCard'
// HOW LONG THE MACHINE TOOK, NOW THE LIBRARY'S — and on a subpath that reaches no React at all,
// so a server or a test can import it without resolving a React version. `duration` ROUNDS where
// our `clock` truncated: 59.6 seconds reads `1m` rather than `59s`, which is what every clock
// beside it already said, and an exact ten minutes drops the `0s`.
export { duration, spellMinutes } from 'ratchet-ui/time'
export { EventFeed, type EventFeedProps } from './EventFeed'
export { FindingCard, type FindingCardProps, type FindingRecord } from './FindingCard'
export { FindingsButton, type FindingsButtonProps } from './FindingsButton'
export { FindingTally, type FindingTallyProps } from './FindingTally'
export { FixDiff, type FixDiffProps } from './FixDiff'
export { FlaggedSource, type FlaggedSourceProps } from './FlaggedSource'
export { type KeySource } from './ForgetKeyChoice'
export { ForgetKeyChoice, type ForgetKeyChoiceProps } from './ForgetKeyChoice'
export { GitCredential, type GitCredentialProps } from './GitCredential'
export { HumanCost, type HumanCostProps } from 'ratchet-ui/components'
export { JdkChoice, type JdkChoiceProps } from './JdkChoice'
// THE PILL AND THE SENTENCE THAT ANSWERS IT. Both pages already agreed on "key set", "no key",
// the two tones and the source sentence; what they could not agree on is the ABSENT case, because
// this page can set a key and the sibling's deliberately renders no key field at all. So that
// sentence is the caller's, as `whenAbsent`.
export { KeyStatus, type KeyStatusProps } from 'ratchet-ui/components'
export { LiveStream, type LiveStreamProps, type LiveView } from './LiveStream'
export { MarkerAccount, type MarkerAccountProps } from './MarkerAccount'
export { MarkerCrumb, type MarkerCrumbProps } from './MarkerCrumb'
export { MarkerIdentity, type MarkerIdentityProps } from './MarkerIdentity'
export { MarkerLinkedText, type MarkerLinkedTextProps } from './MarkerLinkedText'
export { MarkerPaste, type MarkerPasteProps } from './MarkerPaste'
export { MarkerQueue, type MarkerQueueProps } from './MarkerQueue'
export { MarkerGroups, groupsOf, type MarkerGroup, type MarkerGroupsProps } from './MarkerGroups'
export { MARKER_COLUMNS, type MarkerRowData } from './MarkerRow'
export { MarkerTable, type MarkerTableProps } from './MarkerTable'
export { ExportLink, PageHeader, type Crumb, type PageHeaderProps } from './PageHeader'
export { ParallelProvers, type ParallelProversProps } from './ParallelProvers'
export { MirrorRules, type MirrorRulesProps } from './MirrorRules'
export { PromptPreamble, type PromptPreambleProps } from './PromptPreamble'
export { PromptStage, stageOf, type PromptStageProps } from './PromptStage'
export { ProveFinishedNotice, type ProveFinishedNoticeProps } from './ProveFinishedNotice'
export { RateAnswer, type RateAnswerProps } from './RateAnswer'
/**
 * The two payload shapes this tier passes through itself. `SettlementFlags` and `SourceLine` keep
 * their own names; the record `FlaggedSource` cannot, because the component that renders it has
 * that name and one of the two would be dropped from the barrel.
 *
 * THE COMPONENT KEEPS THE PLAIN NAME because it is what a screen writes in JSX, and the record is
 * exported as `FlaggedSourceRecord` — the same distinction `FlaggedSource.tsx` makes internally by
 * importing the type `as Source`. When these move to `@fsm/types`, as `records.ts` says they
 * should, this is the alias to retire.
 */
export {
  type FlaggedSource as FlaggedSourceRecord,
  type SettlementFlags,
  type SourceLine,
} from './records'
export { RestartLog, type Restart, type RestartLogProps } from './RestartLog'
export { RunProgress, type RunProgressProps } from './RunProgress'
// `Lamp` IS THE PACKAGE'S NOW — it went upstream from here in 0.4.0, and what came back parts
// dim from hollow by colour rather than by a dash pattern alone. `Semaphore` stays, because what
// red and green MEAN is this pipeline's vocabulary and was never offered.
export { Lamp } from 'ratchet-ui/components'
export { Semaphore, type SemaphoreProps } from './Semaphore'
// ONE SETTING: WHAT IT IS CALLED, WHAT IT IS SET TO, AND THE CONTROL THAT CHANGES IT.
// `name`/`state`/`anchorId` became `title`/`provenance`/`id`; `changed` kept its name and its
// meaning, because the sibling's settings page only reads and had no opinion about what an
// edited card looks like. It gains a `footnote`, which is where several rows here still put a
// bare `<Account quiet>` by hand.
export { SettingCard, type SettingCardProps } from 'ratchet-ui/components'
export { SeverityBadge, type SeverityBadgeProps } from './SeverityBadge'
export { SourceZip, type SourceZipProps } from './SourceZip'
export { StateBadge, type StateBadgeProps } from './StateBadge'
export { StateCounts, type StateCountsProps } from './StateCounts'
export { StreamPanel, type StreamPanelProps } from './StreamPanel'
export { StreamTail, type StreamTailProps } from './StreamTail'
export { SupersededAttempt, type SupersededAttemptProps } from './SupersededAttempt'
export { TestArtifact, type TestArtifactProps } from './TestArtifact'
export { Thinking, type ThinkingProps } from './Thinking'
export { TimeSpent, type TimeSpentProps } from 'ratchet-ui/components'
export { ToolLog, type ToolCall, type ToolLogProps } from './ToolLog'
/**
 * `TraceEvent` is the row and the eight bodies it dispatches to. They are exported individually
 * because a screen that renders one kind of event outside the feed — the agent tab does — must
 * reach the same body the feed uses rather than write a second one that drifts.
 */
export {
  AnsweredEvent,
  SaidEvent,
  BuildEvent,
  PricedEvent,
  SettledEvent,
  ToolCallEvent,
  TraceEvent,
  type AnsweredEventProps,
  type BuildEventProps,
  type PricedEventProps,
  type SettledEventProps,
  type ToolCallEventProps,
  type TraceEventProps,
  type TraceEventRecord,
} from './TraceEvent'
export { UploadForm, type UploadFormProps } from './UploadForm'
export { UploadOutcome, type UploadOutcomeProps } from './UploadOutcome'
export { VerdictPill, type VerdictPillProps } from './VerdictPill'
export { WhatHappened, type WhatHappenedProps } from './WhatHappened'
