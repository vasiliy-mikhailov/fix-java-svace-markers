#!/usr/bin/env python3
"""fsm-orchestrator: PLAN then PROCESS.

POST /webhook/scan { repo, maxFiles?, pathFilter? }
Phase 1 (plan): list all src/main/java files, fetch each (batched) and count its substantive
methods, write the full worklist to the `scan_files` Data Table as `queued`. So the dashboard
shows the correct total files + total methods + ETA from the start.
Phase 2 (process): loop the worklist one file at a time, call the suspector, mark it `done`.
"""
import json

SCAN_FILES_TABLE = "n4HxltR5dNFds1wb"

PICK = r"""
const cfg = $('Config').first().json;
const branch = $('Repo info').first().json.default_branch;
const tree = ($input.first().json.tree) || [];
const bad = ['/test/', '/tests/', '/generated/', '/target/', 'package-info'];
const pf = (cfg.pathFilter || '').toString();
const picked = tree.filter(t =>
  t.type === 'blob' &&
  (t.path.includes('/src/main/java/') || t.path.startsWith('src/main/java/')) &&
  t.path.endsWith('.java') &&
  !bad.some(b => t.path.toLowerCase().includes(b)) &&
  (!pf || t.path.includes(pf))
).slice(0, cfg.maxFiles);
return picked.map(t => ({ json: { repo: cfg.repo, branch, file: t.path } }));
"""

# Phase-1 planner: fetch every file (batched-parallel) + count substantive methods -> worklist rows.
PLAN = r"""
const files = $('Pick files').all().map(i => i.json);
const tok = $env.GITHUB_TOKEN;
const run_id = '' + Date.now();

function extractMethods(src, file) {
  const s = src
    .replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, m => ' '.repeat(m.length))
    .replace(/"(\\.|[^"\\\n])*"/g, m => '"' + ' '.repeat(Math.max(0, m.length - 2)) + '"')
    .replace(/'(\\.|[^'\\\n])*'/g, m => "'" + ' '.repeat(Math.max(0, m.length - 2)) + "'");
  const cm = s.match(/\b(?:class|interface|enum|record)\s+([A-Za-z_]\w*)/);
  const className = cm ? cm[1] : (file.split('/').pop() || 'X').replace('.java', '');
  const skip = new Set(['if','for','while','switch','catch','synchronized','return','new','else','do','try']);
  const sigRe = /(?:^|\n)([ \t]*(?:@[\w.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default)[ \t\n]+)*[\w.<>\[\],?&\s]+?\s([A-Za-z_]\w*)\s*\([^;{)]*\)\s*(?:throws[\w.,\s]+)?)\{/g;
  const methods = []; let m;
  while ((m = sigRe.exec(s)) !== null) {
    const name = m[2]; if (skip.has(name)) continue;
    const braceAt = sigRe.lastIndex - 1; let depth = 0, end = -1;
    for (let i = braceAt; i < s.length; i++) { if (s[i]==='{') depth++; else if (s[i]==='}'){depth--; if(depth===0){end=i+1;break;}} }
    if (end < 0) continue;
    methods.push({ name, src: src.slice(m.index, end) });
    sigRe.lastIndex = end;
  }
  return { className, methods };
}
const CONTRACT = new Set(['equals','hashCode','compareTo','iterator','close','clone','readObject','writeObject','readResolve']);
function substantive(name, msrc) {
  if (CONTRACT.has(name)) return true;
  const body = msrc.slice(msrc.indexOf('{') + 1, msrc.lastIndexOf('}'));
  if (/\b(if|for|while|switch|catch|instanceof|new |throw)\b|&&|\|\||==|!=|[+\-*/%]=?/.test(body)) return true;
  if (body.replace(/\s/g, '').length > 120) return true;
  return false;
}

const out = [];
const B = 25;
for (let i = 0; i < files.length; i += B) {
  const batch = files.slice(i, i + B);
  const rs = await Promise.all(batch.map(async f => {
    let methods = 1, className = '';
    try {
      const r = await this.helpers.httpRequest({
        url: 'https://api.github.com/repos/' + f.repo + '/contents/' + f.file + '?ref=' + f.branch,
        headers: { 'User-Agent': 'n8n-fsm', Accept: 'application/vnd.github+json', Authorization: 'Bearer ' + tok, Connection: 'close' },
        json: true, timeout: 30000,
      });
      const src = Buffer.from((r.content || '').replace(/\s/g, ''), 'base64').toString('utf8');
      const ex = extractMethods(src, f.file);
      className = ex.className;
      const subst = ex.methods.filter(m => substantive(m.name, m.src)).length;
      methods = subst;   // 0 substantive (e.g. an interface) = 0 units: it will be skipped
    } catch (e) { methods = 1; }
    // module = the path before src/main/java; empty string for a root-level (single-module) repo
    const _mod = f.file.includes('/src/main/java/') ? f.file.split('/src/main/java/')[0] : '';
    return { file: f.file, repo: f.repo, module: _mod,
             class_name: className, methods, status: 'queued', run_id };
  }));
  out.push(...rs);
}
return out.map(json => ({ json }));
"""

