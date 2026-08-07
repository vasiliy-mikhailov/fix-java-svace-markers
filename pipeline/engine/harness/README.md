# The engine's harnesses over three frozen corpora

**They were DIFFERENTIAL harnesses until 2026-08-05. They are a GOLDEN MASTER now, and the difference
is the first thing to understand about these files.**

**What the fixtures are.** `harness/fixtures` is a RECORDING. Eleven decision bodies and their shared
helpers existed in a different implementation before this module; cases were generated against it, its
answer to every one was captured type-tagged, and those answers were frozen here as data. Each family
was then proven the same way: run this implementation over the same cases, diff the answers, and write
down every difference. Three families, three corpora, **6 910 cases**.

**What they can no longer do.** That reference was a JAVASCRIPT implementation, and this module used
to reproduce its value semantics DELIBERATELY — `String(x)`, `x || ''`, truthiness, `Number(x)` — so
that "the answers match" meant "the port is faithful". On 2026-08-05 that emulation was deleted, on
purpose, because two of the behaviours it preserved were defects; the divergences below are now
dominated by differences somebody CHOSE. So:

- **These files no longer prove fidelity to anything.** They pin THIS module's intended behaviour,
  case by case, against answers a deleted program once gave. A red test means "something in these
  classes changed" and nothing more.
- **The reference is gone permanently, and this is not a "with effort" claim.** It exists only in
  `git log`. Restoring it would give you unmaintained code whose agreement with this module would
  prove nothing about this module. There is nothing to regenerate these fixtures FROM.
- **A golden master is a WEAKER instrument than a differential harness**, and it is worth being blunt
  about which guarantee was traded away: a differential harness can tell you that a difference is
  wrong. This can only tell you that a difference is NEW. Whether it is wrong is now a question for a
  person, and the only material they have is what previous people wrote down in this file.

