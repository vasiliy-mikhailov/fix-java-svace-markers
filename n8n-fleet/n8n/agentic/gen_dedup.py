#!/usr/bin/env python3
"""fjb-dedup: the DEDUPLICATION stage — sits between the suspector and the reproducer.

POST /webhook/dedup { repo?, limit? }

Why this is its own stage: the suspector's upsert-time dedup only compared each new candidate against
rows ALREADY in the table, never against the other candidates in the same batch. With 5 methods of a
class analyzed concurrently, three of them reporting the same "missing defensive copy" defect all
landed as distinct suspicions — and each then burned a full reproducer+fixer cycle on the same bug.

This stage clusters the whole `new` backlog per file and keeps ONE canonical suspicion per cluster.
Two invariants:
  * it NEVER deletes a row — a duplicate is marked status='duplicate' with a pointer to its canonical,
    so nothing is destroyed and a wrong merge is always inspectable and reversible;
  * it FAILS OPEN — if the LLM call fails or answers unusably, every suspicion is kept. A duplicate
    costs one wasted prove cycle; a silently dropped finding is a bug we never find again.
"""
import json

from versions import DEDUP_VERSION, PIPELINE_VERSION, short_id, stamp

SUSPICIONS_TABLE = "o17lTGMNGX0RI8wY"

CLUSTER = r"""
const VER = __DEDUPVER__;
const rows = $input.all().map(i => i.json).filter(r => r && r.dedup_key && r.title);
if (!rows.length) return [];

// Group by file: two suspicions are duplicates of one defect essentially only when they live in the
// same file, and grouping keeps each LLM prompt small enough to stay accurate.
const byFile = new Map();
for (const r of rows) {
  const k = (r.file || '') + '';
  if (!byFile.has(k)) byFile.set(k, []);
  byFile.get(k).push(r);
}

const norm = s => (s == null ? '' : s).toString().toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();

async function clusterFile(list) {
  // --- pass 1 (free, exact): identical normalized title in the same file is always the same defect.
  const out = [];            // {row, action:'keep'|'duplicate', canonical, reason}
  const byTitle = new Map();
  const remaining = [];
  for (const r of list) {
    const k = norm(r.title);
    if (byTitle.has(k)) {
      out.push({ row: r, action: 'duplicate', canonical: byTitle.get(k), reason: 'identical title in the same file' });
    } else {
      byTitle.set(k, r);
      remaining.push(r);
    }
  }
  if (remaining.length < 2) {
    remaining.forEach(r => out.push({ row: r, action: 'keep' }));
    return out;
  }

  // --- pass 2 (LLM): same ROOT CAUSE, worded differently. Conservative on purpose.
  const listing = remaining.map((r, i) =>
    `S${i}: [${r.severity}/${r.category}] ${r.method}() — ${r.title}\n     ${(r.description || '').slice(0, 400)}`
  ).join('\n');
  const prompt =
    "You are deduplicating suspected Java defects that were reported INDEPENDENTLY by per-method "
    + "analysis of one file. Several methods often report the SAME underlying defect from different "
    + "angles.\n\nFILE: " + (remaining[0].file || '') + "\n\nSUSPICIONS:\n" + listing + "\n\n"
    + "Group entries that are the SAME underlying defect — meaning ONE code change would resolve all of "
    + "them (same root cause, same place in the code). Do NOT group entries that merely share a theme, a "
    + "category, or a class: two different fields each missing their own null check are TWO defects, and "
    + "a missing null check is not the same defect as a missing bounds check. When in doubt, keep them "
    + "SEPARATE — a wrongly merged defect is never investigated again.\n\n"
    + "For each group of 2+, pick as canonical the entry with the most precise, most actionable "
    + "description. Reply ONLY JSON:\n"
    + '{"groups":[{"canonical":<index>,"duplicates":[<index>,...],"reason":"why these are one defect"}]}\n'
    + "Entries not in any group are kept automatically — omit them.";

  let groups = [];
  try {
    const resp = await this.helpers.httpRequest({
      method: 'POST', url: $env.QWEN_BASE_URL + '/chat/completions',
      headers: { Authorization: 'Bearer ' + $env.QWEN_API_KEY, 'Content-Type': 'application/json', Connection: 'close' },
      body: { model: $env.QWEN_MODEL, messages: [{ role: 'user', content: prompt }], temperature: 0, max_tokens: 32000 },
      json: true, timeout: 3600000,
    });
    const m = (resp.choices && resp.choices[0] && resp.choices[0].message) || {};
    const t = ((m.content || m.reasoning_content) || '') + '';
    const a = t.indexOf('{'), b = t.lastIndexOf('}');
    if (a >= 0 && b > a) {
      const jj = JSON.parse(t.slice(a, b + 1));
      if (Array.isArray(jj.groups)) groups = jj.groups;
    }
  } catch (e) { groups = []; }   // FAIL OPEN: keep everything rather than lose a finding

  const claimed = new Set();
  for (const g of groups) {
    const ci = Number(g && g.canonical);
    const can = remaining[ci];
    if (!can || claimed.has(ci)) continue;
    const dups = Array.isArray(g.duplicates) ? g.duplicates : [];
    for (const di of dups) {
      const d = remaining[Number(di)];
      if (!d || Number(di) === ci || claimed.has(Number(di))) continue;
      claimed.add(Number(di));
      out.push({ row: d, action: 'duplicate', canonical: can, reason: (g.reason || 'same underlying defect') + '' });
    }
  }
  remaining.forEach((r, i) => { if (!claimed.has(i)) out.push({ row: r, action: 'keep' }); });
  return out;
}

// bounded concurrency over files; one bad file must not sink the stage
const files = [...byFile.values()];
const CONC = 4;
const verdicts = [];
for (let i = 0; i < files.length; i += CONC) {
  const settled = await Promise.allSettled(files.slice(i, i + CONC).map(f => clusterFile(f)));
  settled.forEach((s, k) => {
    if (s.status === 'fulfilled') verdicts.push(...s.value);
    else files[i + k].forEach(r => verdicts.push({ row: r, action: 'keep' }));   // fail open
  });
}

// how many duplicates each canonical absorbed, so the kept row says so on the dashboard
const merged = new Map();
for (const v of verdicts) {
  if (v.action !== 'duplicate') continue;
  const k = v.canonical.dedup_key;
  merged.set(k, (merged.get(k) || 0) + 1);
}

// Only rows whose state actually changes are written back.
const out = [];
for (const v of verdicts) {
  if (v.action === 'duplicate') {
    out.push({ json: {
      dedup_key: v.row.dedup_key, status: 'duplicate',
      note: '[' + VER + '] duplicate of ' + v.canonical.method + '() — ' + v.canonical.title
            + ' · ' + (v.reason || ''),
    }});
  } else {
    const n = merged.get(v.row.dedup_key) || 0;
    if (n) out.push({ json: {
      dedup_key: v.row.dedup_key, status: 'new',
      note: '[' + VER + '] canonical — absorbed ' + n + ' duplicate' + (n === 1 ? '' : 's'),
    }});
  }
}
return out;
"""

