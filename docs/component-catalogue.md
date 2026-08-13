# The dashboard, decomposed — one catalogue

Reconciled from nine independent readings of `/Users/vmihaylov/projects/fix-java-svace-markers/agent/src/main/java/tech/mikhailov/fsm/agent/Dashboard.java` (2,821 lines). Every component below corresponds to markup that is emitted today, with the Java function and line range that emits it. Line numbers were re-verified against the file, not copied from the passes; where a pass cited wrongly it is corrected in place.

Ten screens, not nine: `/marker?k=…&a=prompts` (`promptsFor()`, 1467–1522) renders a real page with an alarm and a POST button and **no pass decomposed it**. It is included as §5.4.

---

## 0. Shared types, declared once

```ts
// ---------- the record's vocabulary ----------

/** The seven a prove decides. `settled()` (891-900) is the complement of the four below. */
type Disposition =
  | 'false-positive' | 'by-design' | 'unprovable'
  | 'reproduced' | 'needs-review'
  | 'verified/pr-ready' | 'verified/pr-rejected';

/** Not dispositions. `queued` and `interrupted` are DERIVED, never written to disk:
 *  index() 1615-1634 seeds `queued` from markers.txt and rewrites `proving`→`interrupted`
 *  when results/claims/<slug> is absent. */
type NotSettled = 'proving' | 'infra' | 'queued' | 'interrupted';

type MarkerState = Disposition | NotSettled;
// `not-a-bug` is NOT here. It survives only in dead CSS (line 184) and a dead guard in
// flags() (2320). Nothing writes it. Do not add it to the type.

type Verdict = 'holds' | 'unjudged' | 'refuted';   // verdictOf() 821-824: blank ⇒ 'unjudged'

type TraceKind =
  | 'asked' | 'thought' | 'tool' | 'built' | 'progress' | 'settled' | 'priced' | 'failed';

/** Agents.java 410-426 — the ONE list. STAGES (Dashboard 2039-2044) is a second copy; collapse. */
type ChainAgent =
  | 'reproducer' | 'proof-critic' | 'fixer' | 'fix-critic' | 'pr-maker' | 'pr-critic'
  | 'verdict' | 'verdict-critic' | 'estimator' | 'estimator-critic';
type WatchAgent = 'overwatch' | 'overwatch-critic' | 'interpreter' | 'interpreter-critic';
type AgentName = ChainAgent | WatchAgent | 'chat';

type StageName = 'reproduce' | 'fix' | 'propose' | 'argue' | 'price';

type Severity = 'Critical' | 'Major' | 'Minor' | 'Normal';   // null is a real answer, not a default

type MarkerKey = string;   // `repo|file|line|checker`
type Slug = string;        // Supervisor.slug(key) 297-302 == Dashboard.slug() 571-575 (two copies)

// ---------- shared records ----------

type SourceLine = { n: number; text: string };

/** flagged() 2528-2564. `fileLines` lets the component say the marker points past EOF —
 *  today that sentence is glued into the text blob (2556-2562). */
type FlaggedSource = { lines: SourceLine[]; flagged: number; fileLines: number | null };

/** settlements.jsonl red_verified/green_verified. null = never recorded; the whole object
 *  is null when there is no settlement row at all (flags() returns "" then, 2314-2316). */
type SettlementFlags = { red: boolean | null; green: boolean | null };

type Crumb = { label: string; href: string };

type TabItem = { href: string; label: string; on: boolean };

// ---------- the trace ----------

type EventBase = {
  id: string;              // NEW. Nothing identifies an event today — see RateAnswer.
  at: number;              // epoch ms; num() 2355 turns a bad value into 0, which sorts to the top
  marker: MarkerKey | Slug | 'overwatch' | null;
};

type KnownEvent =
  | (EventBase & { kind: 'asked';    agent: AgentName; prompt: string; reply: string })
  | (EventBase & { kind: 'thought';  agent: AgentName; text: string })
  | (EventBase & { kind: 'tool';     agent: AgentName; tool: string; arguments: string; result: string })
  | (EventBase & { kind: 'built';    phase: 'red' | 'green'; passed: boolean; infra: boolean; summary: string })
  | (EventBase & { kind: 'progress'; note: string })
  | (EventBase & { kind: 'settled';  state: MarkerState; because: string })
  | (EventBase & { kind: 'priced';   minutes: number; itemisation: string })
  | (EventBase & { kind: 'failed';   cause: string });

/** The default branch of one() (2219) renders an unknown kind as its bare name. That is correct
 *  for an append-only record that will grow kinds — keep it, do not throw. */
type UnknownEvent = EventBase & { kind: string & {} };
type TraceEvent = KnownEvent | UnknownEvent;
```

Two facts about the wire that the type above fixes rather than carries:

- **`phase` is lowercase on disk.** `marker()` compares `field(e,"phase").equals("red")` (1865); `one()` uppercases only for display (2203). Three passes typed it `"RED"`. It is `'red' | 'green'`.
- **`passed`, `infra`, `red_verified`, `green_verified` are written UNQUOTED**, which is the entire reason `field()` has the unquoted branch (2707-2722) and the reason the semaphore never lit before it existed. They arrive as real booleans in JSON, never as `"true"`.

---

## 1. Tier PRIMITIVES — no domain knowledge

### `Disclosure`
**Screens:** all. **Java:** hand-rolled `<details>` at 1216 (paste box), 1737 (the `why` cell), 930 (`panel`).
```ts
type DisclosureProps = {
  id: string;                 // NEW and load-bearing — see the note
  summary: React.ReactNode;
  children: React.ReactNode;
  defaultOpen?: boolean;      // default true; ?fold=1 makes it false (open() 2374-2376)
};
```
- **`fold()` is really two components.** `fold(label, body, expand)` (2363-2367) escapes its body into a `<pre>` and appends `(N chars)` to the label — so it *cannot* hold a form or a code block, which is why three call sites hand-wrote `<details>` instead. Split: `Disclosure` (arbitrary children) and `TextFold` (below) built on it.
- `id` is new because `KEEP_OPEN` (286-307) keys open-state by `d.id || '#'+index`, and `fold()` emits no id. On `/settings` the fold only exists for overridden agents, so reverting one agent renumbers the rest and the wrong agent springs open — the same bug KEEP_OPEN's own comment says it fixed for the supervisor. Key by `marker`, `event.id` or `code:<agent>`, never by position.

### `TextFold`
**Screens:** marker (all tabs), trace, supervisor, settings prompts. **Java:** `fold()` 2363-2367.
```ts
type TextFoldProps = {
  id: string;
  label: string;      // the "(N chars)" is computed here from body — never pass a count
  body: string;
  defaultOpen?: boolean;
};
```
- **Empty body renders `null`, not an empty fold** (2364). This is load-bearing in two places: a tool call with no result shows one fold and not two, and an unjudged finding's "what the critic said" simply does not appear — its absence is the signal.
- Name reconciliation: `Fold` in four passes, `Fold`+`expand` in three, `defaultOpen` in two. `defaultOpen` wins; `expand` is a URL fact, not a prop name.

### `CodeBlock` / `DiffBlock`
**Screens:** markers (`/`), marker summary. **Java:** `code()` 2609-2611, `diff()` 2614-2616, `block()` 2618-2622, `colourJava()` 2665-2679, `colourDiff()` 2631-2642.
```ts
type CodeBlockProps = { code: string; language?: 'java' };
type DiffBlockProps = { patch: string };
```
- **Escaping lives here and nowhere else** (comment 2601-2607: a caller handed `block()` a raw `git diff` and a patch containing `<` wrote markup into the page). In React that means these components render text nodes and spans themselves — never `dangerouslySetInnerHTML`, and never a pre-coloured HTML blob from the server.
- `block()` colours everything as Java regardless of the file's language; `language` exists so that stops being silent.
- **Gap, not an absence:** neither is called from `one()`. `/trace` renders the reproducer's test and the fixer's patch as flat `<pre>`. Using `CodeBlock`/`DiffBlock` inside `ToolCallEvent` is *new behaviour* — worth having, but say so.

### `TabRow`
**Screens:** marker + live (chain row is its own component), supervisor, all four settings screens. **Java:** `settingsTabs()` 1075-1086, `supervisorTabs()` 597-607, and the dead `tab()` 2106-2109.
```ts
type TabRowProps = {
  items: TabItem[];          // { href, label, on }
  trailing?: TabItem[];      // departures, not tabs: "the supervisor", "settings" — never `on`
};
```
- **This is the merge the Java could not do.** `tab(key,a,label,current)` builds `/marker?k=…` URLs, which is why `supervisorTabs()` (comment at 596) and `settingsTabs()` both hand-rolled their own anchors. `tab()` now has **zero call sites** — grep confirms `tabs()` writes its five pills inline (2071-2073, 2087-2094). Make `href` a prop and all three collapse into this.
- **Bug not to port:** `settingsTabs` line 1077 lights *prompts* with `current.equals("run") ? "" : "on"` — the negation of the run test. On `?a=model` and `?a=subject` two tabs are lit. Each item tests its own key here.

### `Pill`
**Screens:** everywhere a `.s` pill appears. **Java:** CSS `.s` 53-55, 184-185, 192, 198-200.
```ts
type PillProps = {
  tone: 'good' | 'warn' | 'quiet' | 'alarm' | 'running' | 'aside';
  href?: string;
  children: React.ReactNode;
};
```
- The only presentational primitive in the catalogue. `StateBadge` and `VerdictPill` map *their own vocabulary* to a tone; nothing else may pass `tone` from a payload.

### `Tally`
**Screens:** markers (`/`), twice. **Java:** `.c` markup in `progress()` 2245-2247 and `index()` 1671-1676; CSS 46-48.
```ts
type TallyProps = { value: React.ReactNode; label: string };
```
- Two adjacent `.counts` strips is deliberate, not a merge failure: `progress()` emits one and `index()` emits a second immediately after.

