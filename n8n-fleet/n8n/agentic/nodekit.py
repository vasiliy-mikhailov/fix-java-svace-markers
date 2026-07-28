#!/usr/bin/env python3
"""Run an n8n Code node's JavaScript under node, with the n8n runtime stubbed.

The pipeline's real logic lives in JS strings inside the generators. n8n executes each one with a
handful of injected globals ($json, $(), $env, this.helpers) and nothing else ever type-checks or
runs them, so a defect there surfaces only as a wrong row in a Data Table hours later. This makes
those strings directly callable from a test.

    from nodekit import run_node
    out = run_node(gen_prover.RECORD, json={...}, nodes={"Prep prover": {...}}, per_item=True)

`nodes` maps a node name to the json its `$('Name').item.json` should return. `env` maps $env values.
`http` supplies canned replies for this.helpers.httpRequest, in call order — a test that wants a real
LLM call passes `http=None` and sets a live QWEN_BASE_URL in `env` instead (see the model tests).
"""
import json
import os
import subprocess
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

# n8n wraps a Code node body in a function; `return` at top level is therefore legal. Async is allowed
# and several nodes await. The stub mirrors that shape exactly so the body under test is unmodified.
HARNESS = r"""
const CTX = JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8'));
const __calls = [];
function __proxy(name) {
  const j = CTX.nodes[name];
  if (j === undefined) throw new Error("test harness: no fixture for $('" + name + "')");
  return { item: { json: j }, first: () => ({ json: j }), all: () => [{ json: j }] };
}
const $ = __proxy;
const $json = CTX.json;
const $env = CTX.env || {};
const $input = { all: () => (CTX.input || []).map(j => ({ json: j })), first: () => ({ json: (CTX.input || [])[0] }) };
const console = { log: (...a) => __calls.push({ log: a.join(' ') }) };
let __httpIdx = 0;
const self = {
  helpers: {
    httpRequest: async (opts) => {
      __calls.push({ http: { url: opts.url, body: opts.body } });
      if (CTX.http === null || CTX.http === undefined) throw new Error('test harness: no http fixture');
      const r = CTX.http[Math.min(__httpIdx++, CTX.http.length - 1)];
      if (r && r.__throw) throw new Error(r.__throw);
      return r;
    },
  },
};
(async function () {
  const out = await (async function () {
%s
  }).call(self);
  require('fs').writeFileSync(process.argv[3], JSON.stringify({ out, calls: __calls }));
})().catch(e => {
  require('fs').writeFileSync(process.argv[3], JSON.stringify({ error: String(e && e.message || e) }));
  process.exit(3);
});
"""


def run_node(js, json=None, nodes=None, env=None, http=None, input=None, coverage_dir=None):
    """Execute a Code-node body. Returns its return value. Raises AssertionError on a thrown error."""
    ctx = {"json": json or {}, "nodes": nodes or {}, "env": env or {},
           "http": http, "input": input or []}
    return _run(HARNESS % js, ctx, coverage_dir)


def _run(script, ctx, coverage_dir=None):
    import json as _json
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(script)
        sp = f.name
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        _json.dump(ctx, f)
        cp = f.name
    op = cp + ".out"
    try:
        cmd = ["node", sp, cp, op]
        env = dict(os.environ)
        if coverage_dir:
            # c8 reads V8's own coverage; no instrumentation, so the body under test is byte-identical
            # to what n8n runs. Measuring a rewritten copy would measure the rewrite.
            env["NODE_V8_COVERAGE"] = coverage_dir
        p = subprocess.run(cmd, capture_output=True, text=True, env=env)
        if not os.path.exists(op):
            raise AssertionError("node produced no output\n" + (p.stderr or "")[-2000:])
        with open(op) as f:
            res = _json.load(f)
        if "error" in res:
            raise AssertionError("node body threw: " + res["error"])
        return res["out"], res["calls"]
    finally:
        for p_ in (sp, cp, op):
            try:
                os.unlink(p_)
            except OSError:
                pass
