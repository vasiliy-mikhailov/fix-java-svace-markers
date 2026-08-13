# Screen decomposition, as read from Dashboard.java

Every component below cites the Java function it renders today, with line numbers. The
reconciled catalogue — three tiers, final prop types, the API each screen needs — is in
[component-catalogue.md](component-catalogue.md). This file is the raw per-screen detail
behind it, kept because the notes carry traps the summary drops.


## markers — `/`

Of every marker this run was given, what has been decided about each one, how far through the run is, and what it would have cost a person.


### MarkersScreen

The whole route: header, progress bar, the two count strips, the six-column table of every marker, and the three exit links. Renders EmptyRun in place of everything below the header when there are no markers at all.


```ts
run: RunSummary — total, settled, beganAt, traceEvents, humanMinutes, countsByState, findingsOpen
markers: Marker[] — one per row, already in the order to display
```

*From:* index() lines 1556-1753; route registration line 517-518


> THE STATE ON SCREEN IS NOT settlements.state. A settlement saying `proving` is rewritten to `interrupted` unless results/claims/<slug(key)> exists (1605-1627), and a queued key that HAS a claim but no settlement row is rewritten to `proving` (1629-1634). slug() is everything after the last slash, non-alphanumerics replaced, cut to 80 (571-583) and must match entrypoint.sh exactly — a claim it cannot find reads as a marker nobody is working on. Resolve this server-side; a React client must not be shown the raw state. Row order is markers.txt order (LinkedHashMap seeded from the queue, then settlements overwrite in place) — sorting the table by state throws away the run's plan order.


### PageHeader *(shared)*

The title bar: back crumb on the left, h1, a grey subtitle line, and three icon links top-right — settings gear, envelope to /chat, and FindingsButton.


```ts
title: string — escaped as text
subtitle: ReactNode — on this screen "N marker(s) · M trace event(s)"
back?: string — label of the crumb to /; omitted on this screen
findingsOpen: number — passed through to FindingsButton
```

*From:* head(title, sub) 2413-2415 and head(title, sub, back) 2424-2439; called from index() line 1642


> `title` goes through esc() but `sub` is appended RAW (2438) — other screens pass markup in it, so the React prop is a node, not a string. head() also injects the LIVE and KEEP_OPEN scripts (2426); in React those become the LiveRefresh hook, not part of the header's markup. On this screen `back` is empty: / is where back goes.


### FindingsButton *(shared)*

A ⚠ link to /overwatch. When something stands it turns amber and carries a small round badge with the count; the title reads "N finding(s) the critic has not dismissed".


```ts
open: number — findings whose verdict is `holds` or `unjudged`
```

*From:* findingsButton() 2404-2411; count from holding() 800-809 and verdictOf() 821-824


> DRAWN WITHOUT A BADGE WHEN THE COUNT IS ZERO, on purpose — a badge reading 0 on a clean run teaches readers to ignore it, and it has to still mean something the day it says nineteen. A blank verdict counts as `unjudged` and is INCLUDED; `refuted` is excluded. This replaced a five-row banner that used to sit on this page; do not put the list back here.


### RunProgress

A 4px gradient bar filled to the settled percentage, and under it three Tally boxes: "214 / 356 · 60% settled", elapsed, and "eta, extrapolated".


```ts
total: number
settled: number
beganAt: number — epoch ms of the earliest trace event in the run
now?: number — server clock, so the elapsed reading does not depend on the browser's
```

*From:* progress(total, settled, elapsed) 2235-2249; total/settled/elapsed computed in index() 1635-1640, called at 1665


> TAKE beganAt, NOT elapsed. Java bakes `System.currentTimeMillis() - began` into the HTML, so the clock only moves when the live swap re-renders the page; a component holding beganAt can tick on its own. eta = elapsed/settled*(total-settled) and is an em dash when settled is 0 or equals total; pct is capped at 100. Two degenerate branches to keep: total <= 0 with elapsed > 0 renders ONLY the elapsed box and no bar; total <= 0 with no elapsed renders nothing at all. `settled` counts everything except proving/queued/interrupted — so `infra` counts as settled.


### Tally *(shared)*

One boxed figure: a large bold value with a small grey caption under it. The unit of both count strips.


```ts
value: ReactNode — a number, a duration, an em dash
label: string — the caption
```

*From:* the .c markup in index() 1671-1676 and progress() 2245-2247; CSS .counts/.c lines 46-48


> The strip is a flex-wrap row (.counts); progress() emits one strip and index() emits a second one immediately after, so the screen has two adjacent strips by design, not one merged row.


### StateCounts

The second count strip: one Tally per state present in the run ("180 false-positive", "4 proving", "12 queued"), then a human-equivalent Tally at the end.


```ts
counts: Record<MarkerState, number>
humanMinutes: number
```

*From:* index() 1668-1676


> Sorted alphabetically by state name (TreeMap, 1668) — not by count and not by pipeline order. Every state present gets a tile, including the not-a-disposition ones (queued, proving, interrupted, infra). The human-equivalent tile is drawn only when humanMinutes > 0 and is ALWAYS formatted "Xh Ym" (1674-1675), which is a different formatter from HumanCost's hm(): 45 minutes reads "0h 45m" here and "45m" in the row below. Pick one deliberately when porting.


### MarkerTable

The table and its header row: severity | marker | state | what happened | took | a person would have. One MarkerRow per marker.


```ts
markers: Marker[]
```

*From:* index() 1683-1684 (headers) and 1686/1749 (body); CSS table/th/td 49-51


> One row reads as one sentence left to right: how bad, what and where, what we decided, why, what it cost. `where` used to sit between severity and state and split the two columns a reader compares — the package now lives under the filename in the marker cell. There used to be a seventh `latest` column at the far right; it was removed because a running marker's progress note is its `why`.


### MarkerRow

One marker as a table row: SeverityBadge, MarkerLink, StateBadge + Semaphore, WhatHappened, TimeSpent, HumanCost.


```ts
marker: Marker — the record for one key (see api_needed)
```

*From:* the all.forEach body, index() 1686-1748


> Six cells, so it takes one object rather than six props. Everything it needs is on the marker; nothing is looked up per-row at render time. The row is a link target in exactly two places (filename, and the state pill only while proving) — the row itself is not clickable today.


### SeverityBadge *(shared)*

A small square chip with the analyser's severity — red for Critical, orange for Major, grey for Minor, blue for Normal, and a grey em dash when unknown.


```ts
severity: string | null — "Critical" | "Major" | "Minor" | "Normal" | null
```

*From:* index() 1701 and 1717-1718; severities() 782-791; CSS .sev 56-60


> Picks its own colour by lowercasing the value; do not pass a class. NULL IS A REAL ANSWER, not a default: severity is reference data joined from severities.tsv on basename|line|checker, it covers only the 282 markers that analyser run reported, and the other 74 (src/it, src/test) get an em dash rather than a guess. Note the join is on the FILE BASENAME, not the path — two same-named files in different packages can collide.


### MarkerLink *(shared)*

The identity cell: a link to /marker?k=… labelled `B.java:82`, with the checker name and the package tail on two small grey lines beneath.


```ts
markerKey: string — repo|file|line|checker, and the link's query value
file: string — from the settlement row, else parsed out of the key
checker: string — same fallback
```

*From:* index() 1690-1700 (parsing) and 1719-1723 (markup); CSS .k line 52


> A marker not yet reached has NO settlement row, so file and checker come from the key itself, which is always present — the component must render fully for a queued marker. The directory line is the package tail with `src/main/java/` and `src/test/java/` stripped (1699); it exists because two markers in one run are routinely both LessonPage.java. Line is appended only when non-empty.


### StateBadge *(shared)*

A rounded pill with the state word, coloured by state: green for verified, amber for reproduced/needs-review, grey for false-positive/by-design/unprovable, purple-bordered for interrupted, blue with a pulsing dot for proving, red for infra.


```ts
state: MarkerState
markerKey: string — only used when the pill is a link
```

*From:* index() 1724-1732; css() 2682-2684; CSS lines 54-55, 184-185, 192, 198-200


> PROVING IS THE ONE STATE STILL HAPPENING, so it alone renders as a link to /marker?k=…&a=live; every other word is a conclusion and is plain text. Live bug worth deciding about: css() returns the state as a class only if it matches [a-z-]+, otherwise "infra" — and Prove.java:303 writes `verified/pr-ready` and `verified/pr-rejected` WITH the slash, so both success states render today in the infra red, and the `.verified-pr-ready` / `.verified-pr-rejected` rules at CSS line 54 are unreachable. A React component that maps state→colour directly will silently fix this and change what the page looks like.


### Semaphore *(shared)*

Two small lamps under the state pill: red for "the test failed first", green for "the same test then passed". Lit means the build said so, dim means it was reached and did not happen, hollow-dashed means it was never got to.


```ts
state: MarkerState
redVerified: boolean
greenVerified: boolean
hasSettlement: boolean — renders nothing at all when false
```

*From:* flags(row) 2313-2326


> HOLLOW IS NOT "NO": a marker the reproducer declined never had a red to fail, which is a different answer from a red that passed. `reached` for green is derived from red being true — not from any state. `reached` for red excludes state `not-a-bug`, which nothing in this codebase writes any more (only the dead CSS rule at line 184 remembers it) — that guard is dead and can go. Returns empty string for a marker with no settlement row, so queued rows have no lamps.


### Dot *(shared)*

One lamp of the semaphore: a 9px circle, filled and glowing when lit, dark-filled when dim, transparent with a dashed border when never reached.


```ts
which: 'red' | 'green'
lit: boolean
reached: boolean
```

*From:* dot(which, lit, reached, title) 2328-2331; CSS .sema 186-191


> DROP THE title PROP. Java passes it, but both call sites pass a fixed sentence decided entirely by `which` ("reproduced: the test failed first" / "fixed: the same test then passed"), so the component should own its own tooltip and take three props instead of four.


### WhatHappened

The argument cell, three ways: an em dash when nothing has been said; a grey one-line note cut to 150 chars while the marker is still running; or, once a verdict exists, a disclosure whose closed line is one readable sentence and whose body is the flagged source followed by the whole verdict text.


```ts
summary: string — first paragraph of m/<slug>/summary.txt, "" if nothing interpreted it
verdictText: string — the settlement's verdict_text, RAW and unabridged
lastNote: string — last progress note / settled `because` / failed `cause`
flagged: FlaggedSource | null — passed to SourceExcerpt
```

*From:* index() 1702-1742; summary() 1541-1554; oneLine() 2334-2342; firstSentence() 2579-2598; cut() 2686-2688; CSS td.why 61-63


> THE CLOSED LINE IS DERIVED, NOT STORED: prefer summary.txt's first paragraph, else firstSentence(verdictText) — which flattens markdown, strips a leading restatement of the verdict (an argument opens "false-positive false-positive The static analyzer claims…"), skips any sentence ending in ':' or matching "looking at …", and wants ≥40 chars before it will accept one, falling back to the flattened opening. Keep that in the client and send the raw text; a pre-computed sentence throws away the argument the fold exists to show. The in-flight branch uses oneLine() (first non-blank line that isn't "---") then cuts at 150 with an ellipsis — the verdict branch is deliberately NOT truncated, because a reason cut at 200 chars is a reason nobody can check. This <details> is NOT fold(): fold() labels itself "(N chars)" and wraps a single <pre>, and here the label is a sentence and the body is code + pre.


### SourceExcerpt *(shared)*

The flagged line with four lines of context either side, line-numbered, the flagged one marked, syntax-coloured Java in a bordered pre.


```ts
excerpt: FlaggedSource | null — firstLine, flaggedLine, fileLength, lines[]
```

*From:* code() 2609-2611 → block() 2618-2622 → colourJava() 2665-2679; content from flagged(CHECKOUTS, repo, file, line) 2528-2564; CSS pre.flagged 64-65 and 138-145


> ESCAPING LIVES HERE AND NOWHERE ELSE — a caller once handed this a raw git diff and a patch containing '<' wrote markup into the page. In React that means the component escapes/renders tokens itself; never dangerouslySetInnerHTML the server's blob. Two facts the current blob smuggles in as text and the JSON should carry as data: the flagged line is marked with a ">> " prefix, and when the marker points past EOF it appends "line N — THIS FILE HAS M. The analyser ran against an older revision", which is the difference between a reader trusting the line number and knowing not to. Renders nothing when there is no checkout — a missing tree is a missing convenience, not an error. The source must be read blank-lines-and-all; dropping blank lines shifted every number after line 79 by four.


### TimeSpent

The `took` column: a duration, with "N event(s)" in small grey underneath. Em dash when the marker has no trace events yet.


```ts
spanMs: number — last trace `at` minus first trace `at` for this marker
events: number — trace lines carrying this marker key
```

*From:* index() 1568-1596 (span), 1561-1564 (events), 1743-1745 (markup); clock() 2348-2352


> WALL CLOCK, NOT MACHINE TIME: it is first-to-last event including every gap where the marker sat waiting, so it can read far longer than the work took. Zero renders as an em dash, so "nothing has happened" and "it took no time" look the same.


### HumanCost

The last column: what a person would have spent, as "3h 20m" or "45m". A grey em dash when nothing priced it.


```ts
minutes: number — sum of `priced` events' minutes for this marker
```

*From:* index() 1567/1577-1580 (sum), 1746 (markup); hm() 2344-2346


> AN ESTIMATOR THAT ANSWERED IN PROSE CONTRIBUTES 0, not a guess — num() swallows the unparseable field, so 0 legitimately means both "never priced" and "priced at nothing", and both show an em dash. hm() drops the hours part under 60 minutes; StateCounts' total does not. Same quantity, two formats, one screen.


### EmptyRun

"No markers queued and no prove has run." in grey, in place of the progress bar, counts and table.


```ts

```

*From:* index() 1644-1646; CSS .empty line 212


> Returned before progress() and the counts, so an empty run shows a header and one sentence — not a 0/0 bar and an empty table.


### Elsewhere

Three links at the foot of the table: settings →, what is wrong with the pipeline →, the whole trace, every marker →.


```ts

```

*From:* index() 1749-1751; CSS .back line 212


> The overwatch link is duplicated by FindingsButton in the header — that was deliberate: this one sits under 356 rows and is a link nobody scrolls to, which is why the count moved to the corner. Keep both or drop this one knowingly.


### LiveRefresh *(shared)*

Nothing. Subscribes to the /events SSE stream and, on this route, refetches and re-renders the page, restoring scroll position and which folds were open.


```ts
path: string — the route to refetch; '/' takes the whole-page branch
```

*From:* LIVE 238-270 and KEEP_OPEN 286-330, injected by head() line 2426; the /events route 526-556


> THE INDEX HAS NO CURSOR, so it re-renders wholesale rather than appending — in React that is just refetching the JSON, and scroll restoration becomes unnecessary. What does NOT become unnecessary: open folds. Today they are keyed by a fold's index within the page, which moves every time a marker settles, so the wrong row can spring open — key WhatHappened's disclosure by marker key. Fold state is per-tab (sessionStorage, keyed by pathname+search). The two-second #live poll in KEEP_OPEN is inert here: this page deliberately has no live panel, because the header already carries what the supervisor CONCLUDED and a paragraph of it thinking out loud is a slower way to learn less.


**API this screen needs**