### `ProgressBar`
**Java:** 2243, CSS `.bar` 213-214.
```ts
type ProgressBarProps = { pct: number };   // caller caps at 100 (2240)
```

### `LabeledField`
**Screens:** settings model (×6), settings subject (host). **Java:** `theModel()` 1366-1372 and its `record Field(name,label,type,why)` at 1300-1326; `theSubject()` 1236-1238.
```ts
type LabeledFieldProps = {
  name: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  help?: React.ReactNode;     // `why` — a paragraph, NOT a placeholder or a title attribute
  type?: 'text' | 'number';
};
```
- The Java `record Field` *is* this prop type already, minus `value`. `why` must stay a visible paragraph: the two minute-fields exist as separate fields with separate sentences because collapsing them into one "timeout" killed eighty-six live proves (1099-1102, 1322-1326).
- Every field is `type=text` today, including the numeric ones, because clamping happens on *read* (`Tuning` 65-88), not on input. If you switch to `number`, mirror Tuning's real bounds — and re-seed the form from the POST response, because a saved 5 comes back as 2.

### `SecretField`
**Screens:** settings model (API key), settings subject (git token). **Java:** 1340-1358 and 1239-1250 — the same markup written twice with a different hardcoded element id.
```ts
type SecretFieldProps = {
  name: string;               // 'api_key' | 'token'
  label: string;
  value: string;
  onChange: (v: string) => void;
  help?: React.ReactNode;
};
```
- Reveal and copy become component-local state and a ref. Today both are inline `onclick` strings keyed to `getElementById('apikey')` / `('gittok')` — **which is exactly why the markup could not already be shared**: two of them on one page would fight over the id.
- `navigator.clipboard` needs a secure context, so copy is silently dead over plain http. Render it disabled rather than as a button that does nothing.
- **The two copies disagree about what blank means** and that disagreement must be resolved *outside* this component: on the model tab a blank key is deliberately left alone (prose at 1354-1358), on the subject tab a blank token is refused (1146-1147). Blank-policy is the form's contract, not the field's; document it per screen or the shared component teaches users the wrong rule.
- **The value is a live secret in the page source** (1338-1339 owns that trade: it is the price of reveal+copy, and the reason the dashboard sits behind basic auth). Moving it into JSON keeps the exposure *and* adds a URL that returns the secret on its own. If the port does not need reveal/copy, drop the field from the payload and the secret stops leaving the box.

### `SaveRow`
**Screens:** settings ×4, prompt editor. **Java:** 1373-1377 (model), 1399 (run), 1444-1447 (prompts), 1251-1253 / 1289-1290 (subject).
```ts
type SaveRowProps = {
  saveLabel?: string;                                     // default 'save'
  onSave: () => void;
  destructive?: { label: string; onConfirm: () => void }; // 'put the environment's back' / 'forget it' / 'remove it'
};
```
- **The footgun this fixes:** save and the destructive action are the *same form* today, told apart only by which button submitted it (`<button name=revert value=1>`, `<button name=forget value=1>`). A React client that serialises component state will send `forget` on every submit and silently delete the thing it was asked to replace. Two intents, two handlers, two requests.

### `EmptyNote`
**Screens:** all. **Java:** 1644-1646, 1519, 2158, 632-635, 762-767, 879-881, 964-968; CSS `.empty` 212.
```ts
type EmptyNoteProps = { children: React.ReactNode };
```
- Merges `EmptyRun`, `EmptyTrace`, `EmptyNote` from three passes. The copy is *content*, not a prop default — these sentences do real work ("a quiet page is a good sign", "the supervisor looks on its own schedule", "it can see every marker's state, builds, answers and settlement").
- `/trace`'s copy is hardcoded "Nothing traced for this **marker**" (2158) and is shown unchanged on the whole-trace view, where there is no marker. Fix in the copy, not the props.

### `Account`
**Screens:** settings ×4, marker summary. **Java:** `p.account` at 1207, 1229, 1259, 1281, 1293, 1379, 1389, 1400, 1420, 1837, 1854; CSS 97-98.
```ts
type AccountProps = { children: React.ReactNode; quiet?: boolean };  // quiet = the <span class=k> variant
```
- Static prose. It stays in the component tree, never on the wire — with one exception: `theRun`'s sentence interpolates `Workers.LEAST/MOST/DEFAULT` (1393-1394), so it lives inside `ParallelProvers`.

### `RelativeTime`
**Screens:** chat, live, chat's stream panel. **Java:** `ago()` 1023-1029 and the inline rule in `panel()` 921-926.
```ts
type RelativeTimeProps = { at: number; variant: 'conversation' | 'stream' };
```
- **THERE ARE TWO FORMATTERS AND THEY DISAGREE.** `ago()`: `<90s → "Ns ago"`, `<5400s → "Nm ago"`, else `"Nh ago"` — and no day unit, so an overnight tab reads "31h ago". `panel()`: `at===0 → "nothing yet"`, `>90s → "quiet Nm"`, else `"Ns ago"`. At 100 seconds one says "1m ago" and the other "quiet 1m". One component with a variant keeps the *meaningful* difference (silence vs age) and kills the accidental one.
- **It must tick.** Java recomputes on every render and re-renders every 2-3s. A React component that renders only when the payload changes freezes at "12s ago" for the four minutes an agent is thinking — which is precisely the stretch this number exists to report.

---

## 2. Tier DOMAIN — knows what a marker, a finding, an agent is

### `PageHeader`
**Screens:** all ten. **Java:** `head(title,sub)` 2413-2415, `head(title,sub,back)` 2424-2439.
```ts
type PageHeaderProps = {
  title: string;
  subtitle: React.ReactNode;      // NOT a string
  back?: Crumb;                   // NOT a bare label
  findingsOpen: number;
};
```
**The single biggest prop reconciliation in the catalogue.** Nine passes named this `PageHeader`, `PageHead` or `Head`; five typed `sub: string` and four `sub: ReactNode`.

- `title` goes through `esc()`; **`sub` is appended RAW** (2437-2438). It is raw HTML today because callers push entities (`&middot;`, `&mdash;`) and, on `/marker` and `/trace`, a whole `<span class='s …'>` state pill through it (1816-1817, 2147-2149). A `sub: string` prop re-opens that hole on the first screen that puts a repo name in its subtitle. `React.ReactNode`, and the screens compose.
- `back` is a **label only** — the href is hardcoded `'/'` (2436) regardless of what the label says. Every current caller happens to want `/`, so the coincidence has never bitten. Making it a `Crumb` is the port's chance to stop it being a coincidence.
- `head()` also injects `<style>`, `LIVE` and `KEEP_OPEN` (2425-2426). None of that is the header's job in React: the stylesheet is the app shell, SSE is a hook, fold memory belongs to whoever renders folds.
- `index()` calls the two-arg overload, so **`/` has no back crumb** — it is where back goes.

### `FindingsButton`
**Screens:** all ten. **Java:** `findingsButton()` 2404-2411; count from `holding()` 800-809.
```ts
type FindingsButtonProps = { open: number };
```
- **Prop-value conflict, resolved against the source.** Four passes wrote "verdict is `holds`". `holding()` counts `holds` **OR** `unjudged` and excludes `refuted` (804-806), for a stated reason: *a critic that could not be reached must not be able to suppress a warning by failing.* `open = holds + unjudged`.
- **No badge at zero, on purpose** (2400-2403). The link is always drawn; only the number is suppressed. Take the count, never a `show` flag and never a class.
- Consequence for the API: **every screen's payload carries `openFindings`.** Today the function reaches a module-level `root` (2503) precisely because `head()` takes no results directory.

### `StateBadge`
**Screens:** markers, marker header, trace (`settled` events), supervisor subtitle. **Java:** `css()` 2682-2684; call sites 1728-1731, 1816-1817, 2148, 2210-2211.
```ts
type StateBadgeProps = { state: MarkerState; href?: string };
```
- Merges `StateBadge` / `StatePill`. Prop conflict: one pass passed `markerKey` "only used when the pill is a link". Resolve with `href?` — the caller knows when the pill is a link, the badge does not.
- **`proving` is the one state still happening**, so on the index it alone is a link, to `/marker?k=…&a=live` (1728-1730). Every other word is a conclusion and is plain text.
- **A LIVE BUG THAT MUST NOT BE PORTED:** `css()` returns the state as a class only when it matches `[a-z-]+`, else `"infra"`. `verified/pr-ready` and `verified/pr-rejected` contain a slash, so **the pipeline's two best outcomes render in the red of a broken machine**, and the `.verified-pr-ready` / `.verified-pr-rejected` rules at CSS line 54 have never been reached by anything. Mapping `MarkerState → tone` in TS silently fixes this *and changes what the page looks like* — do it knowingly.

### `VerdictPill`
**Screens:** supervisor. **Java:** `reported()` 750-753.
```ts
type VerdictPillProps = { verdict: Verdict };
```
- **Deliberately NOT shared with `StateBadge`, despite identical markup.** Today it borrows marker-disposition classes: `holds→settled`, `refuted→infra`, `unjudged→needs-review`. `infra` everywhere else in this app means "never ran" — the *opposite* of "the critic knocked it down". Same shape, different vocabulary; give `refuted` its own token.

