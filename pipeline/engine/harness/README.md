# The engine's differential harnesses

Eleven n8n Code-node bodies and shared helpers were ported into this module. Each was proven against
the JavaScript it replaced the same way: generate cases, run both implementations, diff the answers
type-tagged, and write down every difference. Three families, three corpora, **6 910 cases**.

**The comparisons now run on every `mvn test`.** They are the three tests in
`src/test/java/tech/mikhailov/fsm/harness`. A divergence that was not there yesterday is a red test.

```
mvn -pl engine test -Dtest='NodeFamilyHarnessTest,InputFamilyHarnessTest,JsonFamilyHarnessTest'
sh harness/run.sh        # …and unpack + print all three long reports
```

## The three families

| test | ported classes | cases | identical | divergent | classes |
| --- | --- | ---: | ---: | ---: | ---: |
| `NodeFamilyHarnessTest` | `Verdict`, `FixSkeptic`, `PrMaker` | 3 357 | 2 986 | **371** | 77 |
| `InputFamilyHarnessTest` | `PrepProver`, `BuildReproduceInput`, `BuildFixInput` | 2 199 | 1 808 | **391** † | 26 |
| `JsonFamilyHarnessTest` | `JsonExtract`, `ParseTest`, `ParseFix` (via `TestRealness`) | 1 354 | 1 272 | **82** | 11 |

