#!/usr/bin/env python3
"""Generate the fully node-based single-repo n8n pipeline workflow (fjb-pipeline)."""
import json

# ---- Code-node bodies (the "steps pulled out of python", now visible n8n nodes) ----

PICK_FILES = r"""
const cfg = $('Config').first().json;
const branch = $('Repo info').first().json.default_branch;
const tree = ($input.first().json.tree) || [];
const bad = ['/test/', '/tests/', '/generated/'];
const picked = tree.filter(t =>
  t.type === 'blob' &&
  t.path.includes('/src/main/java/') &&
  t.path.endsWith('.java') &&
  !bad.some(b => t.path.toLowerCase().includes(b))
).slice(0, cfg.maxFiles);
return picked.map(t => ({ json: { repo: cfg.repo, branch, path: t.path } }));
"""


def extract_json_js(var):
    return (f"const _a = {var}.indexOf('{{'); const _b = {var}.lastIndexOf('}}');\n"
            f"const _s = (_a >= 0 && _b > _a) ? {var}.slice(_a, _b + 1) : null;")

BUILD_FIND = r"""
const path = $('Loop over files').item.json.path;
const repo = $('Config').first().json.repo;
let src = ($json.data || '').toString();
if (src.length > 6000) src = src.slice(0, 6000);
const prompt =
"You are a careful Java bug finder. Analyze this ONE file for a genuine, user-reachable bug " +
"(correctness / data-corruption / resource-or-credential-leak / API-contract violation). " +
"Ignore style, naming and hypothetical misuse.\n" +
"Reply with ONLY a JSON object: " +
"{\"has_bug\": true|false, \"line\": <int>, \"severity\": \"high|medium|low\", " +
"\"category\": \"correctness|data-corruption|resource-leak|contract-violation|npe\", " +
"\"bug\": \"<one sentence>\", \"why\": \"<how it fails: concrete input -> wrong output>\", " +
"\"fix\": \"<the minimal one-line fix>\"}\n" +
"If there is no real bug, reply {\"has_bug\": false}.\n\n" +
"FILE: " + path + "\n```java\n" + src + "\n```";
return { repo, path, body: {
  model: $env.QWEN_MODEL,
  messages: [{ role: 'user', content: prompt }],
  temperature: 0.2, max_tokens: 5000,
  enable_thinking: true, thinking_budget: 1024
}};
"""

PARSE_FIND = r"""
const path = $('Build FIND request').item.json.path;
const repo = $('Build FIND request').item.json.repo;
const msg = ($json.choices && $json.choices[0] && $json.choices[0].message) || {};
const text = msg.content || msg.reasoning_content || '';
const a = text.indexOf('{'); const b = text.lastIndexOf('}');
const s = (a >= 0 && b > a) ? text.slice(a, b + 1) : null;
let j = null;
try { j = s ? JSON.parse(s) : null; } catch (e) { j = null; }
if (!j || !j.has_bug) return { repo, path, has_bug: false };
return { repo, path, has_bug: true, line: j.line, severity: j.severity,
         category: j.category, bug: j.bug, why: j.why, fix: j.fix };
"""

TOP_FINDINGS = r"""
const cfg = $('Config').first().json;
const order = { high: 0, medium: 1, low: 2 };
const items = $input.all().map(i => i.json);
items.sort((a, b) => (order[a.severity] ?? 3) - (order[b.severity] ?? 3));
return items.slice(0, cfg.proveTop).map(json => ({ json }));
"""

BUILD_PROVE = r"""
const f = $json;
const prompt =
"A suspected bug was found in " + f.path + ":\n" + f.bug + "\nwhy: " + (f.why || '') + "\n\n" +
"Reply with ONLY JSON: {\"test\": \"a self-contained JUnit5 test method that FAILS on the " +
"current code and PASSES once fixed (include the exact assertion)\", " +
"\"fix\": \"the minimal code change as a precise before/after\"}";
return { ...f, body: {
  model: $env.QWEN_MODEL,
  messages: [{ role: 'user', content: prompt }],
  temperature: 0.2, max_tokens: 8000,
  enable_thinking: true, thinking_budget: 1024
}};
"""

PARSE_PROVE = r"""
const f = $('Build PROVE request').item.json;
const msg = ($json.choices && $json.choices[0] && $json.choices[0].message) || {};
const text = msg.content || msg.reasoning_content || '';
const a = text.indexOf('{'); const b = text.lastIndexOf('}');
const s = (a >= 0 && b > a) ? text.slice(a, b + 1) : null;
let j = {};
try { j = s ? JSON.parse(s) : {}; } catch (e) { j = {}; }
return { repo: f.repo, path: f.path, line: f.line, severity: f.severity, category: f.category,
         bug: f.bug, why: f.why, proposed_test: j.test || '', proposed_fix: j.fix || '' };
"""

AGGREGATE = r"""
const cfg = $('Config').first().json;
const all = $input.all().map(i => i.json);
const order = { high: 0, medium: 1, low: 2 };
const findings = all.filter(f => f.has_bug)
  .sort((a, b) => (order[a.severity] ?? 3) - (order[b.severity] ?? 3));
const out = { repo: cfg.repo, scanned: all.length, count: findings.length, findings };
const fname = cfg.repo.replace('/', '__') + '.json';
const b64 = Buffer.from(JSON.stringify(out, null, 2)).toString('base64');
return [{ json: out, binary: { data: { data: b64, mimeType: 'application/json', fileName: fname } } }];
"""

GH_HEADERS = {"parameters": [
    {"name": "User-Agent", "value": "n8n-fjb"},
    {"name": "Accept", "value": "application/vnd.github+json"},
    {"name": "Authorization", "value": "=Bearer {{ $env.GITHUB_TOKEN }}"},
]}