### `Semaphore` + `Lamp`
**Screens:** markers today; **should be on the marker page** (see below). **Java:** `flags()` 2313-2326, `dot()` 2328-2331; CSS `.sema` 186-191.
```ts
type SemaphoreProps = {
  flags: SettlementFlags | null;   // null = no settlement row ⇒ renders nothing
  state: MarkerState;
};

type LampProps = {
  which: 'red' | 'green';
  lit: boolean;                    // the build said so
  reached: boolean;                // this stage was got to at all
};
```
- Prop reconciliation: one pass wanted `hasSettlement: boolean` beside two booleans, another wanted `boolean | null`. Both facts are needed and they are different facts, so: one nullable *object* (no settlement row) containing two nullable booleans (never recorded). Four props become two.
- **`Lamp` drops `title`.** Java passes it, but both call sites pass a fixed sentence decided entirely by `which` ("reproduced: the test failed first" / "fixed: the same test then passed"). The component owns its tooltip. 4 props → 3. Those tooltips are currently the *only* place on the whole dashboard that explains that red is supposed to fail.
- **Hollow is not "no".** A marker the reproducer declined never had a red to fail, which is a different answer from a red that passed. Three appearances, and the middle one is the interesting one.
- `reachedGreen` is derived from `red === true`, not from any state. `reachedRed` excludes `queued` and `not-a-bug` — **the `not-a-bug` guard is dead** (nothing writes that state) and can go.
- **FINDING, unanimous across two passes and confirmed by grep: `flags()` has exactly one caller, `index():1732`.** The semaphore is not on `/marker`. Yet `red_verified`/`green_verified` live per-marker in settlements.jsonl, and the two facts a state cannot carry are precisely the two facts the marker page exists to show. Put it there.

### `SeverityBadge`
**Screens:** markers. **Java:** `index()` 1701, 1717-1718; `severities()` 782-791; CSS 56-60.
```ts
type SeverityBadgeProps = { severity: Severity | null };
```
- Picks its own colour by lowercasing; never takes a class. **`null` is a real answer**: severity is reference data joined from `severities.tsv`, covering the 282 markers that analyser run reported; the other 74 (`src/it`, `src/test`) get an em dash rather than a guess.
- The join key is `basename|line|checker` (787, 1701) — **the file BASENAME, not the path** — so two same-named files in different packages can collide.

### `MarkerIdentity` — was `MarkerLink` on the index
**Screens:** markers. **Java:** `index()` 1690-1700 (parsing), 1719-1723 (markup); CSS `.k` 52.
```ts
type MarkerIdentityProps = {
  markerKey: MarkerKey;
  file: string;      // from the settlement row, else parsed out of the key
  line: string;
  checker: string;
};
```
- Renders `B.java:82` linked to `/marker?k=…`, with the checker and the package tail on two small grey lines. The directory line strips `src/main/java/` and `src/test/java/` (1699) because two markers in one run are routinely both `LessonPage.java`.
- Must render fully for a **queued** marker: there is no settlement row, so file/checker come from the key, which is always present.

### `MarkerCrumb` — was also called `MarkerLink`
**Screens:** trace, supervisor. **Java:** `one()` 2183-2187.
```ts
type MarkerCrumbProps = { marker: string; href: string | null };
```
- **The real finding in the merge: two passes gave one name to two different components.** This one is the small grey line *above* an event on the whole-trace view; its text is `key.substring(key.lastIndexOf('/')+1)`, which for `repo|src/main/java/Foo.java|82|CHECKER` is `Foo.java|82|CHECKER` — not a filename, not a slug, and a *different* truncation from `slug()` (571-575). Do not "fix" it into a filename without checking `marker()`'s own title (1815), which uses the same expression.
- `href: null` is required, not optional: the supervisor's trace sets `marker` to `"overwatch"` or to a directory slug, neither of which is a marker key, so every crumb on `/overwatch?a=trace` links to a marker page for a marker that cannot exist. **Fix it in the payload** — carry the real key or carry `null` — not with a prop that suppresses the link on one screen.

### `MarkerLinkedText`
**Screens:** chat, supervisor findings. **Java:** `linked()` 678-691; map from `slugs()` 694-707.
```ts
type MarkerLinkedTextProps = {
  text: string;                          // raw agent prose
  markers: ReadonlyMap<Slug, MarkerKey>; // Map, not object — order matters
};
```
- Merges `LinkedFindingText` and `MarkerLinkedText`. **Escape first, link second** (comment 675-676): this is the only thing stopping a model's answer from putting markup on the page. In React the escaping is free, so only the linking survives — build an array of strings and `<a>` elements; `dangerouslySetInnerHTML` here reintroduces exactly the hole the ordering closes.
- **Longest slug first** (699-701), so a slug containing a shorter one is linked whole. A `Map` preserves insertion order in JS; a plain object does not guarantee it — hence the type. The `\u0000/\u0001` sentinel dance (685-690) exists only because Java does repeated `replace`; a node-array pass gets it free.
- Link target is `/marker?k=<urlencoded map VALUE>`, not the slug.
- Empty map (no `markers.txt` yet) renders plain text. Normal state before a run starts, not a failure.

### `FlaggedSource`
**Screens:** markers (inside the `why` fold), marker summary. **Java:** `flagged()` 2528-2564 → `code()` 2609 → `block()` 2618 → `colourJava()` 2665; call sites 1739-1741, 1841-1846.
```ts
type FlaggedSourceProps = { source: FlaggedSource | null };   // null ⇒ renders nothing
```
- Merges `SourceExcerpt` and `FlaggedSource`; the two prop shapes (`{firstLine, flaggedLine, fileLength, lines[]}` vs `{lines:{n,text}[], flagged, fileLines}`) reconcile to the second — line numbers travel *with* their lines, so a window that starts at 1 and one that starts at 78 need no separate offset field.
- **Two facts the current blob smuggles in as text and the JSON must carry as data:** the flagged line is marked with a literal `">> "` prefix (2553), and when the marker points past EOF the string `"line N — THIS FILE HAS M. The analyser ran against an older revision"` is appended (2556-2562). That sentence is the difference between a reader trusting the line number and knowing not to; it is a `fileLines` comparison, not prose.
- **Read blank-lines-and-all** (2537-2542): using `read()`, which drops blank lines, shifted every number after line 79 by four and was nearly written up as an analyser bug. If the JSON ships lines, that hazard moves to whoever builds the array.
- Window is ±4 (`AROUND` 2514), decided in Java. Renders nothing when `/work/checkouts` has no tree — a missing tree is a missing convenience, not an error.

### `RunProgress`
**Screens:** markers. **Java:** `progress()` 2235-2249; inputs computed at 1635-1640, called 1665.
```ts
type RunProgressProps = {
  total: number;
  settled: number;
  beganAt: number;   // epoch ms of the earliest trace event; 0 = nothing has run
  now: number;       // server clock at render, so elapsed does not trust the browser's
};
```
- **Take `beganAt`, not `elapsed`.** Java bakes `System.currentTimeMillis() - began` into the HTML (1640), so the clock only moves when the whole page re-renders. A component holding `beganAt` ticks on its own.
- `eta = elapsed/settled*(total-settled)`, an em dash when `settled` is 0 or equals `total`; `pct` capped at 100. Two degenerate branches to keep: `total<=0` with elapsed renders **only** the elapsed tally and no bar; `total<=0` with no elapsed renders nothing at all (2236-2239).
- `settled` counts everything except `proving`/`queued`/`interrupted` (1636-1638) — **`infra` counts as settled.**

### `StateCounts`
**Screens:** markers. **Java:** `index()` 1668-1676.
```ts
type StateCountsProps = {
  counts: Partial<Record<MarkerState, number>>;
  humanMinutes: number;
};
```
- Sorted **alphabetically** by state name (TreeMap, 1668) — not by count, not by pipeline order. Every state present gets a tile, including the not-a-disposition ones.
- The human-equivalent tile is drawn only when `humanMinutes > 0` and is **always** `"Xh Ym"` (1674-1675). `HumanCost` in the row below uses `hm()` (2344-2346), which drops the hours part under 60 minutes. **45 minutes reads "0h 45m" here and "45m" there, on the same screen.** Pick one deliberately.

### `MarkerTable` / `MarkerRow`
**Screens:** markers. **Java:** headers 1683-1684; body 1686-1748.
```ts
type MarkerTableProps = { markers: MarkerRowData[] };
type MarkerRowProps   = { marker: MarkerRowData };

type MarkerRowData = {
  key: MarkerKey; repo: string; file: string; line: string; checker: string;
  severity: Severity | null;
  state: MarkerState;                 // RESOLVED server-side — see below
  flags: SettlementFlags | null;
  events: number; spanMs: number; humanMinutes: number;
  headline: string;                   // summary.txt[0], "" if nothing interpreted it
  verdictText: string;                // RAW, unabridged, "" while in flight
  lastNote: string;                   // last progress note / settled `because` / failed `cause`
  flagged: FlaggedSource | null;
};
```
- Six cells, so the row takes one object rather than six props. Nothing is looked up per row at render time.
- **One row reads as one sentence left to right:** how bad, what and where, what we decided, why, what it cost (comment 1678-1682). `where` used to sit between severity and state and split the two columns a reader compares; a seventh `latest` column was removed because a running marker's progress note *is* its `why`.
- **The state on screen is not `settlements.state`.** A settlement saying `proving` is rewritten to `interrupted` unless `results/claims/<slug(key)>` exists (1619-1627); a queued key that has a claim but no settlement row is rewritten to `proving` (1629-1634). Resolve this server-side — a React client must never be shown the raw state. `slug()` must keep matching `entrypoint.sh` exactly: a claim it cannot find reads as a marker nobody is working on.
- **Row order is `markers.txt` order** (LinkedHashMap seeded from the queue, then settlements overwrite in place, then unqueued settled keys appended). Sorting the table by state throws away the run's plan order. Do not sort server-side either.