† 377 of those 391 are ONE deliberate re-baseline — the GitHub User-Agent, renamed on 2026-08-02. The
14 that were measured against the JavaScript are still the 14. See [Re-baselines](#re-baselines--every-deliberate-move-of-a-catalogue-dated).

Each asserts a CATALOGUE — `harness/fixtures/<family>-expected.json` — rather than a single total.
"371 became 370" says only that something moved; a catalogue names the class that moved, so the review
question is "did I mean to change THAT?".

Nothing in these fixtures is machine-dependent: the cases are plain JSON and no family touches the
filesystem, the clock or the network. The one normalisation the tagged comparison makes — the NAME of
the exception a node body threw, because a `TypeError` and a `NullPointerException` are the same
EVENT — is documented in `TaggedDiff.java` and is the only thing forgiven anywhere.

---

## THE COST OF FREEZING, per family, stated plainly

The three families are **not** equally well served by the freeze, and pretending otherwise would be
the dishonest part. Read them separately.

### `InputFamilyHarnessTest` — the cheap one

Its JavaScript (`prep-prover.js`, `build-reproduce-input.js`, `build-fix-input.js`) had **already been
deleted** when these fixtures were committed; the answers had been sitting in a gitignored
`harness/out` since **2026-07-29**, one `rm -rf` from being gone. Freezing them cost nothing that was
not already spent, and it could be shown they were still the right reference: the port reproduced the
2026-07-29 report exactly — 2 199 / 2 185 / 14 in 25 classes. **Net gain, no new loss.**

That exact reproduction held until **2026-08-02**, when the GitHub User-Agent was deliberately renamed
and 377 previously-identical cases became divergent ON THAT ONE FIELD. The 14 measured divergences are
untouched and still adjudicated; the 26th class is a decision, not a discovery. See
[Re-baselines](#re-baselines--every-deliberate-move-of-a-catalogue-dated).

### `JsonFamilyHarnessTest` — the expensive one, and the one worth being careful about

Its JavaScript (`json-extract.js`, `test-realness.js`, `parse-test.js`, `parse-fix.js`) was **alive
until 2026-07-31**. It was run one last time that day, on Node v22.22.2, its 1 354 answers were
committed, and then it was deleted. So this family paid the full price:

- The corpus is systematic and, for truncation, EXHAUSTIVE — every prefix length of four
  representative replies, because the repair path is the subtlest code in the port. That is a lot of
  coverage and it is now fixed forever at 1 354 cases.
- **A divergence in a case nobody generated can no longer be found.** If someone asks next month what
  `extractJson` did with a reply that interleaves two fences, the answer is no longer "let's run it".
  It is "nobody asked in July 2026".

### `NodeFamilyHarnessTest` — READ THIS BEFORE TRUSTING THE NUMBER

371 divergences, and **that number has never been adjudicated.** The other two families were frozen
with a catalogue somebody had gone through cause by cause. This one was not: its JavaScript
(`verdict.js`, `fix-skeptic.js`, `pr-maker.js`) was deleted before the harness was frozen, its last
run was never written down anywhere in the repository, and the Java has since gained behaviour the
JavaScript never had. Most of the 371 are that:

- **171 of them are the entire skeptic family** — every case — and they are one field:
  `skeptic_answered`, added after the JS was retired so that "the skeptic said no" could be told apart
  from "the skeptic never answered". The JavaScript had no such key. Nothing about the verdict differs.
- **A large block is the verdict node's FAILURE MESSAGES**, rewritten here to say what failed
  (`"the verdict call FAILED: the reply is not an object"`) where the JavaScript said only that no text
  came back. Same routing, different words.
- **85 are `out.state`: `undefined` against `null`** — Java has no `undefined`, and the encoder was
  deliberately not taught to pretend otherwise.

So this test enforces a CHARACTERIZATION: this port's behaviour, pinned class by class against a fixed
reference. A change to verdict routing moves the catalogue and goes red, and that is real value. But a
reviewer reading that diff cannot ask the JavaScript who was right — and for this family, unlike the
other two, **nobody ever did.** Read a red here as "something in these three nodes changed", not as
"the port broke".

### Common to all three

**The fixtures cannot be regenerated.** Not "with effort" — at all. Regenerating them means having the
JavaScript, and the JavaScript exists only in `git log`. Restoring it would give you code that has not
been deployed since the port landed, has not been maintained since, and whose agreement with the
current Java would prove nothing about the current Java. **If somebody proposes "just regenerate the
fixtures", the honest answer is that there is nothing to regenerate them FROM.** Treat these files as
an archive, not a cache.

**The corpora are therefore permanently fixed.** New behaviour in these classes gets ordinary unit
tests — `VerdictTest`, `ParseTestTest`, `PrepProverTest` and the rest, which is where the module's
mutation score comes from anyway. It does NOT get new differential cases, and adding one with a
hand-written "JS answer" would be worse than useless: it would put a guess into a file whose whole
authority is that every value in it was MEASURED.

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
itself cannot tell a reviewer whether a value moved because the port broke or because a human decided
it should. Only a person can say that, and this is where they say it.

---

## Re-baselines — every deliberate move of a catalogue, dated

### 2026-08-02 — the GitHub User-Agent: `n8n-fsm` → `svace-marker-fixer`

**WHAT MOVED.** `harness/fixtures/input-family-expected.json`, and nothing else. One new divergence
class, 378 instances:

```
[1] 378 case(s) at calls[0].headers.User-Agent
    JS   : s:n8n-fsm
    JAVA : s:svace-marker-fixer
```

plus the truncated rendering of class `calls[i]` (`prep prover#1494: null row` — the case where the JS
made no call at all and the port makes one), because the User-Agent is embedded in the request object
that class prints. Totals: identical 2 185 → 1 808, divergent 14 → 391, classes 25 → 26.

**HOW IT WAS CHECKED THAT NOTHING ELSE MOVED.** The rendered `target/harness/input-family-expected-report.txt`
was captured before and after and diffed case by case. All 25 pre-existing classes came through with
identical counts, identical `first:` case ids and identical values; the only additions are the two
above. The other two families were run unchanged and stayed green, so the edit did not reach them.
The arithmetic closes exactly: 378 cases in the frozen corpus carry a User-Agent, of which one
(`prep prover#1493: empty row`) was already divergent for an unrelated reason, leaving 377 that moved
out of `identical` — which is precisely the drop.

**WHY, AND WHY THIS IS NOT A REGRESSION.** `n8n-fsm` was the last live runtime value in this system
named after n8n; nothing has run n8n since July 2026 and the directories are now `pipeline/` and
`pipeline/deploy/`. That string is what GitHub actually sees on every branch lookup and every source
fetch. A repository owner reading their access log and finding `n8n-fsm` learns the name of a workflow
runner that is not running; `svace-marker-fixer` tells them what is reading their source and why.

**AND THE POINT A FUTURE READER NEEDS.** For this ONE field, *"matches the JavaScript"* is no longer
the goal and must not be restored. The JavaScript is retired — see *The cost of freezing* above, there
is nothing to regenerate these fixtures FROM — so the frozen `n8n-fsm` is a record of what a deleted
program sent, not a specification of what this one should send. The catalogue now pins the DECISION:
if that 378 ever changes shape, someone has changed the User-Agent again, and they should be sent
here. Every other line of this file still means what it always meant.

Changed in `PrepProver#lookupRequest` (the branch lookup) and `GithubSourceClient.USER_AGENT` (the
source fetch); asserted in `PrepProverTest`, `OutboundTest`, `ClientContractTest` and
`GithubRepoLookupTest`.
