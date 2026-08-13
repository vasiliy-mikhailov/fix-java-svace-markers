'use client'

import { Suspense, useCallback, useEffect, useState, type ReactNode } from 'react'
import { useSearchParams } from 'next/navigation'
import {
  Account,
  AgentGroupHeading,
  AgentPromptEditor,
  EmptyNote,
  ForgetKeyChoice,
  GitCredential,
  JdkChoice,
  KeyStatus,
  LabeledField,
  MarkerPaste,
  MarkerQueue,
  PageHeader,
  ParallelProvers,
  SaveRow,
  SecretField,
  SettingRow,
  SourceZip,
  TabRow,
  UploadForm,
  UploadOutcome,
  type AgentGroup,
  type KeySource,
} from '@fsm/ui'
import type { AgentName } from '@fsm/types'

import { href, read } from '../../lib/api'

/**
 * THE FOUR SETTINGS SCREENS — one route with four faces, exactly as the Java has it.
 *
 * <p>`/settings`, `?a=run`, `?a=model`, `?a=subject`. The URLs are the Java's own (`settings()`
 * 1116-1121, `settingsTabs()` 1125-1136) because links from the shell's navigation, from the
 * supervisor's findings and from anybody's bookmarks point at them, and a port that quietly moved
 * the run width to `/settings/run` would break every one of those without failing anywhere.
 *
 * <p>NOTHING HERE IS POLLED, and that is a decision rather than an omission. The index refreshes
 * every 15s because a run moves while somebody watches it; these four documents change only when
 * this page changes them. Worse than useless: `AgentPromptEditor` and `ParallelProvers` hold the
 * draft you are typing and deliberately do not re-seed from props, so a poll landing mid-paragraph
 * would either eat the paragraph or be ignored — the honest refresh is the re-read after a write,
 * which is what every handler below does.
 *
 * <p>THE ADAPTER IS THIS FILE'S JOB. The API sends what the record holds and the components take
 * what they draw, and the two vocabularies drifted in four places: `min`/`max`/`default` against
 * `least`/`most`/`fallback`, `keySet`/`keyFrom` against `keyed`/`keySource`, `edited` against a word
 * the row derives from `saved`, and a `group` that can be null against a heading that must name one.
 * Reconciled here, in the one place allowed to know both.
 *
 * <p>WHAT THE RECORD REFUSES TO SEND IS NOT FILLED IN. `ApiSettings` sends whether a key is set and
 * where it came from and never the key, and presence and host for the git credential and never the
 * token — an endpoint has no reveal button to pay for the exposure. So both secret fields start
 * blank, and blank means what each screen says it means: left alone on the model tab, refused on the
 * subject tab.
 */

type Tab = 'prompts' | 'run' | 'model' | 'subject'

/** The four faces, and the Java's URLs for them. `?a=` absent is the prompts page. */
const TABS: { key: Tab; label: string; path: string }[] = [
  { key: 'prompts', label: 'prompts', path: '/settings' },
  { key: 'run', label: 'the run', path: '/settings?a=run' },
  { key: 'model', label: 'the model', path: '/settings?a=model' },
  { key: 'subject', label: 'the subject', path: '/settings?a=subject' },
]

/** What the h1 says on each. The tab's label and the page's title are not the same words on `/`. */
const TITLES: Record<Tab, string> = {
  prompts: 'prompts',
  run: 'the run',
  model: 'the model',
  subject: 'the subject',
}

/** `?a=` names the face; anything else is the prompts page, which is `settings()`'s own default. */
function tabOf(asked: string | null): Tab {
  const found = TABS.find(tab => tab.key === asked)
  return found === undefined ? 'prompts' : found.key
}

type ApiPromptRow = {
  agent: string
  /** `chain` | `watch` | `asked`, or null for an agent no list names — "absent is not a guess". */
  group: string | null
  builtIn: string
  saved: string
  effective: string
  edited: boolean
  differs: boolean
}

type ApiRun = { workers: number; min: number; max: number; default: number }