### `WhatHappened`
**Screens:** markers. **Java:** `index()` 1702-1742; `summary()` 1541-1554; `oneLine()` 2334-2342; `firstSentence()` 2579-2598; `cut()` 2686-2688.
```ts
type WhatHappenedProps = {
  markerKey: MarkerKey;      // the Disclosure's stable id
  headline: string;
  verdictText: string;
  lastNote: string;
  flagged: FlaggedSource | null;
};
```
- Three branches: em dash when nothing has been said; a grey one-line note cut at 150 chars while running; once a verdict exists, a `Disclosure` whose closed line is one readable sentence and whose body is `FlaggedSource` + the whole verdict.
- **The closed line is derived, not stored.** Prefer `summary.txt`'s first paragraph, else `firstSentence(verdictText)` — which flattens markdown, strips a leading restatement of the verdict (arguments open `"false-positive false-positive The static analyzer claims…"`, 2584-2590), skips sentences ending in `:` or matching `looking at …`, wants ≥40 chars, and falls back to the flattened opening capped at 240. **Keep that in the client and send the raw text**; a pre-computed sentence throws away the argument the fold exists to show.
- The verdict branch is deliberately **not** truncated: *a reason cut at 200 characters is a reason nobody can check* (1704-1706).
- This is a `Disclosure`, not a `TextFold`: the label is a sentence and the body is code + prose.

### `TimeSpent` / `HumanCost`
**Java:** span 1568-1596, events 1561-1564, markup 1743-1746; `clock()` 2348-2352, `hm()` 2344-2346.
```ts
type TimeSpentProps = { spanMs: number; events: number };
type HumanCostProps = { minutes: number };
```
- **Wall clock, not machine time:** first-to-last event including every gap the marker sat waiting. Zero renders an em dash, so "nothing has happened" and "it took no time" look the same.
- `minutes === 0` means both "never priced" and "priced at nothing" — `num()` swallows an estimator that answered in prose (1578-1579). Both show an em dash, which is honest.

### `ChainStrip` / `ChainStage` / `AgentChip`
**Screens:** marker (all tabs), live. **Java:** `tabs()` 2059-2096, `STAGES` 2039-2044, `chip()` 2099-2104.
```ts
type ChainStripProps = {
  markerKey: MarkerKey;
  current: '' | AgentName | 'live' | 'prompts' | 'trace';
  runs: Partial<Record<AgentName, number>>;   // count of `asked` events per agent
};

type ChainStageProps = {
  label: StageName;
  producer: { agent: ChainAgent; runs: number };
  critic:   { agent: ChainAgent; runs: number };
  markerKey: MarkerKey;
  current: string;
};

type AgentChipProps = {
  agent: AgentName;
  runs: number;
  active: boolean;
  href: string;
};
```
- Merges `ChainStrip` / `MarkerTabs`. Renamed away from `MarkerTabs` because it is not a `TabRow`: it is five bordered stage groups with pills either side.
- **`runs === 0` omits the count element entirely** — an agent that never ran shows no number, not a zero (2100-2103). Same instinct as the findings badge and the semaphore's hollow lamp.
- **The `↺` glyph is inferred, not recorded.** `producer.runs > 1` *is* the critic having sent the work back — nothing else in the chain makes a producer run twice (2079-2081). Derive it in the component; do not add a `looped` prop or Java keeps the inference. Note it **replaces** the arrow rather than sitting beside it.
- A stage dims when `producer.runs + critic.runs === 0`, and a greyed-out stage is usually the most informative thing on the page (2053-2055).
- Counts must be computed **before** the tab dispatch (1790-1791) — an earlier version dispatched first and two tabs rendered with no counts.
- `STAGES` is a hardcoded second copy of `Agents.CHAIN` that the source itself flags as having drifted (1758-1764: `verdict-critic` was missing and its answers were readable only in the whole trace). **The API should ship the chain shape once, or React should hold one copy — not a copy of the copy.**

### `TraceEvent` and its eight bodies
**Screens:** trace, marker?a=trace, supervisor (all three event views). **Java:** `one()` 2177-2223; kind→colour CSS 182-183, 202-205.
```ts
type TraceEventProps = {
  event: TraceEvent;
  showMarker: boolean;        // true on the whole-trace view; was `key.isEmpty()` (2183)
  markerHref: string | null;  // see MarkerCrumb — null on the supervisor's record
  defaultOpen: boolean;
  feedbackBack: string;       // the URL a rating returns to (`self`, 2178-2180)
};

type AnsweredEventProps = { agent: AgentName; reply: string; prompt: string;
                            eventId: string; marker: string; back: string };
type ThoughtEventProps  = { agent: AgentName; text: string; defaultOpen: boolean };
type ToolCallEventProps = { agent: AgentName; tool: string; arguments: string;
                            result: string; defaultOpen: boolean };
type BuildEventProps    = { phase: 'red'|'green'; passed: boolean; infra: boolean;
                            summary: string; defaultOpen: boolean };
type ProgressNoteProps  = { note: string };
type SettledEventProps  = { state: MarkerState; because: string };
type PricedEventProps   = { minutes: number; itemisation: string };
type FailedEventProps   = { cause: string };
```
- **`index: number` is gone.** Three passes carried it. It is the position in *whichever list this view happens to be showing* — across all markers on `/trace`, within one marker on `/marker?a=trace`, and a third numbering on the agent tab (`mine.indexOf(last)`, 1996, over an **unsorted** list while `events()` sorts by `at`, 2127). The same physical answer therefore posts a different `event` number depending on where you rated it from, and `feedback.jsonl` already holds all three kinds with nothing to tell them apart. Keep `index` only as a React key if you must; the payload gives every event a stable `id`.
- **`infra` beats `passed`** (2205-2206): `infra: true` renders "never ran" whatever `passed` says, because a build that never compiled cannot have failed the *test*, and "failed" would read as evidence about the defect. Derive the word from two booleans; a `label: string` prop moves that judgement back into Java.
- `ThoughtEvent` is styled apart — purple border, purple agent name — because *reasoning is not evidence*. That is a rule keyed on `kind`, which is where it belongs.
- `ProgressNote` is the only kind with no border colour of its own; it recedes, correctly — these are punctuation between the events that matter. Not to be confused with `RunProgress`.
- `FailedEvent` is a prove that broke, **not** a marker that settled as `infra`. The two are easy to conflate in the feed.
- `arguments` arrives as ordinary text with real newlines — `field()` (2697-2701) has already peeled one layer of JSON string. Treat it as text, never re-parse it.
- Unknown kinds render as their bare name. Keep it.

### `EventFeed`
**Screens:** trace, marker?a=trace, supervisor ×3. **Java:** `events()` 2117-2167 and `supervisorEvents()` 629-640 — two functions doing one job in opposite directions.
```ts
type EventFeedProps = {
  events: TraceEvent[];
  order: 'oldest-first' | 'newest-first';
  showMarker: boolean;
  markerHrefFor: (e: TraceEvent) => string | null;
  defaultOpen: boolean;
  feedbackBack: string;
  cursor?: number;            // present only where live append is wanted
};
```
- **The two orders are both deliberate and must both survive.** `/trace` and `/marker?a=trace` sort **ascending** (2127) and append at the bottom (2139-2141, and `beforeend` at line 266) — appending is what makes a live update non-destructive to open folds and scroll. The supervisor sorts **descending** (624, 657) for a stated reason (643-649): *a prove is read after it settles and runs forwards; the supervisor is read while running, and the question is what it just said.* One pass's brief said "newest first" for `/trace`; the code says otherwise. Port the behaviour.
- **Uncapped, on purpose** (609-617): roughly 8MB after an afternoon, and the author's position is that a page silently showing part of a record reads as the record. Virtualise; do not slice.
- Sort a **copy**: `read()` hands back an immutable list and sorting it in place threw `UnsupportedOperationException` out of the handler, which `HttpServer` answers with an empty 20ms reply that looks exactly like a page too big to build (619-622).
- No supervisor view emits `cursor()`, and `LIVE` returns early when `body.dataset.events` is undefined (255-259) — **the whole supervisor screen is static and only updates on reload**, while `/trace` and `/marker` append live. Keep that deliberately, or cursor the supervisor's events too; do not poll a page whose folds are the reader's.

### `RateAnswer`
**Screens:** trace, marker agent tabs, supervisor findings. **Java:** `rate()` 2258-2270, `hidden()` 2272-2274, POST handler **376-388** (one pass cited 1697-1710 — wrong), row written by `record()` 2277-2286.
```ts
type RateAnswerProps = {
  target:
    | { kind: 'answer'; eventId: string }
    | { kind: 'finding'; index: number };   // findings have no id; the file index IS the id
  back: string;
  onSaved?: () => void;
};
```
- **Six props down to three, and the smaller list is also the more honest one.** Today `prompt` and `reply` ride along as hidden fields so the written row is a complete training example without a second read of the trace (2254-2257) — that property is worth keeping, but with a stable `eventId` the *server* does the lookup, and the corpus stops depending on the browser echoing kilobytes back.
- **An escaping hazard that is biting right now:** `hidden()` writes `value='…'` in single quotes and `esc()` (2765-2767) replaces only `&`, `<`, `>` — **never an apostrophe**. Every prompt or reply containing `'` is truncated at the first one and the remainder is parsed as stray attributes. The feedback corpus already holds mutilated training examples. React makes recurrence impossible; the existing rows are suspect.
- On the supervisor, `reported()` calls this with `marker="overwatch"`, `agent="overwatch"`, `prompt=`the finding, `reply=`the judgement (757-758). A feedback row from that page is filed against a marker that does not exist, and the critic's work is attributed to the supervisor. Fix in the payload, not the component.
- The anchor `#f<index>` exists on every finding (748) but `back` is plain `/overwatch`, so saving returns the reader to the top of the page.