CLUSTER = CLUSTER.replace("__DEDUPVER__", json.dumps(short_id(DEDUP_VERSION)))

N = []


def node(name, ntype, params, tv, x, y=300, extra=None):
    n = {"parameters": params, "id": f"d{len(N)+1}", "name": name, "type": ntype,
         "typeVersion": tv, "position": [x, y]}
    if extra:
        n.update(extra)
    N.append(n)
    return name


def dt():
    return {"__rl": True, "mode": "list", "value": SUSPICIONS_TABLE, "cachedResultName": "suspicions"}


node("Dedup webhook", "n8n-nodes-base.webhook",
     {"httpMethod": "POST", "path": "dedup", "responseMode": "lastNode", "options": {}},
     2, 0, extra={"webhookId": "fjbdeduphook01"})
# only rows still awaiting proof: already-proven or already-duplicate rows must not be re-judged
node("Get new suspicions", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "get", "dataTableId": dt(),
      "filters": {"conditions": [{"keyName": "status", "condition": "eq", "keyValue": "new"}]},
      "returnAll": True, "options": {}}, 1.1, 240, extra={"alwaysOutputData": True})
node("Cluster duplicates", "n8n-nodes-base.code",
     {"mode": "runOnceForAllItems", "jsCode": CLUSTER.strip()}, 2, 480,
     extra={"onError": "continueRegularOutput"})
node("Apply verdict", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "update", "dataTableId": dt(),
      "filters": {"conditions": [{"keyName": "dedup_key", "condition": "eq",
                                  "keyValue": "={{ $json.dedup_key }}"}]},
      "columns": {"mappingMode": "defineBelow",
                  "value": {"status": "={{ $json.status }}", "note": "={{ $json.note }}"},
                  "matchingColumns": ["dedup_key"], "schema": [],
                  "attemptToConvertTypes": False, "convertFieldsToString": True},
      "options": {}}, 1.1, 720)
node("Done", "n8n-nodes-base.noOp", {}, 1, 960)

conns = {
    "Dedup webhook":      {"main": [[{"node": "Get new suspicions", "type": "main", "index": 0}]]},
    "Get new suspicions": {"main": [[{"node": "Cluster duplicates", "type": "main", "index": 0}]]},
    "Cluster duplicates": {"main": [[{"node": "Apply verdict", "type": "main", "index": 0}]]},
    "Apply verdict":      {"main": [[{"node": "Done", "type": "main", "index": 0}]]},
}

wf = {"id": "fjbdedup000001", "name": "fjb-dedup", "active": False,
      "nodes": N, "connections": conns, "settings": {"executionOrder": "v1"}}
open("workflow_dedup.json", "w").write(json.dumps(wf, indent=2))
print(f"wrote workflow_dedup.json — {len(N)} nodes ({stamp(DEDUP_VERSION)})")