const FIELDS = [
  { name: 'model', label: 'model', why: 'What to ask for. Must be a name the endpoint below serves.' },
  {
    name: 'base_url',
    label: 'endpoint',
    why:
      'OpenAI-shaped, ending in /v1. The scheme decides the protocol: https negotiates HTTP/2, ' +
      'anything else stays on 1.1, because offering h2c on a cleartext endpoint gets it accepted by ' +
      'vLLM which then loses the body.',
  },
  {
    name: 'temperature',
    label: 'temperature',
    why:
      'Zero, because these agents certify: a judge that answers differently on the same evidence ' +
      'twice is not a judge, and every loopback here replays a decision. Raise it to shake a run ' +
      'that keeps producing the same wrong answer, then put it back.',
  },
  {
    name: 'max_tokens',
    label: 'token cap',
    why:
      '0 for none, which is the setting. A cap is not a smaller number, it is a different ' +
      'behaviour: the last one bounded a stall by truncating the reasoning that caused it, ' +
      'mid-thought.',
  },
  {
    name: 'patience_minutes',
    label: 'silence, minutes',
    why:
      'How long the wire may carry NOTHING before the endpoint is called dead. A stream is silent ' +
      'for milliseconds at a time, so this is generous by design.',
  },
  {
    name: 'ceiling_minutes',
    label: 'generation, minutes',
    why:
      'How long an answer may go on ARRIVING. A different failure: the model is answering and not ' +
      'finishing, and the record says so in different words. This is the one that must not be ' +
      'confused with the other — it was, and it killed eighty-six proves that were fine.',
  },
] as const

/**
 * The six field specs are a compile-time constant in the Java (`theModel()` 1351-1376) and stay one
 * here: they are prose about what a value does, not a value, and an API that shipped them would grow
 * its payload every time somebody improved a sentence.
 *
 * <p>ORDER IS PART OF IT — model and endpoint first, then the two bounds that must not be confused
 * with each other, in the order `Tuning.all()` emits them. And `why` is rendered, never folded into
 * a placeholder or a `title`: the two minute-fields are two fields with two sentences precisely
 * because collapsing them into one "timeout" killed eighty-six live proves.
 */
type FieldName = (typeof FIELDS)[number]['name']

type ModelValues = Record<FieldName, string>

/** A missing key reads as blank rather than as a hole; `Tuning.all()` sends all six. */
const NO_VALUES: ModelValues = {
  model: '',
  base_url: '',
  temperature: '',
  max_tokens: '',
  patience_minutes: '',
  ceiling_minutes: '',
}

type ApiModel = {
  /** File-existence, not different-from-default: save the environment's own values back and it flips. */
  edited: boolean
  values: ModelValues
  keySet: boolean
  /** Null when no key is set anywhere — `keyFrom()` would otherwise answer "the environment". */
  keyFrom: string | null
}

type ApiSubject = {
  markers: { queued: number; repos: string[] }
  credential: { present: boolean; host: string | null }
  jdk: { chosen: string; available: string[]; default: string }
  zip: { present: boolean }
}

/**
 * The badge every screen wears, and the one number these four documents deliberately do not carry.
 *
 * <p>`ApiSettings`' own note: the count is already served for every screen at once by `/api/badges`,
 * and two routes computing it are two routes to keep in step. So it is read from there.
 */
type ApiBadges = { findings: number; proving: number }

/** One document, read once. There is no timer here because nothing on this page moves on its own. */
function useDocument<T>(path: string): {
  data: T | null
  failed: string | null
  reload: () => Promise<void>
} {
  const [data, setData] = useState<T | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const load = useCallback(async () => {
    try {
      const fresh = await read<T>(path)
      setData(fresh)
      setFailed(null)
    } catch (e: unknown) {
      setFailed(e instanceof Error ? e.message : String(e))
    }
  }, [path])
  useEffect(() => {
    void load()
  }, [load])
  return { data, failed, reload: load }
}