### `StreamPanel` / `StreamTail`
**Screens:** live, chat. **Java:** `panel()` 903-933, `LIVE_TAIL` 936; callers 849, 877, 981.
```ts
type StreamPanelProps = {
  who: string;                 // a marker slug on /live, the literal "supervisor" on /chat
  agent: AgentName | null;     // "" on disk ⇒ null
  at: number;                  // 0 = nothing yet
  text: string;
  truncated: boolean;
  defaultOpen?: boolean;       // every caller passes true today
};

type StreamTailProps = { text: string; truncated: boolean };
```
- **This is the shared component used with different data — the finding the brief asked for.** Everywhere else `panel()` gets a marker slug and `m/<slug>/trace.jsonl.live`; on `/chat` it gets the literal `"supervisor"` and `chat-trace.jsonl.live`, a file belonging to no marker. So `who` **must not be typed as a slug** and must not be linked to `/marker`. Three consequences:
  1. The CSS is scoped `.live details.stream` (88-96) and `/chat` renders the panel inside `.chat`, so on that one route the fold has **none of its styling**. The React port fixes this by accident — know it before someone calls it a regression.
  2. The 2s poller only refreshes `#live` (319-331); `/chat` has no such container, so its panel advances solely on the `<meta http-equiv=refresh content=3>` (956).
  3. The DOM id is `live-<who>` **in full** while the summary truncates `who` to 46 chars (922, 930). Keying must use the full `who`.
- **The tail, not the head** (927-928): a reasoning turn runs to tens of thousands of characters and opening on the beginning shows the same paragraph for four minutes. The 4000-char cut stays server-side (it is a bandwidth decision at a 2s poll) and is admitted with `truncated`.
- An unreadable or half-written file (fewer than two newlines) yields `agent=null, at=0, text=""` and must render as "nothing yet", **not** as an error — the file is being rewritten as it is read and the next poll is 2s away (917-919).
- Blank text renders a single ellipsis, not an empty box. No autoscroll today: `max-height` + `overflow:auto`, so the fold does not jump under a reader who has scrolled up.

### `LiveStream`
**Screens:** live. **Java:** `poll()` inside `KEEP_OPEN` 319-331; `/live` route 500-502; `live()` 837-852.
```ts
type LiveStreamProps = {
  markerKey: MarkerKey;
  initial?: LiveView;
  intervalMs?: number;   // 2000
};
```
- **Polled, not pushed, on purpose** (410-412): `/events` fires when the trace and settlement counts move, and an agent reasoning for four minutes moves no counts — which is exactly the stretch worth watching.
- Fold state here deliberately does **not** go through sessionStorage (315-318): these folds are replaced far too often, so open/closed is read off the DOM before the swap and put back after. In React it is state keyed by `who` — never by array index, or a pool-wide list would reshuffle what the reader had open.
- Today `/live` with an empty `k` returns 200 with an empty body, which the poller writes straight into the box: **an error and "no marker asked for" are indistinguishable**, and both blank the panel.

### `ProveFinishedNotice`
**Java:** `live()` 845-848; test in `settled()` 891-900.
```ts
type ProveFinishedNoticeProps = Record<string, never>;
```
- The state is the **complement** of four unfinished states (blank, `proving`, `infra`, `queued`), not a match against the seven dispositions. Keep it that way server-side: a state nobody has thought of yet must read as finished rather than as still running. Reason the branch exists: the `.live` file outlives the prove that wrote it, and a panel still rendering it would be a live view that is quietly a museum.

### Marker-summary components
**Java:** `marker()` 1820-1943.
```ts
type ClaimCardProps = { checker: string; file: string; line: string; claimNote: string | null };
type MarkerAccountProps = { text: string };                       // summary.txt[1]
type BuildOutcomesProps = { builds: { phase: 'red'|'green'; passed: boolean; infra: boolean }[] };
type TestArtifactProps  = { path: string; code: string };
type FixDiffProps       = { patch: string };
```
- `ClaimCard`: the page splits the key on `|` itself (1829-1834) — the JSON sends four fields. `claimIs()` (1524-1539) reads a bundled `/checkers/<name>.txt` (sanitised to `[A-Za-z0-9._-]`), drops the first line, takes up to the first blank line. Its no-note fallback is **prose, not a fact** — send `null` and let the component write the sentence.
- `MarkerAccount`: `summary()` (1541-1554) splits `summary.txt` at the first blank line; `[0]` is the table's headline, `[1]` is this. With no blank line both halves are the whole file, so the list and this page say the same thing twice. **Send them as two named fields, not an array.**
- `BuildOutcomes`: **the state that means its opposite.** `phase: 'red'` is the run *before* the patch and is supposed to FAIL; a red that passed has demonstrated nothing, and 1872-1877 is the only place on the dashboard that says so. `infra: true` is a third outcome, not a pass and not a fail. Java composes the English (1866-1878); the component should, so the wording can change without redeploying the record.
- `TestArtifact`: recovered today by scanning for the **last** `write_file` tool call from *any* agent (1890-1898). `settlements.jsonl` already holds `test_path` and `test_code` and this screen ignores both. **Serve those and delete the scan.**
- `FixDiff`: **the worst dependency on any screen.** The patch is not recorded as an artefact, so `marker()` scrapes it out of the *text of fix-critic's prompt*, between the heading `WHAT IT ACTUALLY CHANGED` and either `\nThe patch changes ` or `\nTHE PATCH DOES NOT TOUCH` (1911-1930). Reword either prompt and the fix silently vanishes from the page. `settlements.jsonl` has `fix_diff` — serve it.

### Marker agent-tab components
**Java:** `marker()` 1945-2024; `asked()` 2028-2036.
```ts
type AgentAnswerProps = { agent: AgentName; reply: string; prompt: string;
                          attempt: number; attempts: number; eventId: string; back: string };
type SupersededAttemptProps = { attempt: number; reply: string; prompt: string };
type ThinkingProps = { turns: { id: string; text: string }[] };   // record order; component reverses
type ToolLogProps = { calls: { tool: string; arguments: string; result: string }[];
                      answered: boolean };
type AgentPendingProps = { agent: AgentName; calls: number; hasThinking: boolean };
```
- **A matching inconsistency the payload must settle:** answers and thoughts match the agent with `equals` (1966, 2031) while tool calls match with `endsWith` (1948). An event whose agent is recorded with any prefix contributes tool calls but no answers.
- `Thinking` is gathered **before** the early returns below it (1959-1962): an agent seven tool calls in has answered nothing, and its thinking is the only account of what it is doing. Any port that renders thinking only alongside an answer loses the live case.
- `ToolLog` arguments are shown **in full** and must stay that way: the argument to `write_file` *is* the test, and the old 110-character cut showed the path and hid the only thing worth reading (1950-1951).
- `AgentPending`: zero calls **and** no thoughts is "has not run"; anything else is "working — N tool call(s), no answer yet". Reporting a mid-answer agent as not-run threw away the only live view of it. Note "has not run" is a claim about the record: a trace still being written reads the same as one that never started.
- `SupersededAttempt` renders newest-first below the final answer (2007) while printing ascending attempt numbers. Decide deliberately rather than inheriting.

### Supervisor components
**Java:** `reported()` 709-769.
```ts
type FindingTallyProps = { holds: number; refuted: number; unjudged: number; restarts: number };

type FindingCardProps = {
  finding: { index: number; at: number; verdict: Verdict; finding: string; judgement: string };
  markers: ReadonlyMap<Slug, MarkerKey>;
  defaultOpen: boolean;
};

type RestartLogProps = { restarts: Restart[]; defaultOpen: boolean };
type Restart = { at: number; id: Slug; marker: MarkerKey; attempt: number;
                 killed: boolean; by: 'person' | 'supervisor'; why: string };
```
- `FindingTally`'s empty branch tests `all.isEmpty()` — **findings only** (717). With zero findings and five restarts the subtitle says "the supervisor has not reported yet" while `RestartLog` renders five restarts directly beneath it. The empty case is `holds+refuted+unjudged === 0 && restarts === 0`.
- `unjudged` is derived as `size - holds - refuted` (715), which folds blank verdicts the same way `verdictOf()` does. One rule, not two.
- `FindingCard`: `index` is the position in the **file**, not in the rendered order — the page walks holds, then unjudged, then refuted (738), and the anchor, id and feedback event all keep the original number. Any reordering must preserve it. Refuted cards are visually demoted, not hidden, deliberately (735-737).
- `RestartLog` today flattens seven recorded fields into one `<pre>` blob showing four (724-729). **The dropped one that matters is `by`:** it distinguishes a restart the supervisor spent from its allowance of two from one a person ordered with `/reprove`, and conflating them was a real bug (426-430; `Supervisor.restarts()` counts only lines without `by="person"`). Give each restart its own row; `id` is a slug and `marker` is the exact key, and neither is a link today.