```
No JSON exists for this route today (only /api/settlements, /api/trace, /api/feedback are served). GET /api/index would have to produce:

{
  "run": {
    "total": 356,                  // every key: queue ∪ settlements
    "settled": 214,                // state not in {proving, queued, interrupted}
    "beganAt": 1755000000000,      // min trace `at` over all markers; 0 = nothing has run
    "serverNow": 1755000900000,    // so the client can compute elapsed without trusting its clock
    "traceEvents": 48211,          // sum over all markers, blank-marker lines included
    "humanMinutes": 1483,          // sum of `priced` minutes across all markers
    "countsByState": {"false-positive": 180, "proving": 4, "queued": 12, "infra": 3},
    "findingsOpen": 3              // overwatch.jsonl verdict in {holds, unjudged}
  },
  "markers": [{
    "key": "org/repo|src/main/java/a/B.java|82|FB.DM_DEFAULT_ENCODING",
    "repo": "org/repo", "file": "src/main/java/a/B.java", "line": 82,
    "checker": "FB.DM_DEFAULT_ENCODING",
    "severity": "Critical",        // null for the ~74 src/it and src/test markers — never guess
    "state": "verified/pr-ready",  // RESOLVED, not settlements.state (see MarkersScreen notes)
    "hasSettlement": true,
    "redVerified": true, "greenVerified": false,
    "events": 214,
    "spanMs": 812000,              // last trace `at` − first trace `at` for this marker
    "humanMinutes": 45,
    "summary": "…first paragraph of m/<slug>/summary.txt…",   // "" if nothing interpreted it
    "verdictText": "…whole argument, unabridged…",            // "" while in flight
    "lastNote": "…last progress note / because / cause…",
    "flagged": { "firstLine": 78, "flaggedLine": 82, "fileLength": 240,
                 "lines": ["…", "…"] }                        // null when no checkout
  }]
}

Order of `markers` is load-bearing: queue order from markers.txt, then any settled key not in the queue appended. Do not sort server-side.
Send facts, not sentences: `verdictText` raw (firstSentence() is a client derivation), `beganAt` not "3h 12m", `severity`/`state` as recorded, `flagged.lines` as an array with the flagged index rather than a blob with ">> " glued on.
```


## One marker — `/marker?k=<repo|file|line|checker>&a=<agent|live|prompts|trace>&fold=1&from=<n> — `a` empty is the summary tab; `a=live|prompts|trace` hand off to live()/promptsFor()/events(), which are other screens' work`

For this one suspicion: what was claimed, at which line, what the builds actually did before and after the patch, what test and patch came out of it, and what each agent said last.


### MarkerHeader *(shared)*

The page top: back-crumb to the marker list, the file name as the title, and under it the full marker key followed by the settled state as a pill. Carries the settings, ask-the-supervisor and findings buttons that sit on every page.


```ts
markerKey: string — the `repo|file|line|checker` key; the title is the part after the last '/'
state: MarkerState | null — null while nothing has settled
back: { label: string, href: string } — 'all markers' → '/'
```

*From:* head(title, sub, back) Dashboard.java:2424-2438, called from marker():1815-1819 and (for the live tab) :1795-1797


> head() escapes `title` but appends `sub` as RAW HTML — marker() hand-builds an escaped string with a <span class='s …'> inside it. A React port must not keep a `sub: string` prop or it re-opens that hole; pass markerKey and state and let the component compose. The state shown is the LAST settlements.jsonl row matching the key (the loop at :1810-1814 overwrites, it does not break).


### StatePill *(shared)*

One word — the marker's state — in the colour its disposition earns.


```ts
state: MarkerState — 'false-positive' | 'by-design' | 'unprovable' | 'reproduced' | 'needs-review' | 'verified/pr-ready' | 'verified/pr-rejected' | 'proving' | 'infra' | 'queued' | 'interrupted'
```

*From:* css(state) Dashboard.java:2682-2684, used inline by marker():1816-1817 and index():1731


> css() returns the state as a class only if it matches `[a-z-]+`, else 'infra'. Both verified/pr-ready and verified/pr-rejected contain a slash, so THE TWO SUCCESS DISPOSITIONS ARE STYLED AS INFRA today. Take `state` and map it in TS; do not take a className.


### FindingsButton *(shared)*

A warning glyph in the header corner linking to /overwatch, with a count badge when the supervisor has findings still standing.


```ts
openFindings: number — findings in overwatch.jsonl whose verdict is `holds`
```

*From:* findingsButton() Dashboard.java:2404-2412


> Deliberately draws no badge at zero — a badge reading 0 trains readers to ignore it. Keep that rule inside the component; the prop is the count, not a boolean.


### ChainStrip *(shared)*

The tab row: a `summary` pill, then the five stages of the chain left to right, then `live`, `prompts`, `the record`, and links out to the supervisor and settings.


```ts
markerKey: string
current: '' | AgentName | 'live' | 'prompts' | 'trace'
runs: Record<AgentName, number> — how many times each agent answered, counted from trace `asked` events
```

*From:* tabs(key, current, events) Dashboard.java:2063-2097; the stage list is STAGES:2043-2049


> The five stages and their producer/critic pairs are a constant in Java (reproduce/fix/propose/argue/price), not data on the wire — decide whether the JSON ships the chain shape or React keeps its own copy. The counts must be computed from events BEFORE the tab dispatch: an earlier version dispatched first and two tabs rendered with no counts (comment at :1789-1791).


### ChainStage

One stage of the chain: its label, the producer chip, an arrow, the critic chip. Greyed out entirely when neither agent ever ran — which is the most informative thing on the strip.


```ts
label: string — 'reproduce' | 'fix' | 'propose' | 'argue' | 'price'
producer: { agent: AgentName, runs: number }
critic: { agent: AgentName, runs: number }
current: string — which chip is the open tab
```

*From:* the STAGES loop inside tabs(), Dashboard.java:2076-2090


> The ↺ 'the critic sent it back' glyph is inferred purely from producer.runs > 1 — nothing in the record says a critic rejected anything. And it REPLACES the arrow rather than sitting beside it, so a looped stage has no arrow at all. Derive it in the component from runs; do not add a `looped` prop, or Java keeps the inference.


### AgentChip *(shared)*

One agent's name with a bold run-count beside it, linking to that agent's tab.


```ts
agent: AgentName
runs: number
active: boolean — this is the tab being shown
markerKey: string — for the href
```

*From:* chip(key, agent, runs, current) Dashboard.java:2099-2104


> runs === 0 renders the `off` class AND omits the count element entirely — an agent that never ran shows no number, not a zero. Keep that distinction; it is the same 'never reached' vs 'happened and was nothing' idea as the semaphore's hollow lamp.


### MarkerTab

A plain anchor for one tab of the marker screen — no run count, no stage grouping.


```ts
markerKey: string
agent: string — empty means the summary tab
label: string
active: boolean
```

*From:* tab(key, a, label, current) Dashboard.java:2106-2109


> FINDING: this has no call sites left. ChainStrip writes its own <a class=pill> anchors inline (:2071-2073, :2091-2096) and never calls tab(). It is a component with no screen. Either drop it, or make ChainStrip's five hand-rolled pills use it — that is the port's chance to stop hand-writing anchors five times.


### Semaphore *(shared)*

Two lamps — RED then GREEN — saying whether a test failed before the patch and passed after it. Lit means the build said so, dim means it was reached and went the wrong way, hollow means it was never got to.


```ts
state: MarkerState — decides whether red was ever reachable
redVerified: boolean | null
greenVerified: boolean | null
```

*From:* flags(row) Dashboard.java:2313-2326


> FINDING: this is NOT on /marker today. flags() has exactly one caller, index():1732 — the marker list. Yet red_verified/green_verified live in settlements.jsonl per marker, and the two facts a state cannot carry are precisely the two facts this screen exists to show. Second finding: `reachedRed` excludes state 'queued' and 'not-a-bug' — 'not-a-bug' is not a disposition this pipeline emits (only the dead CSS rule at :184 still mentions it), so every false-positive marker gets a DIM red rather than a hollow one, i.e. reads as 'we tried and it did not fail' when nothing was ever run. Third: red_verified is written unquoted, and field() only learned to read unquoted values later (:2707-2710) — before that the semaphore never lit for any marker that had genuinely gone red. The JSON must send real booleans and a null for 'never recorded'.


### Lamp *(shared)*

One lamp of the semaphore, in one of three appearances, with the tooltip that says what it would mean.


```ts
which: 'red' | 'green'
lit: boolean — the build said so
reached: boolean — this stage was got to at all
title: string — 'reproduced: the test failed first' / 'fixed: the same test then passed'
```

*From:* dot(which, lit, reached, title) Dashboard.java:2328-2332


> Three states, not two — `lit`/`dim`/`none` — and the middle one is the interesting one. The tooltips are currently the ONLY place on the whole dashboard that explains that red means the test was supposed to fail.


### ClaimCard

What the analyser actually alleged: the checker's name, this pipeline's plain-English note on what that checker means, and the file and line it points at.


```ts
checker: string
file: string
line: number
claimNote: string | null — the pipeline's note for this checker, null when there is none
```

*From:* marker():1828-1841, using claimIs(checker):1524-1539


> The page splits the marker key on '|' into repo/file/line/checker itself (:1829-1834) — the JSON should just send the four fields. claimIs() reads a bundled resource /checkers/<checker>.txt (name sanitised to [A-Za-z0-9._-]), drops the first line, and takes up to the first blank line. Its no-note fallback sentence ('This pipeline has no note for X…') is prose, not a fact — send null and let the component write it.


### FlaggedSource *(shared)*

The flagged line with four lines either side, numbered, the flagged one marked with a >> gutter, Java-coloured. Says so explicitly when the line is past the end of the file.


```ts
lines: { n: number, text: string }[] — the window actually read
flagged: number — the line the analyser named
fileLines: number | null — total lines in the file, so the component can say the marker points past the end
```

*From:* flagged(CHECKOUTS, repo, file, line) Dashboard.java:2528-2563, wrapped by code()→block()→colourJava():2609-2679; called from marker():1843-1849


> Reads Files.readAllLines, NOT the record's read() — read() drops blank lines and shifted every number after the first blank line by one, which was nearly written up as an analyser bug (:2537-2542). If the JSON ships lines, that hazard moves to whoever builds the array. Window is ±4 (AROUND:2514) decided in Java. Returns empty and the whole block vanishes when /work/checkouts has no tree — the reader is told nothing, which is worth a state rather than an absence. block() colours everything as Java regardless of the file's language.


### Account

The lane interpreter's readable account of what happened to this marker, above all the artefacts — labelled 'what happened / read against the record'.


```ts
text: string
```

*From:* marker():1851-1858, using summary(results, key)[1]:1541-1554


> summary.txt is split at the first blank line: [0] is the headline the marker LIST shows, [1] is the body this screen shows. With no blank line both halves are the whole file, so the list and this page then say the same thing twice. Send them as two named fields, not an array.


### BuildOutcomes

What the builds actually did, in sentences: 'Before any patch: the test failed. (This is what it was meant to do.)' and 'After the patch: the test passed.' — plus the parenthetical scolding a red that passed.


```ts
builds: { phase: 'red' | 'green', passed: boolean, infra: boolean }[] — in order
```

*From:* marker():1860-1883, from trace events of kind `built`


> THE STATE THAT MEANS ITS OPPOSITE: phase 'red' is the run BEFORE the patch and is supposed to FAIL; a red that passed has demonstrated nothing, and that is the only place the page says so. `infra: true` means the build produced no test result at all — not a pass and not a fail, a third outcome. Java composes the English today; the component should, from the three booleans, so the wording can change without a redeploy of the record.


### TestArtifact

The reproducing test itself, with the path it was written to and the note that it was written to fail on the unfixed code.


```ts
path: string
code: string
```

*From:* marker():1885-1902 — the last trace event with kind=tool, tool=write_file and non-blank arguments.content


> Recovered by scanning tool arguments, and it takes the LAST such write from ANY agent, not the reproducer's specifically. settlements.jsonl already holds test_path and test_code and this screen ignores both — the JSON should serve those and delete the scan. Rendered in a plain <pre>, uncoloured, unlike FlaggedSource.


### FixDiff

The patch — what would actually change in the reader's repository — as a coloured unified diff.


```ts
diff: string — unified diff text
```

*From:* marker():1904-1935, wrapped by diff()→block(isDiff=true):2614-2637


> THE WORST DEPENDENCY ON THIS SCREEN: the patch is not recorded as an artefact anywhere, so marker() scrapes it out of the TEXT OF fix-critic's PROMPT, between the heading 'WHAT IT ACTUALLY CHANGED' and either '\nThe patch changes ' or '\nTHE PATCH DOES NOT TOUCH'. Reword either prompt and the fix silently disappears from the page. settlements.jsonl has a fix_diff field — serve that. Also: block() is the single place a diff gets escaped, added after a caller passed raw `git diff` output straight through and a patch containing '<' wrote markup into the page (:2600-2607).


### AgentAnswer

On an agent's tab: that agent's final reply in full, the prompt it was given folded beneath, and the rating box. Headed 'answered', or 'final answer, attempt N' when it went more than once.


```ts
agent: AgentName
reply: string
prompt: string
attempt: number — which attempt this was
attempts: number — how many it made in total
```

*From:* marker():1995-2005, over asked(mine, agent):2026-2035


> Answers are matched with equals(agent); tool calls on the same tab are matched with endsWith(agent) (:1943) — inconsistent, and an event whose agent is recorded with any prefix contributes tool calls but no answers.


### SupersededAttempt

An earlier answer the critic sent back: 'attempt N, superseded', with what it said and the prompt it got, both folded.


```ts
attempt: number
reply: string
prompt: string
```

*From:* marker():2006-2012


> Rendered newest-first below the final answer (the loop counts down), which is the reverse of the attempt numbering it prints. Worth deciding deliberately in the port rather than inheriting.


### Thinking

What the agent worked through, one fold per turn, most recent turn first — 'what it worked through' when there was only one, 'what it worked through, turn N' when there were several.


```ts
turns: string[] — thought texts in record order; the component reverses
```

*From:* marker():1962-1976, from trace events of kind `thought`


> Gathered BEFORE the early returns below it on purpose: an agent seven tool calls in has answered nothing, and its thinking is the only account of what it is doing. Any port that renders thinking only alongside an answer loses the live case.


### ToolLog

One fold listing every tool the agent reached for, each call's arguments in full and the result indented under an arrow.


```ts
calls: { tool: string, arguments: string, result: string }[]
answered: boolean — labels the fold 'what it reached for' vs 'what it has reached for' while still working
```

*From:* marker():1943-1957 (collection), :1988-1993 and :2019-2022 (render)


> Arguments are shown IN FULL and must stay that way — the argument to write_file IS the test, and the old 110-character cut showed the path and hid the only thing worth reading (:1949-1950). Matching is endsWith(agent), unlike answers and thoughts which use equals.


### AgentPending

An agent with no answer yet: either 'X has not run for this marker.' or 'working — N tool call(s), no answer yet' above whatever it has been thinking and reaching for.


```ts
agent: AgentName
calls: number
hasThinking: boolean
```

*From:* marker():1978-1993


> The distinction is the point: zero calls AND no thoughts is 'has not run'; anything else is 'working'. Reporting a mid-answer agent as not-run threw away the only live view of it, which is why the branch exists. Note 'has not run' is a claim about the record, not about the agent — a trace still being written reads the same as one that never started.


### Fold *(shared)*

A disclosure: a summary line with the body's character count appended, and the body preformatted inside.


```ts
label: string
body: string
defaultOpen: boolean
```

*From:* fold(label, body, expand) Dashboard.java:2363-2367


> An empty body renders NOTHING AT ALL — not an empty fold. The '(N chars)' is computed from body, so never pass a separate count. Default is OPEN; ?fold=1 collapses everything (open(e):2374-2376), because reading a prove is reading the prompts and a fold costs a click on every single thing the reader came for.


### RateAnswer *(shared)*

A box under an answer asking what the agent should have done instead, with a save button. Posts and comes straight back to the same tab.


```ts
markerKey: string
agent: AgentName
eventId: string — identifies the answer being rated
back: string — the URL to return to
prompt: string
reply: string
```

*From:* rate(marker, agent, event, back, prompt, reply) Dashboard.java:2258-2270, called from marker():2002-2003; the POST handler is route('/feedback'):1697-1710