/**
 * A WRITE, TO THE ONLY ROUTE THAT TAKES ONE.
 *
 * <p>The four documents this page reads are GET-only (`Dashboard` 411-417); the JSON write contracts
 * the decomposition sketches do not exist yet. What does exist is the form route the Java page posts
 * to — `/settings`, one endpoint for six forms, told apart by the hidden `setting` field
 * (`Dashboard` 500-546) — so a save goes there, through `href()` because the shell owns the prefix
 * and a bare `/settings` 404s the moment this tool is mounted at one.
 *
 * <p>Not `read()`: that parses JSON, and this route answers a 303 to an HTML page. The reply body is
 * of no interest except on the subject tab, where it is the only place a refusal exists — see
 * {@link outcomeOf}. Every caller re-reads its document afterwards instead of trusting what it sent,
 * because the server clamps on the way in (`Tuning` 65-88, `Workers.clamp`) and swallows a failed
 * write to answer with what is actually on disk; the re-read IS the error message.
 */
async function post(body: URLSearchParams | FormData): Promise<string> {
  const response = await fetch(href('/settings'), { method: 'POST', body, cache: 'no-store' })
  if (!response.ok) {
    throw new Error(`the save answered ${response.status}`)
  }
  return await response.text()
}

/**
 * WHAT THE SERVER MADE OF AN UPLOAD, READ BACK OUT OF THE PAGE IT ANSWERED WITH.
 *
 * <p>A subject POST is answered in place rather than redirected (`Dashboard` 500-503, 1167-1234):
 * `subjectPosted()` re-renders the whole page with the outcome in it, because what a reader needs
 * after an upload is which lines were wrong. `/api/settings/subject` has no `outcome` field yet, so
 * this is the only copy of that sentence in existence — and without it a refused zip ("that is not a
 * zip — it does not start with PK") would look exactly like a save that worked, which is the failure
 * this whole screen keeps being rewritten to avoid.
 *
 * <p>Narrow on purpose, and it gives up rather than guesses: the flash is the one `.ev` block whose
 * `who` reads `refused` or `done` (1246-1249), the words the Java writes after stripping its own
 * leading-`!` sentinel. The day the JSON route answers a POST with `{refused, text}`, delete this.
 */
function outcomeOf(html: string): { refused: boolean; text: string } | null {
  if (typeof DOMParser === 'undefined') {
    return null
  }
  const page = new DOMParser().parseFromString(html, 'text/html')
  for (const box of Array.from(page.querySelectorAll('div.ev'))) {
    const who = box.querySelector('.who')?.textContent?.trim() ?? ''
    if (who === 'refused' || who === 'done') {
      return { refused: who === 'refused', text: box.querySelector('pre')?.textContent ?? '' }
    }
  }
  return null
}

/**
 * The header and the tab row the four faces share.
 *
 * <p>BUG NOT PORTED (`settingsTabs()` 1127): the prompts tab was lit with
 * `current.equals("run") ? "" : "on"` — the negation of the RUN test rather than a test of its own —
 * so on `?a=model` and `?a=subject` two tabs were lit at once and the row stopped saying where you
 * were. Each item carries its own `on` here and nothing tests another item's key.
 *
 * <p>The supervisor is a DEPARTURE, not a tab: it leaves this row's set, so it goes in `trailing`
 * and is never lit, whatever page you are on.
 */
function Screen({
  tab,
  subtitle,
  findingsOpen,
  children,
}: {
  tab: Tab
  subtitle: ReactNode
  findingsOpen: number
  children?: ReactNode
}) {
  return (
    <>
      <PageHeader
        title={TITLES[tab]}
        subtitle={subtitle}
        // A LABEL IS NOT A DESTINATION — the Java hard-coded `href='/'` whatever the words said, and
        // every settings view happened to mean "all markers". Both fields are carried now.
        back={{ label: 'all markers', href: href('/') }}
        findingsOpen={findingsOpen}
      />
      <TabRow
        items={TABS.map(one => ({ href: href(one.path), label: one.label, on: one.key === tab }))}
        trailing={[{ href: href('/overwatch'), label: 'the supervisor', on: false }]}
      />
      {children}
    </>
  )
}

/** Whatever went wrong with a write, said once and above the controls it belongs to. */
function Trouble({ said }: { said: string | null }) {
  return said === null ? null : <EmptyNote>{said}</EmptyNote>
}

/**
 * PROMPTS — `prompts()` 1465-1522.
 *
 * <p>An edit replaces the built-in entirely and takes effect on the next marker a prover starts, so
 * the row a reader is looking at is a promise about the next prove and not about the one running.
 */
