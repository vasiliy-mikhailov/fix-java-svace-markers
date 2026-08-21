# Requesting two components for ratchet-ui

This is a request, not an instruction, and it is the mirror of `ADOPTING.md`. That document asks
twelve things of this repository and all twelve have been taken, in the prescribed order, one commit
per step. This asks two things back.

It is written after the adoption rather than during it on purpose: nothing here is a condition of
anything there, and if both are declined the twelve stay taken.

**Everything below was checked against both trees rather than described from memory.** The sibling
was read at `4c41505`; the library at the `v0.3.0` tag, `a729954`. Every count in this document is a
grep whose command is worth re-running.

---

## Why these two and not a list

`ADOPTING.md` names eleven components of this repository that are not being asked for, and for nine
of them the reason is one this side agrees with on reading it. `Disclosure` is controlled and carries
a stable id; `TextFold` has a character ceiling; `RelativeTime` self-ticks and has a day rung; these
are two products rather than one written twice, and sharing them would delete the half that does
something.

Two of the eleven are different, and the difference is the same in both cases: **the rule that
declined them chose by which repository, and in these two the evidence points the other way.**

---

## `CodeBlock`, where rule one would take the version nobody calls

### What is actually in the two trees

| | this repository | the sibling |
| --- | --- | --- |
| file | `packages/ui/src/primitives/CodeBlock.tsx`, 88 lines | `agent/ui/packages/ui/src/primitives/CodeBlock.tsx`, 23 lines |
| props | `{ code: string; language?: 'java' }` | `{ children: string }` |
| colouring | one-alternation lexer, comment → string → word → number | none |
| re-entrancy | `matchAll`, so two blocks on a page cannot read each other's `lastIndex` | n/a |
| blank input | renders nothing | renders an empty bordered box |
| **call sites** | **three** — `ToolLog.tsx:62`, `ToolLog.tsx:68`, `TestArtifact.tsx:50` | **zero** |

The sibling's is defined, exported from its barrel at `primitives/index.ts:26`, and rendered by
nothing. `grep -rn "CodeBlock" agent/ui` returns three lines and all three are the declaration.

### The rule, and where it lands

Rule one is: *both consuming dashboards had written it, and the difference between their two versions
was the palette rather than the behaviour; where both had the thing, `bump-java-version`'s version is
the shared one.*

Both halves matter and only the first is satisfied. The difference here is not the palette — one
lexes Java and one does not — so rule one **excludes itself**, and `ADOPTING.md:577` correctly says
so, listing `CodeBlock` among the eleven with a reason that is a description of what this side's
version does more:

> Lexes Java in one alternation, `matchAll` for re-entrancy, `language` so colouring something the
> lexer cannot read stops being silent.

That is not a reason to leave it. It is a reason it could not be taken *by rule one*, written as
though it were a reason it should not be taken at all. Having excluded itself, rule one hands the
case to nothing: there is no clause that says what happens when both wrote it, the versions differ in
behaviour, and one of them has no callers.

**So the request is a third rule and then the component.** Where both repositories wrote it and the
versions differ in behaviour rather than palette, the shared one is the version with call sites; and
where neither has call sites, neither moves. That clause decides this case on the evidence rather
than on which side typed it, and it is the same standard `ADOPTING.md` applies everywhere else — the
0.3.0 rule already takes *behaviour* from whichever side has it and *the name* from whichever side
named it.

### What it costs the package, exactly

**Four new names in the contract**, and this is the real price: `--code-comment`, `--code-string`,
`--code-keyword`, `--code-number`. Nothing else is new — `--bg-subtle`, `--border-soft` and
`--text-secondary` are already in `src/tokens.css`. The sibling defines none of the four today and
would define them to use the component, which is the contract working as intended: a list of names
that belong to nobody, with the values each consumer's own.

If four names is the sticking point rather than the component, the colours can be a prop with the
four tokens as its default, and the contract grows by nothing. This side would rather have the four
names — a syntax highlighter's token vocabulary is about as close to universal as a shared contract
gets — but will take either.

### And the sibling's copy has already drifted from itself

The zero is not "nothing over there needs a code block". `agent/ui/apps/web/app/settings/sections.tsx:346`
writes one out inline, and it is that repository's own `CodeBlock` to eight declarations:
`padding: '10px 12px'`, `overflowX: 'auto'`, `borderRadius: '6px'`, `background: 'var(--bg-subtle)'`,
`border: '1px solid var(--border-soft)'`, `fontSize: '12px'`, `lineHeight: 1.5`,
`color: 'var(--text-secondary)'` — all identical.