### Settings components
```ts
type SettingRowProps = {
  name: string;                 // 'the markers', 'parallel provers', 'reproducer'
  state: string;                // the small uppercase word: 'edited', 'the code's own', 'currently 4'
  changed: boolean;             // picks the accent ITSELF
  anchorId?: string;
  children: React.ReactNode;
};

type AgentGroupHeadingProps = { group: 'chain' | 'watch' | 'asked' };

type AgentPromptEditorProps = {
  agent: AgentName; builtIn: string; saved: string; differs: boolean;
  onSave: (prompt: string) => void; onRevert: () => void;
};

type ParallelProversProps = { workers: number; least: number; most: number;
                              fallback: number; onSave: (n: number) => void };

type KeyStatusProps = { keyed: boolean; keySource: 'this page' | 'the environment' };
type ForgetKeyChoiceProps = { keySource: 'this page' | 'the environment';
                              checked: boolean; onChange: (v: boolean) => void };

type MarkerQueueProps = { queued: number; repos: string[] };
type UploadFormProps  = { setting: 'markers' | 'zip'; onUpload: (f: File) => void };
type MarkerPasteProps = { onUse: (text: string) => void };
type GitCredentialProps = { host: string; token: string;
                            onSave: (host: string, token: string) => void; onForget: () => void };
type JdkChoiceProps = { chosen: string; available: string[]; fallback: string;
                        onSave: (v: string) => void };
type SourceZipProps = { present: boolean; onUpload: (f: File) => void; onRemove: () => void };
type UploadOutcomeProps = { refused: boolean; text: string };
```
- **`SettingRow` merges four passes' `SettingRow` / `SubjectSetting` / `EndpointKeyStatus` / the prompts-page row.** They all use the same idiom, and **the class names lie**: the div is `ev asked` (blue — the colour of a live prompt) when something has been changed and `ev tool` (grey) when it is still the default (1196, 1224, 1256, 1276, 1332, 1435, 1509). Nothing was asked and no tool ran; the trace-event palette is borrowed to mean "someone has changed this". **Take `changed: boolean`. Never inherit a `kind` or `className` from `TraceEvent`.** Two inconsistencies to decide about while porting: the markers row is hardcoded `tool` (1202), so an *empty* queue — a pipeline with nothing to do — looks exactly as calm as a full one; and `theRun` hardcodes `ev asked` (1387), so the run width always looks overridden even at the default 4.
- The row on `/settings` is also a scroll anchor: saving redirects to `/settings#<agent>` and `.ev:target` (CSS 86) outlines it in `#f85149` — **the same red `.ev.failed` uses. A successful save is currently highlighted in the failure colour.**
- `AgentGroupHeading`: Java only tests `Agents.CHAIN.contains(agent)` (1426), so everything else falls under "watching the run" — which puts `chat` there, and chat watches nothing (`Agents.ASKED`, 421-422). **Three groups exist in the record; the page shows two.**
- `AgentPromptEditor`: "edited" means only `saved.isBlank() == false` (1415, 1434) — paste the built-in back verbatim and the row says edited forever, while `promptsFor()` answers the same question with `Prompts.same()` normalisation (1480, 1508). Sending both `saved` and `differs` lets the row say "edited" and "same as the code's" separately. The textarea shows the **effective** prompt, so an edit always starts from what the agent is really running.
- `ParallelProvers`: `Workers.of()` falls back to `DEFAULT` on a missing *or* unreadable file (Workers 36-48), so `workers: 4` cannot be told from "nobody set this" or "the file is a typo". The server clamps on save (`Workers.clamp` 57-59) while the form only sets `min`/`max` in the browser — **render the response, not the request.**
- `KeyStatus` + `ForgetKeyChoice`: **the API key field is OUTSIDE the form.** The `<form>` does not open until 1364, after the key label (1340) and the forget checkbox (1359) are already emitted, and neither carries an HTML5 `form=` attribute. Nothing named `api_key` or `forget_key` is ever submitted, so `Tuning.save()`'s carefully-reasoned key branches (Tuning 138-144) are unreachable from this page. **Porting it inside the form makes the feature work for the first time.**
- `JdkChoice`: `available` must come from the server — it is a property of the image (`Subject.JDKS` = 25, 21, 17, 11, 8). Saving an unknown value fails **silently** (`Subject.saveJdk` returns without writing) and `subjectPosted` detects it only by re-reading and comparing (1158-1160). Validate against `available` and say so.
- `SourceZip`: a boolean is genuinely all the record holds (`Files.isRegularFile`). The byte count is reported once, in the flash message (1174-1175), and is unrecoverable on the next load. "Uploaded 4.2MB on Tuesday" is new state the Java side must start keeping. Note the inverted accent: `present` is the *highlighted* state (1276) because a zip means the network is bypassed.
- `UploadOutcome`: **a wire convention that must not survive.** Java signals refusal with a leading `!` on the string and strips it at render (1130-1182, 1196-1198). Ship `refused: boolean` and text without the sentinel. The text is genuinely multi-line — a bad marker file returns up to twelve complaints joined with `"\n  "` (1136) — so preserve newlines, and ship the complaints as `string[]` so the client can number and link them. Exceptions land here too, as `ClassName: message` (1181-1183), including the 64MB upload cap.

### Chat components
**Java:** `chat()` 952-1000; `SEND_ON_ENTER` 1012-1020.
```ts
type ChatTranscriptProps = { turns: ChatTurnData[]; markers: ReadonlyMap<Slug, MarkerKey>;
                             readable: boolean };
type ChatTurnData = { at: number; who: string; text: string };
type ChatTurnProps = ChatTurnData & { markers: ReadonlyMap<Slug, MarkerKey> };
type AskBoxProps  = { answering: boolean; onAsk: (q: string) => void };
type AskNoticeProps = { said: string };
```
- **`mine` is not a stored fact** — it is `who === "you"` (`Chat.Turn.mine()`, Chat.java 52-54). Anything else, including a value nobody planned for, renders as the supervisor. Keep taking `who`.
- `at === 0` omits the whole "· N ago" span (972). It does not mean *now* and must not fall back to now.
- Body is `white-space: pre-wrap` (125-126), so the author's own newlines are load-bearing — but `Chat.answer()` strips the ends before recording, because a reply opening with two newlines rendered an inch below its own name.
- Order is append order; **do not re-sort by `at`** — a turn recorded with `at=0` would jump to the top.
- `readable` does not exist in Java: `Chat.turns()` swallows the IOException and returns an empty list (Chat.java 88-92), so a corrupted `chat.jsonl` renders as a friendly welcome. One distinguishable state.
- `AskBox`: enter sends, shift+enter is a newline (1002-1011), guarded twice — nothing happens if the textarea is disabled, and whitespace-only is swallowed. **The disabled state IS the confirmation that the question was taken**; there is no separate "sent" indicator, so do not clear-and-re-enable optimistically. `answering` is a process-wide `AtomicBoolean` (Chat.java 45): every open tab is disabled by somebody else's question.
- `AskNotice` is **not a turn** and must not be styled as one — it is the return of `Chat.ask()`: "still answering the last one", or "could not write the question down: …", which means the question is not in the record at all, so the transcript above it is complete. Today it travels in `?said=` and survives every 3s refresh; in React it is transient state from the POST.
- The three tails after the transcript are mutually exclusive, and **one means the opposite of what it looks like**: `unanswered` is not "still thinking", it is "the dashboard restarted mid-reply and no answer is coming" (983-987, Chat.java 105-108). Rendering it as a spinner is the bug the comment was written to prevent.

---

## 3. Tier SCREEN

| Screen | Route | Java | Composes |
|---|---|---|---|
| `MarkersScreen` | `/` | `index()` 1556-1753 | PageHeader, RunProgress, StateCounts, MarkerTable→MarkerRow, EmptyNote, Elsewhere links (1749-1751) |
| `MarkerSummaryScreen` | `/marker?k=` | `marker()` 1815-1943 | PageHeader, ChainStrip, ClaimCard, FlaggedSource, MarkerAccount, BuildOutcomes, TestArtifact, FixDiff, **+ Semaphore (new)** |
| `MarkerAgentScreen` | `/marker?k=&a=<agent>` | `marker()` 1945-2024 | PageHeader, ChainStrip, AgentAnswer, RateAnswer, SupersededAttempt, Thinking, ToolLog, AgentPending |
| `MarkerPromptsScreen` | `/marker?k=&a=prompts` | `promptsFor()` 1467-1522 | PageHeader, ChainStrip, PromptDriftAlarm, PromptUsedRow (§5.4) |
| `MarkerLiveScreen` | `/marker?k=&a=live` | `marker()` 1792-1801 | PageHeader, ChainStrip, LiveStream→(StreamPanel \| ProveFinishedNotice) |
| `TraceScreen` | `/trace`, `/marker?a=trace` | `events()` 2117-2167 | PageHeader, ChainStrip (marker only), FoldToggle, EventFeed |
| `SupervisorScreen` | `/overwatch?a=` | `overwatch()` 584-594 | PageHeader, TabRow, FindingTally, RestartLog, FindingCard, EventFeed(newest-first), EmptyNote |
| `ChatScreen` | `/chat` | `chat()` 952-1000 | PageHeader, ChatTranscript, StreamPanel, AskNotice, AskBox |
| `SettingsPromptsScreen` | `/settings` | `prompts()` 1406-1453 | PageHeader, TabRow, Account, AgentGroupHeading, SettingRow→AgentPromptEditor |
| `SettingsRunScreen` | `/settings?a=run` | `theRun()` 1383-1404 | PageHeader, TabRow, SettingRow→ParallelProvers |
| `SettingsModelScreen` | `/settings?a=model` | `theModel()` 1298-1381 | PageHeader, TabRow, SettingRow→(KeyStatus, SecretField, ForgetKeyChoice, LabeledField ×6, SaveRow), Account |
| `SettingsSubjectScreen` | `/settings?a=subject` | `theSubject()` 1187-1296 | PageHeader, TabRow, UploadOutcome, SettingRow→(MarkerQueue, GitCredential, JdkChoice, SourceZip), Account |

Two screen-level components with no markup of their own:

```ts
type FoldToggleProps = { expanded: boolean; href: string };
```
The label names the **action**, not the state, so it inverts against `expanded` (2153) — `expanded ? "fold the long parts" : "open everything"`. A rebuilder reading that as a state label will flip it. Default is expanded (`open()` 2374-2376, on the reasoning at 2369-2373: *reading a prove is reading the prompts*). Keep the choice in the URL: the live fetch reuses `location.search` (line 262), so the fold choice must survive into the fragment request or appended events arrive expanded on a folded page.