function PromptsTab({ findingsOpen }: { findingsOpen: number }) {
  const { data, failed, reload } = useDocument<ApiPromptRow[]>('/api/settings/prompts')
  const [trouble, setTrouble] = useState<string | null>(null)

  async function write(fields: Record<string, string>) {
    try {
      await post(new URLSearchParams(fields))
      setTrouble(null)
      await reload()
    } catch (e: unknown) {
      setTrouble(e instanceof Error ? e.message : String(e))
    }
  }

  if (failed !== null) {
    return (
      <Screen tab="prompts" subtitle="could not read the prompts" findingsOpen={findingsOpen}>
        <EmptyNote>{failed}</EmptyNote>
      </Screen>
    )
  }
  if (data === null) {
    return <Screen tab="prompts" subtitle="reading the prompts…" findingsOpen={findingsOpen} />
  }

  // `edited` is `saved` being non-blank — the same question the row answers with its own word. It is
  // used HERE, for the header's count, and not passed down: shipping both copies of one fact to one
  // component is how the two started disagreeing in the Java.
  const edited = data.filter(row => row.edited).length

  const rows: ReactNode[] = []
  let heading: AgentGroup | null = null
  // ARRAY ORDER IS THE CONTRACT — `Agents.ORDER`, which is pipeline order, then anything the order
  // does not name, sorted (`ApiSettings.prompts` 152-154). Sorting it here would put estimator-critic
  // first and reproducer eleventh, which is the reverse of how anybody thinks about this chain, so
  // the group headings are emitted as the group CHANGES rather than by grouping the list.
  for (const row of data) {
    const group = groupFrom(row.group)
    if (group !== heading) {
      heading = group
      rows.push(<AgentGroupHeading key={`group:${group}`} group={group} />)
    }
    rows.push(
      <AgentPromptEditor
        // KEYED BY WHAT THE RECORD HOLDS, so a revert remounts the box and re-seeds it from the
        // built-in. The editor does not re-seed from props by design — a payload arriving while
        // somebody is halfway through rewriting a prompt must not eat the paragraph — and a remount
        // is the explicit version of the same thing. Only the agent that was written changes key,
        // so the drafts in the other fourteen boxes survive.
        key={`${row.agent}:${row.edited ? row.saved.length : 'code'}:${String(row.differs)}`}
        // ApiSettings appends any agent `Agents.ORDER` does not name, so a new one is visible before
        // it is listed; the name on the wire is therefore not guaranteed to be one this build knows.
        agent={row.agent as AgentName}
        builtIn={row.builtIn}
        // `effective` is on the wire and is not passed: the editor derives the same thing (the
        // override if there is one, else the built-in) because it owns the textarea's seed.
        saved={row.saved}
        // THE WORD AND THE ACCENT ANSWER DIFFERENT QUESTIONS. `differs` is `Prompts.same()`
        // normalised, computed on the server so that this page and the marker page — which uses it
        // to say a settlement was reached under instructions nobody is using any more — cannot drift.
        differs={row.differs}
        onSave={prompt => void write({ agent: row.agent, prompt })}
        onRevert={() => void write({ agent: row.agent, revert: '1' })}
      />,
    )
  }

  return (
    <Screen
      tab="prompts"
      subtitle={
        <>
          {`${data.length} agent(s) · `}
          {edited === 0 ? "none edited — every one is the code's" : `${edited} edited, the rest are the code's`}
        </>
      }
      findingsOpen={findingsOpen}
    >
      <Trouble said={trouble} />
      <Account>
        An edit here replaces the built-in entirely — there is no merge, because a prompt half from
        the code and half from a box is a prompt nobody can read in one place. It takes effect on the
        next marker a prover starts, not on the ones already running.
      </Account>
      {rows}
    </Screen>
  )
}

/**
 * Null on the wire means no list names this agent, and the component tier's own rule for that case
 * is `asked`, not `watch` — the fallthrough in `@fsm/ui`'s own `groupOf`, which decides the same
 * thing from a name when nothing on the wire says. An agent this build has never heard of is one
 * somebody added, and calling it a watcher is exactly the guess that filed `chat` under "watching
 * the run" when chat watches nothing.
 */
function groupFrom(group: string | null): AgentGroup {
  return group === 'chain' || group === 'watch' ? group : 'asked'
}

