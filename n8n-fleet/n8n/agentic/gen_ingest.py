#!/usr/bin/env python3
"""fsm-ingest: turn a Svace marker report into the `suspicions` backlog.

POST /webhook/ingest { csv_path?, csv_text?, repo, branch?, path_prefix?, include_tests?, only_checkers?, min_severity? }

This REPLACES the parent pipeline's orchestrator + suspector + dedup. There is no file walking and no
LLM detection stage: Svace has already decided what is suspicious, so ingest is a pure, deterministic
transform from one CSV row to one suspicion row. Dedup is dropped too — Svace de-duplicates its own
markers, and the only genuine repeats (same checker, same file, same line) are kept apart by an
occurrence index rather than collapsed, because two markers on one line are two obligations.

The prover downstream is unchanged: it drains status='new' one at a time under the runner lease.
"""
import json

from tables import SUSPICIONS_TABLE, BUGS_TABLE, check as _check_tables
from versions import INGESTER_VERSION, short_id, PIPELINE_VERSION

_check_tables()

# --- checker -> what it MEANS ------------------------------------------------------------------
# The reproducer is asked to disprove or confirm ONE specific claim, so it must be told what the
# claim is. A bare checker name ("FB.EI_EXPOSE_REP2") is not a claim; the one-line meaning is.
#
# `prove` marks how the finding can be settled:
#   "test"  — a JUnit test can exhibit the defect directly (write it red, fix it green)
#   "argue" — no runtime assertion can demonstrate a DEFECT (style, dead code, hard-coded secret);
#             these are expected to end in a written verdict, not a PR. It is a hint to the
#             reproducer, not a gate: it may still return can_prove=true and be taken at its word.
#
# Covers every checker present in the 356-marker WebGoat report.
CHECKER_MAP = {
    # ---- command injection ----
    "PROC_USE.VULNERABLE": ("command-injection", "a process is launched from a command string built with externally-controlled data", "test"),
    "FB.COMMAND_INJECTION": ("command-injection", "a shell/process command is assembled from unvalidated input", "test"),

    # ---- resource leaks ----
    "HANDLE_LEAK": ("resource-leak", "a resource handle is not closed on every path out of the method", "test"),
    "HANDLE_LEAK.EXCEPTION": ("resource-leak", "a resource handle is left open when an exception unwinds the method", "test"),
    "FB.OBL_UNSATISFIED_OBLIGATION": ("resource-leak", "the obligation to close a stream is not discharged on some path", "test"),
    "FB.OBL_UNSATISFIED_OBLIGATION_EXCEPTION_EDGE": ("resource-leak", "the obligation to close a stream is not discharged along an exception edge", "test"),
    "FB.ODR_OPEN_DATABASE_RESOURCE": ("resource-leak", "a JDBC resource is opened and not closed on all paths", "test"),
    "FB.OS_OPEN_STREAM": ("resource-leak", "a stream is opened and never closed", "test"),

    # ---- null dereference ----
    "DEREF_OF_NULL.RET": ("npe", "the return value of a call that can be null is dereferenced without a null check", "test"),
    "DEREF_OF_NULL.RET.LIB": ("npe", "the return value of a LIBRARY call that is documented to return null is dereferenced unchecked", "test"),
    "DEREF_OF_NULL.RET.STAT": ("npe", "the return value of a static call that can be null is dereferenced unchecked", "test"),
    "DEREF_AFTER_NULL": ("npe", "a value that is compared against null on one path is dereferenced on another", "test"),
    "FB.NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE": ("npe", "a possibly-null return value is dereferenced on at least one path", "test"),
    "FB.NP_NULL_PARAM_DEREF": ("npe", "a possibly-null value is passed where a non-null argument is required", "test"),

    # ---- taint / path traversal ----
    "FB.PATH_TRAVERSAL_IN": ("path-traversal", "a filesystem path is built from externally-controlled data without normalization, so '../' escapes the intended directory", "test"),
    "TAINTED_PTR": ("taint", "externally-controlled (tainted) data reaches a sensitive sink without validation", "test"),
    "TAINTED_PTR.MINOR": ("taint", "externally-controlled data reaches a sink with only weak validation", "test"),
    "TAINTED_PTR.COOKIE": ("taint", "data taken from a cookie is used without validation", "test"),

    # ---- secrets / crypto ----
    "FB.HARD_CODE_PASSWORD": ("hardcoded-secret", "a password or credential is hard-coded in the source", "argue"),
    "FB.PREDICTABLE_RANDOM": ("weak-randomness", "java.util.Random is used where a cryptographically secure RNG is required", "test"),
    "FB.DMI_RANDOM_USED_ONLY_ONCE": ("weak-randomness", "a new Random is constructed and used once, so its output is determined by the seed alone", "test"),

    # ---- mutable state exposure ----
    "FB.EI_EXPOSE_REP": ("mutable-exposure", "a getter returns a reference to internal mutable state, so a caller can modify the object's internals", "test"),
    "FB.EI_EXPOSE_REP2": ("mutable-exposure", "a constructor/setter stores an externally supplied mutable object directly, so the caller retains a handle on the object's internals", "test"),
    "FB.MS_PKGPROTECT": ("mutable-exposure", "a mutable static field is more visible than it needs to be", "argue"),
    "FB.ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD": ("mutable-exposure", "an instance method writes to a static field, which races across instances", "test"),

    # ---- encoding / formatting ----
    "FB.DM_DEFAULT_ENCODING": ("default-encoding", "a String/byte conversion relies on the platform default charset, so behaviour changes with the environment", "test"),
    "FB.VA_FORMAT_STRING_USES_NEWLINE": ("default-encoding", "a format string uses \\n where %n is required for platform-correct line endings", "argue"),

    # ---- ignored results ----
    "FB.RV_RETURN_VALUE_IGNORED": ("ignored-result", "the return value of a method is discarded although it carries the result of the call", "test"),
    "FB.RV_RETURN_VALUE_IGNORED_BAD_PRACTICE": ("ignored-result", "the return value of a method that reports failure via its result is discarded", "test"),
    "FB.RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT": ("ignored-result", "the return value of a side-effect-free method is discarded, so the call does nothing", "test"),

    # ---- dead / unreachable ----
    "UNREACHABLE_CODE": ("dead-code", "this code cannot be reached on any execution path", "argue"),
    "UNREACHABLE_CODE.EXCEPTION": ("dead-code", "this code is unreachable because an exception always unwinds before it", "argue"),
    "FB.DLS_DEAD_LOCAL_STORE": ("dead-code", "a value assigned to a local variable is never read", "argue"),
    "FB.URF_UNREAD_FIELD": ("dead-code", "a field is written but never read", "argue"),
    "UNUSED_VALUE": ("dead-code", "a computed value is never used", "argue"),
    "FB.UC_USELESS_OBJECT": ("dead-code", "an object is created and populated but never used", "argue"),

    # ---- type confusion ----
    "COLLECTION.WRONG_ARG_TYPE": ("type-confusion", "a collection method is called with an argument whose type can never match the element type, so the call silently does nothing", "test"),
    "FB.GC_UNRELATED_TYPES": ("type-confusion", "a generic call is made with unrelated types, so it cannot match at runtime", "test"),

    # ---- exception handling ----
    "NO_CATCH": ("exception-handling", "an exception that can be thrown here is not handled", "test"),
    "NO_CATCH.LIBRARY": ("exception-handling", "an exception documented by a library call is not handled", "test"),
    "FB.REC_CATCH_EXCEPTION": ("exception-handling", "catch(Exception) also swallows RuntimeExceptions that were not meant to be caught", "test"),

    # ---- structural smells ----
    "SIMILAR_BRANCHES": ("similar-branches", "two branches have identical bodies, which usually means a copy-paste error left one branch wrong", "test"),
    "SIMILAR_BRANCHES.CATCH": ("similar-branches", "two catch blocks have identical bodies, which usually means one was meant to differ", "test"),
    "FB.ICAST_INTEGER_MULTIPLY_CAST_TO_LONG": ("integer-overflow", "an int multiplication is cast to long only AFTER the multiplication, so it can already have overflowed", "test"),
    "FB.UI_INHERITANCE_UNSAFE_GETRESOURCE": ("resource-lookup", "getClass().getResource() in a subclassable class resolves relative to the SUBCLASS, not this class", "test"),

    # ---- test-quality checkers (only fire on test sources) ----
    "TEST.INCORRECT_MODIFIERS": ("test-quality", "a test method has modifiers that stop the framework from running it", "test"),
    "TEST.FAIL_IN_CATCH": ("test-quality", "a test calls fail() inside a catch block, which hides the real assertion error", "argue"),
    "TEST.MULTIPLE_EXCEPTIONAL_CALLS": ("test-quality", "a test asserts on several calls that can each throw, so a failure does not identify which", "argue"),
}

