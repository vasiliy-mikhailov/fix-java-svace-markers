"""Single source of truth for pipeline + per-stage versions.

Every stage stamps its version into the artifact it produces, so a row in the dashboard always says
which code produced it. Bump the stage version whenever that stage's prompt, tools, or parsing change;
bump PIPELINE_VERSION whenever the shape of the lifecycle changes (a stage added/removed/rewired).

Each string is an id, a date, and a description of what that code DOES.

Lifecycle: suspector -> dedup -> reproducer -> fixer -> pr maker
"""

PIPELINE_VERSION = "P6 (2026-07-22: prover drains the queue CONCURRENTLY with the scan, one at a time under a runner lease; empty-queue lease strand fixed)"

SUSPECTOR_VERSION = "v65 (2026-07-22: one robust verdict parser on both paths — fenced + key-anchored + both-ends + backslash-safe repair)"
DEDUP_VERSION = "d2 (2026-07-22: repo-wide clustering of `new` suspicions; marks duplicates, never deletes)"
REPRODUCER_VERSION = "r5 (2026-07-22: a test that never compiled/ran = infra (retry), not a false not-a-bug verdict; JDK auto-detect covers release-version mismatch)"
FIXER_VERSION = "f3 (2026-07-22: robust JSON extractor for fix edits; records only edits actually APPLIED)"
PR_MAKER_VERSION = "pr3 (2026-07-22: an uncurated draft is banner-marked in pr_body)"

# extra stages that are not part of the four the operator tracks, but still worth stamping
SKEPTIC_VERSION = "sk5 (2026-07-22: fail-closed whitelist; a valid verdict with no reason is not mislabelled unrecognised)"


def short_id(stage_version):
    """Just the leading token ("d1", "v21") — for notes/rows where the full string would bloat the UI."""
    return stage_version.split(" ")[0]


def stamp(stage_version):
    """The one-line header a stage puts at the top of its transcript / prompt."""
    return "[pipeline " + PIPELINE_VERSION + "]  [stage " + stage_version + "]"


def versions_json():
    """Stamped onto every `bugs` row so an artifact carries the versions that produced it."""
    import json
    return json.dumps({
        "pipeline": PIPELINE_VERSION,
        "suspector": SUSPECTOR_VERSION,
        "dedup": DEDUP_VERSION,
        "reproducer": REPRODUCER_VERSION,
        "fixer": FIXER_VERSION,
        "pr_maker": PR_MAKER_VERSION,
        "skeptic": SKEPTIC_VERSION,
    })