> HAZARD: `event` today is an INTEGER POSITION — mine.indexOf(last) (:1994) — into a list that the agent-tab branch never sorts, while events() DOES sort by `at` (:1849). The same answer therefore has a different index depending on which tab computed it, and the index moves as the trace grows, so old feedback rows can point at the wrong answer. The JSON must give each answer a stable id. The prompt and reply ride along as hidden fields so the written row is a complete training example without a second read of the trace — keep that, it is why the file is usable.


**API this screen needs**


```
Two documents. The summary tab, GET /api/marker?k=<key>:
{
  "key": "repo|file|line|checker",
  "repo": "...", "file": "...", "line": 91, "checker": "DEREF_OF_NULL",
  "claimNote": "…first paragraph of the pipeline's note for this checker, or null…",
  "state": "false-positive | by-design | unprovable | reproduced | needs-review | verified/pr-ready | verified/pr-rejected | proving | infra | queued | interrupted | null",
  "settlement": {                      // straight off settlements.jsonl, null until it settles
    "verdictKind": "...", "verdictText": "...",
    "redVerified": true, "greenVerified": false,   // real booleans, null = never recorded
    "testPath": "...", "testCode": "...",          // replaces the write_file scan
    "fixDiff": "--- a/...\n+++ b/...",              // replaces the fix-critic prompt scrape
    "infraReason": "..." },
  "summary": { "headline": "…", "account": "…" },  // summary.txt, split named not indexed
  "flagged": {                                      // null when the checkout tree is absent —
    "requested": 91, "fileLines": 87,               // absent and "nothing to show" differ
    "lines": [ { "n": 87, "text": "…" } ] },
  "builds": [ { "phase": "red", "passed": false, "infra": false, "at": 1755… } ],
  "runs": { "reproducer": 2, "proof-critic": 1 },   // asked-count per agent → ChainStrip
  "openFindings": 3                                  // overwatch verdict=holds → FindingsButton
}
One agent tab, GET /api/marker/agent?k=<key>&a=<agent>:
{
  "agent": "fixer",
  "answers": [ { "id": "…stable…", "at": 1755…, "prompt": "…", "reply": "…" } ],  // oldest first
  "thoughts": [ { "id": "…", "at": 1755…, "text": "…" } ],
  "calls":    [ { "id": "…", "at": 1755…, "tool": "write_file", "arguments": "…", "result": "…" } ]
}
Three things this contract must fix rather than carry over: every event needs a stable id (RateAnswer posts a positional index today, into an unsorted list); red/green must arrive as booleans-or-null, not the string "true" (field() reads unquoted values only since :2707); and testCode/fixDiff must come from settlements.jsonl, because the page currently recovers both by scanning prompt text.
```


## Whole trace — `/trace  (variants: /trace?fold=1 to collapse the folds; /trace?from=N with header X-Fragment: 1 for the live tail — Dashboard.java:395-397)`

What is this pipeline doing — every prompt, thought, tool call, build and settlement across all markers, merged into one time-ordered story, so a reader watching a run in flight can see what it is up to right now rather than why one marker settled the way it did.


### TraceScreen *(shared)*

The whole page: header, the fold/unfold toggle, then every event in ascending time order, oldest at the top and newest at the bottom. Holds the cursor so the live stream can append. Renders EmptyTrace instead of the list when nothing has been traced yet.


```ts
events: TraceEvent[] — already sorted ascending by `at`
focusedMarker: string | null — null on /trace (Java passes key=""); a marker key on /marker?a=trace, which is the same function
expanded: boolean — false only when ?fold=1 is present; default is OPEN
nav: ReactNode — the tab row, empty on /trace; /marker?a=trace passes tabs()
```

*From:* events(), Dashboard.java:2117-2167; route registration 395-397; open() 2374-2376; cursor() 2386-2388


> The brief says "newest first". The code is the opposite: line 2127 sorts ASCENDING by `at`, the fragment path appends at the end (2139-2141), and the browser inserts new HTML with `beforeend` (line 266). Newest is at the BOTTOM. Port the behaviour, not the description — appending is what makes the live update non-destructive to open folds and scroll position. Two more things: `num()` (2355) turns a missing or malformed `at` into 0, so a bad line silently sorts to the very top of the run; and /trace passes an empty key, so the `state` lookup at 2129-2133 compares `field(line,"suspicion_key")` against "" — any settlement row missing that field matches, and a state pill for an unrelated marker lands in this page's subtitle. On /trace that subtitle state should simply not exist.


### PageHeader *(shared)*

The bar every page has: settings gear, envelope to /chat, findings badge, a `← all markers` crumb, the title ("whole trace") and a subtitle line ("every marker · 1041 event(s)").


```ts
title: string
subtitle: ReactNode
back: { label: string, href: string } | null
```

*From:* head(), Dashboard.java:2413-2439; the subtitle assembled by its caller at 2145-2149


> head() appends `sub` UNESCAPED (line 2438) — it is raw HTML because callers embed a state pill in it. On /trace the subtitle is literal text so nothing leaks, but on /marker it is `esc(key)` and the escaping lives at the call site, one caller away from the hole. In React the subtitle is a node and the hazard disappears; do not reintroduce a `subtitleHtml: string` prop. Also note esc() (2765-2767) only replaces & < > — never quotes — so nothing user-supplied may go into an attribute; see RateAnswer.


### FindingsBadge *(shared)*

A ⚠ in the header corner linking to /overwatch, with a count bubble when the critic has findings that still hold, and amber-tinted when so. Draws no number at zero.


```ts
openFindings: number — findings whose verdict is `holds` or unjudged
```

*From:* findingsButton(), Dashboard.java:2404-2411; count from holding(), 800-816


> Deliberately silent at zero (2400-2402): a badge that reads 0 on a clean run trains the reader to ignore it. Keep that. Today this reaches into a module-level `root` path (2503) because head() takes no results directory; in React it is just a number on the page payload.


### FoldToggle *(shared)*

A single link under the header reading "fold the long parts" when the folds are open, and "open everything" when they are collapsed.


```ts
expanded: boolean
focusedMarker: string | null — decides whether the target is /trace?fold=1 or /marker?k=…&a=trace&fold=1
```

*From:* events(), Dashboard.java:2150-2153


> The label names the ACTION, not the current state, so it inverts against `expanded` — a rebuilder reading `expanded ? "fold" : "open"` will assume it is a state label and flip it. Default is expanded: open() returns true when the `fold` param is absent (2374-2376), on the reasoning at 2369-2373 that reading a prove is reading the prompts. In React this becomes a real toggle, but keep it in the URL — the live fetch reuses `location.search` (line 262), so the fold choice has to survive into the fragment request or appended events arrive expanded when the page is not.


### TraceEventRow *(shared)*

One event: a left border coloured by kind, the marker's short name as a link above it (only on the whole-trace view), then a kind-specific body. An unrecognised kind renders as just its own name in the small-caps label slot.


```ts
event: TraceEvent — the discriminated union, tagged by `kind`
focusedMarker: string | null — when null this is the whole-trace view and the marker link is drawn
expanded: boolean — passed down to every Fold in the body
index: number — the event's position in this view, used only by RateAnswer
```

*From:* one(), Dashboard.java:2177-2223; kind→colour rules in CSS at 182-183, 202-205


> THE INDEX IS NOT AN IDENTITY. `index` is the position in the list this view happens to be showing: on /trace it is the index across every marker, on /marker?a=trace it is the index within that one marker. The same physical event therefore posts a different `event` number depending on which page you rated it from (events() 2163-2164 vs 1804), and feedback.jsonl already holds both kinds of number with nothing to distinguish them. Give events a real id in the JSON and pass that instead; keep `index` only as a React key if you must. Second: the default branch (2219) silently renders any unknown kind as a bare label — that is the correct behaviour for an append-only record that will grow kinds, so keep it rather than throwing.


### MarkerLink *(shared)*

The small grey line above an event on the whole-trace view: everything after the last slash of the marker key, linking to that marker's page.


```ts
markerKey: string — the full `repo|file|line|checker` key
```

*From:* one(), Dashboard.java:2183-2187


> The visible text is `key.substring(key.lastIndexOf('/')+1)` — the tail after the last SLASH, which for a key like `repo|src/main/java/Foo.java|82|CHECKER` is `Foo.java|82|CHECKER`, not the file name and not the marker slug. It is a different truncation from slug() (571-582) and from the marker page's own title, which uses the same expression. Do not "fix" it into a filename without checking the marker page agrees, or the link text and its destination's title stop matching.


### AnsweredEvent *(shared)*

An agent's turn: the agent name, the word ANSWERED, the full reply in a wrapped pre block, then the prompt it was given in a fold, then the rating form.


```ts
agent: string
reply: string
prompt: string
marker: string
index: number
```

*From:* one() case "asked", Dashboard.java:2189-2194


> The reply is never truncated and the prompt appears TWICE in the delivered bytes — once in the fold and once as a hidden form field (rate(), 2263). Prompts here run to tens of kilobytes, so on a long run this one case dominates the page size; in React the prompt is one string in the payload and the form reads it from props. This is the only kind that carries a rating control.


### ThoughtEvent *(shared)*

The agent name, the word THOUGHT, and its reasoning in a fold labelled "what it worked through".


```ts
agent: string
text: string
expanded: boolean
```

*From:* one() case "thought", Dashboard.java:2195-2197


> Styled apart from the other kinds — purple border and purple agent name (CSS 182-183) — because reasoning is not evidence. Nothing about that is in the record; it is a rule keyed on `kind`, which is exactly where it should stay.


### ToolCallEvent *(shared)*

The agent name, the tool's name in the label slot, then two folds: the arguments it was called with and what it returned.


```ts
agent: string
tool: string
arguments: string
result: string
expanded: boolean
```

*From:* one() case "tool", Dashboard.java:2198-2202


> `arguments` arrives as ordinary text with real newlines, not as \n — it is JSON that was itself a JSON string, so field() (2697-2701) has already peeled one layer. Treat it as text, not as JSON to re-parse. THE GAP: this is where the test file the reproducer writes and the patch the fixer produces actually pass through, and /trace renders both as flat escaped `<pre>`. code()/diff()/block()/colourJava()/colourDiff() (2609-2679) are never called on this route — their only callers are the index (1739) and the marker page (1845, 1934). Porting this screen faithfully means no syntax colour here; a SourceBlock/DiffBlock used by TraceEventRow would be new behaviour, and worth it, but say so rather than implying it exists.


### BuildEvent *(shared)*

The build phase in caps (RED / GREEN) where the agent name usually goes, then one word — passed, failed, or never ran — and the build output in a fold.


```ts
phase: string — RED | GREEN
passed: boolean
infra: boolean
summary: string
expanded: boolean
```

*From:* one() case "built", Dashboard.java:2203-2207


> A STATE THAT MEANS ITS OPPOSITE: `infra` wins over `passed` (2205-2206). infra=true renders "never ran" whatever `passed` says, because a build that never compiled cannot have failed the test — and "failed" here would read as evidence about the defect. Take the two booleans as props and derive the word; a `label: string` prop moves that judgement back into Java. Both fields are written UNQUOTED in the JSONL, which is the whole reason field() has the unquoted-value branch at 2707-2722 — the bug that branch fixed is why red_verified read empty for every marker that had genuinely gone red.


### SettledEvent

The disposition as a coloured pill — false-positive, verified/pr-ready, reproduced, infra … — followed by the reason it settled that way, in a pre block.


```ts
state: MarkerState
because: string
```

*From:* one() case "settled", Dashboard.java:2210-2212


> This is the event the whole run exists to produce, and on /trace it is the only place a disposition appears.


### StateBadge *(shared)*

One rounded pill carrying a marker's state, coloured by which state it is: green for verified, amber for reproduced and needs-review, grey for false-positive and by-design, blue and pulsing for proving, red for infra.


```ts
state: MarkerState
```

*From:* css(), Dashboard.java:2682-2684, with the palette in CSS 53-55, 184-185, 192, 198-200; used from one() at 2210 and from the header subtitle at 2148


> A LIVE BUG TO NOT PORT: css() returns the state as a class name only if it matches `[a-z-]+`, otherwise "infra". `verified/pr-ready` and `verified/pr-rejected` contain a slash, so they fall through to "infra" and render RED — the pipeline's best outcome shown in the colour of a broken machine. The CSS at line 54 defines `.verified-pr-ready` in green, and nothing has ever emitted that class. In React, map MarkerState → intent explicitly and let the two pr- states be green. Do not take a `className` or `colour` prop; the state is the fact.


### ProgressNote

A single dim line prefixed with a bullet — a note the run left about what it is doing. No agent, no timing.


```ts
note: string
```

*From:* one() case "progress", Dashboard.java:2208-2209


> The only kind with no `.ev` border colour of its own (CSS 202-205 lists the rest), so it recedes — correct, these are punctuation between the events that matter. Not to be confused with progress() at 2235-2249, the bar with the ETA, which belongs to the index and never renders here.


### PricedEvent

How long this would have taken a person — "35 min" where the agent name goes, the label HUMAN-EQUIVALENT, and the itemisation below it.


```ts
minutes: string
itemisation: string
```

*From:* one() case "priced", Dashboard.java:2213-2216


> `minutes` is read as a string and printed as one; nothing sums or formats it. Keep it a string in the JSON only if it really is one on disk — if it is a number, type it as a number and let the component write "min", because a total across a run is the obvious next thing anyone asks of it.


### FailedEvent

The word `failed` in the agent slot and the cause in a pre block, with a red left border.


```ts
cause: string
```

*From:* one() case "failed", Dashboard.java:2217-2218


> This is a prove that broke, not a marker that settled as unproven — different thing from a `settled` event carrying an infra state, and the two are easy to conflate when reading the feed.


### Fold *(shared)*

A disclosure triangle whose summary is a label plus the size of what is inside — "the prompt it was given (14203 chars)" — with the body as wrapped, escaped text.


```ts
label: string
body: string
defaultOpen: boolean
```

*From:* fold(), Dashboard.java:2363-2367; open-state persistence in KEEP_OPEN, 286-334


> Renders NOTHING when the body is empty (2364) — that is why a tool call with no result shows one fold and not two, and why a fold count cannot be predicted from the kind. The char count is the raw body length, after JSON unescaping and before HTML escaping; compute it from the same string you render. The browser today remembers which folds are open in sessionStorage keyed by DOM position (lines 291-301), a key that only holds because events are appended and never renumbered — in React, key that state by event id and the fragility goes away.


### RateAnswer *(shared)*

Under an agent's answer: a dashed rule, the line "Tell this agent what it should have done", a four-row textarea and a save button. Posts and returns you to the same scroll position on the same page.


```ts
marker: string
agent: string
eventIndex: number — posted as the field `event`
back: string — the URL to return to, /trace here
prompt: string
reply: string
```

*From:* rate(), Dashboard.java:2258-2270; hidden(), 2272-2274; the POST handler and Feedback row at 376-388 and record(), 2277-2286


> AN ESCAPING HAZARD THAT IS BITING RIGHT NOW: hidden() writes `value='…'` in single quotes and esc() (2765) escapes only & < >, never an apostrophe. Every prompt or reply containing `'` — which is most of them — is truncated at the first one, and the remainder is parsed as stray attributes. The feedback corpus therefore already holds mutilated training examples. In React the attribute is a prop and this cannot recur, but the existing rows are suspect. The six props are the API contract on purpose (2254-2257): prompt and reply ride along so the server writes a complete training example without re-reading the trace. If you give events real ids, collapse this to (eventId, note) and let the server look the pair up — that is strictly better, and is the one place where a smaller prop list is also a more honest one.


### EmptyTrace *(shared)*

A padded grey line where the feed would be, saying nothing has been traced yet — while still declaring a cursor of 0 so the live stream can start filling it.