SEVERITY_MAP = {"Critical": "high", "Major": "high", "Normal": "medium", "Minor": "low"}
SEVERITY_RANK = {"Critical": 3, "Major": 2, "Normal": 1, "Minor": 0}

PARSE = r"""
const CHECKER_MAP = __CHECKER_MAP__;
const SEVERITY_MAP = __SEVERITY_MAP__;
const SEVERITY_RANK = __SEVERITY_RANK__;
const VERSION = __VERSION__;

const b = ($('Ingest webhook').first().json.body) || {};
const repo = (b.repo || '').toString().trim();
if (!repo) throw new Error('ingest: `repo` is required (e.g. "WebGoat/WebGoat")');
const branch = (b.branch || '').toString().trim();
const includeTests = b.include_tests === true;
// The CI prefix the scanner recorded. Markers carry an absolute BUILD path
// (/builds/gitlab/<group>/<project>/src/main/java/...), which exists on no checkout anywhere.
const prefix = (b.path_prefix === undefined
  ? '/builds/gitlab/drit_digital_trace/owasp-webgoat/'
  : (b.path_prefix || '').toString());
const onlyCheckers = Array.isArray(b.only_checkers) && b.only_checkers.length
  ? new Set(b.only_checkers.map(s => String(s).trim())) : null;
const minRank = SEVERITY_RANK[(b.min_severity || '').toString()] ?? -1;

// --- source: an inline body, or a file off the mounted repo ---
let text = (b.csv_text || '').toString();
let source = 'csv_text';
if (!text) {
  const p = (b.csv_path || '/data/data/svace/webgoat-markers-356.csv').toString();
  const fs = require('fs');
  if (!fs.existsSync(p)) throw new Error('ingest: no CSV at ' + p + ' (is the repo mounted at /data?)');
  text = fs.readFileSync(p, 'utf8');
  source = p;
}

// --- RFC4180-ish CSV: fields are quoted and may contain commas ---
function parseCsv(t) {
  const rows = []; let cur = [], field = '', inQ = false;
  for (let i = 0; i < t.length; i++) {
    const c = t[i];
    if (inQ) {
      if (c === '"') { if (t[i + 1] === '"') { field += '"'; i++; } else inQ = false; }
      else field += c;
    } else if (c === '"') inQ = true;
    else if (c === ',') { cur.push(field); field = ''; }
    else if (c === '\n') { cur.push(field); field = ''; rows.push(cur); cur = []; }
    else if (c !== '\r') field += c;
  }
  if (field.length || cur.length) { cur.push(field); rows.push(cur); }
  return rows.filter(r => r.length && r.some(x => x !== ''));
}

// Absolute CI build path -> repo-relative path. Falls back to slicing at the first `/src/` segment so
// a report from a DIFFERENT CI root still normalizes instead of silently producing an unopenable path.
function normPath(p) {
  let s = String(p || '').replace(/\\/g, '/');
  if (prefix && s.startsWith(prefix)) return s.slice(prefix.length).replace(/^\/+/, '');
  const i = s.indexOf('/src/');
  if (i >= 0) return s.slice(i + 1);
  return s.replace(/^\/+/, '');
}

const rows = parseCsv(text);
if (!rows.length) throw new Error('ingest: CSV parsed to zero rows (' + source + ')');
const head = rows[0].map(h => h.trim().toLowerCase());
const col = (n) => { const i = head.indexOf(n); if (i < 0) throw new Error('ingest: CSV has no `' + n + '` column; got ' + JSON.stringify(head)); return i; };
const iSev = col('severity'), iChk = col('checker'), iFile = col('file'), iLine = col('line');

const out = [];
const seen = Object.create(null);         // dedup_key base -> occurrence count
const skipped = { tests: 0, checker_filter: 0, severity_filter: 0, unmapped_kept: 0, bad_row: 0 };
const unmapped = Object.create(null);

for (let r = 1; r < rows.length; r++) {
  const row = rows[r];
  const checker = (row[iChk] || '').trim();
  const rawFile = (row[iFile] || '').trim();
  const line = parseInt((row[iLine] || '').trim(), 10);
  const sev = (row[iSev] || '').trim();
  if (!checker || !rawFile || !isFinite(line)) { skipped.bad_row++; continue; }
  if (onlyCheckers && !onlyCheckers.has(checker)) { skipped.checker_filter++; continue; }
  if ((SEVERITY_RANK[sev] ?? -1) < minRank) { skipped.severity_filter++; continue; }

  const file = normPath(rawFile);
  // src/test and src/it markers are STRUCTURALLY unfixable by this pipeline: the fixer is refused any
  // edit under a test tree (the runner enforces it server-side), so a marker there could only ever end
  // as a verdict. Excluded by default; `include_tests: true` ingests them anyway.
  const isTest = /(^|\/)src\/(test|it)\//.test(file);
  if (isTest && !includeTests) { skipped.tests++; continue; }

  const m = CHECKER_MAP[checker];
  if (!m) { unmapped[checker] = (unmapped[checker] || 0) + 1; skipped.unmapped_kept++; }
  // An unknown checker is still a real marker — ingest it with the checker name as its own meaning
  // rather than dropping a finding just because this map has not caught up with the scanner.
  const category = m ? m[0] : 'unmapped';
  const meaning = m ? m[1] : ('Svace checker ' + checker + ' (no description available in the checker map)');
  const prove = m ? m[2] : 'test';

  const base = repo + '|' + file + '|' + line + '|' + checker;
  const occ = (seen[base] = (seen[base] || 0) + 1);
  const dedup_key = occ > 1 ? base + '#' + occ : base;
  const cls = (file.split('/').pop() || '').replace(/\.java$/, '');

  out.push({ json: {
    dedup_key,
    marker_id: checker + '@' + file + ':' + line + (occ > 1 ? '#' + occ : ''),
    repo, branch, file,
    class_name: cls,
    method: '',                     // unknown until the prover re-anchors against the real checkout
    line,                           // provisional; re-anchoring may move it
    svace_line: line,               // what Svace actually reported — never overwritten
    anchor: '',
    anchor_status: 'pending',
    category,
    severity: SEVERITY_MAP[sev] || 'low',
    svace_checker: checker,
    svace_severity: sev,
    title: checker + ' at ' + cls + '.java:' + line,
    description: meaning,
    evidence: 'Svace ' + sev + ' marker `' + checker + '` at ' + file + ':' + line
              + '. Claim: ' + meaning + '. Settle-by: ' + prove + '.',
    status: 'new',
    note: '',
    prove_attempts: 0,
    version: VERSION,
    method_key: '',
  }});
}

const summary = {
  source, repo, branch, ingested: out.length, csv_rows: rows.length - 1,
  skipped, unmapped_checkers: unmapped,
};
console.log('[ingest] ' + JSON.stringify(summary));
if (!out.length) throw new Error('ingest: every row was filtered out — ' + JSON.stringify(summary));
// carry the summary on the first item so the final node can report it without a second pass
out[0].json.__summary = JSON.stringify(summary);
return out;
"""

