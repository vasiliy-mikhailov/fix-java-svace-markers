# The engine's differential harnesses

**What the fixtures are.** `harness/fixtures` is a RECORDING. Eleven decision bodies and their shared
helpers existed in a different implementation before this module; cases were generated against it, its
answer to every one was captured type-tagged, and those answers were frozen here as data. Each family
was then proven the same way: run this implementation over the same cases, diff the answers, and write
down every difference. Three families, three corpora, **6 910 cases**.

**The comparisons run on every `mvn test`.** They are the three tests in
`src/test/java/tech/mikhailov/fsm/harness`. A divergence that was not there yesterday is a red test.

```
mvn -pl engine test -Dtest='NodeFamilyHarnessTest,InputFamilyHarnessTest,JsonFamilyHarnessTest'
sh harness/run.sh        # …and unpack + print all three long reports
```

## The three families

| test | classes it pins | cases | identical | divergent | classes |
| --- | --- | ---: | ---: | ---: | ---: |
| `NodeFamilyHarnessTest` | `Verdict`, `FixSkeptic`, `PrMaker` | 3 357 | 2 013 | **1 344** ‡ | 78 |
| `InputFamilyHarnessTest` | `PrepProver`, `BuildReproduceInput`, `BuildFixInput` | 2 199 | 1 808 | **391** † | 26 |
| `JsonFamilyHarnessTest` | `JsonExtract`, `ParseTest`, `ParseFix` (via `TestRealness`) | 1 354 | 1 272 | **82** | 11 |

† 377 of those 391 are ONE deliberate re-baseline — the GitHub User-Agent, renamed on 2026-08-02. The
14 that were measured against the reference are still the 14. See [Re-baselines](#re-baselines--every-deliberate-move-of-a-catalogue-dated).

‡ 973 of those 1 344 are ONE deliberate re-baseline — the verdict sampling temperature, dropped from
0.2 to 0 on 2026-08-05. The 371 that were there before are still the 371, unchanged and in the same
classes. See [Re-baselines](#re-baselines--every-deliberate-move-of-a-catalogue-dated).

Each asserts a CATALOGUE — `harness/fixtures/<family>-expected.json` — rather than a single total.
"371 became 370" says only that something moved; a catalogue names the class that moved, so the review
question is "did I mean to change THAT?".

Nothing in these fixtures is machine-dependent: the cases are plain JSON and no family touches the
filesystem, the clock or the network. The one normalisation the tagged comparison makes — the NAME of
the exception a body threw, because a type error and a `NullPointerException` are the same EVENT — is
documented in `TaggedDiff.java` and is the only thing forgiven anywhere.

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