/** THE RUN — `theRun()` 1441-1469. */
function RunTab({ findingsOpen }: { findingsOpen: number }) {
  const { data, failed, reload } = useDocument<ApiRun>('/api/settings/run')
  const [trouble, setTrouble] = useState<string | null>(null)
  // Bumped on every accepted write so the control remounts and re-seeds from the reply. Without it a
  // saved 40 that the server clamped to 16 would go on reading 40 until somebody reloaded — a form
  // reporting a number the server never accepted.
  const [saves, setSaves] = useState(0)

  async function save(workers: number) {
    try {
      await post(new URLSearchParams({ setting: 'workers', workers: String(workers) }))
      setTrouble(null)
      await reload()
      setSaves(n => n + 1)
    } catch (e: unknown) {
      setTrouble(e instanceof Error ? e.message : String(e))
    }
  }

  if (failed !== null) {
    return (
      <Screen tab="run" subtitle="could not read the run width" findingsOpen={findingsOpen}>
        <EmptyNote>{failed}</EmptyNote>
      </Screen>
    )
  }
  if (data === null) {
    return <Screen tab="run" subtitle="reading the run width…" findingsOpen={findingsOpen} />
  }

  return (
    <Screen tab="run" subtitle="how many markers are proved at once" findingsOpen={findingsOpen}>
      <Trouble said={trouble} />
      <ParallelProvers
        key={`${data.workers}:${saves}`}
        workers={data.workers}
        // The bounds are served rather than agreed, and the names differ on the two sides of this
        // line: `min`/`max`/`default` is what the record calls them, `least`/`most`/`fallback` is
        // what the control's own paragraph calls them.
        least={data.min}
        most={data.max}
        fallback={data.default}
        onSave={workers => void save(workers)}
      />
      <Account quiet>
        Takes effect as the next marker starts. Lowering it does not stop a prove that is already
        running — the pool simply stops replacing them until it is back under the number.
      </Account>
    </Screen>
  )
}

