'use client'

import { useEffect, useMemo, useState } from 'react'
import {
  Account,
  AskBox,
  AskNotice,
  ChatTranscript,
  EmptyNote,
  Loaded,
  PageHeader,
  StreamPanel,
  type ChatTurnData,
  type Crumb,
  type Style,
  useAsk,
} from '@fsm/ui'
import { AGENTS, type AgentName, type MarkerId, type MarkerKey } from '@fsm/types'

import { ApiError, href, read } from '../../lib/api'

/**
 * ASK THE SUPERVISOR — the only screen in the zone that writes to the record instead of reading it.
 *
 * <p>The dashboard answers one question well, and only the one it was built to answer. A reader who
 * wants to know why the reproducer keeps timing out on one checker family has to read three hundred
 * traces; the agent that watches the run has already read them and is otherwise unreachable. This is
 * where it is reachable.
 *
 * <p>THREE TAILS HANG UNDER THE TRANSCRIPT AND EXACTLY ONE OF THEM SHOWS. `answering` wins;
 * `unanswered` is the question only when nothing is being written; otherwise nothing. That order is
 * not a preference — `ApiChat.chat` reads the two booleans a moment apart, in that order, because an
 * answer can land between the reads, and a client that resolves them the other way round draws the
 * restart notice over an answer that is arriving.
 *
 * <p>AND THE SECOND OF THEM MEANS THE OPPOSITE OF WHAT IT LOOKS LIKE. `unanswered` is not "still
 * thinking": it is `Chat.unanswered` — the last turn is yours and nothing is running — which happens
 * when the container was redeployed mid-reply, and it is deployed often. Given a spinner it reads as
 * a page that waits forever for a reply nobody is writing. It gets a sentence saying so instead.
 *
 * <p>THE ADAPTER IS THIS FILE'S JOB. The API sends what the record holds — the whole partial answer,
 * the `{slug,key}` join per turn, a free-string agent name — and the components take what they draw:
 * a 4000-character tail, one map for the transcript, an `AgentName | null`. Reconciling it here is
 * deliberate; neither half is allowed to know the other's vocabulary.
 */

/** One `{slug,key}` pair: a marker a single turn names, and the key its link goes to. */
type ApiNamedMarker = { slug: string; key: string }

type ApiTurn = {
  /** Epoch ms, or 0 for a record with no timestamp. Zero is NOT now — `ChatTurn` omits the span. */
  at: number
  /** As recorded. `mine` is `who === "you"` and is derived in the component, never sent. */
  who: string
  text: string
  /**
   * THE MARKERS THIS TURN NAMES, AND ONLY THIS TURN.
   *
   * `ApiChat.named` joins per turn because the queue is 356 entries and this document is polled
   * every three seconds; the whole map would be the whole payload. Empty for a turn that names
   * none, which is most of them.
   */
  markers: ApiNamedMarker[]
}

/** The three lines of `chat-trace.jsonl.live`. Null for absent AND for a torn read — see below. */
type ApiPartial = { agent: string; at: number; text: string }

type ApiChat = {
  /** Oldest first, in append order, ALL of them. Only the last twenty go back to the model. */
  turns: ApiTurn[]
  answering: boolean
  unanswered: boolean
  /** The server's clock. See the note on why nothing here is measured against it. */
  serverNow: number
  live: ApiPartial | null
}

/**
 * `Dashboard.LIVE_TAIL`. How much of an answer in progress fits on a screen.
 *
 * THE CUT IS THE SCREEN'S TO MAKE, which is why the API sends the whole partial and says so in
 * `ApiChat.live`: how much fits is not a fact about the record. And it is the END that is kept, not
 * the start — a reasoning turn runs to tens of thousands of characters, and a panel that opened on
 * the beginning showed the same paragraph for four minutes while a reader concluded the page had
 * died.
 */
const LIVE_TAIL = 4_000

/**
 * THE LITERAL THE JAVA HANDS `panel()` HERE, and it is not a marker slug.
 *
 * Every other caller of that component passes a slug over `m/<slug>/trace.jsonl.live`; this one
 * passes `"supervisor"` over `chat-trace.jsonl.live`, a file that belongs to no marker at all. So it
 * is never linked and never used to build a marker URL — typed as a slug it would compile and then
 * send a reader to `/marker?k=supervisor`. It is also the fold's stable id (`live-supervisor`), so
 * it must not be recomputed per render.
 */
const WHO = 'supervisor'