```ts
focusedMarker: string | null
```

*From:* events(), Dashboard.java:2154-2160


> The copy is hardcoded "Nothing traced for this marker." and is shown unchanged on /trace, where there is no marker — take the prop and say "Nothing traced yet." when it is null. The comment at 2155-2157 records the more expensive bug: this branch used to return before emitting the cursor, so a page opened before the first event declared itself unable to take fragments and stayed empty for the entire prove. The React equivalent is subscribing to the stream even when the list is empty — the empty state is not a terminal state.


**API this screen needs**


```
Today: GET /api/trace (Dashboard.java:370) already returns `"[" + join(lines(trace)) + "]"` — the raw JSONL concatenated into an array. It is NOT what this screen needs: it is unsorted (lines() at 2452-2468 appends results/trace.jsonl, then each m/<marker>/trace.jsonl in directory order, so it is N ordered runs stapled together, not one story), untyped, carries no settlement state, and has no cursor. What the screen needs:

GET /api/trace?from=0
{
  "cursor": 1041,              // events.length; today emitted as <script>document.body.dataset.events=N</script> (cursor(), 2386-2388)
  "openFindings": 3,           // for the header badge; today computed in-process by holding(), 800-816
  "events": [                  // ALREADY SORTED ASCENDING BY `at` — the client must not have to re-derive the story
    { "id": "3d1f...#17",      // NEW. Nothing in the record identifies an event today; see TraceEventRow notes
      "at": 1755102233123,     // number, ms. Present on every line, used only for the sort today — nothing renders it
      "marker": "repo|path/File.java|82|FB.DM_DEFAULT_ENCODING",
      "kind": "asked" | "thought" | "tool" | "built" | "progress" | "settled" | "priced" | "failed",

      // kind: "asked"
      "agent": "reproducer", "prompt": "...", "reply": "...",
      // kind: "thought"
      "agent": "skeptic", "text": "...",
      // kind: "tool"
      "agent": "reproducer", "tool": "Write", "arguments": "{...}", "result": "...",
      // kind: "built"   — booleans, unquoted in the JSONL, which field() at 2702 handles specially
      "phase": "RED", "passed": false, "infra": false, "summary": "...",
      // kind: "progress"
      "note": "...",
      // kind: "settled"
      "state": "verified/pr-ready", "because": "...",
      // kind: "priced"
      "minutes": "35", "itemisation": "...",
      // kind: "failed"
      "cause": "..."
    }
  ]
}

Live tail: GET /events already exists (526-558) and pushes `{"trace":N,"settled":M}` every 2s when either count moves. The React screen keeps it as-is: when n.trace > cursor, fetch /api/trace?from=cursor and append. Do NOT re-fetch the whole array — the reason the Java does fragments (2135-2143) is that replacing the body closes every fold the reader opened.

Not needed and not served: nothing on this screen renders code(), diff(), block(), colourJava() or colourDiff(). Their only call sites are the index (1739) and the marker page (1845, 1934). See ToolCallEvent notes — this is a gap, not an absence of markup to port.
```


## The supervisor (/overwatch) — `/overwatch, with `?a=` selecting one of four views ("" = findings, "overwatch", "overwatch-critic", "trace") and `?fold=1` collapsing every disclosure. Dispatch is Dashboard.java:584-594.`

What is wrong with the pipeline itself — what the supervisor has concluded, whether its critic let the conclusion stand, which proves it cut short, and the record it worked through to say any of it.


### SupervisorScreen

The route shell: reads `a` and renders one of three bodies — FindingsView (default), SupervisorEventList filtered to one agent, or SupervisorEventList over the whole record. The tab row is built once and passed into whichever body wins.


```ts
view: 'findings' | 'overwatch' | 'overwatch-critic' | 'trace'
expand: boolean — every fold open unless ?fold=1
```

*From:* overwatch() 584-594; route registration 404-409


> overwatch() also takes `from: int` and `fragment: boolean` and uses neither. No supervisor view emits cursor(), and the LIVE script returns early when body.dataset.events is undefined (255-259) — so this whole screen is static and only updates on reload, while /trace and /marker append live. If React keeps that, keep it deliberately; if it polls, the events array must be cursored or the reader's open folds get blown away. An unrecognised ?a= value falls through to the findings body but matches no tab, so the row renders with nothing lit.


### PageHeader *(shared)*

The bar on every page: settings gear, an envelope to /chat, the findings badge, an optional back crumb to '/', the h1 and a subtitle line.


```ts
title: string
sub: ReactNode — the tally line
back?: { label: string; href: string }
```

*From:* head() 2413-2439 (two overloads); called from reported() 716-721 and supervisorEvents() 631


> `sub` is appended UNESCAPED (line 2438) — reported() passes an HTML entity (&middot;) through it. In React this must become nodes, not a string, or the middot is the least of it. Also: reported() passes back="all markers"; supervisorEvents() passes no back at all, so three of the four views have no exit crumb. That asymmetry is not intentional — it is just two call sites.


### FindingsBadge *(shared)*

A warning glyph in the header, with a count bubble, linking to /overwatch. Drawn plain when nothing stands.


```ts
open: number — holds + unjudged
```

*From:* findingsButton() 2404-2411; count from holding() 800-809


> Deliberately shows no bubble at zero (javadoc 2400-2403: a badge reading zero teaches readers to ignore it). `refuted` is excluded but `unjudged` counts — a finding the critic never reached is not one it dismissed. On this screen the badge and FindingTally read the same file in the same request, so they should be one number in the payload rather than two reads.


### SupervisorTabs *(shared)*

The four supervisor tabs — findings, overwatch, overwatch-critic, the record — plus a trailing settings link that is never lit.


```ts
current: 'findings' | 'overwatch' | 'overwatch-critic' | 'trace'
```

*From:* supervisorTabs() 597-607


> This is the interesting one. tab(key, a, label, current) (2106-2109) cannot be reused: it builds /marker?k=… URLs and these are not markers — the Java comment at 596 says exactly that. The shared component is a TabRow taking items of { href, label, on }, and tab()/supervisorTabs() both become callers that compute hrefs. Likewise chip(key, agent, runs, current) (2099-2104) is the right shape for the two agent tabs — an agent with a run count and an `off` state when it has never spoken — but the supervisor tabs carry no counts because nothing computes them.


### FindingTally

The findings subtitle: "3 hold, 1 refuted, 3 unjudged · 2 prove(s) restarted", or "the supervisor has not reported yet".


```ts
holds: number
refuted: number
unjudged: number
restarts: number
```

*From:* reported() 713-721


> The empty branch tests `all.isEmpty()` — findings only. With zero findings and five restarts the subtitle says "the supervisor has not reported yet" and the restart count silently vanishes, while RestartLog renders five restarts directly beneath it. In React the empty case should be `holds+refuted+unjudged === 0 && restarts === 0`. Also note unjudged is derived as size - holds - refuted, which folds blank verdicts in the same way verdictOf() does — keep one rule, not two.


### RestartLog

A single row labelled "the tree was cut here", holding a disclosure titled "N restart(s)" that lists each cut prove: its id, which attempt, whether it was killed, and the reason given.


```ts
restarts: Restart[] — { at, id, marker, attempt, killed, by, why }
expand: boolean
```

*From:* reported() 723-733; records written by Supervisor.record() 262-269


> Java flattens the whole list into one preformatted blob inside a fold, so it renders four of the seven recorded fields. The dropped ones matter: `by` distinguishes a restart the supervisor spent from its allowance of two from one a person ordered with the /reprove button, and that distinction was a real bug once (route comment 426-430; Supervisor.restarts() 173-193 counts only lines without by="person"). A reader looking at this fold today cannot tell who cut the tree. `id` is a directory slug, so it is linkable through the same slugs map LinkedFindingText uses, and `marker` is the exact key — neither is a link today. Give each restart its own row; the concatenated text block is not a design, it is a StringBuilder.


### FindingCard

One finding: an anchored row (id f0, f1 …) tagged "overwatch", a verdict pill, the finding text with any marker slugs it names turned into links, a disclosure holding what the critic said, and a rating form under it.


```ts
finding: Finding — { at, verdict, finding, judgement }
index: number — position in file order
slugs: Record<string, string>
expand: boolean
```

*From:* reported() 738-761


> `index` is the index in the FILE, not in the rendered order — the page walks holds, then unjudged, then refuted, and the id/anchor/feedback-event all keep the original number. Any React reordering must preserve it or every feedback row written from this page points at the wrong finding. The row's border colour comes from borrowed classes: `ev asked` (blue) for holds and unjudged, `ev tool` (grey) for refuted, so refuted findings are visually demoted rather than hidden — deliberate, per the comment at 736-737. The anchor exists but nothing links to it: rate() is given back="/overwatch" with no #f{index}, so saving feedback returns the reader to the top of the page.


### VerdictPill

A small state pill reading holds, unjudged or refuted, coloured by which one it is.


```ts
verdict: 'holds' | 'unjudged' | 'refuted'
```

*From:* reported() 750-753; normalisation in verdictOf() 821-824


> Today it maps holds→class `settled`, refuted→`infra`, unjudged→`needs-review` — those are MARKER disposition names being reused for a supervisor verdict, and `infra` everywhere else in this app means "never ran", the opposite of "the critic knocked it down". So this must NOT share a component with the settled-state pill in one() (2210-2211) even though the markup is identical: same shape, different vocabulary. Take the verdict, pick your own colour, and give refuted its own token.


### LinkedFindingText *(shared)*

The body of a finding as written, with every queued marker's directory slug it mentions turned into a link to that marker's page.


```ts
text: string
slugs: Record<slug, markerKey> — longest slug first
```

*From:* linked() 678-691; map built by slugs() 694-707 from markers.txt


> Two hazards baked into the Java, both of which change shape in React. First, order: it escapes and THEN links (comment 675-676), because the text is written by an agent and would otherwise put markup on the page — in React the escaping is free, so only the linking survives. Second, the sentinel dance: slugs are sorted longest-first and each substitution is wrapped in  / so a slug that is a prefix of a longer one does not get its middle relinked. The React equivalent is one split pass over a longest-first alternation, not repeated replaces. If markers.txt is missing or empty the map is empty and the text renders plain — that is the normal state before a run starts, not a failure.


### RateAnswer *(shared)*

A textarea and save button under an answer, posting the note plus the prompt and reply as one training example, then returning the reader to the page they were on.


```ts
marker: string
agent: string
event: number
back: string
prompt: string
reply: string
```

*From:* rate() 2258-2270; posted to /feedback route 374-388, written by record() 2277-2286


> Six props and already at the limit; it is the same component the marker screens use, unchanged. What is odd here is the arguments: reported() passes marker="overwatch", agent="overwatch", prompt=the finding, reply=the judgement. So a feedback row from this page is attributed to a marker that does not exist, and the prompt/reply pair is really a finding/judgement pair. If the training corpus is ever read by agent, the supervisor's critic's work is filed under the supervisor.


### Fold *(shared)*

A disclosure with a label and the character count of its contents, open by default unless the page asked for everything folded.


```ts
label: string
body: string
expand: boolean
```

*From:* fold() 2363-2367; default from open() 2374-2376


> Renders NOTHING when body is empty — that is load-bearing here: an unjudged finding has judgement "", so "what the critic said" simply does not appear, and its absence is the signal. A React version that renders an empty disclosure changes what an unjudged finding looks like. The "(N chars)" in the summary is the raw length, and open-by-default is deliberate (javadoc 2369-2373): reading a prove is reading the prompts.


### SupervisorEventList

A titled page of the supervisor's trace events, newest first, uncapped, one TraceEvent per line — either the whole record or just one agent's share of it.


```ts
title: string
events: TraceEvent[]
expand: boolean
self: string — the URL feedback forms return to
```

*From:* supervisorEvents() 629-640; callers supervisorRecord() 618-626 and supervisorAgent() 650-659


> Newest first, unlike a marker's trace, and for a stated reason (javadoc 643-649): a prove is read after it settles and runs forwards; the supervisor is read while running and the question is what it just said. Uncapped and untrimmed on purpose — roughly 8MB after an afternoon (javadoc 609-617); the author's position is that a page silently showing part of a record reads as the record. A React list should virtualise rather than slice. Two sorting notes for a rebuilder: read() hands back an immutable list and sorting it in place threw out of the handler once, answered by an empty 20ms reply that looked exactly like a page too big to build (comment 619-622) — so sort a copy. And supervisorAgent() filters on an exact agent-name match against the two hardcoded tab values, so an event from any third supervisor agent appears only under "the record".


### TraceEvent *(shared)*

One event, switched on its kind: an agent's answer with the prompt folded beneath it, a thought, a tool call with arguments and result, a build verdict, a progress note, a settlement, a price, or a failure.


```ts
event: TraceEvent
markerKey: string — "" on this screen
index: number
expand: boolean
self: string
```

*From:* one() 2177-2223; called from supervisorEvents() 637


> The busiest component and shared verbatim with /trace and /marker, but this screen feeds it something the other callers do not. Because markerKey is "", it renders a crumb linking to /marker?k={event.marker} (2183-2187) — and the supervisor's trace sets marker="overwatch" for its own events (Overwatch.java:63-64, JsonlTrace:32) or a directory slug for progress lines about a prove (Supervisor.java:274). Neither is a marker key (repo|file|line|checker), so on this screen every one of those crumbs links to a marker page for a marker that cannot exist. Fix it in the payload — carry the real key or carry nothing — not with a prop that suppresses the link. Second: `at` is never rendered anywhere in this component, even though the entire list is sorted by it; "newest first" is asserted in the subtitle and unverifiable on the page. ago() (1023) exists and is not called here.


### EmptyNote *(shared)*

A quiet block of prose where a list would be, saying what the emptiness means.


```ts
children: ReactNode
```

*From:* reported() 762-767 and supervisorEvents() 632-635


> Two different texts, and both do real work rather than saying "no data": the findings one says a quiet page is a good sign and points at the tabs; the events one says the supervisor looks on its own schedule. Keep the copy as content, not as a prop default. Note the findings empty block is appended AFTER the verdict loops, so a page with restarts and no findings shows the restart fold above this note telling the reader nothing has been reported.


**API this screen needs**


```
GET /api/overwatch?a=<view> — one payload, four views. Nothing here is JSON today; reported()/supervisorEvents() compute and emit HTML.

{
  "view": "findings" | "overwatch" | "overwatch-critic" | "trace",
  "open": 7,                        // holds + unjudged, for the header badge (holding(), 800-809)
  "tally": { "holds": 3, "refuted": 1, "unjudged": 3, "restarts": 2 },
  "slugs": { "<dir-slug>": "repo|file|line|checker" },   // slugs(), 694-707, from markers.txt
  "findings": [                     // overwatch.jsonl, file order = chronological ascending
    { "index": 0,                   // index in file order; the feedback row's event id
      "at": 1755012345678,
      "verdict": "holds" | "unjudged" | "refuted",   // blank normalised to "unjudged", verdictOf() 821-824
      "finding": "## finding …",
      "judgement": "" }             // "" is how an unjudged finding is shaped, not an error
  ],
  "restarts": [                     // restarts.jsonl, Supervisor.record() 262-269
    { "at": 1755…, "id": "<dir-slug>", "marker": "repo|file|line|checker",
      "attempt": 2, "killed": true, "by": "person" | "" }   // "" (absent) means the supervisor
  ],
  "events": [                       // overwatch-trace.jsonl, ALWAYS sorted at DESC
    { "at": 1755…, "kind": "asked|thought|tool|built|progress|settled|priced|failed",
      "agent": "overwatch" | "overwatch-critic",
      "marker": "overwatch",        // NOT a marker key — see TraceEvent notes
      "prompt": "…", "reply": "…",  // kind-specific, exactly the keys one() reads (2188-2220)
      "text": "…", "tool": "…", "arguments": "…", "result": "…",
      "phase": "RED", "passed": "true", "infra": "false", "summary": "…",
      "note": "…", "state": "…", "because": "…",
      "minutes": "…", "itemisation": "…", "cause": "…" }
  ]
}

Two things the route already has in hand and throws away, and the JSON should carry:
- overwatch-settlements.jsonl is passed into overwatch() (line 408) and never read by any branch. The supervisor's own settlements are unreachable from every view. Either serve them or stop opening the file.
- Per-agent event counts. supervisorTabs() hardcodes the two agent names with no counts; if those tabs become Chips, the payload needs "agents": [{"key":"overwatch","events":412}, …]. Nothing computes that today.

The response should NOT include: any CSS class, the pill colour, the "N chars" fold summary, or the pre-joined restart paragraph. All four are decisions the components make from the fields above.
```