PARSE = (PARSE
         .replace("__CHECKER_MAP__", json.dumps({k: list(v) for k, v in CHECKER_MAP.items()}, indent=None))
         .replace("__SEVERITY_MAP__", json.dumps(SEVERITY_MAP))
         .replace("__SEVERITY_RANK__", json.dumps(SEVERITY_RANK))
         .replace("__VERSION__", json.dumps(short_id(INGESTER_VERSION))))

SUMMARY = r"""
// Report what landed. The insert node emits one item per upserted row, so count them here rather
// than trusting the parse node's own tally — this is the number that actually reached the table.
const items = $input.all();
let summary = {};
try { summary = JSON.parse($('Parse markers').first().json.__summary || '{}'); } catch (e) {}
return [{ json: { ok: true, rows_written: items.length, ...summary } }];
"""

N = []


def node(name, ntype, params, tv, x, y=300, extra=None):
    n = {"parameters": params, "id": f"i{len(N)+1}", "name": name, "type": ntype,
         "typeVersion": tv, "position": [x, y]}
    if extra:
        n.update(extra)
    N.append(n)
    return name


def dtref(tid, nm):
    return {"__rl": True, "mode": "list", "value": tid, "cachedResultName": nm}


def clear_node(label, tid, nm, x):
    # Match-all = (repo isNotEmpty OR repo isEmpty) under matchType=anyCondition. isNotEmpty alone
    # leaves behind half-written rows whose columns are all NULL. Inherited verbatim from the parent,
    # where that exact gap kept stale rows on the dashboard after a re-run.
    return node(label, "n8n-nodes-base.dataTable",
                {"resource": "row", "operation": "deleteRows", "dataTableId": dtref(tid, nm),
                 "matchType": "anyCondition",
                 "filters": {"conditions": [{"keyName": "repo", "condition": "isNotEmpty", "keyValue": ""},
                                            {"keyName": "repo", "condition": "isEmpty", "keyValue": ""}]},
                 "options": {}}, 1.1, x, y=140,
                extra={"onError": "continueRegularOutput", "executeOnce": True, "alwaysOutputData": True})