def http_get(url, headers=None, text=False):
    p = {"url": url, "options": {}}
    if headers:
        p["sendHeaders"] = True
        p["headerParameters"] = headers
    if text:
        p["options"] = {"response": {"response": {"responseFormat": "text"}}}
    return p


def http_post_llm():
    return {
        "method": "POST",
        "url": "={{ $env.QWEN_BASE_URL }}/chat/completions",
        "sendHeaders": True,
        "headerParameters": {"parameters": [
            {"name": "Authorization", "value": "=Bearer {{ $env.QWEN_API_KEY }}"},
            {"name": "Content-Type", "value": "application/json"},
            # force a fresh socket each iteration: vLLM closes the idle keep-alive connection
            # between loop iterations, and reusing that dead socket hangs until timeout.
            {"name": "Connection", "value": "close"},
        ]},
        "sendBody": True,
        "contentType": "json",
        "specifyBody": "json",
        "jsonBody": "={{ JSON.stringify($json.body) }}",
        # serialization is handled by the Loop-over-files node (one file per iteration),
        # so this only ever sees a single request at a time — well under the stream timeout.
        "options": {"timeout": 280000},
    }


def code(js, per_item=False):
    return {"mode": "runOnceForEachItem" if per_item else "runOnceForAllItems", "jsCode": js.strip()}


N = []          # nodes
x = 0


def node(name, ntype, params, tv=1):
    global x
    N.append({
        "parameters": params,
        "id": f"node-{len(N)+1:02d}",
        "name": name,
        "type": ntype,
        "typeVersion": tv,
        "position": [x, 300],
    })
    x += 220
    return name


node("Webhook", "n8n-nodes-base.webhook",
     {"httpMethod": "POST", "path": "run", "responseMode": "onReceived", "options": {}}, 2)
node("Config", "n8n-nodes-base.set", {"assignments": {"assignments": [
    {"id": "c1", "name": "repo", "value": "={{ $json.body.repo || 'google/gson' }}", "type": "string"},
    {"id": "c2", "name": "maxFiles", "value": "={{ $json.body.maxFiles || 25 }}", "type": "number"},
    {"id": "c3", "name": "proveTop", "value": "={{ $json.body.proveTop || 5 }}", "type": "number"},
]}, "options": {}}, 3.4)
node("Repo info", "n8n-nodes-base.httpRequest",
     http_get("=https://api.github.com/repos/{{ $json.repo }}", GH_HEADERS), 4.2)
node("List tree", "n8n-nodes-base.httpRequest",
     http_get("=https://api.github.com/repos/{{ $('Config').item.json.repo }}/git/trees/{{ $json.default_branch }}?recursive=1", GH_HEADERS), 4.2)
node("Pick files", "n8n-nodes-base.code", code(PICK_FILES), 2)
# serialize: one file per iteration (the user asked for no parallelism). splitInBatches v3
# accumulates each loop result in `processedItems` and emits them all on the `done` output.
node("Loop over files", "n8n-nodes-base.splitInBatches", {"batchSize": 1, "options": {}}, 3)
node("Fetch content", "n8n-nodes-base.httpRequest",
     http_get("=https://raw.githubusercontent.com/{{ $json.repo }}/{{ $json.branch }}/{{ $json.path }}"), 4.2)
node("Build FIND request", "n8n-nodes-base.code", code(BUILD_FIND, per_item=True), 2)
node("FIND (vLLM)", "n8n-nodes-base.httpRequest", http_post_llm(), 4.2)
node("Parse FIND", "n8n-nodes-base.code", code(PARSE_FIND, per_item=True), 2)
node("Aggregate", "n8n-nodes-base.code", code(AGGREGATE), 2)
node("Write results", "n8n-nodes-base.readWriteFile", {
    "operation": "write",
    "fileName": "=/results/{{ $('Config').item.json.repo.replace('/', '__') }}.json",
    "dataPropertyName": "data",
    "options": {},
}, 1)

# the LLM calls are slow and the endpoint can drop a connection under load: retry them
for n in N:
    if n["name"] in ("FIND (vLLM)", "PROVE+FIX (vLLM)"):
        n["retryOnFail"] = True
        n["maxTries"] = 3
        n["waitBetweenTries"] = 5000
    if n["name"] == "Webhook":
        n["webhookId"] = "fjbrunhook0001"   # required for the production /webhook/run endpoint

def to(*nodes):
    return [{"node": n, "type": "main", "index": 0} for n in nodes]

# explicit topology: linear head, then a serial loop over files, then aggregate+write.
# splitInBatches has two outputs: [0]=done (all accumulated findings), [1]=loop (next file).
conns = {
    "Webhook":            {"main": [to("Config")]},
    "Config":             {"main": [to("Repo info")]},
    "Repo info":          {"main": [to("List tree")]},
    "List tree":          {"main": [to("Pick files")]},
    "Pick files":         {"main": [to("Loop over files")]},
    "Loop over files":    {"main": [to("Aggregate"), to("Fetch content")]},  # [0]=done, [1]=loop
    "Fetch content":      {"main": [to("Build FIND request")]},
    "Build FIND request": {"main": [to("FIND (vLLM)")]},
    "FIND (vLLM)":        {"main": [to("Parse FIND")]},
    "Parse FIND":         {"main": [to("Loop over files")]},                 # back to the loop
    "Aggregate":          {"main": [to("Write results")]},
}

wf = {
    "id": "fjbpipeline0001",
    "name": "fjb-pipeline",
    "active": False,
    "nodes": N,
    "connections": conns,
    "settings": {"executionOrder": "v1"},
}
open("workflow_pipeline.json", "w").write(json.dumps(wf, indent=2))
print(f"wrote workflow_pipeline.json — {len(N)} nodes")