/** THE MODEL — `theModel()` 1349-1438. */
function ModelTab({ findingsOpen }: { findingsOpen: number }) {
  const { data, failed, reload } = useDocument<ApiModel>('/api/settings/model')
  const [values, setValues] = useState<ModelValues | null>(null)
  const [apiKey, setApiKey] = useState('')
  const [forget, setForget] = useState(false)
  const [trouble, setTrouble] = useState<string | null>(null)

  // RE-SEEDED FROM THE REPLY, NOT FROM THE REQUEST. `Tuning` clamps on the way OUT (65-88), so a
  // saved temperature of 5 comes back as 2 and a form still showing 5 tells the reader a value no
  // prove will ever be given. Safe to do on every payload because nothing polls: a payload arrives
  // when the page loads and when a save has just been answered, which is exactly when the boxes
  // should show what was kept.
  useEffect(() => {
    if (data !== null) {
      setValues({ ...NO_VALUES, ...data.values })
    }
  }, [data])

  async function write(fields: Record<string, string>) {
    try {
      await post(new URLSearchParams({ setting: 'model', ...fields }))
      setTrouble(null)
      // The key never appears in the reply — the record refuses to serve it — so the box is cleared
      // by hand once it has been sent, and the checkbox with it.
      setApiKey('')
      setForget(false)
      await reload()
    } catch (e: unknown) {
      setTrouble(e instanceof Error ? e.message : String(e))
    }
  }

  if (failed !== null) {
    return (
      <Screen tab="model" subtitle="could not read the model settings" findingsOpen={findingsOpen}>
        <EmptyNote>{failed}</EmptyNote>
      </Screen>
    )
  }
  if (data === null || values === null) {
    return <Screen tab="model" subtitle="reading the model settings…" findingsOpen={findingsOpen} />
  }

  // NULL IS NOT "THE ENVIRONMENT". `keyFrom` is null when no key is set anywhere, and the only
  // control that reads it is the one that offers to drop a key saved here — so anything that is not
  // literally "this page" leaves that control inert. Guessing the other way arms a destructive
  // button on the strength of a field the record declined to answer.
  const keySource: KeySource = data.keyFrom === 'this page' ? 'this page' : 'the environment'

  return (
    <Screen
      tab="model"
      subtitle={
        data.edited
          ? "edited — the environment's values are underneath"
          : "every value is the environment's or the code's"
      }
      findingsOpen={findingsOpen}
    >
      <Trouble said={trouble} />
      {/* ONE ROW, ONE REQUEST, AND THAT IS THE FIX. In the Java the `<form>` opened AFTER the key
          field and the forget checkbox, and neither carried a `form=` attribute, so nothing named
          `api_key` or `forget_key` was ever submitted and `Tuning.save()`'s key branches were
          unreachable from the page that exists to reach them — somebody typing a key in was told it
          had saved. These are controlled inputs whose state this screen holds, so there is no longer
          a subtree that can be inside or outside a form by accident. */}
      <SettingRow
        name="the endpoint"
        state={data.edited ? 'edited' : "the environment's"}
        changed={data.edited}
      >
        <KeyStatus keyed={data.keySet} keySource={keySource} />
        <SecretField
          name="api_key"
          label="API key"
          value={apiKey}
          onChange={setApiKey}
          help="Blank leaves the stored key alone, so a browser that clears this box cannot silently unset it and leave every agent talking to an endpoint that refuses them. It starts blank because the record sends whether a key is set and never the key itself — use the checkbox to drop one saved here."
        />
        <ForgetKeyChoice keySource={keySource} checked={forget} onChange={setForget} />
        {FIELDS.map(field => (
          <LabeledField
            key={field.name}
            name={field.name}
            label={field.label}
            value={values[field.name]}
            onChange={value => setValues({ ...values, [field.name]: value })}
            help={field.why}
          />
        ))}
        <SaveRow
          onSave={() =>
            void write({
              ...values,
              // ABSENT OR BLANK MEANS LEAVE IT ALONE (`Tuning.save` 137-141), and `forget_key` is
              // read as the literal "1".
              ...(apiKey.trim().length === 0 ? {} : { api_key: apiKey }),
              ...(forget ? { forget_key: '1' } : {}),
            })
          }
          // Revert deletes the whole file, which also drops a key saved from this page — a
          // consequence the Java page never stated. `SaveRow` arms it before it fires, and it is its
          // own request rather than a second button on one form, because a client that serialises
          // its state would have posted `revert=1` on every save.
          destructive={{ label: "put the environment's back", onConfirm: () => void write({ revert: '1' }) }}
        />
      </SettingRow>
      <Account quiet>
        Takes effect on the next marker a prover starts. Nothing running is disturbed — a prove is a
        fresh process per marker, which is the only reason this is a form rather than an env var.
      </Account>
    </Screen>
  )
}