```ts
type LiveRefreshProps = { path: string };   // the /events SSE hook
```
`LIVE` 238-272 + the `/events` route 526-558. On `/` it refetches wholesale (the index has no cursor); on trace views it appends from the cursor. In React the scroll restoration becomes unnecessary; **open folds do not** — today they are keyed by a fold's index within the page, which moves every time a marker settles, so the wrong row springs open. Key by marker key / event id. The 2s `#live` poll inside `KEEP_OPEN` is inert on `/` — that page has no live panel by design (1660-1664: the header already carries what the supervisor *concluded*, and a paragraph of it thinking out loud is a slower way to learn less).

---

## 4. What the shared package holds

Needed by more than one screen — everything else can live beside its screen.

| Component | Screens |
|---|---|
| `PageHeader` | all 12 |
| `FindingsButton` | all 12 (via PageHeader) |
| `TabRow` | supervisor, settings ×4 |
| `Disclosure`, `TextFold` | markers, marker ×4, trace, supervisor, settings prompts, subject |
| `Pill` | markers, marker, trace, supervisor |
| `StateBadge` | markers, marker header, trace, marker?a=trace subtitle |
| `Semaphore`, `Lamp` | markers (+ marker summary, once fixed) |
| `SeverityBadge` | markers (only — but domain-shaped; keep it shared) |
| `MarkerIdentity` | markers |
| `MarkerCrumb` | trace, supervisor |
| `MarkerLinkedText` | chat, supervisor |
| `FlaggedSource`, `CodeBlock` | markers, marker summary (+ trace, if the gap is closed) |
| `DiffBlock` | marker summary (+ trace) |
| `TraceEvent` + 8 kind bodies, `EventFeed` | trace, marker?a=trace, supervisor ×3 |
| `RateAnswer` | trace, marker agent tabs, supervisor findings |
| `StreamPanel`, `StreamTail`, `RelativeTime` | live, chat |
| `LiveStream` | live (+ any future pool view — `proving()` 855-883 already builds one panel per claim and **has no caller**; the plural is latent) |
| `SecretField`, `LabeledField`, `SaveRow` | settings model, settings subject |
| `SettingRow` | settings ×4 |
| `Account`, `EmptyNote`, `Tally`, `ProgressBar` | many |
| `ChainStrip`, `ChainStage`, `AgentChip` | marker ×4 tabs, live |

Screen-local, no shared value: `RunProgress`, `StateCounts`, `MarkerTable`, `MarkerRow`, `WhatHappened`, `TimeSpent`, `HumanCost`, `ClaimCard`, `BuildOutcomes`, `TestArtifact`, `FixDiff`, `AgentAnswer`, `SupersededAttempt`, `Thinking`, `ToolLog`, `AgentPending`, `FindingCard`, `FindingTally`, `RestartLog`, `VerdictPill`, `AgentPromptEditor`, `AgentGroupHeading`, `ParallelProvers`, `KeyStatus`, `ForgetKeyChoice`, `MarkerQueue`, `UploadForm`, `MarkerPaste`, `GitCredential`, `JdkChoice`, `SourceZip`, `UploadOutcome`, `ChatTranscript`, `ChatTurn`, `AskBox`, `AskNotice`, `ProveFinishedNotice`, `FoldToggle`.

Deleted in the merge: `tab()`/`MarkerTab` (no call sites — `tabs()` hand-writes its five pills at 2071-2073 and 2087-2094), `settingsTabs`, `supervisorTabs` (all three become `TabRow` callers); `MarkerTabs` (→ `ChainStrip`); `Dot` (→ `Lamp`); `StatePill` (→ `StateBadge`); `FindingsBadge` (→ `FindingsButton`); `PageHead`/`Head` (→ `PageHeader`); `AccountNote` (→ `Account`); `EmptyRun`/`EmptyTrace` (→ `EmptyNote`); `SourceExcerpt` (→ `FlaggedSource`); `TraceScreen`'s and `SupervisorEventList`'s bodies (→ `EventFeed`).

---

## 5. The API

Existing today (Dashboard 368-375): **`/api/settlements`**, **`/api/trace`**, **`/api/feedback`** — each is `"[" + join(lines(file)) + "]"`, i.e. the raw JSONL concatenated. `lines()` (2452-2468) appends `results/<name>` then each `m/<marker>/<name>` in directory order, so **`/api/trace` is N ordered runs stapled together, not one story**. None is typed, sorted, id'd or cursored.

Every payload below carries `openFindings` (holds + unjudged), because `PageHeader` draws the badge on every page.

### 5.1 `GET /api/index` — MarkersScreen — **NEW**
```jsonc
{
  "openFindings": 3,
  "run": {
    "total": 356, "settled": 214,
    "beganAt": 1755000000000, "serverNow": 1755000900000,
    "traceEvents": 48211, "humanMinutes": 1483,
    "countsByState": { "false-positive": 180, "proving": 4, "queued": 12, "infra": 3 }
  },
  "markers": [{
    "key": "org/repo|src/main/java/a/B.java|82|FB.DM_DEFAULT_ENCODING",
    "repo": "org/repo", "file": "src/main/java/a/B.java", "line": "82",
    "checker": "FB.DM_DEFAULT_ENCODING",
    "severity": "Critical",                 // null for the ~74 src/it and src/test markers
    "state": "verified/pr-ready",           // RESOLVED, not settlements.state
    "flags": { "red": true, "green": false }, // null when there is no settlement row
    "events": 214, "spanMs": 812000, "humanMinutes": 45,
    "headline": "…summary.txt first paragraph…",   // "" if nothing interpreted it
    "verdictText": "…whole argument, unabridged…", // "" while in flight
    "lastNote": "…last progress note / because / cause…",
    "flagged": { "flagged": 82, "fileLines": 240, "lines": [{ "n": 78, "text": "…" }] }
  }]
}
```
`/api/settlements` can back the per-marker half (verdict text, flags) but **cannot serve this route**: the state column requires the claims-directory rewrite (1619-1634), the order requires `markers.txt`, and severity requires `severities.tsv`. Send facts, not sentences: `beganAt` not "3h 12m", raw `verdictText` (`firstSentence()` is a client derivation), `flagged.lines` as an array with the flagged index rather than a blob with `">> "` glued on.

### 5.2 `GET /api/marker?k=` — MarkerSummaryScreen — **NEW** (part from `/api/settlements`)
```jsonc
{
  "openFindings": 3,
  "key": "…", "repo": "…", "file": "…", "line": "91", "checker": "DEREF_OF_NULL",
  "claimNote": "…first paragraph of /checkers/<name>.txt, or null…",
  "state": "false-positive",                  // or null while nothing has settled
  "settlement": {                             // straight off settlements.jsonl; null until it settles
    "verdictKind": "…", "verdictText": "…",
    "redVerified": true, "greenVerified": false,   // real booleans; null = never recorded
    "testPath": "…", "testCode": "…",              // REPLACES the write_file scan (1890-1898)
    "fixDiff": "--- a/…\n+++ b/…",                 // REPLACES the fix-critic prompt scrape (1911-1930)
    "infraReason": "…"
  },
  "summary": { "headline": "…", "account": "…" },  // split named, not indexed
  "flagged": { "flagged": 91, "fileLines": 87, "lines": [{ "n": 87, "text": "…" }] },
  "builds": [{ "phase": "red", "passed": false, "infra": false, "at": 1755… }],
  "runs": { "reproducer": 2, "proof-critic": 1 }
}
```
`/api/settlements` serves `settlement` verbatim once the fields are typed; everything else is new.

### 5.3 `GET /api/marker/agent?k=&a=` — MarkerAgentScreen — **NEW** (derivable from `/api/trace`)
```jsonc
{
  "openFindings": 3, "key": "…", "agent": "fixer",
  "runs": { "…": 1 },
  "answers":  [{ "id": "…stable…", "at": 1755…, "prompt": "…", "reply": "…" }],  // oldest first
  "thoughts": [{ "id": "…", "at": 1755…, "text": "…" }],
  "calls":    [{ "id": "…", "at": 1755…, "tool": "write_file", "arguments": "…", "result": "…" }]
}
```
Server-side, match the agent with **one** rule (`equals`) for all three lists — today calls use `endsWith` (1948).

### 5.4 `GET /api/marker/prompts?k=` — MarkerPromptsScreen — **NEW, and no pass covered this screen**
`promptsFor()` 1467-1522 renders: header, ChainStrip, an alarm listing agents whose prompts changed since this marker was proved with a **POST `/reprove` button** (1492-1504, route 431-440), then one `SettingRow` per agent with `what it was told here` and, when changed, `what it would be told now`.
```jsonc
{
  "openFindings": 3, "key": "…",
  "agents": [{ "agent": "reproducer", "usedHere": "…", "nowIs": "…", "changed": true }],
  "stale": ["reproducer", "fixer"]
}
```
```ts
type PromptDriftAlarmProps = { stale: AgentName[]; markerKey: MarkerKey; onReprove: (why: string) => void };
type PromptUsedRowProps    = { agent: AgentName; usedHere: string; nowIs: string; changed: boolean };
```
- The prompt an agent ran under is **recovered, not recorded**: `asked` carries the whole prompt and the task is appended after a `\n\n---\n\n` separator, so everything before it is the instruction (1473-1475). Same-ness is `Prompts.same()` normalisation (1480) — **not** the blank-check `/settings` uses, which is why the two pages can disagree about whether an agent is "edited".
- `/reprove` is the only destructive control in the whole UI and is *not* counted against the supervisor's allowance of two (`by="person"`, 421-430).

