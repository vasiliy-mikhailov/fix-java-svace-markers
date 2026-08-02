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
| `InputFamilyHarnessTest` | `PrepProver`, `BuildReproduceInput`, `BuildFixInput` | 2 199 | 2 185 | **14** | 25 |
| `JsonFamilyHarnessTest` | `JsonExtract`, `ParseTest`, `ParseFix` (via `TestRealness`) | 1 354 | 1 272 | **82** | 11 |

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
not already spent, and it can be shown they are still the right reference: the port reproduces the
2026-07-29 report exactly — 2 199 / 2 185 / 14 in 25 classes. **Net gain, no new loss.**

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
