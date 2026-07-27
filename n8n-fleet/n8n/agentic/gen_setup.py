#!/usr/bin/env python3
"""Generate fsm-setup: creates the `suspicions` and `bugs` n8n Data Tables (idempotent)."""
import json

SUSPICIONS = [
    ("dedup_key", "string"),   # repo|file|line|checker (normalized) -> upsert key; the prover's row id
    ("repo", "string"),
    ("file", "string"),
    ("class_name", "string"),
    ("method", "string"),
    ("line", "number"),         # line in the tree we actually check out (may differ from svace_line)
    ("category", "string"),     # command-injection|resource-leak|npe|path-traversal|... (see CHECKER_MAP)
    ("severity", "string"),     # high|medium|low  (mapped from svace_severity)
    ("title", "string"),
    ("description", "string"),
    ("evidence", "string"),     # the checker's one-line meaning + the flagged source line
    ("status", "string"),       # new|reproducing|reproduced|fixed|verified|rejected|pr_ready
    ("note", "string"),         # free-form progress / rejection reason
    ("version", "string"),      # ingester version that produced this row (see versions.py)
    ("method_key", "string"),   # the method_runs row that produced it (transcript lookup key)
    # --- columns the PROVER reads/writes that the parent's spec never declared. The live fix-java-bugs
    # tables have them (added out-of-band), so the parent works; a table created fresh from this spec
    # did NOT, and the prover's first `Update suspicion` (which writes prove_attempts) rejects the
    # unknown column and crashes every prove. Declared here so a clean setup is actually runnable.
    ("prove_attempts", "number"),
    ("branch", "string"),
    # --- Svace marker provenance -------------------------------------------------------------
    ("marker_id", "string"),      # stable id for this marker within the report
    ("svace_checker", "string"),  # e.g. FB.EI_EXPOSE_REP2, HANDLE_LEAK, TAINTED_PTR
    ("svace_severity", "string"), # Critical|Major|Normal|Minor, verbatim from the report
    ("svace_line", "number"),     # the line Svace reported, BEFORE re-anchoring
    ("anchor", "string"),         # enclosing symbol we re-anchored onto
    ("anchor_status", "string"),  # exact | relocated | unresolved  (see the ingester)
]

METHOD_RUNS = [
    ("method_key", "string"),   # repo|file|method  (upsert key)
    ("repo", "string"),
    ("file", "string"),
    ("class_name", "string"),
    ("method", "string"),
    ("findings", "number"),
    ("tool_calls", "number"),
    ("status", "string"),       # done
    ("dialog", "string"),       # the suspector ReAct transcript (prompt, tool calls+results, verdict)
]

SCAN_FILES = [
    ("file", "string"),         # repo-relative path (the worklist key)
    ("repo", "string"),
    ("module", "string"),
    ("class_name", "string"),
    ("methods", "number"),      # substantive methods the suspector will analyze
    ("status", "string"),       # queued | scanning | done | error
    ("run_id", "string"),       # which scan produced this row
]

BUGS = [
    ("suspicion_key", "string"),
    ("repo", "string"),
    ("file", "string"),
    ("title", "string"),
    ("jdk", "string"),
    ("test_path", "string"),
    ("test_code", "string"),
    ("fix_diff", "string"),
    ("red_verified", "boolean"),   # test fails on unpatched code
    ("green_verified", "boolean"), # test passes after the fix
    ("value_score", "number"),
    ("value_verdict", "string"),
    ("pr_title", "string"),
    ("pr_body", "string"),
    ("state", "string"),           # pr_ready|needs_review|pr_rejected|fix_failed|false_positive|
                                   # not_reproduced|infra_error
    ("versions", "string"),        # JSON: pipeline + ingester/reproducer/fixer/pr_maker versions
    ("infra_reason", "string"),    # why state=infra_error (never a verdict about the code)
    ("branch", "string"),          # which branch the artifact was proven on (parent writes it; see above)
    # --- the second first-class output: a written rebuttal for a marker that will not reproduce.
    # This is a DELIVERABLE, not a footnote — a marker that yields no PR must still yield an argued
    # verdict a human can accept or reject.
    ("verdict_text", "string"),
    ("verdict_kind", "string"),    # false-positive | by-design | unprovable  (see the verdict stage)
    ("svace_checker", "string"),   # carried onto the artifact so verdicts group by checker
]


def cols(spec):
    return {"column": [{"name": n, "type": t} for n, t in spec]}


def dt_node(name, table, spec, x):
    return {
        "parameters": {
            "resource": "table",
            "operation": "create",
            "tableName": table,
            "columns": cols(spec),
            "options": {"createIfNotExists": True},
        },
        "id": f"dt-{table}",
        "name": name,
        "type": "n8n-nodes-base.dataTable",
        "typeVersion": 1.1,
        "position": [x, 300],
    }


nodes = [
    {
        "parameters": {"httpMethod": "POST", "path": "setup", "responseMode": "onReceived", "options": {}},
        "id": "wh", "name": "Webhook", "type": "n8n-nodes-base.webhook", "typeVersion": 2,
        "position": [0, 300], "webhookId": "fsmsetuphook01",
    },
    dt_node("Create suspicions", "suspicions", SUSPICIONS, 240),
    dt_node("Create bugs", "bugs", BUGS, 480),
    dt_node("Create scan_files", "scan_files", SCAN_FILES, 720),
    dt_node("Create method_runs", "method_runs", METHOD_RUNS, 960),
]

conns = {
    "Webhook": {"main": [[{"node": "Create suspicions", "type": "main", "index": 0}]]},
    "Create suspicions": {"main": [[{"node": "Create bugs", "type": "main", "index": 0}]]},
    "Create bugs": {"main": [[{"node": "Create scan_files", "type": "main", "index": 0}]]},
    "Create scan_files": {"main": [[{"node": "Create method_runs", "type": "main", "index": 0}]]},
}

wf = {
    "id": "fsmsetup00000001",
    "name": "fsm-setup",
    "active": False,
    "nodes": nodes,
    "connections": conns,
    "settings": {"executionOrder": "v1"},
}
# Write on RUN only, for the same reason as the other generators (see gen_prover.py).
if __name__ == "__main__":
    open("workflow_setup.json", "w").write(json.dumps(wf, indent=2))
    print(f"wrote workflow_setup.json — suspicions({len(SUSPICIONS)} cols) + bugs({len(BUGS)} cols)")