Two declarations differ. The margin, which is a call site's business. And the inline copy has dropped
`fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace'` altogether, so it renders in whatever
monospace the user agent picks while the component renders in the named stack. That is the drift an
unused component gets: not a bug today, because a `<pre>` is monospace either way, and precisely the
kind of thing that stops being true later.

So this is rule two's own shape — a component on one side and the same thing written out inline on
the other — occurring **inside a single repository**. The long bodies in that dashboard's record go
through `TextFold`, which has its own `<pre>` and is not this; the one place an actual code sample
appears on screen is the inline one.

**What the sibling gains:** a component its own settings page already needed and reimplemented, plus
colouring it has never had. The `language` prop is the part worth having even while unused: absent
means *no* colouring, which is the honest render for a language nothing here can lex. The failure it
exists to prevent is this repository's own — the Java it replaced ran `colourJava()` over everything
it was handed, so an XML fragment came back with `int` and `class` highlighted wherever those
happened to be words.

---

## `Semaphore`, where rule two nearly fits and stops one step short

### The fact the sibling has and cannot show

`ADOPTING.md:457` declines `Semaphore` with:

> The sibling has nothing at all, so there is no second version for the rule to choose between.

That is true of the markup and not of the evidence. `agent/ui/packages/types/src/index.ts:101-102`
declares:

```ts
  baselineGreen: boolean
  gateGreen: boolean
```

They are on the wire, they are in the fixtures, and `Pipeline.test.tsx:46-47` and `tables.test.tsx:29-30`
both pin them. **No source file in `agent/ui` reads either one.** The sibling's server computes two
proof facts per row and no reader of the dashboard can see them. `PipelineMark` is not a counter-example:
it answers "which pipeline produced this row", which is a different question.

### The rule, and the step it stops short of

Rule two is: *where one dashboard had a COMPONENT and the other had the same thing written out
inline, the behaviour taken is the inline one's and the name is the component's.*

The shape here is one step further along that same line. One side has a component; the other has the
data, computed, transmitted, tested — and no rendering at all. That is not a second version to choose
between, and it is not nothing either. It is a consumer holding the evidence with nowhere to put it,
which is a stronger case for sharing than "both wrote it", not a weaker one.

### What is actually asked for, and it is the smaller half

**Not `Semaphore`.** `Semaphore` knows what red and green mean here — that a red lamp is a test that
*failed on purpose*, that green is reached only when red went red, that a marker nobody has judged
has no lamps. That is this pipeline's vocabulary and `ADOPTING.md` is right that a shared package is
the wrong place to decide it. It stays.

**`Lamp` is what is asked for**, and it is domain-free already. Three appearances rather than two,
which is its whole point:

| appearance | means |
| --- | --- |
| lit | the build said so |
| dim | it was reached and did not happen |
| hollow | it was never got to |

The middle one is the reason it exists. A red that passed and a red that was never run are different
answers, and two lamps that could only be on or off would tell a reader they were the same.

It also carries `role="img"` with the whole sentence as its label rather than a bare `title` on an
empty element, because these tooltips carry a standard of proof and a decorative `<i>` with a
tooltip is invisible to anyone not holding a mouse.

**Proposed shape, and it adds nothing to the contract:**

```tsx
export type LampProps = {
  lit: boolean
  reached: boolean
  /** The caller's colour, so no build vocabulary travels. */
  colour: string
  /** The whole sentence. The caller owns what its lamps mean. */
  label: string
}
```

`colour` and `label` from the caller is not a concession, it is the precedent this release already
set: `DataTable`'s `rowClassName` is a prop for exactly the same reason, so that a value belonging to
one consumer never has to exist inside the package. `--build-red`, `--build-green` and `--build-none`
then stay in this repository's `tokens.css` where they are, and the contract grows by nothing at all.

**What the sibling gains:** a two-lamp cell in `BumpTable` built from `baselineGreen` and `gateGreen`,
with its own words for what they mean — and two facts its own server has been computing all along
become readable.

---

## What this side offers

Both, written and tested against the package's own conventions, as a pull request rather than as a
request that somebody else do the work: `Lamp` with its three appearances and its own test file,
`CodeBlock` with the lexer and a test that a keyword inside a string stays inside the string.

The call sites here change in the same commit, so the package never carries a component this
repository has not already moved to.

## If you decline

Nothing breaks and nothing is owed. This repository keeps both, the sibling keeps a `CodeBlock` with
no callers and two booleans with no readers, and the duplication is the duplication that already
exists.

The one thing worth taking even then is the third rule. It costs nothing, it decides `CodeBlock`
either way, and its absence is the reason a component with no call sites would have won a tiebreak
against one with three.
