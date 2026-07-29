// @ts-nocheck
'use strict';
/**
 * Single source of truth for pipeline + per-stage versions.
 *
 * Every stage stamps its version into the artifact it produces, so a row in the dashboard always says
 * which code produced it. Bump a stage version whenever that stage's prompt, tools or parsing change;
 * bump PIPELINE_VERSION whenever the shape of the lifecycle changes.
 *
 * Lifecycle: ingester -> reproducer -> fixer -> pr maker, with a verdict branch off the reproducer.
 */
const PIPELINE_VERSION = 'S1 (2026-07-27: Svace markers replace the LLM suspector as the suspicion source; a marker that will not reproduce ends in a WRITTEN verdict instead of a bare not_reproduced)';

const INGESTER_VERSION = 'i1 (2026-07-27: CSV -> one suspicion per marker; CI path prefix stripped, checker mapped to category + one-line meaning, src/test+src/it excluded by default)';
const VERDICT_VERSION = 'vd1 (2026-07-27: source-only rebuttal — no Svace endpoint available, so the argument is made from the checker\'s claim + the actual code, and enrichment sits behind a stub)';
const ANCHOR_VERSION = 'a1 (2026-07-27: markers re-anchored onto the enclosing symbol, because the scanned commit is unknown and line numbers drift against upstream HEAD)';

const REPRODUCER_VERSION = 'r5 (2026-07-22: a test that never compiled/ran = infra (retry), not a false not-a-bug verdict; JDK auto-detect covers release-version mismatch)';
const FIXER_VERSION = 'f3 (2026-07-22: robust JSON extractor for fix edits; records only edits actually APPLIED)';
const PR_MAKER_VERSION = 'pr3 (2026-07-22: an uncurated draft is banner-marked in pr_body)';
const SKEPTIC_VERSION = 'sk5 (2026-07-22: fail-closed whitelist; a valid verdict with no reason is not mislabelled unrecognised)';

/** Just the leading token ("i1", "vd1") — for notes/rows where the full string would bloat the UI. */
function shortId(stageVersion) {
  return String(stageVersion).split(' ')[0];
}

/** The one-line header a stage puts at the top of its transcript / prompt. */
function stamp(stageVersion) {
  return '[pipeline ' + PIPELINE_VERSION + ']  [stage ' + stageVersion + ']';
}

/** Stamped onto every `bugs` row so an artifact carries the versions that produced it. */
function versionsJson() {
  return JSON.stringify({
    pipeline: PIPELINE_VERSION,
    ingester: INGESTER_VERSION,
    anchor: ANCHOR_VERSION,
    reproducer: REPRODUCER_VERSION,
    fixer: FIXER_VERSION,
    pr_maker: PR_MAKER_VERSION,
    skeptic: SKEPTIC_VERSION,
    verdict: VERDICT_VERSION,
  });
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = {
  PIPELINE_VERSION, INGESTER_VERSION, VERDICT_VERSION, ANCHOR_VERSION,
  REPRODUCER_VERSION, FIXER_VERSION, PR_MAKER_VERSION, SKEPTIC_VERSION,
  shortId, stamp, versionsJson,
};
