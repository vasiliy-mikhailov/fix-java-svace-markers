#!/usr/bin/env python3
"""Write verdicts for markers that were settled BEFORE the verdict stage covered every outcome.

The verdicts table is meant to be the one place a reviewer reads to know where all 282 markers landed.
Markers settled earlier only got a verdict if they failed to reproduce, so the table had a hole in it
exactly where the pipeline had done the most work — the proven fixes.

Every verdict written here is composed from data already stored on the artifact (state, test path,
JDK, PR title, failure reason), so nothing is re-run and no LLM is called. It uses gen_prover's
EXEC_VERDICT_FN verbatim rather than re-implementing the wording, so a backfilled row and a freshly
produced one are byte-identical and a reviewer cannot be misled about which wrote what.

    python3 backfill_verdicts.py --dry-run      # show what would change
    python3 backfill_verdicts.py                # apply

Runs from the workstation (node lives here, not on the host): rows are pulled over ssh, composed
locally, and applied with a single guarded UPDATE per row.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tables  # noqa: E402
import gen_prover  # noqa: E402

HOST = os.environ.get("FSM_HOST", "mh")
DB = os.environ.get("FSM_DB", "/home/vmihaylov/fix-java-svace-markers/n8n-fleet/n8n/n8n-data/database.sqlite")
BUGS = tables.BUGS_TABLE


def ssh(script):
    p = subprocess.run(["ssh", HOST, "python3 - <<'PYEOF'\n" + script + "\nPYEOF"],
                       capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit("remote failed:\n" + p.stderr[-2000:])
    return p.stdout


def fetch():
    return json.loads(ssh(f"""
import sqlite3, json
db = sqlite3.connect("file:{DB}?mode=ro", uri=True)
cols = ["id","state","test_path","jdk","pr_title","pr_body","infra_reason","value_score","verdict_text"]
rows = [dict(zip(cols, r)) for r in db.execute(
    "select " + ",".join(cols) + " from data_table_user_{BUGS}")]
print(json.dumps(rows))
"""))


def compose(rows):
    """Run EXEC_VERDICT_FN over the rows — the same code the live workflow uses."""
    js = gen_prover.EXEC_VERDICT_FN + """
const ROWS = JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8'));
process.stdout.write(JSON.stringify(ROWS.map(r => {
  const v = execVerdict(r.state, {
    test_path: r.test_path, jdk: r.jdk, pr_title: r.pr_title, pr_body: r.pr_body,
    infra_reason: r.infra_reason, attempts: 3, test_score: r.value_score,
  });
  return { id: r.id, kind: v.kind, text: v.text };
})));
"""
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(js)
        jsp = f.name
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(rows, f)
        jp = f.name
    try:
        p = subprocess.run(["node", jsp, jp], capture_output=True, text=True)
        if p.returncode != 0:
            raise SystemExit("composer failed:\n" + p.stderr[-2000:])
        return json.loads(p.stdout)
    finally:
        os.unlink(jsp)
        os.unlink(jp)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    rows = fetch()
    # Only ever FILL A HOLE. A row that already carries a verdict was argued by the model against the
    # real source; overwriting that with composed boilerplate would destroy the better artifact.
    todo = [r for r in rows if not (r.get("verdict_text") or "").strip()]
    print("artifacts: %d total, %d already have a verdict, %d to backfill"
          % (len(rows), len(rows) - len(todo), len(todo)))
    if not todo:
        return
    out = compose(todo)
    by_kind = {}
    for o in out:
        by_kind[o["kind"]] = by_kind.get(o["kind"], 0) + 1
    print("would write:", by_kind)
    print("\nsample:")
    for o in out[:2]:
        print("  [%s] %s\n" % (o["kind"], o["text"][:220]))
    if args.dry_run:
        print("dry run — nothing written")
        return

    payload = json.dumps(out)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        f.write(payload)
        pf = f.name
    try:
        subprocess.run(["scp", "-q", pf, f"{HOST}:/tmp/fsm_backfill.json"], check=True)
    finally:
        os.unlink(pf)
    # timeout + the same "only if still empty" guard, so a verdict written by the live prover between
    # the read and the write is never clobbered.
    print(ssh(f"""
import sqlite3, json
rows = json.load(open("/tmp/fsm_backfill.json"))
db = sqlite3.connect("{DB}", timeout=60)
n = 0
for r in rows:
    cur = db.execute(
        "update data_table_user_{BUGS} set verdict_text=?, verdict_kind=? "
        "where id=? and coalesce(verdict_text,'')=''", (r["text"], r["kind"], r["id"]))
    n += cur.rowcount
db.commit(); db.close()
print("backfilled %d artifact(s)" % n)
"""))


if __name__ == "__main__":
    main()