### 5.5 `GET /api/trace?from=N` — TraceScreen — **replaces the use of `/api/trace`**
```jsonc
{
  "openFindings": 3,
  "cursor": 1041,                 // today: <script>document.body.dataset.events=N</script> (2386-2388)
  "events": [ /* TraceEvent, ALREADY SORTED ASCENDING BY at, each with a stable id */ ]
}
```
The existing `/api/trace` supplies the raw lines and nothing else: it is unsorted, untyped, id-less and cursor-less. Live tail keeps `/events` (526-558) exactly as it is: when `n.trace > cursor`, fetch `?from=cursor` and **append**. Do not refetch the whole array — the reason the Java does fragments (2135-2143) is that replacing the body closes every fold the reader opened.

`/marker?a=trace` is the same document filtered: `GET /api/trace?k=<key>&from=N`, plus `state` for the subtitle. **On `/trace` there must be no subtitle state at all** — Java passes an empty key, so the lookup at 2129-2133 compares `suspicion_key` against `""` and any settlement row missing that field matches, landing an unrelated marker's pill in the page title.

### 5.6 `GET /api/overwatch?a=` — SupervisorScreen — **NEW**
```jsonc
{
  "view": "findings" | "overwatch" | "overwatch-critic" | "trace",
  "openFindings": 7,
  "tally": { "holds": 3, "refuted": 1, "unjudged": 3, "restarts": 2 },
  "markers": { "<slug>": "repo|file|line|checker" },
  "findings": [{ "index": 0, "at": 1755…, "verdict": "holds", "finding": "…", "judgement": "" }],
  "restarts": [{ "at": 1755…, "id": "<slug>", "marker": "…", "attempt": 2,
                 "killed": true, "by": "person", "why": "…" }],
  "events": [ /* TraceEvent, sorted at DESC, marker → real key or null */ ]
}
```
Two things this route already has in hand and throws away: **`overwatch-settlements.jsonl` is passed in (line 408) and read by no branch** — serve it or stop opening the file; and per-agent event counts, which nothing computes, so the two agent tabs carry no numbers even though `AgentChip` is exactly their shape.

### 5.7 `GET /api/chat`, `GET /api/chat/live`, `POST /api/chat` — ChatScreen — **NEW**
```jsonc
// GET /api/chat
{ "openFindings": 3, "answering": true, "unanswered": false, "readable": true,
  "turns": [{ "at": 1755…, "who": "you", "text": "…" }] }

// GET /api/chat/live  — polled ONLY while answering
{ "who": "supervisor", "agent": "chat", "at": 1755…, "text": "…tail…", "truncated": true }

// POST /api/chat {"q":"…"} → 200 {"said": ""} | {"said":"still answering the last one"}
//                              | {"said":"could not write the question down: …"}
```
The 303 (506-513) exists only so a meta refresh cannot re-post; over JSON that constraint disappears, but **the invariant behind it does not: one question in flight, and the second is refused rather than queued.**

### 5.8 `GET /api/live?k=` and `GET /api/marker/live?k=` — MarkerLiveScreen — **NEW**
```jsonc
// /api/live — the 2s poll, the only thing that changes
{ "marker": "…", "slug": "…", "settled": false,
  "panel": { "who": "<slug>", "agent": "reproducer", "at": 1755…, "text": "…tail…", "truncated": true } }
// panel is null when settled or when k is empty — and "k was empty" must be an error, not a 200 with ""

// /api/marker/live — the shell, fetched once
{ "openFindings": 3, "key": "…", "title": "B.java|82|CHECKER",
  "runs": { "reproducer": 2 }, "live": { …the above… } }
```
`panels: [Panel]` is the latent plural: `proving()` (855-883) already builds one panel per unsettled claim and emits "No prove is running." when there are none — **and nothing calls it.** If the pool-wide view is wanted back, this endpoint grows `panels`; the components already work per panel.

### 5.9 `GET/POST /api/settings/*` — the four settings screens — **NEW**
```jsonc
// GET /api/settings/prompts
{ "openFindings": 3, "overriddenCount": 0,
  "agents": [{ "agent": "reproducer", "group": "chain",   // chain | watch | asked — from Agents, not a sentence
               "builtIn": "…", "saved": "", "differs": false }] }   // order = Agents.ORDER, then unlisted, sorted
// POST /api/settings/prompts/{agent} {"prompt":"…"} ; DELETE → revert

// GET /api/settings/run  { "workers": 4, "least": 1, "most": 16, "default": 4 }
// PUT /api/settings/run  {"workers": 6}   → server clamps; render the RESPONSE

// GET /api/settings/model
{ "edited": true,                       // file existence, NOT diff-from-default
  "values": { "model":"…","base_url":"…","temperature":"0","max_tokens":"0",
              "patience_minutes":"4","ceiling_minutes":"240" },   // CLAMPED, not as-typed
  "key": { "set": true, "from": "this page" } }                   // + "value" only if reveal/copy survives
// POST /api/settings/model { values, api_key?, forget_key?, revert? }
//   api_key absent or blank ⇒ LEAVE IT ALONE (Tuning 138-141); revert ignores everything else

// GET /api/settings/subject
{ "markers": { "queued": 356, "repos": ["https://github.com/owner/repo.git"] },
  "credential": { "host": "github.com", "token": "ghp_…" },
  "jdk": { "chosen": "25", "available": ["25","21","17","11","8"], "default": "25" },
  "zip": { "present": true },
  "outcome": null }   // or { "refused": true, "complaints": ["line 12: 3 field(s), not 4 — …"] }
// POST stays multipart (files), `setting` ∈ markers|token|jdk|zip as the discriminator.
// FORGET BECOMES ITS OWN REQUEST — today it is inferred from which button submitted (1122, 1253, 1290).
```
Today one POST to `/settings` answers six different forms and picks by a hidden `setting` field (447-499); the subject POST is answered **in place** with the whole page (452) rather than redirected, so its response shape is GET + `outcome`.

### 5.10 Shared and unchanged
- `GET /api/markers.json` → `{ "<slug>": "<markerKey>" }` from `slugs()` (694-707). Chat and supervisor both need it; it changes only when the queue changes, so cache it client-side rather than inlining a 356-entry map into a 3-second poll.
- `POST /api/feedback` `{ eventId | findingIndex, note, back }` → 200. **New**; the existing `/feedback` is a 303 (376-388) and the existing `/api/feedback` is the GET corpus, which stays as the training export.
- `GET /events` (526-558) stays exactly as is: `{"trace":N,"settled":M}` every 2s when either count moves.

---

## 6. Bugs and dead code the port must decide about, in one list

| # | What | Where | Decision |
|---|---|---|---|
| 1 | `css()` sends both `verified/pr-*` states to the `infra` red; the green rules at CSS 54 are unreachable | 2682-2684, CSS 54 | Map state→tone in TS. **This changes what the page looks like.** |
| 2 | `settingsTabs` lights *prompts* with `!current.equals("run")` — two tabs lit on model and subject | 1077 | Each tab tests its own key |
| 3 | `rate()` posts a positional index; three views number the same answer differently | 1996, 2140, 2164 | Stable `eventId` in the payload |
| 4 | `hidden()` writes `value='…'` and `esc()` never escapes `'` — every prompt with an apostrophe is truncated in the corpus | 2272-2274, 2765-2767 | Fixed by React; **existing feedback rows are suspect** |
| 5 | The API key field and forget checkbox sit **outside** the `<form>`; saving a key does nothing | 1340-1364 | Move inside; the feature starts working |
| 6 | `/trace`'s subtitle matches any settlement row lacking `suspicion_key` | 2129-2133 | No state in the subtitle on the whole-trace view |
| 7 | `RestartLog` drops `by`, so a reader cannot tell who cut the tree | 723-733 | One row per restart, `by` shown |
| 8 | `flags()` never rendered on `/marker`, where red/green matter most | 1732 sole call site | Add `Semaphore` to the marker summary |
| 9 | `TestArtifact` scans `write_file` args; `FixDiff` scrapes fix-critic's **prompt text** | 1890-1898, 1911-1930 | Serve `test_code` / `fix_diff` from settlements.jsonl |
| 10 | Fold state keyed by DOM position — reverting a prompt or settling a marker opens the wrong fold | KEEP_OPEN 291-301, 1449 | Key by marker key / event id / `code:<agent>` |
| 11 | Two `ago` formatters disagree at 100s; two human-minute formats disagree on one screen | 1023-1029 vs 921-926; 1674 vs 2344 | Pick one of each, deliberately |
| 12 | Two copies of `slug()`; two copies of the chain order | 571-575 vs Supervisor 297-302; STAGES 2039 vs Agents.CHAIN 410 | One each |
| 13 | `.ev:target` outlines a successful save in the failure red | CSS 86, 493 | New token |
| 14 | Dead: `tab()` (no callers), `proving()` (no callers), `SHOWN` (unused), the `not-a-bug` guard in `flags()`, `overwatch-settlements.jsonl` (opened, never read) | 2106, 855, 818, 2320, 408 | Delete or wire up — say which |
| 15 | Forget/remove is inferred from which button submitted the form | 1122, 1253, 1290 | Separate request |
| 16 | `Chat.turns()` returns `[]` for both "nothing asked" and "file would not parse" | Chat.java 88-92 | Add `readable` |
| 17 | `/live?k=` empty returns 200 with an empty body, indistinguishable from an error | 837-852, 500-502 | Error status |
| 18 | `Workers.of()` cannot distinguish default from unreadable | Workers 36-48 | Report whether a readable file existed |
| 19 | Syntax colouring never reaches `/trace`, where the test and the patch actually pass through | `code`/`diff` callers 1739, 1845, 1934 only | Using `CodeBlock`/`DiffBlock` in `ToolCallEvent` is **new behaviour** — worth it, but say so |
| 20 | `marker()`'s agent match: `equals` for answers/thoughts, `endsWith` for tool calls | 1948 vs 1966, 2031 | One rule, server-side |