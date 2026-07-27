#!/usr/bin/env python3
"""Generate fjb-setup: creates the `suspicions` and `bugs` n8n Data Tables (idempotent)."""
import json

SUSPICIONS = [
    ("dedup_key", "string"),   # repo|file|method|category (normalized) -> dedup / upsert key
    ("repo", "string"),
    ("file", "string"),
    ("class_name", "string"),
    ("method", "string"),
    ("line", "number"),
    ("category", "string"),     # correctness|npe|resource-leak|contract-violation|data-corruption
    ("severity", "string"),     # high|medium|low
    ("title", "string"),
    ("description", "string"),
    ("evidence", "string"),     # concrete input -> wrong output
    ("status", "string"),       # new|reproducing|reproduced|fixed|verified|rejected|pr_ready
    ("note", "string"),         # free-form progress / rejection reason
    ("version", "string"),      # suspector version that produced this finding (see versions.py)
    ("method_key", "string"),   # the method_runs row that produced it (transcript lookup key)
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
    ("state", "string"),           # verified|pr_ready|pr_open|rejected
    ("versions", "string"),        # JSON: pipeline + suspector/reproducer/fixer/pr_maker versions
    ("infra_reason", "string"),    # why state=infra_error (never a verdict about the code)
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
        "position": [0, 300], "webhookId": "fjbsetuphook01",
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
    "id": "fjbsetup00000001",
    "name": "fjb-setup",
    "active": False,
    "nodes": nodes,
    "connections": conns,
    "settings": {"executionOrder": "v1"},
}
open("workflow_setup.json", "w").write(json.dumps(wf, indent=2))
print(f"wrote workflow_setup.json — suspicions({len(SUSPICIONS)} cols) + bugs({len(BUGS)} cols)")