## Ask the supervisor (the chat) — `/chat`

What does the agent that has already read the whole run say about it — asked in your own words, answered here, with the answer arriving while you watch.


### AskSupervisorScreen

The whole route: header, the transcript of turns, then exactly one of three tails — the live partial answer, the restart notice, or nothing — then the notice line and the ask box. While an answer is coming it also emits a 3-second meta refresh.


```ts
turns: ChatTurn[] — oldest first, every turn, not just the last twenty
answering: boolean — an answer is being written right now
unanswered: boolean — the last turn is a question with nothing under it
live: StreamState | null — the partial answer, present only while answering
said: string — what the POST replied, empty when the question was taken
markers: Record<slug, markerKey> — for linking marker names inside answers
```

*From:* chat(), Dashboard.java:952-1000; route at Dashboard.java:506-517


> The 3-second meta refresh (line 956) exists ONLY while answering, and on this route it is the only thing that advances the live panel — the 2-second poller in KEEP_OPEN (Dashboard.java:308-330) looks for an element with id=live, which this page never renders. Port it as a poll gated on `answering` that stops when the flag drops; a page that keeps refreshing after the answer lands is the thing the meta tag was chosen to avoid, because there is nothing to lose while you wait and everything to lose while you read.
> 
> `answering` is a process-wide AtomicBoolean (Chat.java:43), not per-reader: every open /chat tab sees the same flag and has its box disabled by somebody else's question.
> 
> The three tails are mutually exclusive and one of them means the opposite of what it looks like: `unanswered` is not "still thinking", it is "the dashboard restarted mid-reply and no answer is coming" (Chat.java:98-103). Rendering it as a spinner is the bug the Java comment at 983-985 was written to prevent.
> 
> `turns` is all of them; only the last KEEP=20 go back to the model (Chat.java:36, 189-191). The page is not the prompt.


### ChatTranscript

The column of turns, oldest at the top, newest just above the ask box. When nothing has been asked, a dim paragraph saying so and what the supervisor can see instead.


```ts
turns: ChatTurn[]
markers: Record<slug, markerKey>
readable: boolean — false when the record exists but would not parse
```

*From:* chat(), Dashboard.java:962-989 (the <div class=chat> and its empty state at 964-968)