/**
 * The crumb, THROUGH `href()`.
 *
 * The Java's `head()` hard-coded this destination to `/` whatever the label said, and every caller
 * happened to mean "all markers" so the coincidence never bit. It is a prop now — and it goes
 * through `href()` because a link written as `/` leaves the zone the moment a shell mounts it at a
 * prefix.
 */
const BACK: Crumb = { label: 'all markers', href: href('/') }

const TITLE = 'ask the supervisor'

/**
 * The Java wrote this with an `&mdash;` entity, because `head()` appended the subtitle RAW while it
 * escaped the title. `PageHeader` takes a node and React escapes both, so the character is written
 * as itself; an HTML string here would draw the entity source instead.
 */
const SUBTITLE =
  'the agent that watches this run, over the whole record. It reads; it cannot restart or set ' +
  'aside a prove — those are buttons on a marker’s own page.'

/** `.chat{padding:14px 24px}` and `.say{max-width:64rem}` (CSS 119-120, 129). The column is the
 *  screen's; the box around each turn is the component's. */
const COLUMN: Style = { padding: '14px 24px 24px', maxWidth: '64rem' }

/**
 * A NAME THE COMPONENT CAN DRAW, or nothing.
 *
 * `agent` is line 1 of a file two processes touch, so it arrives as free text; `StreamPanel` takes
 * the closed union and `null` for "the file has not parsed yet". Anything unrecognised — a rename on
 * the Java side, a half-written line — becomes null and the panel simply omits the name, which is
 * the same thing it does for a torn read. Casting an unknown string through would put a value in a
 * union that no longer describes it.
 */
function agentOf(name: string): AgentName | null {
  const known: readonly string[] = AGENTS
  return known.includes(name) ? (name as AgentName) : null
}

/**
 * EVERY MARKER THE CONVERSATION NAMES, AS THE ONE MAP THE TRANSCRIPT TAKES.
 *
 * The wire joins `{slug,key}` per turn (a 356-entry map on a 3-second poll is the whole payload);
 * `ChatTranscript` takes one map for the column. Unioning them here is still strictly narrower than
 * the Java, which handed every turn the whole queue.
 *
 * FIRST WINS on a repeated slug, and repeats are possible: `Supervisor.slug` takes the tail after
 * the last slash, replaces every non-alphanumeric and cuts to eighty, so two markers can share one
 * and neither can be turned back. Last-wins would let a turn scrolled past silently repoint a link
 * the reader has already followed.
 *
 * No sort here: `MarkerLinkedText` orders its own alternation longest-first, on purpose, because the
 * whole correctness argument (`Foo_java_82_NULL_DEREF` contains `Foo_java_82_NULL`) rests on it and
 * it will not trust a caller for that.
 */
function named(turns: readonly ApiTurn[]): ReadonlyMap<MarkerId, MarkerKey> {
  const all = new Map<MarkerId, MarkerKey>()
  for (const turn of turns) {
    for (const marker of turn.markers) {
      // An empty slug is contained in every text there is and would name every marker on every turn.
      if (marker.slug.length > 0 && !all.has(marker.slug)) {
        all.set(marker.slug, marker.key)
      }
    }
  }
  return all
}

/** The turn as the transcript draws it. The per-turn join is lifted out by `named` above. */
function toTurn(turn: ApiTurn): ChatTurnData {
  return { at: turn.at, who: turn.who, text: turn.text }
}

function fetchChat(): Promise<ApiChat> {
  return read<ApiChat>('/api/chat')
}

/**
 * ASKING, THROUGH THE ONE ROUTE THAT RECORDS A QUESTION.
 *
 * `POST /api/chat {"q"}` is specified and not built: the route registered at Dashboard.java 408
 * answers every method with the conversation, so a JSON post there would return 200 and record
 * nothing. `Chat.ask` is reachable only from the form arm at Dashboard.java 556-560, so that is
 * where the question goes — form-encoded, as that arm reads it. When the JSON arm exists, this
 * function is the only thing that changes.
 *
 * NOT THROUGH `read()`, because the reply is not JSON: the POST is answered with a 303 so a page
 * that refreshes itself cannot re-ask, and following it lands on the Java page. What `Chat.ask`
 * said comes back in that redirect's query, which `response.url` — the URL after redirects — still
 * carries. `href()` all the same: the prefix is the shell's and this is the same mount.
 */
async function put(question: string): Promise<string> {
  const response = await fetch(href('/chat'), {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ q: question }).toString(),
    cache: 'no-store',
  })
  if (!response.ok) {
    throw new ApiError(response.status, '/chat')
  }
  // Blank on success — the redirect goes to bare `/chat` and there is nothing to tell anybody.
  return new URL(response.url, window.location.href).searchParams.get('said') ?? ''
}