node("Ingest webhook", "n8n-nodes-base.webhook",
     {"httpMethod": "POST", "path": "ingest", "responseMode": "lastNode", "options": {}},
     2, 0, extra={"webhookId": "fsmingesthook1"})
# Fresh ingest = fresh backlog. `bugs` is cleared alongside `suspicions` for the parent's reason: every
# bug row points at a suspicion_key, so keeping bugs while wiping suspicions leaves orphaned artifacts
# that contradict the new report on screen.
clear_node("Clear suspicions", SUSPICIONS_TABLE, "suspicions", 220)
clear_node("Clear bugs", BUGS_TABLE, "bugs", 440)
node("Parse markers", "n8n-nodes-base.code",
     {"mode": "runOnceForAllItems", "jsCode": PARSE.strip()}, 2, 660)
_SUS_COLS = ["dedup_key", "marker_id", "repo", "branch", "file", "class_name", "method", "line",
             "svace_line", "anchor", "anchor_status", "category", "severity", "svace_checker",
             "svace_severity", "title", "description", "evidence", "status", "note",
             "prove_attempts", "version", "method_key"]
# EXPLICIT mapping, not autoMapInputData: `Parse markers` also emits `__summary` on the first item, and
# n8n REJECTS an unknown column on autoMap — which would fail the whole insert. Same failure mode the
# parent hit on its bugs upsert.
node("Insert suspicions", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "upsert", "dataTableId": dtref(SUSPICIONS_TABLE, "suspicions"),
      "filters": {"conditions": [{"keyName": "dedup_key", "condition": "eq",
                                  "keyValue": "={{ $json.dedup_key }}"}]},
      "columns": {"mappingMode": "defineBelow",
                  "value": {c: "={{ $json['" + c + "'] }}" for c in _SUS_COLS},
                  "matchingColumns": ["dedup_key"], "schema": [],
                  "attemptToConvertTypes": False, "convertFieldsToString": True},
      "options": {}}, 1.1, 880)