N = []
def node(name, ntype, params, tv, x, y=300, extra=None):
    n = {"parameters": params, "id": f"o{len(N)+1}", "name": name, "type": ntype,
         "typeVersion": tv, "position": [x, y]}
    if extra: n.update(extra)
    N.append(n)
    return name

GH = {"parameters": [
    {"name": "User-Agent", "value": "n8n-fsm"},
    {"name": "Accept", "value": "application/vnd.github+json"},
    {"name": "Authorization", "value": "=Bearer {{ $env.GITHUB_TOKEN }}"},
]}
def ghget(url):
    return {"url": url, "sendHeaders": True, "headerParameters": GH, "options": {}}

def sf(): return {"__rl": True, "mode": "list", "value": SCAN_FILES_TABLE, "cachedResultName": "scan_files"}

# --- clean slate on every re-run: wipe the previous run's rows so the dashboard never mixes runs.
# deleteRows requires >=1 condition, so `repo isNotEmpty` is the match-all (every row carries a repo).
# deleteRows emits one item PER DELETED ROW, so every clear node (and Config) is executeOnce -> the
# downstream chain still sees exactly one item.
# `bugs` is cleared too: every bug row points at a suspicion_key from the run that produced it, so
# keeping bugs while wiping suspicions leaves orphaned rows that contradict the fresh scan on screen.
SUSPICIONS_TABLE = "o17lTGMNGX0RI8wY"
METHOD_RUNS_TABLE = "HsKuoBC5FPT0iJNh"
BUGS_TABLE = "tO8quypeTL29rLAE"
def dtref(tid, nm): return {"__rl": True, "mode": "list", "value": tid, "cachedResultName": nm}
def clear_node(label, tid, nm, x):
    # match-all = (repo isNotEmpty OR repo isEmpty) under matchType=anyCondition; the isEmpty half is
    # what catches half-written rows whose columns are all NULL — isNotEmpty alone leaves them behind.
    return node(label, "n8n-nodes-base.dataTable",
                {"resource": "row", "operation": "deleteRows", "dataTableId": dtref(tid, nm),
                 "matchType": "anyCondition",
                 "filters": {"conditions": [{"keyName": "repo", "condition": "isNotEmpty", "keyValue": ""},
                                            {"keyName": "repo", "condition": "isEmpty", "keyValue": ""}]},
                 "options": {}}, 1.1, x, y=140,
                extra={"onError": "continueRegularOutput", "executeOnce": True, "alwaysOutputData": True})

node("Scan webhook", "n8n-nodes-base.webhook",
     {"httpMethod": "POST", "path": "scan", "responseMode": "lastNode", "options": {}},
     2, 0, extra={"webhookId": "fsmscanhook001"})
# clean slate BEFORE planning: prior-run files / suspicions / method dialogs / live transcripts
clear_node("Clear files", SCAN_FILES_TABLE, "scan_files", 200)
clear_node("Clear suspicions", SUSPICIONS_TABLE, "suspicions", 400)
clear_node("Clear methods", METHOD_RUNS_TABLE, "method_runs", 600)
clear_node("Clear bugs", BUGS_TABLE, "bugs", 700)
node("Clear live", "n8n-nodes-base.httpRequest", {
    "method": "POST", "url": "http://fsm-java-runner:8090/live/clear",
    "sendBody": True, "contentType": "json", "specifyBody": "json", "jsonBody": "={{ JSON.stringify({}) }}",
    "options": {"timeout": 30000}}, 4.2, 900, y=140,
    extra={"onError": "continueRegularOutput", "executeOnce": True, "alwaysOutputData": True})
# Config sits AFTER the clears, so it reads the webhook node by name (not $json).
node("Config", "n8n-nodes-base.set", {"assignments": {"assignments": [
    {"id": "r", "name": "repo", "value": "={{ $('Scan webhook').first().json.body.repo }}", "type": "string"},
    {"id": "m", "name": "maxFiles", "value": "={{ $('Scan webhook').first().json.body.maxFiles || 100000 }}", "type": "number"},
    {"id": "p", "name": "pathFilter", "value": "={{ $('Scan webhook').first().json.body.pathFilter || '' }}", "type": "string"},
]}, "options": {}}, 3.4, 1000, extra={"executeOnce": True, "alwaysOutputData": True})
node("Repo info", "n8n-nodes-base.httpRequest",
     ghget("=https://api.github.com/repos/{{ $json.repo }}"), 4.2, 1200)
node("List tree", "n8n-nodes-base.httpRequest",
     ghget("=https://api.github.com/repos/{{ $('Config').item.json.repo }}/git/trees/{{ $json.default_branch }}?recursive=1"),
     4.2, 1400)
