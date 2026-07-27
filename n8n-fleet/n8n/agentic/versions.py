"""Single source of truth for pipeline + per-stage versions.

Every stage stamps its version into the artifact it produces, so a row in the dashboard always says
which code produced it. Bump the stage version whenever that stage's prompt, tools, or parsing change;
bump PIPELINE_VERSION whenever the shape of the lifecycle changes (a stage added/removed/rewired).

Each string is an id, a date, and a description of what that code DOES.

Lifecycle: ingester -> reproducer -> fixer -> pr maker, with a verdict branch off the reproducer.
"""

PIPELINE_VERSION = "S1 (2026-07-27: Svace markers replace the LLM suspector as the suspicion source; a marker that will not reproduce ends in a WRITTEN verdict instead of a bare not_reproduced)"

INGESTER_VERSION = "i1 (2026-07-27: CSV -> one suspicion per marker; CI path prefix stripped, checker mapped to category + one-line meaning, src/test+src/it excluded by default)"
VERDICT_VERSION = "vd1 (2026-07-27: source-only rebuttal — no Svace endpoint available, so the argument is made from the checker's claim + the actual code, and enrichment sits behind a stub)"
ANCHOR_VERSION = "a1 (2026-07-27: markers re-anchored onto the enclosing symbol, because the scanned commit is unknown and line numbers drift against upstream HEAD)"

# Retired with the input swap, kept so an OLD stored artifact's `versions` blob still resolves.
SUSPECTOR_VERSION = "v65 (2026-07-22: RETIRED — Svace markers replaced LLM detection in S1)"
DEDUP_VERSION = "d2 (2026-07-22: RETIRED — Svace de-duplicates its own markers, so the dedup stage was dropped in S1)"
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
        "ingester": INGESTER_VERSION,
        "anchor": ANCHOR_VERSION,
        "reproducer": REPRODUCER_VERSION,
        "fixer": FIXER_VERSION,
        "pr_maker": PR_MAKER_VERSION,
        "skeptic": SKEPTIC_VERSION,
        "verdict": VERDICT_VERSION,
    })