export default function ChatScreen() {
  const [chat, setChat] = useState<ApiChat | null>(null)
  const [failed, setFailed] = useState<string | null>(null)

  const answering = chat?.answering ?? false
  const turns = chat?.turns ?? []
  // Recomputed only when the document changes: this walks every turn's join, and while an answer is
  // coming the document changes every three seconds.
  const markers = useMemo(() => named(turns), [turns])

  useEffect(() => {
    let alive = true
    const load = () =>
      fetchChat().then(
        doc => {
          if (alive) {
            setChat(doc)
            setFailed(null)
          }
        },
        (e: unknown) => {
          if (alive) {
            setFailed(e instanceof Error ? e.message : String(e))
          }
        },
      )
    void load()
    /**
     * POLLED ONLY WHILE AN ANSWER IS BEING WRITTEN, and it stops when the flag drops.
     *
     * The Java emitted `<meta http-equiv=refresh content=3>` from inside the `answering` branch and
     * nowhere else, and the reasoning is worth keeping: there is nothing to lose while you wait and
     * everything to lose while you read. A page that kept polling after the answer landed would be
     * re-rendering a conversation somebody is halfway through.
     *
     * This effect re-runs on the flag flipping, which costs one extra read at each transition and
     * buys the final one — the read that observes `answering: false` also carries the recorded
     * answer, so the last poll is the one that draws it.
     *
     * The document carries the partial answer inline, so this one poll advances both. The 2-second
     * `#live` poller the marker pages use never ran here: it refreshes an element with `id=live` and
     * this page has never rendered one.
     */
    const timer = answering ? setInterval(() => void load(), 3_000) : null
    return () => {
      alive = false
      if (timer !== null) {
        clearInterval(timer)
      }
    }
  }, [answering])

  /**
   * THE RECORD SAYS WHETHER THE QUESTION LANDED, NOT THE POST.
   *
   * `AskBox` clears nothing and re-enables nothing on its own, because `Chat.ask` can answer "still
   * answering the last one" or "could not write the question down" — and the second means the
   * question is not in the record at all, so the transcript above is complete and the draft is the
   * only copy left. So: post, re-read, and let what came back decide. The box is remounted (and the
   * draft dropped) only once the turn is in the conversation.
   *
   * THE ROUND TRIP IS INSIDE `send`, WHICH IS THE WHOLE REASON THIS ONE CLOSURE COULD TAKE `useAsk`
   * AND THE FOUR ON THE SETTINGS PAGE COULD NOT. `useAsk` reads the landing from whatever `send`
   * resolves to and does not await `onAnswer`, so a page that posts and then re-reads in `onAnswer`
   * would clear `busy` while the document under it was still the old one. Whether THIS question
   * landed is not knowable from the POST, so the re-read is part of the ask rather than something
   * that happens after it — and `busy` then spans the whole trip, which closes a window this page
   * had open: between the post and the re-read `AskBox` was live with a stale `answering`, and a
   * second click posted a second question.
   */
  const asking = useAsk<string, { reply: string; doc: ApiChat | null; before: number }>({
    send: async question => {
      const before = turns.length
      const reply = await put(question)
      try {
        return { reply, doc: await fetchChat(), before }
      } catch (e: unknown) {
        // A RE-READ THAT FAILS IS NOT A QUESTION THAT WAS REFUSED. The question is in the record;
        // this page simply cannot see the record, which is the other failure entirely and the one
        // `Loaded` draws. Caught here so it never reaches `useAsk` as a refusal.
        setFailed(e instanceof Error ? e.message : String(e))
        return { reply, doc: null, before }
      }
    },
    read: ({ reply, doc, before }) => {
      if (reply !== '') {
        return { landed: false, why: reply }
      }
      // `Chat.ask` sets the flag and appends the turn before it returns, so a question that was
      // taken is visible in this very read. Nothing new and nothing running means it was not taken
      // and nobody said why — which must not be drawn as the silence of a question being answered.
      if (doc !== null && !doc.answering && doc.turns.length === before) {
        return {
          landed: false,
          why: 'the question was not written down — nothing was added to the conversation. Ask again.',
        }
      }
      return { landed: true }
    },
    onAnswer: ({ doc }) => {
      if (doc !== null) {
        setChat(doc)
        setFailed(null)
      }
    },
  })

  // THE THREE STATES, IN ONE PLACE — see `Loaded`. Two early returns before, whose headers said
  // different things and whose waiting branch drew nothing under the title.
  if (failed !== null || chat === null) {
    return (
      <Loaded
        what="conversation"
        failed={failed}
        value={chat}
        header={<PageHeader
          title={TITLE}
          subtitle={SUBTITLE}
          back={BACK}
          findingsOpen={0}
        />}
      >
        {() => null}
      </Loaded>
    )
  }

  const live = chat.live
  const tail = live === null ? '' : live.text.slice(-LIVE_TAIL)
  return (
    <>
      {/*
        `findingsOpen` IS NOT ON THIS WIRE. Every payload is meant to carry it because the badge is
        on all twelve screens, and `/api/chat` does not — so this page cannot say how many findings
        stand. Zero is the honest rendering of that and not a claim of a clean run: the component
        suppresses the number entirely at zero and draws the plain grey link, exactly as it does
        before anything has been judged. Inventing a count is the alternative, and there is no
        number to invent.
      */}
      <PageHeader title={TITLE} subtitle={SUBTITLE} back={BACK} findingsOpen={0} />
      <div style={COLUMN}>
        {/*
          `readable` HAS NO COUNTERPART ON THE WIRE. `Chat.turns` swallows the IOException and
          returns an empty list, so a corrupted `chat.jsonl` and a conversation nobody has started
          arrive here as the same empty array; the document cannot tell them apart and neither can
          this page. True is what "the document parsed and said nothing was wrong" means — a read
          that actually failed is the branch above, not a friendly welcome. When the API grows the
          field (#16) it comes straight through here.
        */}
        <ChatTranscript turns={chat.turns.map(toTurn)} markers={markers} readable={true} />
        {chat.answering ? (
          /*
           * A TORN READ IS NOT AN ERROR, and this is where that lands. `live` is null both for a
           * partial that has not been written yet and for a read that caught the file mid-rewrite —
           * `ApiChat.live` parses all-or-nothing on purpose, and the next poll is three seconds
           * away. The panel's own absent state is `agent=null, at=0, text=""`, which draws "nothing
           * yet" and an ellipsis. An error banner for a file that will be whole again in three
           * seconds teaches a reader to ignore error banners.
           */
          <StreamPanel
            who={WHO}
            agent={live === null ? null : agentOf(live.agent)}
            at={live === null ? 0 : live.at}
            text={tail}
            truncated={live !== null && live.text.length > LIVE_TAIL}
          />
        ) : chat.unanswered ? (
          /*
           * NOT A SPINNER, and not a turn either — nobody said this. A question with nothing under
           * it and nothing running means the dashboard was restarted while the answer was being
           * written, which happens on every deploy. Left unsaid the page reads as still thinking
           * and never stops.
           */
          <Account quiet>
            No answer came back — the dashboard restarted while it was being written. Ask again.
          </Account>
        ) : null}
        {/* Renders nothing when the question was taken. It is not a turn and is not styled as one:
            "could not write the question down" says the question is NOT in the record above it. */}
        <AskNotice said={asking.refused} />
        {/*
          REMOUNTED BY THE RECORD. `AskBox` deliberately keeps the draft through a refusal — the
          only confirmation this design has is the box going disabled — so the draft is dropped
          exactly when the conversation grows a turn, and not a moment earlier. Keying on the count
          rather than on the last turn's text: two questions can be the same words in the same
          second, and a key that collided would leave the second one's draft on screen.
        */}
        <AskBox key={turns.length} answering={chat.answering || asking.busy} onAsk={asking.ask} />
      </div>
    </>
  )
}

/*
 * WHY `serverNow` IS READ AND NOT USED. It is on the wire so elapsed can be measured without
 * trusting the browser's clock, which may be minutes out — but `RelativeTime` owns its own ticking
 * clock (it has to: a component that re-renders only when the payload changes freezes at "12s ago"
 * for the four minutes an agent spends thinking) and takes no `now`. Shifting every `at` by the skew
 * to compensate would forge the timestamps the turns are stamped with, including the `dateTime`
 * attribute, so the skew stays uncorrected here until the component can take the reference clock.
 *
 * WHY THERE IS NO `useSearchParams` ON THIS SCREEN. `/chat` takes no `?k=` and no `?a=`; its one
 * parameter was `?said=`, which existed only because a 303 had to carry a sentence, and it is state
 * now. A bookmark carrying an old `?said=` is a stale sentence about a question asked long ago, and
 * ignoring it is the fix.
 */