> The empty state is copy, not an error: it tells a first-time reader what the agent can see (every marker's state, builds, answers, settlement, and any trace it wants to open). Do not replace it with a bare "no messages".
> 
> `readable` has no counterpart in Java — Chat.turns() swallows an IOException and returns an empty list (Chat.java:88-92), so a corrupted chat.jsonl renders as a friendly welcome. That is worth one distinguishable state.
> 
> Order is append order from the file; there is no sort. Do not re-sort by `at` — a turn recorded with at=0 would jump to the top.


### ChatTurn

One thing said: a small uppercase byline reading "you" or "supervisor" followed by "· 4m ago", then the text in a left-bordered block — blue border for yours, grey for the supervisor's — with the author's own line breaks kept.


```ts
at: number — epoch ms; 0 means the record has no timestamp
who: string — the recorded speaker, free text
text: string — raw model or human text, unescaped
markers: Record<slug, markerKey>
```

*From:* chat(), Dashboard.java:969-978; the Turn record and mine() at Chat.java:50-55


> `mine` is not a stored fact — it is `who === "you"` (Chat.java:52-54). Anything else, including a value nobody planned for, renders as the supervisor. Keep taking `who` and derive the side; a `mine: boolean` prop would put the decision in Java where it is not recorded.
> 
> `at === 0` omits the whole "· Nago" span (line 972). It does not mean now, and it must not fall back to now.
> 
> The body is `white-space: pre-wrap` (Dashboard.java:125-126), so the text's own newlines are load-bearing — but Chat.answer() strips the ends before recording (Chat.java:151-155) precisely because a reply that opened with two newlines rendered an inch below its own name. Strip at the ends, keep everything inside.
> 
> Never render `text` as HTML. See MarkerLinkedText.


### MarkerLinkedText *(shared)*

Agent prose as plain text, with any marker directory slug it happens to name turned into a link to that marker's own page.


```ts
text: string — raw, unescaped
markers: Record<slug, markerKey> — slug to the `repo|file|line|checker` key
```

*From:* linked(), Dashboard.java:678-691; the map from slugs(), Dashboard.java:694-707; called for chat at Dashboard.java:977


> Escape first, link second (line 679, and the comment at 974-975). This is the only thing stopping a model's answer from putting markup on the page, so in React it must build an array of strings and <a> elements — dangerouslySetInnerHTML here reintroduces exactly the hole the ordering closes.
> 
> Slugs are matched longest-first: slugs() sorts the keys by descending length (lines 699-701) so a slug that contains a shorter one is linked whole instead of having its middle replaced. A Map preserves that order in JS; a plain object does not guarantee it — sort the entries in the component rather than trusting the JSON.
> 
> The U+0000/U+0001 sentinels (lines 685-690) exist because a second pass would otherwise rewrite a slug sitting inside an href already emitted. A node-array implementation gets this for free: only match against text nodes not yet linked.
> 
> The link target is `/marker?k=<urlencoded marker key>` — the map value, not the slug.


### Ago *(shared)*

How long ago, in the unit a person reading a conversation wants: "12s ago" under a minute and a half, "7m ago" under ninety minutes, "3h ago" beyond.


```ts
at: number — epoch ms
```

*From:* ago(), Dashboard.java:1023-1029; called from chat() at Dashboard.java:972


> Thresholds are 90 seconds and 5400 seconds, and there is no day unit — a conversation left open overnight reads "31h ago".
> 
> It is computed against the wall clock at render time, which in Java means it is recomputed by the 3-second refresh and in React means it needs a ticker. A one-shot format leaves a chat that has been open for ten minutes saying "2s ago".
> 
> THERE ARE TWO OF THESE. StreamPanel formats the same fact with a different rule ("nothing yet" / "quiet 4m" / "12s ago", Dashboard.java:924-928) and they disagree in the overlap: at 100 seconds this says "1m ago" and the panel says "quiet 1m". Porting them as one component means picking a winner; porting them as two means keeping a discrepancy nobody chose.


### StreamPanel *(shared)*

A disclosure fold, open by default, whose summary is who is speaking, which agent it is, and how long since the last token; inside, the tail of what they have written so far in a monospace block.


```ts
who: string — the speaker; "supervisor" on this screen, a marker slug everywhere else
agent: string — the named agent behind it, blank if unknown
at: number — epoch ms of the last write; 0 means nothing has arrived
text: string — the tail of the partial answer
open: boolean — whether the fold starts expanded
```

*From:* panel(), Dashboard.java:903-933 (LIVE_TAIL at 936); called for chat at Dashboard.java:981 with Chat.live(), Chat.java:63-65


> THIS IS THE SHARED COMPONENT USED WITH DIFFERENT DATA, and it is the interesting finding on this screen. Everywhere else panel() is given a marker slug and `m/<slug>/trace.jsonl.live` (Dashboard.java:849, 877); here it is given the literal string "supervisor" and `chat-trace.jsonl.live`, a file that belongs to no marker. Three things follow from that mismatch:
>   1. The CSS is scoped `.live details.stream` (Dashboard.java:88-94) and /chat renders the panel inside `.chat`, not inside a `.live` box — so on this one route the fold has none of its styling. The React port fixes this by accident, which is worth knowing before someone calls it a regression.
>   2. The 2-second poller refreshes only a container with id=live (Dashboard.java:308-330). /chat has none, so its panel advances solely on the meta refresh.
>   3. The fold's DOM id is `live-<who>` (line 930), which here is the stable `live-supervisor` — that is the key KEEP_OPEN uses to remember a reader who collapsed it (Dashboard.java:289-292), so keep the id stable across renders.
> 
> Only the last 4000 characters of `text` are shown, and it is the END on purpose (lines 926-928): a reasoning turn runs to tens of thousands of characters, and opening on the beginning shows the same paragraph for four minutes. If the JSON sends the whole partial, the component still takes the tail.
> 
> `who` is truncated to 46 characters in the label (line 920). Blank `text` renders as a single ellipsis, not as an empty box.
> 
> Its relative-time rule is its own and not Ago's — see the note there. `at`, `agent` and `text` are the three lines of the .live file (Dashboard.java:906-918), which is being rewritten while it is read; the parse is deliberately all-or-nothing, and a torn read renders as "nothing yet" rather than as an error.


### AskBox

A three-row textarea and an "ask" button. While an answer is coming both are disabled and the placeholder changes from "ask about the run… (enter sends)" to "answering the last one…".


```ts
answering: boolean — disables the box and swaps the placeholder
onAsk: (question: string) => void
```

*From:* chat(), Dashboard.java:993-1000; the key handler in SEND_ON_ENTER, Dashboard.java:1012-1021; the POST arm of the route, Dashboard.java:506-513


> Enter sends, Shift+Enter is a newline (the reasoning is at 1004-1011: almost every message is one line, so reaching for the button after each is the whole friction). The handler is guarded twice — it does nothing if the textarea is disabled, and it swallows the keypress without sending when the value is only whitespace.
> 
> The disabled state IS the confirmation that the question was taken; there is no separate "sent" indicator. Do not clear-and-re-enable optimistically.
> 
> The POST is answered with a 303 today so the meta refresh cannot re-post the question (Dashboard.java:503-505 explains it: a page that re-asks itself every three seconds is a question asked twenty times before its first answer). Over JSON that constraint disappears — post, then refetch — but the invariant behind it does not: one question in flight, and the second one is refused rather than queued.
> 
> `autofocus` on the textarea is why this route is usable at all from the envelope icon in the header.


### AskNotice

A single dim line between the transcript and the box, saying why the last question did not go through.


```ts
said: string — empty when the question was accepted
```

*From:* chat(), Dashboard.java:990-992; the strings come from Chat.ask(), Chat.java:113-131


> This is NOT a turn and must not be styled as one — it is the return of Chat.ask(): "still answering the last one" when a question arrives while one is in flight, or "could not write the question down: <message>" when the record could not be appended. On success it is empty and the redirect goes to bare /chat.
> 
> Today it travels in `?said=` (line 509-510), which means it survives every one of the 3-second refreshes and sits on screen until the reader navigates away. In React it should be transient state from the POST response and clear on the next successful ask.
> 
> The failure case matters more than it looks: "could not write the question down" means the question is not in the record at all, so the transcript above it is complete and correct — there is nothing missing to explain.


### PageHead *(shared)*

The bar every page here has: the title, a dim subtitle under it, a back crumb in the top-left, and three icon links in the top-right — settings, ask the supervisor, and findings.


```ts
title: string
sub: ReactNode — the explanatory line under the title
back: {label: string, href: string} | null
findingsOpen: number — forwarded to FindingsBadge
```

*From:* head(), Dashboard.java:2424-2438 (two-argument overload at 2413-2415); called for this screen at Dashboard.java:958-962


> `title` is escaped, `sub` is NOT (compare lines 2436 and 2437) — the subtitle is trusted HTML today and this screen's carries an &mdash; entity. As a prop it must be text or nodes, never an HTML string; that asymmetry is a hole waiting for the first page that puts a repo name in its subtitle.
> 
> The back crumb's href is hard-coded to "/" (line 2432) no matter what the label says — here the label is "all markers" and it happens to be right. Making href a prop is the port's chance to stop that being a coincidence.
> 
> The envelope icon links to /chat, so on this route it points at the page you are already on. Left as-is it is harmless; marking the current route is a small honest improvement.
> 
> head() also emits <style> plus the LIVE and KEEP_OPEN scripts (line 2425-2426). None of that is the component's job in React — the stylesheet is the app shell, the SSE reconnection is a hook, and fold memory belongs to whatever renders the folds.


### FindingsBadge *(shared)*

A warning icon in the header corner, amber with a small count on it when the critic has findings nobody has dismissed, plain grey when it has none.


```ts
open: number — findings whose verdict is holds or unjudged
```

*From:* findingsButton(), Dashboard.java:2404-2411; the count from holding(), Dashboard.java:800-809


> Zero renders NO badge, not a badge reading 0 — the comment at 2398-2401 is explicit about why: a control that shows zero on a clean run teaches the reader to ignore it, and this one has to still mean something on the day it says nineteen. The link itself is always present.
> 
> `open` counts holds AND unjudged, and excludes refuted (Dashboard.java:803-806). Counting unjudged is deliberate: a critic that could not be reached must not be able to suppress a warning by failing. Take the number, or take the three counts and add them here — but do not take a colour or a CSS class.
> 
> The title text differs by state ("3 finding(s) the critic has not dismissed" versus "what is wrong with the pipeline"), which the component picks from `open`.


**API this screen needs**


```
GET /chat.json — the whole screen in one document (today: Dashboard.java:506-517 returns HTML from chat()).

{
  "title": "ask the supervisor",
  "sub": "the agent that watches this run, over the whole record. It reads; it cannot restart or set aside a prove — those are buttons on a marker's own page.",
  "back": {"label": "all markers", "href": "/"},
  "findingsOpen": 3,                     // holding(overwatch.jsonl): verdict holds OR unjudged
  "answering": true,                     // Chat.answering(), process-wide
  "unanswered": false,                   // Chat.unanswered(): last turn is mine and nothing runs
  "readable": true,                      // NEW: false when chat.jsonl exists but did not parse
  "turns": [                             // Chat.turns(), oldest first, ALL of them
    {"at": 1755100000000, "who": "you",        "text": "why do the FB.DM checkers keep timing out?"},
    {"at": 1755100240000, "who": "supervisor", "text": "three of the four…"}
  ],
  "live": {                              // present only while answering; null otherwise
    "who": "supervisor",                 // literal, not a marker slug
    "agent": "chat",                     // line 1 of chat-trace.jsonl.live
    "at": 1755100261000,                 // line 2, epoch ms
    "text": "…last 4000 chars, the END not the start…"
  },
  "markers": {                           // slugs(markers.txt), longest key first
    "acme-src-Foo-java-91-FB_DM_DEFAULT_ENCODING": "acme|src/Foo.java|91|FB.DM_DEFAULT_ENCODING"
  }
}

POST /chat {"q": "…"} -> 200 {"said": ""} on accepted, {"said": "still answering the last one"} or
{"said": "could not write the question down: …"} otherwise. Today this is a 303 to /chat?said=…
(Dashboard.java:506-513) so a refresh cannot re-post; over JSON the redirect is unnecessary and
`said` should be the POST response, not a URL parameter.

Three notes on the shape:
- `markers` is the same map every screen that renders agent prose needs (overwatch uses it at
  Dashboard.java:754). Split it to GET /markers.json and cache it client-side; it changes only when
  the queue changes, and inlining a 356-entry map into a 3-second poll is the whole payload.
- `live` should be polled on its own (GET /chat/live.json) while `answering` is true and not at all
  when it is false. The Java page uses <meta http-equiv=refresh content=3> for exactly this and
  drops the tag the moment the answer lands.
- `readable` does not exist in Java today: Chat.turns() returns an empty list both for "nothing has
  been asked" and for "the file would not parse" (Chat.java:88-92), so the page shows the welcome
  copy over a broken record.
```


## the model — model, endpoint and bounds, with the API key field — `/settings?a=model`

What every prover will ask, and whether there is a credential to ask it with — changeable without killing the pool, because a prove is a fresh process per marker.


### SettingsTabs *(shared)*

The five-link row shared by all four settings screens: prompts / the run / the model / the subject, plus a trailing link out to the supervisor that is styled like the others but is not a tab of this screen.


```ts
current: 'prompts' | 'run' | 'model' | 'subject' — which screen is showing; the component picks its own highlight
```

*From:* settingsTabs(), Dashboard.java:1075-1086; called from theModel() at :1331


> BUG TO NOT PORT. Line 1077 marks prompts active with `current.equals("run") ? "" : "on"` — the negation of the run test, not a test for prompts. On /settings?a=model TWO tabs are lit (prompts and the model), and the same on ?a=subject. Only ?a=run looks right. Take `current` and compare it to each tab's own key. Also: the fifth link (the supervisor, /overwatch) can never be `on` — it is a departure, not a tab, and should probably render past a spacer.


### PageHeader *(shared)*

The bar every page opens with: gear to /settings, envelope to /chat, the findings warning, an optional back crumb, then the h1 and a subtitle line. Here the title is 'the model' and the subtitle is one of two sentences about whether the values are edited.


```ts
title: string — 'the model'
sub: ReactNode — 'edited — the environment's values are underneath' or 'every value is the environment's or the code's'
back: string — 'all markers', linking to /; omit for no crumb
openFindings: number — passed through to FindingsButton
```

*From:* head(String,String,String), Dashboard.java:2424-2439; invoked by theModel() at :1328-1330


> `title` goes through esc() but `sub` is appended RAW (:2437-2438) because callers hand it entities — theModel passes `&mdash;`. In React make sub a ReactNode and use a real em-dash; do not dangerouslySetInnerHTML it. The back crumb is hardcoded to href='/' regardless of the label text (:2436), so `back` is a label, not a destination.


### FindingsButton *(shared)*

The warning glyph in the header, carrying a count badge and a redder style when the critic is holding findings nobody has dismissed.


```ts
open: number — findings in overwatch.jsonl whose verdict is 'holds' or 'unjudged'; 0 renders the plain glyph and the 'what is wrong with the pipeline' tooltip
```

*From:* findingsButton(), Dashboard.java:2404-2411; count from holding(), :800-809


> Takes a count, not a class — the `some-hold` class and the badge are both derived from open>0 inside. Note the count is not 'all findings': refuted ones are excluded, so 0 means nothing outstanding, not nothing recorded.


### EndpointKeyStatus

A banner in the shape of a trace-event row — 'the endpoint' on the left, and on the right either 'a key is set, from the environment' or the shouted 'NO KEY SET — nothing will answer'. Its border is blue when the settings have been edited and grey when every value is still the environment's.


```ts
keyed: boolean — Tuning.keyed(), whether any key resolves at all
keySource: 'this page' | 'the environment' — Tuning.keyFrom(); meaningless when keyed is false
edited: boolean — Tuning.edited(); the component picks blue vs grey from this
```

*From:* theModel(), Dashboard.java:1332-1336


> THE CLASS NAMES MEAN THE OPPOSITE OF THEIR NAMES HERE. The div is `ev asked` (blue, the colour of a live prompt) when edited, and `ev tool` (grey, neutral) when untouched. Nothing was asked and no tool ran; the trace-event palette is being borrowed to mean 'someone has changed this'. Same inversion at :1256 for the JDK. Port the meaning (edited / pristine), not the class. Also: this block opens a <div class=ev> at :1332 that stays open until :1380 and swallows the whole form — the banner is a container, not a sibling, so in React it wraps the fields.


### SecretField *(shared)*

A labelled password input with two icon buttons beside it: an eye that toggles the input between password and text (flipping to a covered-eyes glyph), and a clipboard that copies the value and flashes a check for 1.2 seconds. Under it, the paragraph explaining that blank means leave-it-alone.


```ts
name: string — 'api_key' (the subject screen uses 'token')
label: string — 'API key'
value: string — the raw secret, prefilled
hint: ReactNode — the paragraph under the field
```

*From:* theModel(), Dashboard.java:1340-1358; the git-token twin at theSubject(), :1239-1250


> THE FIELD IS OUTSIDE THE FORM. The <form> does not open until :1364, after both this label and the forget checkbox are already emitted, and neither carries an HTML5 `form=` attribute. Nothing named api_key or forget_key is ever submitted — Tuning.save()'s carefully-reasoned key branches (Tuning.java:138-144) are unreachable from this page. Today this field is read-reveal-copy only; saving a key here silently does nothing. Port it inside the form and the feature starts working for the first time.
> Two more: (1) both buttons are inline onclick strings keyed to a hardcoded element id ('apikey' here, 'gittok' at :1240), so two SecretFields on one page would fight over the id — in React this is component-local state and a ref. (2) esc() escapes only & < > (:2765-2767) and the value lands in a SINGLE-quoted attribute, so a secret containing an apostrophe breaks out of the attribute; the prop must carry the raw string and let React escape it. navigator.clipboard needs a secure context, so copy is silently dead over plain http — worth a disabled state rather than a button that does nothing.


### ForgetKeyChoice

A single checkbox — 'forget the key stored here and use the environment's' — that appears only when the key currently in force came from this page rather than from the environment.


```ts
keySource: 'this page' | 'the environment' — the component renders nothing for 'the environment'
name: string — 'forget_key', posted as value 1
```

*From:* theModel(), Dashboard.java:1359-1363; consumed (in principle) by Tuning.save(), Tuning.java:142-144


> Kept separate from SecretField because its visibility is decided by a different fact (where the key came from) than the field's own value, and the subject screen's twin uses a submit button named `forget` instead of a checkbox (:1252-1253) — same intent, different mechanism, so they are not one component. Like the key field, it is currently emitted outside the <form> and is therefore inert.


### TuningField

One labelled text input with the current value prefilled and a paragraph of reasoning underneath — for model, endpoint, temperature, token cap, silence-minutes and generation-minutes.


```ts
name: string — the settings key: model | base_url | temperature | max_tokens | patience_minutes | ceiling_minutes
label: string — 'token cap', 'silence, minutes'
type: string — every field is 'text' today, including the numeric ones
value: string — Tuning.all()'s value for this name, or '' if absent
why: string — the paragraph explaining what the value does and what breaks when it is wrong
```

*From:* the local `record Field(name,label,type,why)` at Dashboard.java:1300, its six instances at :1301-1326, and the render loop at :1366-1372


> This record IS the component's props type already; it just lacks the value, which the loop pulls from `now` at render time (:1369). `why` is not a tooltip — the two minute-fields exist as separate fields with separate sentences precisely because collapsing them into one 'timeout' killed eighty-six live proves (:1099-1102, :1322-1326). Do not truncate it into a placeholder or a title attribute.
> Every type is 'text' even for temperature and the two minute counts, so the browser offers no validation; the clamping happens on READ instead (Tuning.java:65-88). Consequence for the React port: a value the user typed and a value the pipeline will use can differ, and the field shows the latter after a save. If you change type to 'number', mirror Tuning's real bounds (temperature 0-2, max_tokens 0-200000, patience 1-120, ceiling 1-1440) rather than inventing new ones.


### ModelSettingsForm

The form that wraps the six tuning fields and posts them together, carrying the hidden marker that tells the one /settings endpoint which of the four screens is submitting.


```ts
values: Record<string, string> — Tuning.all(), keyed by field name
edited: boolean — whether a settings file exists, for the revert affordance
setting: 'model' — the discriminator posted alongside; the same endpoint also serves 'workers', 'markers', 'token', 'jdk', 'zip'
```

*From:* theModel(), Dashboard.java:1364-1378; hidden(), :2272-2274; the dispatch that reads `setting` at :448-496


> The six Field specs are a compile-time constant in Java (:1301-1326), so they can stay a client-side constant in React and out of the JSON — unless the prose is meant to be editable, in which case it becomes part of the GET payload. `hidden(setting,…)` is not a component in React; it is why the POST body needs a discriminator, because one route answers six different forms and picks by this string. Field order is significant: model and endpoint first, then the two bounds that must not be confused, in the order Tuning.all() emits them.


### TuningSaveRow

The save button, and beside it — only when the settings have been edited — a plain-styled 'put the environment's back' button that discards every stored value.


```ts
edited: boolean — Tuning.edited(); false hides the revert button entirely
```

*From:* theModel(), Dashboard.java:1373-1377; handled at :459-463 and Tuning.revert(), Tuning.java:158-160


> Revert is a submit button named `revert` in the SAME form, so it posts every field and the server ignores them all (:459-460) — the destructive path shares a form with the save path and is distinguished only by which button was pressed. In React these are two intents and should be two handlers. Revert deletes the whole file, which also drops a key stored from this page — that consequence is not stated on the page and probably deserves a confirm. The subject and run screens have their own save rows with different verbs ('remove it', 'forget it'), so this one is not shared.


### AccountNote *(shared)*

The small closing paragraph in which the page accounts for itself: 'Takes effect on the next marker a prover starts. Nothing running is disturbed.'


```ts
children: ReactNode — the sentence
```

*From:* theModel(), Dashboard.java:1379-1380; the same shape at theSubject() :1293-1295 and theRun() :1389-1394


> Prose, not data — the only component here with no fact behind it. It earns its name because the claim is load-bearing: it is the reason this screen exists as a form instead of an env var, and it is true only because a prove is a fresh process per marker (Tuning.java:14-16). If that ever stops being true, this paragraph is the lie the page tells.


**API this screen needs**


```
GET /api/settings/model
{
  "edited": true,                       // Tuning.edited() — a settings file exists on the volume
  "values": {                           // Tuning.all(), Tuning.java:115-124, IN THIS ORDER
    "model": "qwen3-coder",
    "base_url": "http://vllm:8000/v1",
    "temperature": "0",                 // strings, not numbers: the page round-trips text
    "max_tokens": "0",
    "patience_minutes": "4",
    "ceiling_minutes": "240"
  },
  "key": {
    "set": true,                        // Tuning.keyed()
    "from": "this page" | "the environment",   // Tuning.keyFrom() — exactly these two strings today
    "value": "sk-..."                   // the RAW key. Today it is already in the page source
                                        // (Dashboard.java:1342-1343); the comment at 1337-1339
                                        // says that is the price of reveal+copy and the reason
                                        // the whole dashboard sits behind basic auth. If the
                                        // React port does not need reveal/copy, drop this field
                                        // and the secret stops leaving the box.
  },
  "openFindings": 3                     // Dashboard.holding(overwatch.jsonl), :800-809 — the
                                        // header badge, needed by every screen
}

POST /api/settings/model
{
  "values": { "model": "...", ... },    // only keys present are written; Tuning.save():129-134
  "api_key": "sk-...",                  // ABSENT or BLANK means LEAVE IT ALONE (Tuning.java:138-141)
  "forget_key": true,                   // drop the stored key, fall back to env (142-144)
  "revert": true                        // delete the file entirely; ignores everything above
}
→ 200 with the same shape as GET (today it is a 303 to /settings?a=model, :467-469).

Two honesty requirements on the reply:
 - The echoed values are CLAMPED, not as-typed. Save temperature=5 and the file holds 5 while
   all() returns 2 (Tuning.java:65-72,81-88). The React form must re-seed from the response or it
   will show a value the pipeline will not use.
 - "edited" is file-existence, not diff-from-default. Save the environment's own values back and
   edited flips true.
```


## the subject — `/settings?a=subject`

What is this pipeline pointed at right now — which markers are queued and which repo they name, whether a credential can reach it, whether an uploaded tree is standing in for the clone, and which JDK the subject's tests will run on — and change any of it without a redeploy.


### SubjectSettings

The whole screen: header, settings tab bar, an optional outcome banner from the last upload, then four setting rows (markers, credential, JDK, zip), then a closing note that any of it takes effect on the next marker a prover starts and that a prove already running keeps the tree it was given.


```ts
subject: SubjectPayload — the whole GET payload (see api_needed)
outcome?: {refused: boolean, text: string} — present only in the POST response
```

*From:* Dashboard.java:1187-1296 theSubject(results, said); routed at 1069 (GET) and 452 (POST)


> POST is answered in place, not redirected (452, and the comment above it) — subjectPosted() calls theSubject() with a message, so the POST response body is the SAME payload as GET plus `outcome`. Do not model the POST as returning only a message. The closing 'takes effect on the next marker' paragraph exists in three near-identical wordings (1293-1295, theRun 1400-1403, theModel 1379-1380); if you share it, share the sentence, not the CSS. Note also that head()'s subtitle repeats the queued count that MarkerQueue already shows.


### PageHead *(shared)*

The fixed top of every page: gear to /settings, envelope to /chat, the findings warning, an optional back crumb, then an h1 title and a subtitle line. Here: title 'the subject', subtitle 'N marker(s) queued', crumb 'all markers'.


```ts
title: string — escaped as text
sub: ReactNode — see notes; here it is a count sentence
back: string — crumb label, empty for no crumb
```

*From:* Dashboard.java:2413-2439 head(title, sub) / head(title, sub, back); called at 1193


> ESCAPING HAZARD: `title` and `back` go through esc(), `sub` does NOT (2437-2438) — it is raw HTML today because theModel passes '&mdash;' through it (1328-1330). In React it must be ReactNode, or every caller has to be de-entitied. Second trap: `back` is only a LABEL — the href is hardcoded to '/' (2436), so a back prop that looks like a destination is not one. head() also injects the page's whole <style>, LIVE and KEEP_OPEN blobs (2425-2426); that part does not port.


### FindingsButton *(shared)*

A warning glyph linking to /overwatch, with a count badge and an alarmed style only when the critic is holding at least one finding. Nothing but the bare glyph when the count is zero.


```ts
open: number — findings in overwatch.jsonl whose verdict is `holds`
```

*From:* Dashboard.java:2404-2411 findingsButton(); emitted from head() at 2434


> Deliberately draws no badge at zero (see the comment at 2400-2403) — 'a badge reading zero teaches a reader to ignore it'. Keep that: the component takes the count and decides, it must not take a `show` flag. Today it reads overwatch.jsonl itself from a static field; as a component it needs the count handed to it, which means EVERY route's JSON must carry it, not just this one.


### SettingsTabs *(shared)*

The tab row shared by all four settings screens: prompts / the run / the model / the subject, plus a link out to the supervisor. The current tab is highlighted.


```ts
current: 'prompts' | 'run' | 'model' | 'subject'
```

*From:* Dashboard.java:1075-1086 settingsTabs(current); called at 1194


> BUG TO FIX, NOT PORT: the prompts tab is lit whenever current is not 'run' (1077) — `current.equals("run") ? "" : "on"`. On this screen that means BOTH 'prompts' and 'the subject' render as current. The other three tabs test their own name correctly. Also: this is hand-rolled and does not call the existing tab(key, a, label, current) helper (2106-2109), because tab() hardcodes href='/marker?k=...'. One <Tab> can serve both if href becomes a prop; the supervisor link is a plain tab that is never current.


### UploadOutcome

A banner above the settings, shown only after a POST: 'refused' in the alarmed accent with the reason, or 'done' in the quiet accent with what was applied. The body is preformatted, because a rejected marker file reports one complaint per line.


```ts
refused: boolean
text: string — may be multi-line; render preserving newlines
```

*From:* Dashboard.java:1195-1200; the message is built by subjectPosted() 1117-1185


> WIRE CONVENTION THAT MUST NOT SURVIVE: Java signals refusal with a leading '!' on the string and strips it at render (1196-1198). The JSON should carry `refused` as a boolean and text without the sentinel — otherwise every consumer re-implements the strip. The text is genuinely multi-line: a bad marker file returns up to 12 complaints joined with '\n  ' (1136, Subject.complaints 111-139), so a <pre>-equivalent is load-bearing, not styling. It also embeds user-supplied fragments (Subject.cut 208-211), so it must stay escaped as text. Exceptions land here too, as 'ClassName: message' (1181-1183) — including the 64MB upload cap (Upload.java:28,90-91).


### SubjectSetting *(shared)*

The frame all four settings share: a name ('the markers'), a one-line status of what it is right now, a paragraph explaining why it matters, and the form that changes it. Rows that have been moved off the default get a bright left accent; rows still at the default stay grey.


```ts
name: string — 'the markers', 'a private repository', 'which JDK', 'a source zip'
changed: boolean — set away from the image's default
status: ReactNode — what it is now, in one line
explanation: ReactNode — the standing prose, not a message
children: ReactNode — the form
```

*From:* Dashboard.java:1202, 1224, 1256, 1276 (the four openings in theSubject); same shape in theRun 1387-1392 and theModel 1332-1336


> THE CLASS NAMES LIE AND YOU SHOULD DROP THEM. These are `.ev asked` and `.ev tool` — the trace-event kinds (CSS at 203-204) — reused as decoration. Here `asked` means 'somebody changed this' and `tool` means 'still the default': credential set (1224), JDK not 25 (1256), zip present (1276). UploadOutcome reuses the SAME two classes for the opposite axis, refused vs done (1196). So do NOT give this component a `kind` or `className` prop inherited from TraceEvent; give it `changed` and let it pick. Inconsistency worth deciding on: the markers row is hardcoded `tool` (1202) and never highlights, so an EMPTY queue — the pipeline has nothing to do — looks exactly as calm as a full one.


### MarkerQueue

How many markers are queued and the first repository they name ('and N more' when they name several), the rule that they are taken in the order given and validated whole-file before anything is replaced, an upload control, and a fold holding a paste box.


```ts
queued: number — non-blank lines in markers.txt
repos: string[] — distinct repo fields, in first-seen order
```

*From:* Dashboard.java:1202-1222; counts from Subject.count() Subject.java:85-91 and Subject.repos() 94-103


> Serve the WHOLE repos list even though the page shows only repos[0] and a count (1204-1205) — the truncation is the component's decision, and the credential row is about reaching exactly these hosts. Both are derived from markers.txt at read time; there is no stored 'queue' record, so an empty or absent file is queued=0 / repos=[] and is normal, not an error. The queue this replaces is not deleted — it is renamed markers-before-<millis>.txt beside it (Subject.saveMarkers 142-158) — and the prose promises that, but nothing on this screen lists those archives.


### UploadForm *(shared)*

A file picker and an upload button; for the zip, also a quiet 'remove it' button beside it. The picker accepts the right type for what it is uploading.


```ts
setting: 'markers' | 'zip' — which subject this posts as; the component derives the accept types and the forget label
canForget: boolean — draw the remove button (zip present)
```

*From:* Dashboard.java:1212-1215 (markers, accept .txt) and 1286-1291 (zip, accept .zip, with remove)


> SAVE AND FORGET ARE THE SAME FORM, told apart only by which button submitted it — `<button name=forget value=1>` (1290, and 1253 on the credential), read back as any non-blank `forget` part (1122). A React port that serializes component state will send `forget` on EVERY submit and silently delete the thing it was asked to replace. Make forget a separate request. The `setting` discriminator is a hidden input on all four forms (hidden() 2272-2274); in JSON it is just a field, and the accept list should be derived from it rather than passed, since Java already couples them.


### MarkerPaste

A disclosure labelled 'or paste them', holding a ten-row textarea with a sample 'repo|file|line|checker' line as placeholder, and a 'use these' button.


```ts
placeholder?: string — the sample marker line, if it should be configurable at all
```

*From:* Dashboard.java:1216-1221


> This <details> is written by hand and does NOT use the existing fold(label, body, expand) helper (2363-2367) — it cannot: fold() escapes its body into a <pre> and appends a '(N chars)' count to the label, so it holds text, never a form. If you build a shared Disclosure, this is the case that proves fold() is really two components: a TextFold that shows a size, and a plain Disclosure. Posts the same `setting=markers` as UploadForm; the server takes the file if one is present and falls back to this text (1125-1128), so both controls are one endpoint with two optional fields.


### GitCredential

Whether a token is stored and for which host, or 'no credential — public clones only'; the reason it lives in git's credential store rather than in a clone URL; a host field, the token field, a save button, and — only when one is stored — 'forget it'.


```ts
host: string — empty when no credential is stored
token: string — the stored token, sent to the browser (see notes)
```

*From:* Dashboard.java:1224-1254; host from Subject.tokenHost() Subject.java:183-191, value from Subject.token() 193-202


> THIS ROUTE'S JSON WILL CONTAIN A LIVE CREDENTIAL. Java already ships it in the page source (1241) so the eye and copy buttons can work; theModel's comment (1337-1339) owns that trade for the API key and says basic auth is what makes it acceptable. Moving it to JSON keeps the exposure and adds a URL that returns the secret on its own — decide deliberately, and if you keep it, the endpoint needs the same auth as the page. Both host and token are reconstructed by string-slicing the single credentials line, so a token containing '@' round-trips wrong (lastIndexOf('@'), 187/198). Saving with either field blank is REFUSED (1146-1147) — see SecretField for why that matters.


### SecretField *(shared)*

A labelled masked input with two icon buttons beside it: one toggles between masked and visible (the icon flips), one copies the value to the clipboard and shows a tick for a moment.


```ts
label: string — 'token', 'API key'
name: string — the field name posted
value: string — the current secret, masked
```

*From:* Dashboard.java:1239-1250 (the git token) and 1340-1353 (the API key in theModel) — the same markup written twice with a different element id


> ALREADY DUPLICATED, AND THE TWO COPIES DISAGREE ON WHAT BLANK MEANS. On the model tab a blank key is LEFT ALONE, deliberately, so a browser that clears the field cannot silently unset the key (prose at 1354-1358). Here a blank token is REFUSED with 'a host and a token are both needed' (1146-1147), and Subject.saveToken also returns early on blank (169-171). Identical control, opposite rule — pick one before this becomes a shared component, because the shared version will teach users the wrong one. The port also has to replace the two inline onclick handlers, which currently reach the input by hardcoded DOM id ('gittok', 'apikey') — that is exactly why the markup could not already be shared.


### JdkChoice

Which JDK the builds run on, the explanation that this is what the subject's TESTS run on rather than what compiles them, a picker of the JDKs in this image, and 'use it'.


```ts
chosen: string — '25' unless one was picked
available: string[] — the JDKs in this image, newest first
```

*From:* Dashboard.java:1256-1274; Subject.jdk() Subject.java:43-50, Subject.JDKS Subject.java:37


> `available` must come from the server, not be hardcoded in the client — it is 'the JDKs in this image' (Subject.java:29-37) and changing the image changes it. '25' is the default AND the value that makes the row read as unchanged (1256). Saving an unknown value fails SILENTLY: saveJdk returns without writing (Subject.java:64-66), and subjectPosted only detects it by re-reading and comparing (1158-1160) — so the API should validate against `available` and say so, rather than reporting success-by-readback. The <option value> is the one unescaped interpolation on this screen (1270); safe only because the list is a constant.


### SourceZip

Whether an uploaded tree is standing in for the clone — 'uploaded — nothing is cloned while it is here' versus 'none; the markers' repository is cloned' — the note that a zip wrapping a single directory is unwrapped so marker paths resolve, and the upload/remove control.


```ts
present: boolean — a source.zip exists on the volume
```

*From:* Dashboard.java:1276-1291; Subject.hasZip() Subject.java:80-82


> A boolean is genuinely all the record holds (an isRegularFile check) — there is no name, size or upload time anywhere. The byte count is reported ONCE, in the flash message right after upload (1174-1175), and is unrecoverable on the next page load. If a rebuilder wants 'uploaded 4.2MB on Tuesday', that is new state the Java side must start keeping, not a prop you can ask for. Note the inverted accent: present is the highlighted state here (1276) because a zip means the network is bypassed — the loud row is the unusual one, not the failing one. Validation is a two-byte 'PK' check on the first bytes (1170-1171), so the refusal text 'that is not a zip — it does not start with PK' is the whole of the check.


**API this screen needs**


```
GET /api/settings/subject — and POST of any of the four forms must return this SAME shape with `outcome` filled, because subjectPosted() re-renders the whole page rather than redirecting (Dashboard.java:452, 1184).

{
  "openFindings": 2,          // for FindingsButton in the header; every route needs it
  "markers": {
    "queued": 356,            // Subject.count — non-blank lines in markers.txt
    "repos": ["https://github.com/owner/repo.git", "..."]   // Subject.repos, distinct, first-seen order; serve all, the component truncates
  },
  "credential": {
    "host": "github.com",     // "" when none stored
    "token": "ghp_..."        // the live secret — see GitCredential notes; omit it and the copy/reveal buttons die
  },
  "jdk": {
    "chosen": "25",
    "available": ["25", "21", "17", "11", "8"],   // Subject.JDKS — a property of the image, not of the client
    "default": "25"                                // so the client can compute `changed` without hardcoding it
  },
  "zip": { "present": true },  // all the record holds; no name, size or timestamp exists
  "outcome": null              // or {"refused": true, "text": "the queue was NOT replaced:\n  line 12: 3 field(s), not 4 — it is repo|file|line|checker\n  ..."}
}

Notes on the contract:
- `outcome.refused` replaces the leading-'!' sentinel Java uses internally (1196); the text arrives already stripped and may be multi-line.
- POST bodies stay multipart (files), with `setting` as the discriminator: "markers" (file | text), "token" (host, token), "jdk" (jdk), "zip" (file). Forget should become its own request or an explicit boolean field — today it is inferred from which button submitted (1122, 1253, 1290) and is a live footgun for a client that serializes form state.
- Marker validation should return the complaint list as a string[] rather than pre-joined (Subject.complaints returns a List already, flattened at 1136); the client can then number and link them.
- Nothing here needs a colour, a class or a formatted status string: every accent on this screen is derivable from `credential.host !== ""`, `jdk.chosen !== jdk.default`, and `zip.present`.
```


## Settings — the prompts (and the run width) — `/settings  (tab `?a=run` for the run width; `/prompts` 301-redirects here — Dashboard.java:442-446, 447-499, 1065-1072)`

What is every agent actually being told before the next marker starts, which of those instructions are mine rather than the code's, and how many provers will run at once.


### Head *(shared)*

The page's top bar: a back crumb to all markers on the left, the gear / envelope / findings icons on the right, then the title and a one-line subtitle. On this screen the subtitle is "15 agent(s) · none edited — every one is the code's" or "… · 3 edited, the rest are the code's".


```ts
title: string — escaped
sub: ReactNode — Java appends this UNESCAPED (line 2438) and prompts() feeds it &middot;/&mdash; entities, so in React it must be a node, not a string
back?: { label: string } — omitted renders no crumb; every settings view passes "all markers" pointing at /
```

*From:* head(title, sub, back) — Dashboard.java:2413-2439; the subtitle text is built in prompts():1416-1418 and theRun():1385


> Java computes the subtitle sentence inside prompts(). Pass `agents.length` and `overriddenCount` to the screen and let it write the sentence; do not ship the sentence as a prop. head() also injects the LIVE (SSE) and KEEP_OPEN scripts (2425-2426) — behaviour that becomes app-level in React, not part of a header component.


### FindingsBadge *(shared)*

A warning glyph in the header corner linking to /overwatch, with a count badge when the critic has findings that still hold, and a redder frame in that case. Title text changes between "N finding(s) the critic has not dismissed" and "what is wrong with the pipeline".


```ts
open: number — count of overwatch findings with verdict `holds`; the component decides badge/no-badge and the `some-hold` accent
```

*From:* findingsButton() — Dashboard.java:2404-2411, called from head():2434


> Its own doc comment says "It is drawn only when something stands" — the code draws the anchor unconditionally and only suppresses the number. Keep the code's behaviour (always a link), not the comment's.


### SettingsTabs

The tab row under the header: prompts | the run | the model | the subject, then a plain link out to the supervisor. The current tab is filled blue.


```ts
current: 'prompts' | 'run' | 'model' | 'subject' — a route fact, not a class name
```

*From:* settingsTabs(current) — Dashboard.java:1075-1086


> TWO BUGS TO NOT CARRY OVER. (1) The prompts tab is lit by `!current.equals("run")`, so on ?a=model and ?a=subject two tabs are highlighted at once. (2) These tabs are NOT the existing tab(key,a,label,current) helper (2106-2109) — that one hard-codes /marker?k= hrefs. Settings needs its own Tab, or tab() must take a href rather than a marker key. Same markup class (`nav.tabs`), different destination family.


### SettingRow *(shared)*

The bordered block that every changeable thing on this screen sits in: agent or setting name in blue, a small uppercase state word beside it ("edited", "the code's own", "currently 4"), then the control. A left border coloured by whether this differs from the code's own.


```ts
name: string — the agent, or "parallel provers"
state: string — the short uppercase word; a fact about the row, e.g. 'edited'
overridden: boolean — picks the accent itself
anchorId?: string — the row's id, so /settings#reproducer lands on it
children: ReactNode
```

*From:* the `<div class='ev …'><span class=who>…</span><span class=kind>…</span>` idiom in prompts():1435-1439, theRun():1387-1388 and promptsFor():1509-1513


> The accent classes are borrowed trace-event kinds: `ev asked` (blue) for overridden, `ev tool` (grey) for the built-in (CSS 202-206). That vocabulary means nothing here — take `overridden: boolean` and choose. theRun() hard-codes `ev asked`, so the run width always looks overridden even when it is the default 4; pass `workers !== default` instead. Also: saving redirects to /settings#<agent>, and `.ev:target` (CSS:86) outlines the row in #f85149 — the same red `.ev.failed` uses. A successful save is currently highlighted in the failure colour.


### AgentGroupHeading

A small grey heading that breaks the list into groups: "the chain, in the order Prove calls it" above the ten that run inside a prove, "watching the run" above the rest.


```ts
group: 'chain' | 'watch' | 'asked' — the component writes the sentence
```

*From:* prompts():1424-1431 (the `belongs`/`stage` change-detector), reading Agents.CHAIN (Agents.java:410-415)


> Java only tests `Agents.CHAIN.contains(agent)`, so everything else falls under "watching the run" — which puts `chat` there, and chat watches nothing (Agents.java:421-422: "The one that speaks only when spoken to"). Three groups exist in the record (CHAIN, WATCH, ASKED); the page shows two. Take the real group and add the third heading.


### AgentPromptEditor

One agent's instructions: name, whether it is edited or the code's own, a 16-row textarea holding the prompt that agent will actually be given, a save button, and — only when an override exists — a "put the code's back" button and a collapsed fold showing the built-in for comparison.


```ts
agent: string
builtIn: string — the code's prompt
saved: string — the override verbatim, '' when none
onSave(prompt: string)
onRevert()
```

*From:* prompts():1432-1450, the per-agent body of the loop; save/revert handled at Dashboard.java:483-496 via Prompts.save/Prompts.revert


> "Edited" here means only `saved.isBlank() == false` (1415, 1434) — paste the built-in back verbatim and save, and the row says edited forever until you revert, while the marker page's promptsFor() answers the same question with Prompts.same() normalisation (1480, 1508). If the API also sends `differs`, this row can say "edited" and "same as the code's" separately. The textarea shows the EFFECTIVE prompt (override if any, else built-in) — that is Prompts.effective, so an edit always starts from what the agent is really running. Escaping: esc() (2765-2767) handles & < > and NOT quotes, while the markup writes id='…' and hidden value='…' in single quotes (1435-1441, 2272-2274); safe only because agent names come from a fixed list. Takes effect on the next marker a prover starts, never on one already running (1421-1423).


### Fold *(shared)*

A disclosure triangle whose summary is a label plus the body's character count — "what the code says (4,183 chars)" — opening onto a preformatted block. Renders nothing at all when the body is empty.


```ts
label: string
body: string — empty body renders null
expand: boolean
id?: string — NEW, see notes
```

*From:* fold(label, body, expand) — Dashboard.java:2363-2367; used here at prompts():1449 and promptsFor():1514-1515


> KEEP_OPEN (286-307) remembers open folds by `d.id || '#'+index`, and fold() emits no id — so on this page they are remembered by position among all <details>. The fold only exists for overridden agents, so reverting one agent shifts the indices and reopens a different agent's fold. That is the exact bug KEEP_OPEN's own comment says was fixed for the supervisor. Give Fold an id (`code:<agent>`).


### Account *(shared)*

A paragraph of the page's own explanation, in the prose voice the dashboard uses everywhere — what this control does, when it takes effect, and what it will not do. A dimmer variant for the second-order caveat.


```ts
children: ReactNode
quiet?: boolean — the `<span class=k>` variant used for the takes-effect caveats
```

*From:* prompts():1420-1423; theRun():1389-1394 and 1400-1403 (both the plain and the `<span class=k>` form)


> This is static copy in Java, not data — it should stay in the component tree as JSX, not travel over the API. The one exception is theRun's sentence, which interpolates Workers.LEAST/MOST/DEFAULT (1393-1394); those three are props of ParallelProvers, so the sentence lives there.


### ParallelProvers

The run tab's single control: "parallel provers / currently 4", a paragraph on why more is not faster past a point, a number input bounded by the allowed range with a save button, and a note that lowering it does not stop a prove already running.


```ts
workers: number — the width in force now
least: number
most: number
fallback: number — Workers.DEFAULT, so the copy can name it
onSave(workers: number)
```

*From:* theRun(results) — Dashboard.java:1383-1404; values from Workers.of/LEAST/MOST/DEFAULT (Workers.java:26-49); the POST is handled at Dashboard.java:472-482


> Workers.of() falls back to DEFAULT on a missing OR unreadable file (Workers.java:39-48), so `workers: 4` cannot be told apart from "nobody has ever set this" or "the file is a typo" — if the row is to show `overridden` honestly, Workers has to report whether a readable file existed. Also: the server clamps on save (clamp, 57-59) and the form only sets min/max in the browser, so the input's bounds are advisory — the number you get back may not be the number you sent, and the UI must render the response rather than the request. Failures to write are swallowed (475-477) and answered by redrawing the page with what is actually on disk; keep that — the redraw is the error message.


**API this screen needs**


```
No JSON exists for this route today; `settings()` switches on `?a` and returns HTML (Dashboard.java:1065-1072). Two documents cover the screen.

GET /api/settings/prompts
{
  "agents": [
    { "agent": "reproducer",
      "group": "chain",            // "chain" | "watch" | "asked" — from Agents.CHAIN/WATCH/ASKED, NOT the sentence Java prints
      "builtIn": "You are given a Svace marker…",   // Prompts built-in; "" when the runtime never registered one
      "saved": "",                 // the override file verbatim, "" when none — Prompts.saved()
      "differs": false             // Prompts.same(effective, builtIn) — a fact the prompts page does not compute today
    }, …                           // ARRAY ORDER IS THE CONTRACT: Agents.ORDER, then anything unlisted, sorted
  ],
  "overriddenCount": 0,            // agents with a non-blank override — the header's "N edited"
  "findingsOpen": 3                // holding(overwatch.jsonl), for the header badge
}

POST /api/settings/prompts/{agent}   { "prompt": "…" }   → the new row state
DELETE /api/settings/prompts/{agent}                     → revert to the built-in
(Java today: one POST to /settings, discriminated by a hidden `setting` field, else treated as a prompt save — Dashboard.java:455-496.)

GET /api/settings/run
{ "workers": 4, "least": 1, "most": 16, "default": 4 }   // Workers.of/LEAST/MOST/DEFAULT
PUT /api/settings/run { "workers": 6 }                   // server clamps; Workers.clamp

Both responses want `findingsOpen`, because `head()` draws the badge on every page.
```


## live — what one prove is saying right now — `/live?k=<suspicion_key> (an HTML fragment, polled every 2000ms). The screen a reader actually opens is /marker?k=<suspicion_key>&a=live, which server-renders the same fragment once inside a container the poller then owns. The index links into it from any row whose state is `proving` (Dashboard.java:1728).`

Is this prove still running, and if so, which agent is speaking, how long ago did it last say anything, and what is the end of what it has said so far?


### LiveTab

The whole screen: header (marker file name as title, the fixed line "what this prove is saying now", back to "all markers"), the marker tab row with `live` selected, and the polling container underneath.


```ts
markerKey: string — the full `repo|file|line|checker` suspicion key
runs: Record<AgentName, number> — asked-counts per agent, for the tab row
initialLive: LiveView — the first /api/live payload, so the screen is not blank for 2s
```

*From:* Dashboard.java:1792-1801 (the `agent.equals("live")` branch of marker()); route registered at Dashboard.java:500-502


> The title is `key.substring(key.lastIndexOf('/') + 1)` — the file-and-line tail of the key, not the key. The container must keep id="live" and a data-live URL: the poller finds it by id and reads its own endpoint off the element, which is how one poller serves this page and any future one without either knowing about the other (Dashboard.java:1793-1794, 320-321).


### LiveStream *(shared)*

Nothing of its own: every 2 seconds it refetches the live payload for this marker and swaps in either the finished notice or a StreamPanel, keeping each fold's open/closed state across the swap.


```ts
markerKey: string
intervalMs: number = 2000
initial?: LiveView
```

*From:* Dashboard.java:319-331 (poll() inside KEEP_OPEN); Dashboard.java:500-502 (/live route); Dashboard.java:837-852 (live())


> POLLED, NOT PUSHED, on purpose: /events fires when the trace and settlement counts move, and an agent reasoning for four minutes moves no counts — which is exactly the stretch worth watching. Fold state here deliberately does NOT go through sessionStorage (Dashboard.java:316-318): these folds are replaced far too often, so the open/closed map is read off the DOM before the swap and put back after, keyed by panel id. In React that map is state keyed by `who` and the swap problem disappears — but the key must stay `who`, not an array index, or a pool-wide list would reshuffle what the reader had open. Today `/live` with an empty `k` returns a 200 with an empty body (live() returns ""), which the poller writes straight into the box: an error and "no marker asked for" are indistinguishable, and both blank the panel.


### ProveFinishedNotice

One dim line: "This prove has finished. What it said is on the tabs above; this view is only for one still running."


```ts
(none today)
```

*From:* Dashboard.java:845-848, with the test in settled() at Dashboard.java:891-900


> The state is computed as the COMPLEMENT of the three unfinished states (blank, proving, infra, queued), not as a match against the seven dispositions. Keep it that way in the API: a state nobody has thought of yet must read as finished rather than as still running. The reason this branch exists at all: the .live file survives the prove that wrote it, so a panel that kept rendering it would be a live view that is quietly a museum. It says "the tabs above" but links to nothing — the tab row is its only exit.


### StreamPanel *(shared)*

One disclosure fold: a summary line of who is speaking, which agent, and how long ago, over a scrolling pre of the tail of what they have said.


```ts
who: string — the marker slug, or "supervisor" on /chat; also the fold's stable key
agent: AgentName | null — which agent is speaking; null when the file has not parsed yet
at: number — epoch ms of the last write; 0 means nothing yet
text: string — the tail of the answer in progress
defaultOpen: boolean = true
```

*From:* Dashboard.java:903-933 (panel()); called from live() 849, proving() 877, and chat() 981


> THIS IS THE ONE COMPONENT THIS SCREEN SHARES WITH A SCREEN THAT IS NOT A MARKER: chat() renders it with who="supervisor" over chat-trace.jsonl.live, so `who` is not always a marker slug and must not be typed as one or linked to /marker. The DOM id is `live-<who>` with `who` IN FULL while the summary truncates it to 46 chars — the poller's bookkeeping keys on the whole name, so a React key must too. Every one of the three callers passes open=true; the parameter has no `false` caller today. An unreadable or half-written file (fewer than two newlines) yields agent="", at=0, text="" and must render as the "nothing yet" state rather than as an error — the file is being rewritten as it is read and the next poll is 2s away (Dashboard.java:917-919).


### StreamAge *(shared)*

The age fragment of the panel's summary: "nothing yet" with no stamp, "quiet Nm" past 90 seconds, "Ns ago" otherwise — dim, after a middot.


```ts
at: number — epoch ms; 0 means never written
```

*From:* Dashboard.java:921, 924-926


> Takes the timestamp, not a formatted string: this is the prop most likely to arrive pre-rendered from Java and it must not. Two traps. (1) Java recomputes `ago` on every render and renders every 2s, so the number moves for free; a React component that renders only when the payload changes will freeze at "12s ago" while a quiet agent thinks for four minutes — it needs its own ticking clock. (2) Minutes floor-divide from seconds, so 91s reads "quiet 1m" and 119s still reads "quiet 1m"; the 90s threshold and the truncation are both deliberate and should be ported exactly. This is NOT the same formatter as ago() at Dashboard.java:1023 used by /chat turns.


### StreamTail *(shared)*

The scrolling monospace block of what the model has said so far, capped in height; a single ellipsis when there is nothing yet.


```ts
text: string
truncated: boolean — text was cut from the head
```

*From:* Dashboard.java:927-932, with LIVE_TAIL = 4000 at Dashboard.java:936


> THE END, NOT THE START. A reasoning turn runs to tens of thousands of characters; opening on the beginning would show the same paragraph for four minutes. The 4000-char cut currently happens in Java, which makes it a presentation decision baked into the wire — but here it is also a bandwidth decision at a 2s poll, so the honest API is to keep the cut server-side and admit it with `truncated`. This is raw model output going into a pre: Java escapes it (esc()) and React escapes by default — never reach for dangerouslySetInnerHTML on this prop. There is no autoscroll today, just max-height with overflow:auto, so the fold does not jump under a reader who has scrolled up.


### MarkerTabs *(shared)*

The chain nav across the top: a summary pill, the five stages each with their producer and critic, then live / prompts / the record / the supervisor / settings.


```ts
markerKey: string
current: '' | AgentName | 'live' | 'prompts' | 'trace' — 'live' on this screen
runs: Record<AgentName, number> — asked-count per agent for this marker
```

*From:* Dashboard.java:2063-2096 (tabs()); called for this screen at Dashboard.java:1797


> The five stages are a hardcoded second copy of the chain order (STAGES, Dashboard.java:2039-2044) that the Java source itself flags as drifting from Agents.CHAIN — worth collapsing into one list during the port rather than copying the copy. `runs` is a count of kind=="asked" trace lines, which is the only thing that distinguishes a stage that ran from one that never did.


### ChainStage *(shared)*

One stage of the chain: its lowercase label, the producer chip, an arrow, the critic chip — dimmed as a whole when neither ever ran, and with a loop glyph in place of the arrow when the producer answered more than once.


```ts
label: string — 'reproduce' | 'fix' | 'propose' | 'argue' | 'price'
producer: { agent: AgentName; runs: number }
critic: { agent: AgentName; runs: number }
markerKey: string
current: string
```

*From:* Dashboard.java:2073-2085


> The loop glyph is not extra data: producer.runs > 1 IS the critic having sent the work back, because a producer runs a second time only when Prove asks it to after an objection. Derive it, do not ship a `looped` flag. The stage dims on producer.runs + critic.runs == 0.


### AgentChip *(shared)*

One agent's name with its run count in bold, linking to that agent's tab; dimmed when it never ran, highlighted when it is the current tab.


```ts
markerKey: string
agent: AgentName
runs: number
current: string
```

*From:* Dashboard.java:2099-2104 (chip())


> The count is omitted entirely at zero, not shown as 0 — same instinct as the findings badge. On this screen every chip is off-current, since `current` is 'live'.


### PageHeader *(shared)*

The band every page starts with: settings gear and ask-the-supervisor envelope in the top-right corner, the findings badge beside them, a back crumb on the left, then the title and a subtitle line.


```ts
title: string
sub: ReactNode — plain text here, but other screens pass marker keys and state pills into it
back?: string — label of the crumb, always to '/'
```

*From:* Dashboard.java:2413-2439 (head()); called for this screen at Dashboard.java:1795-1796


> `sub` is the one prop here that is not a fact: Java hands it pre-built HTML and other screens push a state pill through it. On /live it is the constant "what this prove is saying now", so this screen does not need the rich form — but the shared component will, and it should take nodes rather than a string of markup. head() is also where the two page scripts (LIVE, KEEP_OPEN) are injected; in React both become hooks and nothing about them belongs in a header component.


### FindingsButton *(shared)*

A warning glyph in the header linking to /overwatch, carrying a count badge, highlighted when anything is standing.


```ts
openFindings: number — findings in overwatch.jsonl whose verdict holds
```

*From:* Dashboard.java:2404-2411 (findingsButton()), via head() at Dashboard.java:2434


> DRAWN ONLY WHEN SOMETHING STANDS — a badge reading zero on a clean run teaches a reader to ignore the control, and it has to still mean something on the day it says nineteen. So the component decides its own presence from the count; do not let the caller pass a `show` flag. Note this makes every page, including this polled one, depend on a global count from a file the live payload otherwise never touches — it should come from the page shell fetch, not from the 2s poll.


**API this screen needs**


```
Today /live returns HTML and /marker?k&a=live returns a whole page; neither has JSON. Two payloads are needed.

GET /api/live?k=<suspicion_key> — the polled fragment, the only thing that changes every 2s:
{
  "marker": "repo|file|line|checker",   // echo of k; null when k was empty
  "slug": "Dashboard_java_1873_DEREF",  // Supervisor.slug(marker): text after the last '/', [^A-Za-z0-9._-] -> '_', cut to 80. This is the m/<slug>/ directory name AND the panel's `who`.
  "settled": false,                     // true iff m/<slug>/settlements.jsonl holds any state that is not "" | "proving" | "infra" | "queued". Server-side, because it is the complement of three states, not a list of seven.
  "panel": {                            // null when settled, or when k is empty
    "who": "Dashboard_java_1873_DEREF", // identity/key of the fold; "supervisor" on /chat
    "agent": "reproducer",              // line 1 of m/<slug>/trace.jsonl.live; "" when the file is absent or has fewer than two newlines
    "at": 1755079982431,                // line 2, epoch ms; 0 = nothing yet
    "text": "…the tail…",               // line 3 onward, last LIVE_TAIL=4000 chars
    "truncated": true                   // text was cut from the HEAD, not the tail
  }
}
`panels: [Panel]` is the latent plural of this: proving() already builds one panel per entry of results/claims/ (minus the settled ones) and emits "No prove is running." when the list is empty — but nothing calls proving() today, so no route serves it. If the pool-wide view is ever wanted back, this endpoint grows `panels` and drops `panel`; the components below already work per-panel.

GET /api/marker/live?k=<key> — the page shell, fetched once (everything here is stable for the life of the screen except runs):
{
  "marker": "repo|file|line|checker",
  "title": "Dashboard.java|1873|DEREF_OF_NULL",  // client can derive: substring after last '/'
  "runs": { "reproducer": 2, "proof-critic": 1, "fixer": 1 },  // count of trace.jsonl lines for this marker with kind == "asked", grouped by agent. Drives every chip's count and the stage on/off + loop glyph.
  "openFindings": 3,                              // holding() over overwatch.jsonl, for the header badge on every page
  "live": { …the /api/live body above, so first paint needs one round trip… }
}
```
