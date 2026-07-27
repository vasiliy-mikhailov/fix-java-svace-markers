#!/bin/sh
# Maintain up to $MAX concurrent bugfind containers, draining $QUEUE one repo at a
# time. Idempotent and self-throttling: n8n calls this on a schedule; each call tops
# the running set back up to MAX. flock prevents two overlapping ticks from racing
# the queue file. POSIX sh so it runs under the hardened n8n image's busybox shell.
set -eu
DIR="$(cd "$(dirname "$0")" && pwd)"
QUEUE="${QUEUE:-$DIR/queue.txt}"
MAX="${MAX:-4}"
IMAGE="${IMAGE:-bugfind:latest}"
WORK="${WORK:-$HOME/fix-java-bugs}"
NET="${NET:-bridge}"          # needs GitHub (clone) + the vLLM endpoint reachable

exec 9>"$DIR/.dispatch.lock"
flock -n 9 || { echo "dispatch: already running, skip"; exit 0; }

set -a; [ -f "$DIR/.env" ] && . "$DIR/.env"; set +a   # QWEN_* + MAX_FILES/PROVE_TOP

running() { docker ps --filter "name=fsmn8n-" -q | wc -l | tr -d ' '; }
next_repo() { grep -m1 -vE '^[[:space:]]*(#|$)' "$QUEUE" || true; }

launched=0
while [ "$(running)" -lt "$MAX" ]; do
  repo="$(next_repo)"
  [ -z "$repo" ] && break
  # pop it from the queue
  grep -vxF "$repo" "$QUEUE" > "$QUEUE.tmp" && mv "$QUEUE.tmp" "$QUEUE"
  safe="fsmn8n-$(printf '%s' "$repo" | tr '/:' '--' | tr -cd 'A-Za-z0-9-')"
  docker rm -f "$safe" >/dev/null 2>&1 || true
  docker run -d --rm --name "$safe" --network "$NET" \
    -e QWEN_API_KEY -e QWEN_BASE_URL -e QWEN_MODEL -e MAX_FILES -e PROVE_TOP \
    -e THINK_BUDGET -e MAX_TOKENS -e WORK=/work \
    -v "$WORK":/work -w /work \
    "$IMAGE" "$repo" >/dev/null
  echo "dispatch: launched $safe ($repo)"
  launched=$((launched + 1))
done
left="$(grep -cvE '^[[:space:]]*(#|$)' "$QUEUE" 2>/dev/null || echo 0)"
echo "dispatch: launched=$launched running=$(running) queue_left=$left"