/** THE SUBJECT — `theSubject()` 1237-1345, and `subjectPosted()` 1167-1234 for every write. */
function SubjectTab({ findingsOpen }: { findingsOpen: number }) {
  const { data, failed, reload } = useDocument<ApiSubject>('/api/settings/subject')
  const [outcome, setOutcome] = useState<{ refused: boolean; text: string } | null>(null)
  const [trouble, setTrouble] = useState<string | null>(null)
  // Bumped only when the server said DONE. A picked file, a pasted queue and a typed token are
  // one-shot inputs: once they have been applied, leaving them sitting in the controls invites
  // sending them twice. On a refusal they stay exactly where they are — the reader needs the twelve
  // complaints AND the list they pasted, and throwing the list away to show the complaints would be
  // its own small disaster.
  const [applied, setApplied] = useState(0)

  async function write(parts: Record<string, string | File>) {
    const body = new FormData()
    for (const [name, value] of Object.entries(parts)) {
      body.append(name, value)
    }
    try {
      const said = outcomeOf(await post(body))
      setTrouble(null)
      setOutcome(said)
      await reload()
      if (said !== null && !said.refused) {
        setApplied(n => n + 1)
      }
    } catch (e: unknown) {
      setOutcome(null)
      setTrouble(e instanceof Error ? e.message : String(e))
    }
  }

  if (failed !== null) {
    return (
      <Screen tab="subject" subtitle="could not read the subject" findingsOpen={findingsOpen}>
        <EmptyNote>{failed}</EmptyNote>
      </Screen>
    )
  }
  if (data === null) {
    return <Screen tab="subject" subtitle="reading the subject…" findingsOpen={findingsOpen} />
  }

  return (
    <Screen
      tab="subject"
      subtitle={`${data.markers.queued} marker(s) queued`}
      findingsOpen={findingsOpen}
    >
      <Trouble said={trouble} />
      {outcome === null ? null : <UploadOutcome refused={outcome.refused} text={outcome.text} />}
      <MarkerQueue queued={data.markers.queued} repos={data.markers.repos} />
      {/* BESIDE THE ROW RATHER THAN INSIDE IT: `MarkerQueue` is a whole row and takes no children,
          which is why its own empty state says "upload a markers file or paste a list below".
          The two controls are one endpoint with two optional fields — the server takes the file when
          there is one and falls back to the text (1174-1178). `forget` is NOT sent from either: in
          the Java it was inferred from which button submitted the form, which is the footgun a
          client that serialises its state walks straight into. */}
      <UploadForm key={`markers:${applied}`} setting="markers" onUpload={file => void write({ setting: 'markers', file })} />
      <MarkerPaste key={`paste:${applied}`} onUse={text => void write({ setting: 'markers', text })} />
      <GitCredential
        key={`credential:${applied}`}
        // Host and presence are the whole of what the record sends: the token is kept in git's own
        // credential store rather than in a clone URL so that it never reaches a process list every
        // prover can read, and an endpoint handing it back would undo that on its own. So the box
        // starts blank — and on THIS page blank is refused rather than left alone (1196-1197), which
        // is the opposite of the model tab's rule and is why the field says so itself.
        host={data.credential.host ?? ''}
        token=""
        onSave={(host, token) => void write({ setting: 'token', host, token })}
        onForget={() => void write({ setting: 'token', forget: '1' })}
      />
      <JdkChoice
        key={`jdk:${data.jdk.chosen}:${applied}`}
        chosen={data.jdk.chosen}
        // From the server, always: this is the list of JDKs in THIS image, and a client with its own
        // copy offers a Java that is not installed.
        available={data.jdk.available}
        fallback={data.jdk.default}
        onSave={jdk => void write({ setting: 'jdk', jdk })}
      />
      <SourceZip
        key={`zip:${applied}`}
        present={data.zip.present}
        onUpload={file => void write({ setting: 'zip', file })}
        onRemove={() => void write({ setting: 'zip', forget: '1' })}
      />
      <Account quiet>
        Any of this takes effect on the next marker a prover starts. A prove already running keeps
        the tree it was given.
      </Account>
    </Screen>
  )
}

function SettingsScreen() {
  // THE QUERY PARAMETER IS THE ROUTE, exactly as the Java's `settings()` reads it. Four screens, one
  // URL family, and the bookmarks people already have.
  const asked = useSearchParams().get('a')
  const tab = tabOf(asked)

  // Read separately and never invented: the settings documents carry no findings count on purpose.
  // Zero before it answers means the glyph draws without a badge, which is the absence of a claim —
  // a badge reading zero is the thing the Java went out of its way not to draw.
  const badges = useDocument<ApiBadges>('/api/badges')
  const findingsOpen = badges.data?.findings ?? 0

  if (tab === 'run') {
    return <RunTab findingsOpen={findingsOpen} />
  }
  if (tab === 'model') {
    return <ModelTab findingsOpen={findingsOpen} />
  }
  if (tab === 'subject') {
    return <SubjectTab findingsOpen={findingsOpen} />
  }
  return <PromptsTab findingsOpen={findingsOpen} />
}

/**
 * `useSearchParams()` reads something that does not exist until the browser does, and this zone is
 * statically exported — so the boundary is required rather than decorative: without it the export
 * refuses to prerender the page at all.
 *
 * <p>The fallback header omits `back` rather than passing undefined. `exactOptionalPropertyTypes`
 * means an optional prop is ABSENT or a value, and that distinction is the same one this whole
 * screen is careful about everywhere else.
 */
export default function SettingsPage() {
  return (
    <Suspense fallback={<PageHeader title="settings" subtitle="reading…" findingsOpen={0} />}>
      <SettingsScreen />
    </Suspense>
  )
}
