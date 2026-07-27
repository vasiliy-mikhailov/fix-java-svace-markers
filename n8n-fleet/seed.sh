#!/usr/bin/env bash
# Seed the dispatch queue: synthetic (planted-bug) repos first, then the warm repos.
# Run once on mh before starting the dispatcher (or any time to refill).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HOME/fix-java-bugs}"
QUEUE="$DIR/queue.txt"

# 1) (re)generate the synthetic planted-bug repos under $WORK/data/repos/synthbench__*
( cd "$WORK" && python3 synth_bench.py ) || echo "seed: synth_bench.py skipped/failed (continuing)"

# 2) build the queue: synthetics first (fast validation vs the oracle), then warm repos
{
  for d in "$WORK"/data/repos/synthbench__*; do
    [ -d "$d" ] && echo "synth:$(basename "$d")"
  done
  grep -vE '^[[:space:]]*(#|$)' "$DIR/warm_repos.txt"
} > "$QUEUE"
echo "seed: $(grep -cvE '^[[:space:]]*(#|$)' "$QUEUE") repos -> $QUEUE"