node("Ingest summary", "n8n-nodes-base.code",
     {"mode": "runOnceForAllItems", "jsCode": SUMMARY.strip()}, 2, 1100)

conns = {
    "Ingest webhook":    {"main": [[{"node": "Clear suspicions", "type": "main", "index": 0}]]},
    "Clear suspicions":  {"main": [[{"node": "Clear bugs", "type": "main", "index": 0}]]},
    "Clear bugs":        {"main": [[{"node": "Parse markers", "type": "main", "index": 0}]]},
    "Parse markers":     {"main": [[{"node": "Insert suspicions", "type": "main", "index": 0}]]},
    "Insert suspicions": {"main": [[{"node": "Ingest summary", "type": "main", "index": 0}]]},
}

wf = {"id": "fsmingest000001", "name": "fsm-ingest", "active": False,
      "nodes": N, "connections": conns, "settings": {"executionOrder": "v1"}}
# Write on RUN only — importing this module (test_ingest.py does, to reach PARSE) must never overwrite
# the deployable artifact. See the note in gen_prover.py: an import-time write shipped stub table ids.
if __name__ == "__main__":
    open("workflow_ingest.json", "w").write(json.dumps(wf, indent=2))
    print(f"wrote workflow_ingest.json — {len(N)} nodes, {len(CHECKER_MAP)} checkers mapped "
          f"[{PIPELINE_VERSION.split(' ')[0]}]")
