# prompts/ — the five texts the pipeline sends to the model

One file per stage, plain text, in git. These are **source**, not configuration and not secrets: they
are what the pipeline actually says to the model, and editing one is a behaviour change that should be
diffed and reviewed like any other.

| stage         | file              | what it does                                              |
|---------------|-------------------|-----------------------------------------------------------|
| `reproducer`  | `reproducer.txt`  | writes the failing JUnit test that settles one Svace marker |
| `fixer`       | `fixer.txt`       | corrects the source, never the test it was handed          |
| `fix-skeptic` | `fix-skeptic.txt` | judges whether the fix is general or over-fit              |
| `pr-maker`    | `pr-maker.txt`    | decides whether a pull request is opened upstream          |
| `verdict`     | `verdict.txt`     | writes the rebuttal a reviewer reads instead of a patch     |

Those five are every model call the pipeline makes.

## The resolution rule

Per stage, in this order:

1. `<FSM_PROMPTS_DIR>/<stage>.txt` — **this directory. It wins.**
2. `DEFAULT_<STAGE>_PROMPT` in the process environment — `DEFAULT_REPRODUCER_PROMPT`,
   `DEFAULT_FIXER_PROMPT`, `DEFAULT_FIX_SKEPTIC_PROMPT`, `DEFAULT_PR_MAKER_PROMPT`,
   `DEFAULT_VERDICT_PROMPT`.
3. the text compiled into the class — `tech.mikhailov.fsm.orch.Prompts` for the two agent briefs, and
   `DEFAULT_PROMPT` on `FixSkeptic`, `PrMaker` and `Verdict`.

Per **stage**, not per directory: dropping in one file tunes one stage and leaves the other four
exactly as they were. A deployment with no prompts directory at all resolves every stage to the
built-in text and behaves precisely as it did before this directory existed.

`FSM_PROMPTS_DIR` defaults to `/data/prompts`. `deploy/docker-compose.yml` mounts the repository root at
`/data` read-only for the `fsm` service, so that path is this directory seen from inside the container.
Read-only is fine — nothing writes here — and there is no image to rebuild, because these files are read
from that mount on the way up rather than copied into the image.

## Checking that the file you edited is the file it sent

The orchestrator names the source of all five stages on the way up, one line each:

```
docker compose logs fsm | grep '\[prompts\]'

[prompts] directory /data/prompts — a file here overrides the DEFAULT_ fallback
[prompts] reproducer <- FILE /data/prompts/reproducer.txt (2895 chars)
[prompts] fixer <- FILE /data/prompts/fixer.txt (950 chars)
...
```

This is the only observable difference between tuning something and tuning nothing: a prompt that
silently fell back and a prompt that was picked up produce identical rows, identical verdicts and
identical run histories.

## Editing one

- **Keep the placeholders.** `reproducer.txt` and `fixer.txt` must contain `__STAMP__`, which is
  replaced with the pipeline + stage version so a reply can be traced back to the instructions that
  produced it. The other three are `String.format` templates with a fixed number of **positional**
  `%s`: 5 for `fix-skeptic`, 8 for `pr-maker`, 13 for `verdict`. The prompt builder in each engine node
  is what defines the order. A literal percent sign must be written `%%`.
- **Bump the stage version** in `tech.mikhailov.fsm.orch.Versions` when you change what a stage does.
  Two wordings under one stamp produce rows that cannot be told apart, which is what makes the
  feedback loop unreadable.
- **A malformed or empty file stops the process starting**, by name, with the path. That is
  deliberate: a blank prompt sends the model no instructions at all, and a 282-marker run would come
  back unusable over 6-26 hours with nothing red anywhere.
- **No credentials, ever.** These files are tracked in git. Nothing here is interpolated, so there is
  never a reason for a key to be in one.

## Where this directory is deliberately not read

- **`fsm-engine`, the one-stage HTTP debugging service.** `POST /node/fix-skeptic`, `/node/pr-maker`
  and `/node/verdict` build their prompt from the compiled-in `DEFAULT_PROMPT`, because the engine
  takes everything from the request body and holds no deployment state of its own — that is what makes
  it a pure function you can replay a stage against. Reproducing exactly what a run sent means passing
  the text yourself. The prove chain does not go through this service at all: the orchestrator embeds
  the engine as a library.
- **Nowhere else, and keep it that way.** There is exactly one copy of each brief: the file here, with
  `tech.mikhailov.fsm.orch.Prompts` as the compiled-in last resort. A second copy anywhere is worse than
  none — it is the one an editor finds and changes while the run keeps sending the other, and the two
  produce identical rows, identical verdicts and identical run histories.

The behaviour is pinned in `pipeline/orchestrator/src/test/java/tech/mikhailov/fsm/orch/PromptSourceTest.java`
and `PromptFilesTest.java`.
