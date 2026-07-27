#!/usr/bin/env bash
# Deploy the generated workflows into the fsm n8n: import -> RE-ACTIVATE -> restart -> wait.
#
#   ./deploy.sh                       # all three
#   ./deploy.sh workflow_prover.json  # just one
#
# The re-activate step is not optional. `n8n import:workflow` DEACTIVATES the workflow it overwrites
# ("Deactivating workflow ..." in its own output), so a deploy that only imports leaves the prover
# switched off: its 60s schedule never fires, nothing drains the queue, and the symptom is simply that
# nothing happens — no error anywhere. That cost a 12-minute silent stall the first time.
#
# The restart is also required: n8n only picks up trigger changes on boot, and it warns as much
# ("Changes will not take effect if n8n is running"). Restarting KILLS any in-flight prove; Data Table
# rows survive, and a marker mid-prove returns to status='new' on the next tick.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
CONTAINER="${CONTAINER:-fsm-n8n}"
N8N_URL="${N8N_URL:-http://127.0.0.1:5680}"
# Workflows whose triggers must be live after a deploy. fsm-setup is one-shot but stays active so its
# webhook keeps answering; fsm-prover carries the schedule trigger that drains the queue.
ACTIVE_IDS="fsmsetup00000001 fsmingest000001 fsmprover00001"

FILES=("$@")
if [ ${#FILES[@]} -eq 0 ]; then
  FILES=(workflow_setup.json workflow_ingest.json workflow_prover.json)
fi

# PRE-FLIGHT: every dataTableId in the artifacts must be one of the ids in tables.py.
# A workflow pointing at a table that does not exist fails only at RUN time, one node in, with
# "Could not find the data table: '<id>'" — and because the prover's schedule just tries again a
# minute later, the queue looks alive while nothing is ever proven. Catch it before importing.
echo "==> checking table ids"
python3 - "${FILES[@]}" <<'PY'
import json, re, sys, os
here = os.path.dirname(os.path.abspath(sys.argv[0])) if sys.argv[0] else "."
sys.path.insert(0, os.getcwd())
import tables
valid = {tables.SUSPICIONS_TABLE, tables.BUGS_TABLE, tables.SCAN_FILES_TABLE, tables.METHOD_RUNS_TABLE}
bad = []
for path in sys.argv[1:]:
    if not os.path.exists(path):
        sys.exit("    missing artifact: %s (run the generator first)" % path)
    blob = open(path).read()
    for tid in set(re.findall(r'"dataTableId":\s*\{[^}]*?"value":\s*"([^"]+)"', blob)):
        if tid not in valid:
            bad.append("%s -> %s" % (path, tid))
if bad:
    sys.exit("    STALE/STUB TABLE IDS (regenerate with `python3 gen_<x>.py`):\n      "
             + "\n      ".join(bad))
print("    ok — all artifacts reference the live tables")
PY

for f in "${FILES[@]}"; do
  echo "==> importing $f"
  docker exec "$CONTAINER" n8n import:workflow --input="/data/n8n-fleet/n8n/agentic/$(basename "$f")" 2>&1 \
    | grep -viE '^\s*$' | sed 's/^/    /'
done

echo "==> re-activating (import turns workflows off)"
for id in $ACTIVE_IDS; do
  docker exec "$CONTAINER" n8n update:workflow --id="$id" --active=true 2>&1 \
    | grep -viE 'deprecated|^\s*$' | sed 's/^/    /' || true
done

echo "==> restarting $CONTAINER"
docker restart "$CONTAINER" >/dev/null

echo -n "==> waiting for n8n"
for _ in $(seq 1 60); do
  sleep 3
  if curl -fsS -o /dev/null "$N8N_URL/healthz" 2>/dev/null; then echo " ready"; break; fi
  echo -n "."
done

echo "==> active flags now:"
docker exec "$CONTAINER" n8n list:workflow 2>/dev/null | sed 's/^/    /' || true