Read [What was lost on 2026-08-05](#what-was-lost-on-2026-08-05-stated-so-nobody-has-to-work-it-out)
before quoting a divergence count at anybody.

**The comparisons run on every `mvn test`.** They are the three tests in
`src/test/java/tech/mikhailov/fsm/harness`. A divergence that was not there yesterday is a red test.

```
mvn -pl engine test -Dtest='NodeFamilyHarnessTest,InputFamilyHarnessTest,JsonFamilyHarnessTest'
sh harness/run.sh        # …and unpack + print all three long reports
```

## The three families

| test | classes it pins | cases | identical | divergent | classes |
| --- | --- | ---: | ---: | ---: | ---: |
| `NodeFamilyHarnessTest` | `Verdict`, `FixSkeptic`, `PrMaker` | 3 357 | 1 991 | **1 366** ‡ | 138 |
| `InputFamilyHarnessTest` | `PrepProver`, `BuildReproduceInput`, `BuildFixInput` | 2 199 | 1 402 | **797** † | 464 |
| `JsonFamilyHarnessTest` | `JsonExtract`, `ParseTest`, `ParseFix` (via `TestRealness`) | 1 354 | 1 226 | **128** † | 20 |

† ‡ **READ THE 2026-08-05 ENTRY ON THE JS REMOVAL BEFORE READING THESE NUMBERS.** The reference was a
JavaScript implementation and this module used to reproduce its VALUE SEMANTICS on purpose; that
emulation was deleted, so all three catalogues moved at once and the divergence counts above are now
dominated by differences somebody chose. Per family: json-family 82 → 128, input-family 391 → 797,
node-family 1 344 → 1 366.

† 316 of input-family's 797 are still the GitHub User-Agent re-baseline of 2026-08-02. It was 378
until the JS removal re-signed 62 of them into a single earlier class: those 62 are the cases whose
`branch` is a present `0` or `false`, and this code no longer makes the branch-lookup REQUEST at all,
so they differ at `calls[0]` before they can differ at a header. It is a control-flow change and not a
spelling; it is itemised in the 2026-08-05 entry.

‡ 1 076 of node-family's 1 366 are still the verdict sampling temperature, dropped from 0.2 to 0 on
2026-08-05, and the 371 measured against the reference are still there. See
[Re-baselines](#re-baselines--every-deliberate-move-of-a-catalogue-dated) for all of it.

Each asserts a CATALOGUE — `harness/fixtures/<family>-expected.json` — rather than a single total.
"371 became 370" says only that something moved; a catalogue names the class that moved, so the review
question is "did I mean to change THAT?".

Nothing in these fixtures is machine-dependent: the cases are plain JSON and no family touches the
filesystem, the clock or the network. The one normalisation the tagged comparison makes — the NAME of
the exception a body threw, because a type error and a `NullPointerException` are the same EVENT — is
documented in `TaggedDiff.java` and is the only thing forgiven anywhere.

---

## What was lost on 2026-08-05, stated so nobody has to work it out

The sections below were written while the frozen answers still meant "the port is faithful". They are
left as they were written, because they are the record of what was measured. This section says what
stopped being true.

**1. The 82 json-family and 14 input-family divergences were adjudicated AGAINST A PROGRAM THAT COULD
STILL BE RUN. That is what is gone.** Adjudicating one meant: read the case, form a theory about why
the two answers differ, and then — for the json family until 2026-07-31, and for the input family
against a report reproduced exactly — CHECK the theory by asking the reference what it did with a
neighbouring input. The conclusions those reviews reached are still in this file. **The method that
reached them is not available for the next 96 divergences, or for any divergence found from now on.**
Nobody can settle a question about these classes by running anything; they can only read.

**2. The counts stopped isolating the adjudicated cases.** All 25 of the input family's originally
measured classes are still in the catalogue, but only 15 are byte-identical. 3 print a renamed string
inside a whole-object value, so they appear under a new signature; 8 have GAINED cases from the new
coercions, so "1 case at `out (keys)`" is now "2 cases at `out (keys)`, one of which somebody
adjudicated in July and one of which nobody has ever looked at". The json family is the same story
smaller: 9 of 11 byte-identical, one re-signed, one lost 6 cases to field classes.

**3. "Identical" still means what it says, and it stopped being the goal.** 4 619 of the 6 910 cases
answer exactly as the reference did, and that is a real fact about them. What changed is the
direction: for every coercion class in the tables below, AGREEMENT WITH THE REFERENCE WOULD NOW BE A
REGRESSION. So "4 619 identical" can no longer be quoted as 4 619 cases' worth of evidence that the
port is right — it is 4 619 cases where nobody has yet had a reason to differ.

**4. What was NOT lost.** The corpora are untouched: 3 357 + 2 199 + 1 354 = 6 910 cases, the same
cases, and no case was added, dropped or edited. Every divergence class carries a path and a value
pair, so a change still names itself. `extract` is 955/955 identical and every one of those 955 still
means what it meant. And every class in all three catalogues was attributed to a named decision before
this re-record, with the residue read by hand — which is the last time that particular check could be
made cheaply, and is why the tables above are as long as they are.

---

## THE COST OF FREEZING, per family, stated plainly

The three families are **not** equally well served by the freeze, and pretending otherwise would be
the dishonest part. Read them separately.

### `InputFamilyHarnessTest` — the cheap one

The reference for this family had **already been deleted** when these fixtures were committed; its
answers had been sitting in a gitignored `harness/out` since **2026-07-29**, one `rm -rf` from being
gone. Freezing them cost nothing that was not already spent, and it could be shown they were still the
right reference: this implementation reproduced the 2026-07-29 report exactly — 2 199 / 2 185 / 14 in 25
classes. **Net gain, no new loss.**

That exact reproduction held until **2026-08-02**, when the GitHub User-Agent was deliberately renamed
and 377 previously-identical cases became divergent ON THAT ONE FIELD. The 14 measured divergences are
untouched and still adjudicated; the 26th class is a decision, not a discovery. See
[Re-baselines](#re-baselines--every-deliberate-move-of-a-catalogue-dated).

### `JsonFamilyHarnessTest` — the expensive one, and the one worth being careful about

Its reference was **alive until 2026-07-31**. It was run one last time that day, on Node v22.22.2, its
1 354 answers were committed, and then it was deleted. So this family paid the full price:

- The corpus is systematic and, for truncation, EXHAUSTIVE — every prefix length of four
  representative replies, because the repair path is the subtlest code in this module. That is a lot of
  coverage and it is now fixed forever at 1 354 cases.
- **A divergence in a case nobody generated can no longer be found.** If someone asks next month what
  `extractJson` did with a reply that interleaves two fences, the answer is no longer "let's run it".
  It is "nobody asked in July 2026".

### `NodeFamilyHarnessTest` — READ THIS BEFORE TRUSTING THE NUMBER

1 344 divergences, of which **973 are the 2026-08-05 temperature re-baseline** and are a decision
rather than a discovery — one field of the outbound request body, adjudicated in
[Re-baselines](#re-baselines--every-deliberate-move-of-a-catalogue-dated) below.

The other 371 are the original measurement, and **that number has never been adjudicated.** The other
two families were frozen with a catalogue somebody had gone through cause by cause. This one was not:
its reference was deleted before the harness was frozen, its last run was never written down anywhere in
the repository, and this implementation has since gained behaviour the reference never had. Most of the
371 are that:

- **171 of them are the entire skeptic family** — every case — and they are one field:
  `skeptic_answered`, added after the reference was retired so that "the skeptic said no" could be told
  apart from "the skeptic never answered". The reference had no such key. Nothing about the verdict
  differs.
- **A large block is the verdict body's FAILURE MESSAGES**, rewritten here to say what failed
  (`"the verdict call FAILED: the reply is not an object"`) where the reference said only that no text
  came back. Same routing, different words.
- **85 are `out.state`: an absent value against `null`** — the encoder was deliberately not taught to
  invent a distinction Java does not have.

So this test enforces a CHARACTERIZATION: this implementation's behaviour, pinned class by class against
a fixed reference. A change to verdict routing moves the catalogue and goes red, and that is real value.
But a reviewer reading that diff cannot ask the reference who was right — and for this family, unlike
the other two, **nobody ever did.** Read a red here as "something in these three classes changed", not
as "this module broke".

### Common to all three

**The fixtures cannot be regenerated.** Not "with effort" — at all. Regenerating them means having the
reference implementation, and it exists only in `git log`. Restoring it would give you code that has not
been deployed or maintained since, and whose agreement with the current code would prove nothing about
the current code. **If somebody proposes "just regenerate the fixtures", the honest answer is that there
is nothing to regenerate them FROM.** Treat these files as an archive, not a cache.

**The corpora are therefore permanently fixed.** New behaviour in these classes gets ordinary unit
tests — `VerdictTest`, `ParseTestTest`, `PrepProverTest` and the rest, which is where the module's
mutation score comes from anyway. It does NOT get new differential cases, and adding one with a
hand-written "reference answer" would be worse than useless: it would put a guess into a file whose
whole authority is that every value in it was MEASURED.

---

## Changing a catalogue

```
mvn -pl engine test -Dtest=JsonFamilyHarnessTest -Dharness.record=true
git diff harness/fixtures/json-family-expected.json
```

Every line of that diff is a behaviour that was measured against a program that no longer exists.
Re-recording without reading it is how a differential harness becomes a rubber stamp.

**So every re-record gets an entry below, and a re-record without one is indistinguishable from
somebody quietly making a failing test pass.** That is the whole reason the section exists: the diff
itself cannot tell a reviewer whether a value moved because this code broke or because a human decided
it should. Only a person can say that, and this is where they say it.

---

## Re-baselines — every deliberate move of a catalogue, dated

### 2026-08-06 — the fixer's location hint: read the anchor off the item that has one

**WHAT MOVED.** `input-family`, three cases, all at `out.agent_input`, all in `build fix input`:
identical 1402 → 1399, divergent 797 → 800, divergence classes 464 → 467, per-node
`build fix input` [162,101] → [162,98]. `node-family`, `json-family` and the runner catalogue did not
move at all — a prediction that this would touch node-family was wrong and is recorded here so the
next reader does not inherit it.

**WHY.** `BuildFixInput` read `Json.str(j, "anchor")` where `j` is the PREP item. `anchor` is not one
of `PrepProver.Outcome`'s 23 emitted keys — it is re-derived by `BuildReproduceInput`, one stage
later. So in production the read always yielded `""` and **every fixer prompt the pipeline has ever
sent has been missing its `(in method())` hint**, which the code's own comment calls "the more
trustworthy half of the location" because the line number has usually drifted. `src` four lines above
already came from the right item.

**WHY NOTHING CAUGHT IT.** `BuildFixInputTest` hand-built `marker("anchor", "login")` — the anchor on
the prep item, a request shape `ProveChain` cannot produce. The test agreed with the code and both
disagreed with the wiring. There was no test driving one stage's real output into the next, so the
seam nobody asserted is exactly where the defect lived. `TheFixerIsToldWhereTheMarkerIsTest` now
drives `BuildReproduceInput`'s actual output into `BuildFixInput` and fails if the hint is dropped;
the unit test asserts the anchor on the prep item changes NOTHING, which is the defect stated as a
rule.

**WHAT THE MOVED CASES SHOW, INCLUDING THE UNCOMFORTABLE PART.** All three are hostile-typed corpus
inputs, not production shapes: the corpus crafts an `anchor` that is a number or a boolean, so the
reference and this build disagree about which item carried one. Two consequences worth recording
rather than smoothing over:

- The reference emitted `(in x())` and `(in 42())` for cases where this build now emits no hint. That
  is the corpus feeding an anchor onto the prep item, which production never does — so the divergence
  is a fact about the fixtures and not about the pipeline.
- With the read corrected, a NON-STRING anchor renders as `(in false())` or `(in 42())` — a fake
  method name, which is precisely what the guard's comment warns against for `undefined`. Production
  cannot reach it (`BuildReproduceInput` writes `em.name()` or `""`, always a String), so this is not
  fixed here. If the anchor ever becomes attacker- or model-influenced, the emptiness check is the
  wrong guard and it should test for a plausible identifier instead.

**HOW IT WAS CHECKED.** The defect was reproduced against the compiled stages before anything changed,
using `ProveChain`'s real wiring; the fix was measured against all four catalogues before any fixture
was written; and both new tests were shown to fail with the original read restored, with the source
file checksummed back afterwards.

### 2026-08-05 — the JavaScript emulation was DELETED, and all three catalogues moved

**WHAT MOVED.** All three: `json-family`, `input-family` and `node-family`. This is the largest
re-baseline in this file by two orders of magnitude and the only one where "review the diff line by
line" is not a thing a person can honestly claim to have done — so what was reviewed is stated
exactly, below, and what was not is stated too.

| family | identical | divergent | classes |
| --- | --- | --- | --- |
| json-family | 1 272 → 1 226 | 82 → **128** | 11 → **20** |
| input-family | 1 808 → 1 402 | 391 → **797** | 26 → **464** |
| node-family | 2 013 → 1 991 | 1 344 → **1 366** | 78 → **138** |

**WHAT CHANGED IN THE CODE.** `lib/Js.java` and `lib/JsValue.java` are gone. They reproduced
JavaScript's value semantics — `String(x)`, `x || ''`, truthiness, `Number(x)`,
`Number::toString` — because this module is a port of an n8n JavaScript implementation and these
catalogues froze the match. `lib/Values.java` and `lib/SourceText.java` replace them, under one rule:
**absent is the only thing that reads as empty; a value that is present reads as itself.** Two of the
old behaviours were DEFECTS the freeze was protecting, and they were fixed rather than preserved:

- `Llm.concat` wrote the literal word `undefined` INTO PROMPTS SENT TO THE MODEL. It is now
  `Llm.orMissing`, which names the field: `(repository not recorded)`. Empty was considered and
  rejected by reading the templates — *"contributions to %s."* becomes *"contributions to ."*, a
  broken sentence a model fills in for itself.
- `Json.str`'s `x || ''` swallowed a legitimate `0` and `false`, so a marker on line 0 and a
  `red_reproduced: false` were indistinguishable from a field nobody set.

Three behaviours were KEPT and re-justified on Java grounds rather than JS fidelity: the whitespace
set (`String.isBlank` excludes U+00A0 and U+FEFF, and a BOM-only file must count as empty or a marker
is adjudicated against a file with no code in it), the lenient leading-integer parse (the Svace CSV's
`Line` column really contains `"7 (col 3)"` and `Integer.parseInt` would drop the row), and the
tolerant base64 decoder (`Base64.getMimeDecoder` throws on the truncated final group that this
pipeline's own `SRC_MAX` cut produces — a file that WAS received must never be reported as one that
could not be fetched).

**HOW IT WAS CHECKED, AND WHAT THAT DOES AND DOES NOT PROVE.** Every divergence class in all three
catalogues was attributed to one of the decisions above, mechanically, and the residue was read by
hand. Nothing is left unattributed. The counts below are class-instances — one case that diverges at
three paths appears in three classes — which is why they exceed the divergent-case counts.

*input-family, all 464 classes, 1 461 class-instances:*

| decision | classes | instances |
| --- | ---: | ---: |
| a present `0`/`false` is kept where `\|\|` discarded it | 194 | 550 |
| a container renders as JSON, not `[object Object]` / `1,2` | 96 | 310 |
| blank-or-absent falls back, where `\|\|` took only the falsy set | 72 | 152 |
| a boolean / array / `"0b101"` is not a line number | 43 | 47 |
| `undefined` and `null` are one absent value | 40 | 59 |
| the word `undefined` is no longer written | 11 | 11 |
| `.java` is stripped as a SUFFIX, not as the first occurrence | 3 | 9 |
| digits, never an exponent | 4 | 7 |
| (unchanged) the 2026-08-02 User-Agent re-baseline | 1 | 316 |
| **total** | **464** | **1 461** |

The split between the first three rows is a judgement about which decision to name when two apply to
one class — a `module` that is an array both serialises as JSON and stops being blank — so read the
rows as a partition of a whole that is fully accounted for, not as independent measurements. **The
column that carries the weight is the total: 464 of 464, with no residue.**

**TWO of those classes are not a spelling, and a reader who skims the table will miss them.**

- **62 cases: the GitHub branch lookup NO LONGER HAPPENS.** Class `calls[0]`, the reference's whole
  request against `(missing)`. Every one of the 62 is a `prep prover` case whose `suspicion.branch` is
  a present `0` or `false` (31 each, and no other case in the corpus has one). `branch || ''` made
  those falsy, so the reference asked GitHub for the default branch; `branch` is now a branch named
  `"0"`, so there is nothing to look up. That is the intended rule and it removes a network call,
  which is the right direction — but it is a control-flow change and it is why the User-Agent class
  dropped 378 → 316: those 62 cases now differ EARLIER than the header and are signed to this class
  instead.
- **70 cases: `out.branch_ok`, `false` → `true`** — the `{"default_branch": false}` reply, discussed
  under *WHY THIS IS NOT A REGRESSION* below.

*node-family:* 73 of the 78 pre-existing classes are byte-identical — same count, same `first:` case,
same values — covering 1 734 cases. The other 5 are `calls[i]` and `out` classes whose RENDERED VALUE
contains a string that was renamed, so they appear as "gone" and come back identical but for that
string; the same pattern the temperature re-baseline below documents. 65 classes are new, 241 cases:

| what moved | classes | cases |
| --- | ---: | ---: |
| the placeholder renaming, VISIBLE in the recorded value | 15 | 179 |
| the same renaming and the other decisions, visible only as a moved `…(length)` marker | 47 | 56 |
| a present `0` or `false` reaching `pr_body` / `pr_reason` | 3 | 6 |
| **total** | **65** | **241** |

**THE MIDDLE ROW IS A HOLE IN THE CATALOGUE AND IT WAS FILLED BY MEASURING, NOT BY ASSUMING.** The
renderer keeps 220 characters and then writes `…(length)`; these 47 classes are LLM prompts thousands
of characters long, so all the catalogue records of them is that the length moved. They were re-read
in full, out of the live answers, before the re-record: all 59 differing spans are one of the same
placeholder renamings (32 shapes), a container serialising as JSON (9), or a present `0`/`false`
surviving (5) — plus three that are worth naming because they change a whole section of a prompt
rather than a word:

- `SVACE DETAIL:` — 4 cases where an empty Svace trace made the reference emit
  `SVACE DETAIL: \nSVACE TRACE: []` and this code emits
  `SVACE DETAIL: unavailable (no Svace endpoint is configured for this deployment; argue from the code).`
  An empty array is now blank and takes the fallback, so the model is told the trace is unavailable
  instead of being shown `[]`.
- 2 cases where a `method_text` of `false` is kept, so the prompt carries
  `The method the marker points into:` with a body of `false`, where the reference fell back to
  `Source file:` and the whole file.

**Verdict ROUTING did not move**: no class at `out.state`, `out.suspicion_status` or
`out.verdict_kind` changed.

*json-family:* 9 of 11 classes byte-identical (43 cases). One re-signed — 12 cases moved from
`repro_root_cause` to `repro_value_verdict`, because the class is named after the FIRST differing
field and `value_verdict` now differs too (a numeric `value_verdict: 0` is kept instead of
collapsing). One lost 6 cases to field classes. 10 new classes, 64 cases, all of them a container
serialising or a `0`/`false` surviving. `extract` is 955/955 identical, untouched.

**ONE json-family class DECIDES something rather than spelling it.** 12 cases at `can_prove`,
`false` → `true`: a reply of `{"can_prove": true, "test_code": false}` collapsed to
`can_prove: false` under `||`, because the falsy `test_code` read as empty and the node will not
claim a proof with no test. The test code is now the string `"false"`, which is not empty, so
`can_prove` stays `true` and a "test" whose source is the word `false` goes downstream. A model has to
send a boolean where a test belongs to reach it. Recorded rather than repaired, on the same rule as
`branch_ok`: the fix is a type check on `test_code`, not a return to `||`. 6 more cases put a `jdk`
of `0` or `false` into the build request the same way.

**AND THE ENCODER WAS FIXED, WHICH IS NOT A BEHAVIOUR CHANGE BUT CHANGES THE NUMBERS.**
`InputFamilyDiff.tag` tagged JS `undefined` `'u'` and `null` `'z'`. The JS-removal rewrite made it
`if (v == null) return "u"` — which left `case null -> "z"` UNREACHABLE and quietly forgave a
distinction: every Java null was reported as matching the reference's `undefined`. That is a second
forgiveness, and this harness grants exactly one (the exception NAME, in `TaggedDiff`). The encoder
now tags every Java null `'z'`, the same as the node family's encoder has always done, and the two
slots where the REFERENCE left a variable unassigned (`out` when the body threw, `threw` when it did
not) say so at the call site instead. It is strictly more accurate and it costs nothing: 1 402
identical with the honest encoding against 1 393 with the fudge.

**WHY THIS IS NOT A REGRESSION, AND WHERE THE HONEST DOUBT IS.** Every divergence above is reachable
only from an input the deployment does not produce — a `default_branch` that is `false`, a `file` that
is an array, a `svace_line` of `"0b101"` — or is one of the two named defect fixes. The live path is
well-typed and its answers did not move.

Two classes are worth a future reader's attention rather than a shrug, and neither is a JS-fidelity
question:

- **`out.branch_ok`, 70 cases: `false` → `true`.** A GitHub reply of `{"default_branch": false}` is
  now accepted as the branch `"false"`, where the reference flagged `no default_branch returned`. The
  rule that produced it is the right one; the flagging was the better OUTCOME. GitHub cannot send
  this, so it is recorded here rather than fixed, and if it ever needs fixing the repair is a type
  check on the reply, not a return to `||`.
- **`readFile`-style key changes have a live analogue in the runner** — see
  `runner/harness/README.md`, same date. The engine's own identity, `dedup_key`, was checked against
  the deployed backlog and does NOT move: all 282 live keys are byte-identical, and that is now
  pinned by `ParseMarkersTest.TheDedupKeyIsAMarkersIdentityAndMayNotBeReSpelled` rather than by
  somebody's inspection.

**AND THE POINT A FUTURE READER NEEDS.** These catalogues are no longer, in the main, a measurement of
"did the port stay faithful". That question was retired with the reference. What they are now is a
CHARACTERIZATION of three families of decision bodies, fixed at 6 910 cases, with every difference
from the last program that did the job written down and attributed. A red test here still means
exactly what it meant: something in these classes changed. It no longer means somebody broke the port.

### 2026-08-05 — the verdict sampling temperature: `0.2` → `0`

**WHAT MOVED.** `harness/fixtures/node-family-expected.json`, and nothing else. One new divergence
class, 1 076 instances:

```
[1] 1076 case(s) at calls[i].body.temperature
    first: verdict: route state=not_reproduced attempts=2 infra=
    JS   : n:0.2
    JAVA : n:0
```

plus the truncated rendering of two `calls[i]` classes — the 3 cases where the reference made no call
at all, so the comparison stops at the whole call object and never descends to the field. Their
rendered java value carries the temperature inside it, so the trailing length marker moved and nothing
else did: `…(2373)` → `…(2371)` on the 2-case class and `…(2424)` → `…(2422)` on the 1-case class. The
220-character prefix that precedes the marker is byte-identical in both.

Totals: identical 2 986 → 2 013, divergent 371 → 1 344, classes 77 → 78. Per node,
`verdict` 2 655/2 848 → 1 682/2 848 identical; **`skeptic` 0/171 and `prmaker` 331/338 unchanged.**

**HOW IT WAS CHECKED THAT NOTHING ELSE MOVED.** Not by reading the catalogue diff — by comparing the
answers underneath it. This build's raw node-family answers were dumped over the frozen corpus at 0.2
and again at 0, and the two dumps were walked against each other case by case and path by path with the
same traversal `TaggedDiff` uses. Over all 3 357 cases there is **exactly one distinct `(path, from,
to)` triple in the entire delta**: `calls[i].body.temperature`, `n:0.2` → `n:0`, in 1 079 cases, every
one of them a verdict case. Zero differences at any other path; zero skeptic cases and zero prmaker
cases changed at all, byte for byte — which is what makes them a control group and not just two rows
that happened to hold still.

The arithmetic closes exactly, in both directions:

- 1 079 answers changed; 1 076 of them show up as a temperature divergence against the reference. The
  missing 3 are the cases where the reference made **no call**, so the diff stops at `calls[0]` as a
  whole-object difference — the same 3 that moved the two length markers above. 1 076 + 3 = 1 079.
- 973 cases went from identical to divergent and **0 went the other way**. Every one of those 973 has
  the temperature as its *only* divergence from the reference. The remaining 1 076 − 973 = 103 were
  already divergent for an unrelated, still-catalogued reason and now carry this field as well.
- 2 986 − 973 = 2 013 identical, and 371 + 973 = 1 344 divergent. Both match the recorded catalogue.
- 75 of the 77 pre-existing classes are byte-identical — same count, same `first:` case, same values.
  The 2 that are not are the two length markers, and no class was removed.

The other two families were run unchanged and stayed green, so the edit did not reach them.

**WHY, AND WHY THIS IS NOT A REGRESSION.** `lib/Llm.java` already stated the rule — *0 for the skeptic
(a certification should not vary run to run) and 0.2 for the two that write prose* — and then filed
`Verdict` under "the two that write prose". Verdict does not write prose. It produces `kind`, which is
copied verbatim into the `verdict_kind` column and picks the marker's `SuspicionStatus`:
`false-positive` asserts we tested the claim and it does not hold, `by-design` concedes the claim and
calls the code deliberate, `unprovable` says we never managed to test it. Those are three different
findings about somebody else's source code.

Measured on 2026-08-04: 20 already-settled markers were re-proved through `POST /api/prove/marker`
against a container that was **never restarted** — byte-identical code, same image, same prompts, same
model — and 3 came back with a different verdict. One of the three was an `unprovable` that became
`infra_stuck`, an infrastructure failure rather than a disagreement, so the true verdict churn on
identical input is 2 in 20 — **a direction, not a rate**: at n=20 the interval is wide, and no test in
this repo can re-measure it because it needed a live container. A column that is read as a finding moved on
time on input that did not move.

**AND THE POINT A FUTURE READER NEEDS.** For this ONE field, *"matches the recording"* is no longer the
goal and must not be restored. The reference is retired — see *The cost of freezing* above, there is
nothing to regenerate these fixtures FROM — so the frozen `0.2` is a record of what a deleted program
sent, not a specification of what this one should send. The catalogue now pins the DECISION: if that
1 076 ever changes shape, someone has changed a sampling temperature, and they should be sent here.
Every other line of this file still means what it always meant, and the 371 divergences that were
measured against the reference are all still there, in their original classes, at their original counts.

Changed in `Verdict.java` (the adjudication call) and in the `Llm.chat` javadoc that misclassified it;
enforced by `ACertificationDoesNotVaryRunToRunTest`, which pins all three call sites by driving the real
node entry points and reading the temperature back out of the request each one built, and which fails if
a fourth call site appears without being classified. **One site is deliberately still 0.2** — `PrMaker`,
whose single call writes prose *and* returns a branched-on `decision`. That is a known defect the
constant alone cannot fix (the repair is splitting the call) and it is pinned at its current value so it
cannot drift silently; it is also why `prmaker` was available as a control group here.

### 2026-08-02 — the GitHub User-Agent: `n8n-fsm` → `svace-marker-fixer`

**WHAT MOVED.** `harness/fixtures/input-family-expected.json`, and nothing else. One new divergence
class, 378 instances:

```
[1] 378 case(s) at calls[0].headers.User-Agent
    JS   : s:n8n-fsm
    JAVA : s:svace-marker-fixer
```

plus the truncated rendering of class `calls[i]` (`prep prover#1494: null row` — the case where the
reference made no call at all and this code makes one), because the User-Agent is embedded in the
request object that class prints. Totals: identical 2 185 → 1 808, divergent 14 → 391, classes 25 → 26.

**HOW IT WAS CHECKED THAT NOTHING ELSE MOVED.** The rendered `target/harness/input-family-expected-report.txt`
was captured before and after and diffed case by case. All 25 pre-existing classes came through with
identical counts, identical `first:` case ids and identical values; the only additions are the two
above. The other two families were run unchanged and stayed green, so the edit did not reach them.
The arithmetic closes exactly: 378 cases in the frozen corpus carry a User-Agent, of which one
(`prep prover#1493: empty row`) was already divergent for an unrelated reason, leaving 377 that moved
out of `identical` — which is precisely the drop.

**WHY, AND WHY THIS IS NOT A REGRESSION.** That string is what GitHub actually sees on every branch
lookup and every source fetch. The frozen value named a workflow runner that has nothing to do with this
pipeline; a repository owner reading their access log learns nothing from it. `svace-marker-fixer` tells
them what is reading their source and why.

**AND THE POINT A FUTURE READER NEEDS.** For this ONE field, *"matches the recording"* is no longer the
goal and must not be restored. The reference is retired — see *The cost of freezing* above, there is
nothing to regenerate these fixtures FROM — so the frozen value is a record of what a deleted program
sent, not a specification of what this one should send. The catalogue now pins the DECISION: if that 378
ever changes shape, someone has changed the User-Agent again, and they should be sent here. Every other
line of this file still means what it always meant.

Changed in `PrepProver#lookupRequest` (the branch lookup) and `GithubSourceClient.USER_AGENT` (the
source fetch); asserted in `PrepProverTest`, `OutboundTest`, `ClientContractTest` and
`GithubRepoLookupTest`.