node("Pick files", "n8n-nodes-base.code", {"jsCode": PICK.strip()}, 2, 1600)
node("Plan methods", "n8n-nodes-base.code", {"jsCode": PLAN.strip()}, 2, 1800)
node("Insert worklist", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "upsert", "dataTableId": sf(),
      "filters": {"conditions": [{"keyName": "file", "condition": "eq", "keyValue": "={{ $json.file }}"}]},
      "columns": {"mappingMode": "autoMapInputData", "value": {}, "matchingColumns": [],
                  "schema": [], "attemptToConvertTypes": False, "convertFieldsToString": True},
      "options": {}}, 1.1, 2000)
node("Loop over files", "n8n-nodes-base.splitInBatches", {"batchSize": 1, "options": {}}, 3, 2220)
node("Call suspector", "n8n-nodes-base.httpRequest", {
    "method": "POST", "url": "http://127.0.0.1:5678/webhook/suspect",
    "sendBody": True, "contentType": "json", "specifyBody": "json",
    "jsonBody": "={{ JSON.stringify({repo: $json.repo, branch: $('Repo info').first().json.default_branch, file: $json.file}) }}",
    "sendHeaders": True, "headerParameters": {"parameters": [{"name": "Connection", "value": "close"}]},
    "options": {"timeout": 7200000},   # 2h: unlimited per-method investigation + class audit can run long
}, 4.2, 2440, y=200, extra={"onError": "continueRegularOutput"})
node("Mark done", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "update", "dataTableId": sf(),
      "filters": {"conditions": [{"keyName": "file", "condition": "eq",
                                  "keyValue": "={{ $('Loop over files').item.json.file }}"}]},
      "columns": {"mappingMode": "defineBelow", "value": {"status": "done"},
                  "matchingColumns": ["file"], "schema": [], "attemptToConvertTypes": False,
                  "convertFieldsToString": True}, "options": {}}, 1.1, 2660, y=200),
# scan finished -> run the DEDUP stage over the whole `new` backlog before anything is proven.
# Per-file dedup cannot see duplicates reported by sibling methods in the same batch, so this repo-wide
# pass is what keeps the reproducer from proving the same defect three times.
node("Call dedup", "n8n-nodes-base.httpRequest", {
    "method": "POST", "url": "http://127.0.0.1:5678/webhook/dedup",
    "sendBody": True, "contentType": "json", "specifyBody": "json", "jsonBody": "={{ JSON.stringify({}) }}",
    "sendHeaders": True, "headerParameters": {"parameters": [{"name": "Connection", "value": "close"}]},
    "options": {"timeout": 3600000}}, 4.2, 2440, y=420,
    extra={"onError": "continueRegularOutput"})
node("Done", "n8n-nodes-base.noOp", {}, 1, 2660, y=420)

conns = {
    "Scan webhook":    {"main": [[{"node": "Clear files", "type": "main", "index": 0}]]},
    "Clear files":     {"main": [[{"node": "Clear suspicions", "type": "main", "index": 0}]]},
    "Clear suspicions":{"main": [[{"node": "Clear methods", "type": "main", "index": 0}]]},
    "Clear methods":   {"main": [[{"node": "Clear bugs", "type": "main", "index": 0}]]},
    "Clear bugs":      {"main": [[{"node": "Clear live", "type": "main", "index": 0}]]},
    "Clear live":      {"main": [[{"node": "Config", "type": "main", "index": 0}]]},
    "Config":          {"main": [[{"node": "Repo info", "type": "main", "index": 0}]]},
    "Repo info":       {"main": [[{"node": "List tree", "type": "main", "index": 0}]]},
    "List tree":       {"main": [[{"node": "Pick files", "type": "main", "index": 0}]]},
    "Pick files":      {"main": [[{"node": "Plan methods", "type": "main", "index": 0}]]},
    "Plan methods":    {"main": [[{"node": "Insert worklist", "type": "main", "index": 0}]]},
    "Insert worklist": {"main": [[{"node": "Loop over files", "type": "main", "index": 0}]]},
    "Loop over files": {"main": [[{"node": "Call dedup", "type": "main", "index": 0}],
                                 [{"node": "Call suspector", "type": "main", "index": 0}]]},
    "Call dedup":      {"main": [[{"node": "Done", "type": "main", "index": 0}]]},
    "Call suspector":  {"main": [[{"node": "Mark done", "type": "main", "index": 0}]]},
    "Mark done":       {"main": [[{"node": "Loop over files", "type": "main", "index": 0}]]},
}

wf = {"id": "fsmorchestr0001", "name": "fsm-orchestrator", "active": False,
      "nodes": N, "connections": conns, "settings": {"executionOrder": "v1"}}
open("workflow_orchestrator.json", "w").write(json.dumps(wf, indent=2))
print(f"wrote workflow_orchestrator.json — {len(N)} nodes (plan + process)")
