#!/usr/bin/env bash
#
# migrate-to-spring.sh — rename the LIVE fsm deployment on mh from
#
#     /home/vmihaylov/fix-java-svace-markers   ->   /home/vmihaylov/fix-java-svace-markers-spring
#
# without losing the one thing that cannot be recreated: the orchestrator's H2 store (282 markers,
# 53 artifacts, every drafted PR body, 6-26 hours of run per pass).
#
# THE STACK IS ONE SERVICE. deploy/docker-compose.yml declares `fsm` (container `fsm`): the schedule,
# the queue, the two tables, the dashboard AND the prover, in one process (fsm.runner.mode=local — the
# prove no longer crosses a socket). Beside it is `engine` (container `fsm-engine`), which is
# PROFILE-GATED: `docker compose up -d` does not start it, nothing in a run calls it, and it exists only
# to replay one stage by hand. The containers fsm-orchestrator and fsm-runner NO LONGER EXIST; n8n, the
# Node runner (fsm-java-runner) and the Node dashboard (fsm-dashboard) were deleted before them, and
# every step here that asked n8n's sqlite, edited n8n's idea of its own host, listed n8n's workflows or
# probed the Node runner went with them.
#
# SO THIS SCRIPT NO LONGER SPELLS CONTAINER NAMES OUT. It READS the services and their `container_name`
# from the compose file it is about to migrate — discover_topology(), called at the top of pre-flight —
# and everything below is aimed at what that returns. The previous revision hardcoded
# ORCH=fsm-orchestrator / RUNNER=fsm-runner, and on the day those two services merged into `fsm` every
# step aimed at them became a silent no-op, INCLUDING the pre-flight that refuses to migrate while a
# prove is in flight. Constants cannot notice a topology change; the compose file cannot miss one.
#
# THAT IS THE FAILURE MODE THIS PARAGRAPH HAS ALWAYS BEEN ABOUT: a step aimed at a container that does
# not exist does not fail loudly. `docker exec` on a missing container writes one line to stderr, and
# probes are written `|| true` so that a dead endpoint reports as a check rather than killing the run; a
# migration whose post-flight is half no-ops reads exactly like a migration that passed.
#
# HENCE THE RULE EVERY GUARD BELOW OBEYS, and it is worth stating once because it is the whole design:
#   * a guard that PROTECTS something — "is a build in progress?", "is everything down before I rename?"
#     — FAILS, loudly and by name, when the container it needs to ask is not running. "I could not ask"
#     is never reported as "nothing is happening".
#   * only a loop that legitimately tolerates absence — the profile-gated engine, a teardown — may
#     continue past a missing container, and it says out loud that it skipped it and why.
# A check that quietly did not run reads exactly like a check that passed.
#
# WHY THIS IS NOT A FIND-AND-REPLACE. Three facts, each of which turns a one-line rename into data loss:
#
#   1. THE NAMED VOLUMES PIN THEIR PHYSICAL NAME, and that pinning is the only reason a rename is safe.
#      Each volume block in deploy/docker-compose.yml carries an explicit `name:`
#          fsm-orchestrator-state:  name: fsm_fsm-orchestrator-state    the H2 store
#          fsm-runner-cache:        name: fsm_fsm-runner-cache          the prover's clone/build cache
#      so Compose does NOT derive `<project>_<key>` and the project name is no longer load-bearing data
#      retention. Rename `name: fsm`, rename the directory, do both — those two volumes keep resolving
#      to the same bytes on disk.
#
#      WITHOUT the pins this was a data-loss trap, and the trap is worth remembering because it is
#      silent: Compose would look for a volume that does not exist, CREATE IT EMPTY, and start a healthy
#      orchestrator serving an empty backlog — schema.sql recreates the tables, so nothing errors and
#      nothing is red. That is how a validated run disappears with no signal at all.
#
#      SO THIS SCRIPT NO LONGER COPIES VOLUMES. Copying would be worse than useless now: it would write
#      `<newproject>_<key>`, a name the pinned `name:` guarantees Compose will never resolve, so the
#      "migrated" data would sit in a volume nothing mounts while the stack ran on the original. What
#      the script does instead is VERIFY THE INVARIANT the safety rests on — every volume pins a
#      physical name (step 5) — and then check that the running container actually mounted the pinned
#      name (post-flight 3). An unpinned volume is the one thing that could still lose the store, so it
#      is a hard stop before anything is touched.
#
#   2. THE COMPOSE FILE BIND-MOUNTS THE DIRECTORY BEING RENAMED. `fsm` binds <root> itself at /data
#      (read-only, for the Svace CSVs and prompts/) and additionally binds <root>/feedback writable; the
#      profile-gated engine binds the same root read-only whenever it is started. Renaming a directory
#      out from under a running
#      container does not error — the container keeps a handle on the old inode and the next `up -d`
#      rebinds to a path that may not exist. Docker then CREATES a missing bind source rather than
#      refusing to start, so the failure mode is an empty root-owned directory and a green `docker ps`.
#      Hence: stop first (step 3), rename second (step 4).
#
#   3. THE DOMAIN IS ALREADY HANDLED, AND THERE IS NOW NOTHING TO EDIT. Caddy serves BOTH
#      fix-java-svace-markers.mikhailov.tech and fix-java-svace-markers-spring.mikhailov.tech from one
#      site block, and it reverse-proxies /dashboard/ to the pipeline container on 8085. This script
#      does not go near the Caddyfile — and it no longer rewrites a host anywhere, because N8N_HOST /
#      WEBHOOK_URL / N8N_EDITOR_BASE_URL were the only settings in the compose file that named either
#      host and they went with the service. Nothing in the live stack knows its own external address.
#
#      ONE THING TO CHECK BY HAND, ONCE, and it is not this script's to fix: that upstream is now the
#      container `fsm`. A Caddyfile still saying `reverse_proxy fsm-orchestrator:8085` resolves nothing
#      and answers 502 on a stack this script will report as entirely healthy — every check below asks
#      the published loopback port, which is a different route from Caddy's.
#
# USAGE
#     ./migrate-to-spring.sh --dry-run          # print every step, change nothing
#     ./migrate-to-spring.sh                    # ask for confirmation, then do it
#     ./migrate-to-spring.sh --yes              # no prompt (for a scripted window)
#     ./migrate-to-spring.sh --wipe-runner-cache   # ALSO retire the leaked tokens; see the flag's block
#
# WHAT --dry-run MEANS HERE, precisely, because a vague answer to this is useless in a maintenance
# window: nothing that changes state runs. No stop, no rename, no tar, no docker volume create/rm, no
# sed. Those steps print as `+ <command>`. The READ-ONLY questions ARE actually asked and their answers
# printed, marked `? <command>` — a dry run whose whole job is to tell you whether it is safe to migrate
# is worthless if it refuses to look. (Every probe is now genuinely read-only: the one that was not — a
# lease acquire, a write — went with the /lease endpoint it probed.)
#
# IDEMPOTENCE. Re-running after a failure resumes rather than repeats: the rename is skipped if it has
# already happened, the volume copy is skipped if the target already matches, and `up -d` is idempotent
# by nature. The pre-migration counts are recorded ONCE,
# to $BACKUP_DIR/pre-migration-counts.env, and reused on every later run — after a restart the live
# numbers are no longer the pre-migration truth, so re-reading them would make post-flight compare the
# system against itself and pass no matter what was lost.
#
set -euo pipefail

# ---------------------------------------------------------------------------------------------------
# Configuration. Every value is overridable from the environment so this can be rehearsed against a
# copy without editing the script.
# ---------------------------------------------------------------------------------------------------
OLD_ROOT="${FSM_OLD_ROOT:-/home/vmihaylov/fix-java-svace-markers}"
NEW_ROOT="${FSM_NEW_ROOT:-/home/vmihaylov/fix-java-svace-markers-spring}"

REL_COMPOSE="pipeline/deploy/docker-compose.yml"

BACKUP_DIR="${FSM_BACKUP_DIR:-/home/vmihaylov/fsm-migration-backups}"
REC_FILE="$BACKUP_DIR/pre-migration-counts.env"

# The compose project the LIVE volumes belong to. Verified against the volumes themselves in pre-flight
# rather than trusted.
LIVE_PROJECT="${FSM_LIVE_PROJECT:-fsm}"
VOL_STATE="fsm-orchestrator-state"      # the H2 store — the volume that must never be lost
# The prover's clones and build workspaces, mounted at /cache. Named for the service that used to own
# them: the prove runs inside the one process now, and the volume kept its name so the live deployment
# kept its checkouts across the merge.
VOL_JAVA_CACHE="fsm-runner-cache"
# The DELETED Node runner's clones. The service is gone from compose and the container is gone from the
# host, but THE VOLUME OUTLIVES BOTH until somebody removes it, and it holds a live credential — see
# --wipe-runner-cache. It is never copied forward (nothing mounts it any more); it is only retired.
VOL_JS_CACHE="fsm-java-runner-cache"

# THE CONTAINER NAMES ARE NOT WRITTEN HERE ANY MORE. They were —
#     ORCH=fsm-orchestrator; ENGINE=fsm-engine; RUNNER=fsm-runner
# — and two of those three names stopped existing when the orchestrator and the runner merged into one
# service, which cost this script its most important guard: the pre-flight "is a build in progress?"
# probe was wrapped in `if ctr_running "$RUNNER"`, that test went false forever, and the guard reported
# NOTHING instead of refusing to proceed. It would have let somebody move a deployment mid-prove.
#
# discover_topology() (below, called first thing in pre-flight) reads the services and their
# `container_name` out of the compose file being migrated and sets:
#     MAIN_SVC / MAIN_CTR      the one service `docker compose up -d` starts — the pipeline AND the
#                              prover. REQUIRED: every protective guard asks this container.
#     OPT_SVCS / OPT_CTRS      profile-gated services (today: engine). Their absence is NORMAL and is
#                              reported as a skip with its reason, never as a pass and never silently.
#     ALL_CTRS                 every container the compose file can produce, for the teardown checks.
MAIN_SVC=""; MAIN_CTR=""
OPT_SVCS=(); OPT_CTRS=(); OPT_PROFS=()
ALL_CTRS=()

# The one loopback-published port: compose publishes 127.0.0.1:8085 and nothing else.
ORCH_HOST_URL="${FSM_ORCH_HOST_URL:-http://127.0.0.1:8085}"
# Fallback port for a profile-gated service that publishes nothing useful to us — the real one is read
# from the running container's own PORT (see engine_net_url), because that is the value it is listening
# on rather than the value this file remembers.
OPT_PORT_DEFAULT="${FSM_ENGINE_PORT:-8092}"

# Helper image for the volume work and for the two questions that need a JSON parser. node:24-alpine
# deliberately: busybox gives tar/gzip/find/sha256sum and node gives fetch. It used to be guaranteed
# present because the dashboard service ran it; that service is deleted, so `docker pull` it into the
# host's image cache BEFORE the window rather than discovering mid-migration that this host is offline.
HELPER_IMAGE="${FSM_HELPER_IMAGE:-node:24-alpine}"

MIN_BACKUP_BYTES="${FSM_MIN_BACKUP_BYTES:-4096}"
HEALTH_TIMEOUT_S="${FSM_HEALTH_TIMEOUT_S:-420}"

TS="$(date +%Y%m%d-%H%M%S)"

DRY=0
ASSUME_YES=0
WIPE_JS_CACHE=0
FORCE_UNANSWERABLE=0

STEP_NOW="startup"
PF_FAIL=0
PF_CHECKS=0

# ---------------------------------------------------------------------------------------------------
# Output helpers. `+` marks something that changes state, `?` marks a question asked of the system.
# ---------------------------------------------------------------------------------------------------
hdr()  { printf '\n=== %s\n' "$*"; }
info() { printf '      %s\n' "$*"; }
plan() { printf '  +   %s\n' "$*"; }
qmark(){ printf '  ?   %s\n' "$*"; }
warn() { printf '  !   %s\n' "$*"; }

die() { printf '\n!!! ABORT (%s): %s\n' "$STEP_NOW" "$*" >&2; exit 1; }

pass() { PF_CHECKS=$((PF_CHECKS + 1)); printf '  PASS  %s\n' "$*"; }
fail() { PF_CHECKS=$((PF_CHECKS + 1)); PF_FAIL=$((PF_FAIL + 1)); printf '  FAIL  %s\n' "$*"; }

# Run a state-changing command, or only print it under --dry-run. Arguments containing whitespace are
# printed quoted, so a `sh -c '...'` payload reads as one argument rather than as a host-level && that the
# operator might copy and run in the wrong shell.
act() {
  local a out=""
  for a in "$@"; do
    case "$a" in *[[:space:]]*) out="$out '$a'" ;; *) out="$out $a" ;; esac
  done
  plan "${out# }"
  [ "$DRY" -eq 1 ] && return 0
  "$@"
}
# Same, for something that genuinely needs a shell (pipes, redirection).
actsh() { plan "sh -c: $1"; [ "$DRY" -eq 1 ] && return 0; bash -c "$1"; }

usage() {
  # The comment block at the top of this file, up to `set -euo pipefail`. Found rather than hardcoded:
  # the range was '2,60p' and drifted the moment the header changed length, which silently truncated
  # --help mid-sentence.
  sed -n "2,$(($(grep -n '^set -euo pipefail$' "$0" | head -1 | cut -d: -f1) - 1))p" "$0" \
    | sed 's/^# \{0,1\}//'
  trap - EXIT      # --help is not a migration; do not print the rollback after it
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --yes|-y) ASSUME_YES=1 ;;
    --wipe-runner-cache) WIPE_JS_CACHE=1 ;;
    # Only for "I could not ASK whether a prove is running" — never for "a prove IS running". A
    # confirmed in-flight prove is refused whatever flags are passed.
    --force-unanswerable) FORCE_UNANSWERABLE=1 ;;
    --backup-dir) shift; BACKUP_DIR="${1:?--backup-dir needs a path}"; REC_FILE="$BACKUP_DIR/pre-migration-counts.env" ;;
    -h|--help) usage ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
  shift
done

# ---------------------------------------------------------------------------------------------------
# ROLLBACK. Printed at the end of every run — successful or not — because the moment you need it is the
# moment you cannot go and look for it. The EXIT trap is the only place that prints it, so it cannot be
# skipped by an early failure.
# ---------------------------------------------------------------------------------------------------
rollback_text() {
  cat <<ROLLBACK

------------------------------------------------------------------------------------------------------
ROLLBACK — how to put this back exactly as it was
------------------------------------------------------------------------------------------------------
Nothing in this script deletes data. The old named volumes are LEFT IN PLACE, both backups are on disk,
and the directory rename is reversible with one mv. In order:

  1. Stop whatever is up (harmless if it is already down):
       docker compose -f $NEW_ROOT/$REL_COMPOSE down
       docker compose -f $OLD_ROOT/$REL_COMPOSE down
     NEVER with -v. \`down -v\` deletes the named volumes, which is the one irreversible act available.

  2. Put the directory back:
       mv $NEW_ROOT $OLD_ROOT

  3. If anyone edited \`name:\` in the compose file, put the old project name ($LIVE_PROJECT) back —
     that line is what binds the stack to ${LIVE_PROJECT}_${VOL_STATE}. This script never edits it.

  4. Start it from the old path:
       cd $OLD_ROOT/pipeline/deploy && docker compose up -d

  5. Only if the data itself looks wrong, restore from the COLD backup in $BACKUP_DIR
     (the *.cold.*.tar.gz file — the hot one was taken with the stack running and may be torn):

       docker compose -f $OLD_ROOT/$REL_COMPOSE down
       docker run --rm -v ${LIVE_PROJECT}_${VOL_STATE}:/v -v $BACKUP_DIR:/bk $HELPER_IMAGE \\
         sh -c 'rm -rf /v/* /v/.[!.]* 2>/dev/null; tar -xzf /bk/orchestrator-state.cold.<timestamp>.tar.gz -C /v'

       cd $OLD_ROOT/pipeline/deploy && docker compose up -d

  6. Verify the way this script does, not by looking at \`docker ps\`:
       curl -fsS $ORCH_HOST_URL/healthz          # 'ok' means H2 answered a real query
     and compare the marker count against $REC_FILE.

  7. If --wipe-runner-cache was used there is nothing to roll back: a build cache is not data, and the
     volume it removed belonged to a service that no longer exists. Nothing re-clones into it.
------------------------------------------------------------------------------------------------------
ROLLBACK
}

ROOT_NOW="$OLD_ROOT"   # resolved for real in pre-flight; the trap may fire before that
on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    printf '\n!!! FAILED during: %s (exit %s)\n' "$STEP_NOW" "$rc"
    printf '!!! The stack may be DOWN. Read the rollback below before doing anything else.\n'
  fi
  rollback_text
  exit "$rc"
}
trap on_exit EXIT

# ---------------------------------------------------------------------------------------------------
# Small primitives
# ---------------------------------------------------------------------------------------------------
have_docker() { command -v docker >/dev/null 2>&1; }
ctr_running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" = "true" ]; }
vol_exists()  { docker volume inspect "$1" >/dev/null 2>&1; }

# WHY THIS EXISTS SEPARATELY FROM ctr_running. `ctr_running` answers false for two different worlds —
# "the container is there and stopped" and "there is no such container" — and the guards below have to
# tell them apart to report honestly. `docker compose down` REMOVES containers, so absence is the normal
# shape of a stopped deployment; absence of the container a guard needed to ask, while the rest of the
# stack is up, is the shape of a script aimed at a ghost.
#
# `docker inspect` on a missing container still writes an empty line to stdout before failing, so the
# status is captured and TESTED rather than piped through a `|| echo` that would print "\nabsent".
ctr_state() {
  local s
  s="$(docker inspect -f '{{.State.Status}}' "$1" 2>/dev/null | tr -d '[:space:]' || true)"
  if [ -n "$s" ]; then printf '%s' "$s"; else printf 'absent (no such container)'; fi
}

# The compose project name as declared in the file — the string the volume prefix comes from.
compose_project_name() {
  awk '/^[[:space:]]*name:[[:space:]]*[^[:space:]]/ {
         sub(/^[[:space:]]*name:[[:space:]]*/, ""); gsub(/["\047]/, ""); sub(/[[:space:]].*$/, "");
         print; exit }' "$1"
}

# ---- READING THE TOPOLOGY OUT OF THE COMPOSE FILE --------------------------------------------------
# One line per service under the top-level `services:` mapping:
#
#     <service key>\t<container_name>\t<profiles, comma-separated or empty>
#
# Anchored on the file's real indentation — two spaces for a service key, four for its own keys — which
# is the same shape volume_pinned_name() reads the volume blocks with. A top-level comment (column 0,
# `#`) does NOT end the services block; any other column-0 key does. Nested mappings (build:, args:,
# environment:) sit at six spaces or deeper and are therefore invisible here, which is what keeps a
# `name:` under build/args from being mistaken for a service's.
#
# `profiles:` is accepted in both YAML spellings — inline `profiles: ["engine"]` and a block list on the
# following lines — because which one the file uses is not this script's business to depend on.
compose_services() {  # <compose-file>
  awk '
    function flush(  s) { if (svc != "") { print svc "\t" ctr "\t" prof } }
    /^services:[[:space:]]*$/ { ins = 1; next }
    /^[^[:space:]#]/          { if (ins) { flush(); svc = "" } ; ins = 0 }
    ins && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ {
      flush()
      svc = $0; sub(/^  /, "", svc); sub(/:[[:space:]]*$/, "", svc)
      ctr = ""; prof = ""; inprof = 0
      next
    }
    ins && svc != "" && /^    container_name:[[:space:]]*[^[:space:]]/ {
      ctr = $0
      sub(/^[[:space:]]*container_name:[[:space:]]*/, "", ctr)
      gsub(/["\047]/, "", ctr); sub(/[[:space:]].*$/, "", ctr)
      inprof = 0; next
    }
    ins && svc != "" && /^    profiles:/ {
      prof = $0
      sub(/^[[:space:]]*profiles:[[:space:]]*/, "", prof)
      gsub(/[]["\047]/, "", prof); gsub(/[[:space:]]+/, "", prof)
      inprof = (prof == "") ? 1 : 0            # empty value => the list is on the following lines
      next
    }
    ins && inprof && /^      -[[:space:]]*[^[:space:]]/ {
      p = $0; sub(/^[[:space:]]*-[[:space:]]*/, "", p)
      gsub(/["\047]/, "", p); sub(/[[:space:]].*$/, "", p)
      prof = (prof == "") ? p : prof "," p
      next
    }
    ins && /^    [A-Za-z0-9_.-]+:/ { inprof = 0 }
    END { if (ins) flush() }
  ' "$1"
}

# WHICH CONTAINER IS WHICH, and this is the function that replaced three constants.
#
# THE MAIN SERVICE IS THE ONE WITH NO PROFILE — the one and only thing `docker compose up -d` starts.
# That is not a guess about names: a service without `profiles:` is started by a bare `up -d` by
# definition, and this stack's whole claim ("one command, one container") is that there is exactly one.
# If a later revision adds a second unprofiled service, this REFUSES rather than picking one, because
# every protective guard below asks MAIN_CTR and guessing wrong would aim them at a ghost again — which
# is the exact defect this function exists to make impossible.
discover_topology() {  # <compose-file>
  local file="$1" svc ctr prof n=0
  MAIN_SVC=""; MAIN_CTR=""
  OPT_SVCS=(); OPT_CTRS=(); OPT_PROFS=(); ALL_CTRS=()

  while IFS="$(printf '\t')" read -r svc ctr prof; do
    [ -n "$svc" ] || continue
    n=$((n + 1))
    # A service with no container_name gets `<project>-<service>-1` from Compose, which the project
    # rename this script performs would CHANGE — so every `docker exec` here would address a name that
    # is correct only before the migration. Refuse rather than derive it.
    [ -n "$ctr" ] || die "service '$svc' in $file declares no \`container_name:\`. Compose would then name its container <project>-$svc-1 — a name the project rename this script performs is free to change — and every probe below addresses containers by name. Add an explicit container_name to that service."
    ALL_CTRS+=("$ctr")
    if [ -n "$prof" ]; then
      OPT_SVCS+=("$svc"); OPT_CTRS+=("$ctr"); OPT_PROFS+=("$prof")
    elif [ -n "$MAIN_SVC" ]; then
      die "two services in $file have no \`profiles:\` — '$MAIN_SVC' ($MAIN_CTR) and '$svc' ($ctr). \`docker compose up -d\` would start both, and this script would have to guess which one holds the H2 store, answers /healthz and runs the prove. It does not guess: every guard below asks ONE container by name, and aiming them at the wrong one is how the previous revision came to check nothing at all. Teach this script the new topology before migrating with it."
    else
      MAIN_SVC="$svc"; MAIN_CTR="$ctr"
    fi
  done < <(compose_services "$file")

  [ "$n" -gt 0 ] || die "no services parsed out of $file. Either the file is not the compose file this script thinks it is, or its indentation is not the two-space shape compose_services() reads. Refusing to run blind: with no service names, every check below would silently address nothing."
  [ -n "$MAIN_SVC" ] || die "every service in $file is profile-gated, so \`docker compose up -d\` starts NOTHING. There is no container to ask whether a prove is running, no /healthz to check and nothing to migrate. Refusing."
}

# The address a profile-gated sidecar answers on, asked of the container itself rather than remembered.
engine_net_url() {  # <container>
  local p; p="$(docker exec "$1" printenv PORT 2>/dev/null || true)"
  case "$p" in ''|*[!0-9]*) p="$OPT_PORT_DEFAULT" ;; esac
  printf 'http://%s:%s' "$1" "$p"
}

# The HTTP status <url> answers when asked from INSIDE <container>, or nothing at all when the question
# could not be put.
#
# THE ANSWER IS VALIDATED, NOT TRUSTED, and that is not defensive programming for its own sake: when
# `docker exec` itself fails — no curl in the image, the container restarting, the daemon refusing — the
# CLI's complaint lands on the same stream curl writes `%{http_code}` to. A caller that compares that
# string against "000" reads `OCI runtime exec failed: … "curl": executable file not found` as a
# perfectly good HTTP status and prints PASS for an endpoint it never reached. Three digits or nothing.
probe_http() {  # <container> <url>
  local out
  out="$(docker exec "$1" curl -s -o /dev/null -m 20 -w '%{http_code}' "$2" 2>/dev/null || true)"
  case "$out" in [0-9][0-9][0-9]) printf '%s' "$out" ;; esac
}

# Keep only lines that are plain shell assignments, so output from a container can be eval'd safely.
only_kv() { grep -E '^[A-Za-z_][A-Za-z0-9_]*=[A-Za-z0-9_.:/@+-]*$' || true; }

# ---- the reader ------------------------------------------------------------------------------------
# ASK THE ORCHESTRATOR. /api/state is the document the dashboard renders, so these are the same numbers
# a human would read off the page. Printed as shell assignments; see only_kv.
JS_ORCH='
const url = process.argv[1];
fetch(url + "/api/state").then(async (r) => {
  if (!r.ok) { console.error("HTTP " + r.status); process.exit(1); }
  const j = await r.json();
  const rows = j.suspicions || [];
  const byStatus = {};
  for (const s of rows) { const k = String(s.status || "unknown").replace(/[^A-Za-z0-9_]/g, "_"); byStatus[k] = (byStatus[k] || 0) + 1; }
  const out = [];
  out.push("MARKERS=" + rows.length);
  out.push("ARTIFACTS=" + (j.bugs || []).length);
  out.push("RUNNING_JOBS=" + (j.activity || []).filter((a) => a.status === "running").length);
  for (const k of Object.keys(byStatus)) out.push("ST_" + k + "=" + byStatus[k]);
  console.log(out.join("\n"));
}).catch((e) => { console.error(String((e && e.message) || e)); process.exit(1); });
'
# WHERE THE PARSE RUNS, now that the two containers that shipped a JavaScript runtime are deleted.
# THIS HOST HAS NO node: `node -e` on mh exits 127. fsm-dashboard and fsm-n8n used to provide one and
# both are gone, so the parse happens in a throwaway $HELPER_IMAGE container instead — which is the same
# image the volume work already uses, so this adds no new prerequisite.
#
# `--network host` and the PUBLISHED port, not a compose network: the orchestrator publishes
# 127.0.0.1:8085, and that is the identical endpoint the plain-curl checks in post-flight use, so a
# failure here and a failure there mean the same thing about the same route. Attaching to
# `<project>_default` would instead guess a network name that the very project rename this script exists
# to perform is free to change.
read_orch() {
  docker run --rm --network host "$HELPER_IMAGE" node -e "$JS_ORCH" "$ORCH_HOST_URL" 2>/dev/null | only_kv
}

# ---------------------------------------------------------------------------------------------------
# STEP 1 — PRE-FLIGHT
# Refuse to run if a prove is in flight, and print what is about to happen. Renaming mid-prove costs
# that marker: the orchestrator's StartupReconciler puts a 'proving' row back to 'new' on the way up, so
# the DATA survives — what is lost is the model time already spent on it, which for one marker is
# minutes to an hour.
# ---------------------------------------------------------------------------------------------------
preflight() {
  STEP_NOW="pre-flight"
  hdr "STEP 1/8  PRE-FLIGHT"

  have_docker || die "no docker on PATH — this script only runs on the deployment host"

  # -- which root is live (this is what makes a re-run resume instead of repeat) --
  if [ -d "$OLD_ROOT" ] && [ -d "$NEW_ROOT" ]; then
    die "BOTH $OLD_ROOT and $NEW_ROOT exist. Refusing to guess which one is live — resolve by hand."
  elif [ -d "$OLD_ROOT" ]; then
    ROOT_NOW="$OLD_ROOT"; RENAME_DONE=0
    info "live checkout: $ROOT_NOW (not yet renamed)"
  elif [ -d "$NEW_ROOT" ]; then
    ROOT_NOW="$NEW_ROOT"; RENAME_DONE=1
    info "live checkout: $ROOT_NOW — ALREADY RENAMED, step 4 will be skipped"
  else
    die "neither $OLD_ROOT nor $NEW_ROOT exists"
  fi
  COMPOSE_FILE="$ROOT_NOW/$REL_COMPOSE"
  COMPOSE_DIR="$(dirname "$COMPOSE_FILE")"
  COMPOSE_READ="$COMPOSE_FILE"    # see rename_root: the two differ only under --dry-run
  [ -f "$COMPOSE_FILE" ] || die "no compose file at $COMPOSE_FILE"

  # -- THE OVERRIDE, WHICH -f TURNS OFF --
  #
  # Compose auto-merges docker-compose.override.yml ONLY under default file discovery. The moment you
  # pass `-f`, that stops: you get exactly the files you named. Every compose call in this script passes
  # `-f "$COMPOSE_FILE"` so it can address a checkout by path rather than by cwd — which means without
  # this block the script's own `up -d` would restore the stack from the BASE FILE ALONE, in a different
  # shape from the operator's own `docker compose up -d` in that same directory.
  #
  # That is not hypothetical here. deploy/docker-compose.override.yml.example — the shipped shape of it —
  # puts the main service on `proxy-net`, the external bridge where QWEN_BASE_URL resolves
  # (http://inference-vllm:8000/v1 is a compose service name belonging to ANOTHER stack). Restore
  # without it and the container comes back with no route to the model. Post-flight 8 does catch that
  # and main() aborts — but by then the stack has already been stopped, renamed and restarted in a
  # topology the host never asked for, and the operator is reading "Roll back" instead of a migration.
  #
  # So: if an override is sitting next to the base file, it is part of this deployment's definition and
  # every invocation gets it. Named explicitly and printed, because a silently-applied override is its
  # own kind of surprise.
  COMPOSE_FILES=(-f "$COMPOSE_FILE")
  COMPOSE_OVERRIDE="$COMPOSE_DIR/docker-compose.override.yml"
  if [ -f "$COMPOSE_OVERRIDE" ]; then
    COMPOSE_FILES+=(-f "$COMPOSE_OVERRIDE")
    info "compose override found, and every call below includes it: $COMPOSE_OVERRIDE"
    info "  (\`-f\` disables Compose's automatic merge, so this has to be explicit or the restart"
    info "   would use a different topology from your own \`docker compose up -d\` in that directory)"
  else
    info "no docker-compose.override.yml beside the base file — using it alone"
  fi

  # -- the project name, and therefore the volume names --
  DECLARED_PROJECT="$(compose_project_name "$COMPOSE_FILE")"
  [ -n "$DECLARED_PROJECT" ] || die "no \`name:\` in $COMPOSE_FILE — without it Compose derives the project from the directory basename (\`$(basename "$COMPOSE_DIR")\`), and the volume names follow it. That basename was \`n8n\` and collided with fix-java-bugs' stack; it is renameable precisely because this file names the project outright. Refusing to run."
  info "compose project declared in the file : $DECLARED_PROJECT"
  info "compose project the live volumes use : $LIVE_PROJECT"

  # -- THE TOPOLOGY, READ FROM THE FILE ABOUT TO BE MIGRATED --
  # Everything below addresses these names. They are not constants any more; see discover_topology.
  qmark "read services + container_name from $COMPOSE_FILE"
  discover_topology "$COMPOSE_FILE"

  # THE OVERRIDE IS MERGED INTO EVERY COMPOSE CALL, BUT NOT INTO THE TOPOLOGY READ ABOVE, which parses
  # one file by hand. Today that gap is harmless: the shipped override
  # (deploy/docker-compose.override.yml.example) adds only `networks:` to services the base file already
  # declares, so the service list, the container names and which service is profile-gated are all
  # unchanged by it. But an override may legally add a SERVICE or set a container_name, and either would
  # make MAIN_CTR/ALL_CTRS wrong — every probe below addresses containers by name, and aiming them at the
  # wrong one is how the previous revision came to check nothing at all.
  #
  # So rather than teach the hand parser to merge YAML, refuse the case it cannot see. This is the same
  # trade the parser already makes for a missing container_name: name the hazard, stop, let a human teach
  # the script the new topology.
  if [ -n "${COMPOSE_OVERRIDE:-}" ] && [ -f "$COMPOSE_OVERRIDE" ]; then
    local ovr_svcs
    ovr_svcs="$(awk '/^services:[[:space:]]*$/{s=1;next} /^[^[:space:]#]/{s=0} s && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/{gsub(/[: ]/,"");print}' "$COMPOSE_OVERRIDE")"
    local o
    for o in $ovr_svcs; do
      printf '%s\n' "${ALL_CTRS[@]}" >/dev/null   # ALL_CTRS is set; the check below is on service names
      grep -qE "^  $o:[[:space:]]*$" "$COMPOSE_FILE" \
        || die "$COMPOSE_OVERRIDE declares a service '$o' that is not in $COMPOSE_FILE. The topology above was read from the base file alone, so this script does not know that service's container_name or whether it is profile-gated — and every guard below addresses containers by name. Add it to the base file, or teach discover_topology to read the merged config."
    done
    if grep -qE '^[[:space:]]+container_name:' "$COMPOSE_OVERRIDE"; then
      die "$COMPOSE_OVERRIDE sets a container_name. The topology above was parsed from $COMPOSE_FILE only, so the names every probe below uses would be the base file's, not the ones Compose will actually create. Move the container_name into the base file, or teach discover_topology to read the merged config."
    fi
    info "override adds no service and no container_name — the topology read above still holds"
  fi
  info "main service (started by \`up -d\`) : $MAIN_SVC -> container $MAIN_CTR"
  local i
  if [ "${#OPT_CTRS[@]}" -eq 0 ]; then
    info "profile-gated services            : none declared"
  else
    for i in $(seq 0 $((${#OPT_CTRS[@]} - 1))); do
      info "profile-gated service             : ${OPT_SVCS[$i]} -> container ${OPT_CTRS[$i]} (profile: ${OPT_PROFS[$i]}; \`up -d\` does NOT start it)"
    done
  fi

  qmark "docker volume inspect ${LIVE_PROJECT}_${VOL_STATE}"
  vol_exists "${LIVE_PROJECT}_${VOL_STATE}" \
    || die "${LIVE_PROJECT}_${VOL_STATE} does not exist. That volume IS the H2 store (282 markers, every drafted PR body). Its absence means this script's picture of the live system is wrong — do not continue on assumptions."
  info "H2 volume present: ${LIVE_PROJECT}_${VOL_STATE}"
  for v in "$VOL_JS_CACHE" "$VOL_JAVA_CACHE"; do
    if vol_exists "${LIVE_PROJECT}_${v}"; then info "cache volume present: ${LIVE_PROJECT}_${v}"
    else info "cache volume absent (fine, it is rebuildable): ${LIVE_PROJECT}_${v}"; fi
  done

  # -- WHAT IS ACTUALLY UP, container by container --
  # A SURVEY, NOT A GUARD: absence is tolerated here, and every container's state is printed, because
  # the conclusion drawn below ("nothing can be proving") is only sound if you can see what was asked.
  # ctr_running() alone cannot support it — it answers false both for "stopped" and for "no such
  # container", and the second of those is what a script aimed at ghosts looks like from the inside.
  ANY_RUNNING=0
  MAIN_RUNNING=0
  local c up=""
  for c in "${ALL_CTRS[@]}"; do
    info "container $c: $(ctr_state "$c")"
    if ctr_running "$c"; then
      ANY_RUNNING=1; up="$up $c"
      [ "$c" = "$MAIN_CTR" ] && MAIN_RUNNING=1
    fi
  done
  if [ "$ANY_RUNNING" -eq 0 ]; then
    # This is a positive finding, not a skip, and the reason it is trustworthy is the volume check
    # above: `docker compose down` REMOVES containers, so a stopped deployment has no containers at all
    # — and ${LIVE_PROJECT}_${VOL_STATE} being present is what says these are the right names for the
    # right stack rather than a script looking in the wrong place and finding nothing.
    info "no container of this stack is running (the H2 volume ${LIVE_PROJECT}_${VOL_STATE} IS present,"
    info "so this is the stack, stopped — not a misaimed script). Nothing can be proving."
  else
    info "running:$up"
  fi

  # -- IS A PROVE IN FLIGHT? --
  hdr "STEP 1b   is a prove in flight?"
  local inflight=0 unanswerable=0

  if [ "$ANY_RUNNING" -eq 0 ]; then
    pass "nothing running, so no prove is in flight"
  else
    # (a) the orchestrator's own claim. A marker is claimed by flipping its row to 'proving' — the lock
    #     and the state are one row, so this is the authoritative answer, not a heuristic. It is also
    #     the ONLY claim there is now: n8n's workflows were the second prover and they are deleted.
    qmark "docker run --rm --network host $HELPER_IMAGE node -e <fetch $ORCH_HOST_URL/api/state>"
    local raw; raw="$(read_orch || true)"
    if [ -n "$raw" ]; then
      eval "$raw"
      LIVE_MARKERS="${MARKERS:-}"; LIVE_ARTIFACTS="${ARTIFACTS:-}"
      info "orchestrator: ${MARKERS:-?} markers, ${ARTIFACTS:-?} artifacts, ${RUNNING_JOBS:-?} job execution(s) running"
      info "by status: $(printf '%s\n' "$raw" | grep '^ST_' | tr '\n' ' ')"
      if [ "${ST_proving:-0}" -gt 0 ]; then
        fail "${ST_proving} marker(s) are status='proving' — A PROVE IS IN FLIGHT"; inflight=1
      else
        pass "no marker is status='proving'"
      fi
      if [ "${RUNNING_JOBS:-0}" -gt 0 ]; then
        fail "${RUNNING_JOBS} batch execution(s) still running"; inflight=1
      else
        pass "no batch execution is running"
      fi
    else
      warn "could not read $ORCH_HOST_URL/api/state"
      unanswerable=1
    fi

    # (b) THE BUILD LOCK, AND THIS IS THE GUARD THE WHOLE PRE-FLIGHT EXISTS FOR. The prove runs INSIDE
    #     $MAIN_CTR now (fsm.runner.mode=local): LocalRunner owns one FIFO build thread and shells out to
    #     Maven from this process. The queue is deliberately not exposed over HTTP, so the honest way to
    #     ask is to look for the build itself. /proc, not `ps`: procps is not installed in the image.
    #
    #     IT USED TO ASK fsm-runner, AND THAT COST THIS GUARD EVERYTHING. The probe was wrapped in
    #     `if ctr_running "$RUNNER"` with RUNNER=fsm-runner; the day the runner merged into the
    #     orchestrator that container stopped existing, the test went false forever, and the branch was
    #     skipped in silence — no line printed, `inflight` left at 0, and the script cheerfully offering
    #     to stop a stack that might be ninety minutes into a build. A guard that cannot run must SAY SO
    #     and stop the run; it must never fall through to the happy path.
    local probe='for p in /proc/[0-9]*/cmdline; do tr "\000" " " < "$p" 2>/dev/null; echo; done'
    if [ "$MAIN_RUNNING" -eq 0 ]; then
      warn "CANNOT ASK WHETHER A BUILD IS IN PROGRESS: $MAIN_CTR ($MAIN_SVC) is $(ctr_state "$MAIN_CTR"),"
      warn "yet something in this stack is up ($up). The prove runs inside THAT container, so this probe"
      warn "has nowhere to look — and 'I could not look' is not 'no build is running'. Migrating now"
      warn "could stop a deployment mid-prove, which costs that marker every minute already spent on it."
      unanswerable=1
    else
      qmark "docker exec $MAIN_CTR sh -c '<read /proc/*/cmdline>' | grep -Ei 'maven|mvn|gradle'"
      # THE EXEC'S OWN EXIT STATUS, not the grep's. `docker exec … | grep … || true` reports an exec that
      # failed outright — container restarting, daemon refusing — as an empty match, which prints as
      # "holds no build". That is the same false negative in a different costume, so the exec is run on
      # its own and its status is read before anything is matched.
      local procs="" rc=0
      procs="$(docker exec "$MAIN_CTR" sh -c "$probe" 2>/dev/null)" || rc=$?
      if [ "$rc" -ne 0 ]; then
        warn "CANNOT ASK WHETHER A BUILD IS IN PROGRESS: \`docker exec $MAIN_CTR\` exited $rc."
        warn "The probe did not run. An unreadable /proc is not an idle prover."
        unanswerable=1
      else
        local builds
        builds="$(printf '%s\n' "$procs" | grep -Ei 'maven|mvn|gradle' | grep -v grep | head -3 || true)"
        if [ -n "$builds" ]; then
          fail "$MAIN_CTR IS BUILDING — the build lock is held:"
          printf '        %s\n' "$builds"
          inflight=1
        else
          pass "$MAIN_CTR holds no build (no maven/gradle process in it)"
        fi
      fi
    fi

    # THERE IS NO (c). A third probe used to acquire the runner's `prover` lease and read "is a prove in
    # flight" off whether it was already held. That lease was n8n's mutual exclusion: two schedule ticks
    # could race, so whoever proved took it first. n8n is gone, and the orchestrator never takes it —
    # Spring Batch gives single-flight structurally, one JobInstance per parameter set.
    #
    # So NOTHING HOLDS THE LEASE ANY MORE, and the probe would have acquired it cleanly every time and
    # reported "free" — including while a prove was running. A pre-flight that returns a false negative
    # on the exact condition it exists to detect is worse than no pre-flight, because `inflight=0` reads
    # as an answer rather than as an absence. That is why the endpoint went with it rather than being
    # left to answer 404.
    #
    # (a) and (b) above are direct observations of the real thing and are unaffected by any of that: the
    # `proving` rows the pipeline itself wrote, and a maven/gradle process actually running inside the
    # container that runs the prove.
  fi

  if [ "$inflight" -ne 0 ]; then
    die "A PROVE IS IN FLIGHT. Refusing to migrate — the rename would cost that marker's work. Wait for it to settle (the dashboard's activity panel, or watch until no marker is 'proving'), or stop the schedule first with FSM_PROVE_SCHEDULE=false in .env + \`docker compose up -d --no-deps $MAIN_SVC\`, then re-run. No flag overrides this."
  fi
  if [ "$unanswerable" -ne 0 ] && [ "$FORCE_UNANSWERABLE" -eq 0 ]; then
    die "Could not ASK whether a prove is running (see the ! lines above). 'I cannot tell' is not 'nothing is running', and a guard that could not run is not a guard that passed. Fix the probe — or, if the container it needed is gone, find out why before moving anything — and re-run. --force-unanswerable is only for a stack you have verified idle by hand."
  fi

  # A prove can still START in the gap between here and the stop below: the schedule is fixedDelay
  # PT60S. Say so, rather than pretending the check is a lock.
  if [ "$ANY_RUNNING" -eq 1 ]; then
    warn "the prover ticks every 60s: a new prove could start between this check and the stop in step 3."
    warn "for a zero-risk window set FSM_PROVE_SCHEDULE=false in $COMPOSE_DIR/.env and \`docker compose up -d --no-deps $MAIN_SVC\` first."
  fi

  # -- print what it is about to do --
  local rename_note="" wipe_note=""
  [ "${RENAME_DONE:-0}" -eq 1 ] && rename_note="   [already done, will skip]"
  [ "$WIPE_JS_CACHE" -eq 1 ] && wipe_note="
      5b. WIPE ${LIVE_PROJECT}_${VOL_JS_CACHE} (--wipe-runner-cache): retires the tokenized clone URLs"
  hdr "STEP 1c   the plan"
  cat <<PLAN
      1. this pre-flight
      2. back up ${LIVE_PROJECT}_${VOL_STATE} (H2) to $BACKUP_DIR, verify it is non-empty and holds
         fsm.mv.db, abort if not
      3. docker compose down (WITHOUT -v — volumes are kept), then re-take the backup COLD
      4. mv $ROOT_NOW -> $NEW_ROOT$rename_note
      5. verify every named volume pins a physical \`name:\` (nothing is copied — see step 5's own block)$wipe_note
      6. docker compose up -d from $NEW_ROOT, wait for $MAIN_CTR to report healthy and answer /healthz
      7. post-flight: ask $MAIN_CTR — mounts, volume, /healthz, the marker count, the prover's cache and
         JDKs, the model endpoints, the secrets, /data, the dashboard — PASS/FAIL each, non-zero on any FAIL
      8. print the rollback
PLAN
  if [ "$DRY" -eq 0 ] && [ "$ASSUME_YES" -eq 0 ]; then
    printf '\n  Proceed? this stops the live stack. [yes/NO] '
    local answer; read -r answer
    [ "$answer" = "yes" ] || die "not confirmed"
  fi
}

# ---------------------------------------------------------------------------------------------------
# STEP 2 — RECORD + BACK UP
# ---------------------------------------------------------------------------------------------------

# Record the pre-migration truth ONCE. Everything post-flight compares against comes from here, so a
# re-run must not refresh it: after a restart the live numbers are the post-migration numbers, and
# comparing the system against itself passes no matter how much was lost.
record_counts() {
  STEP_NOW="record pre-migration counts"
  if [ -f "$REC_FILE" ]; then
    info "reusing the counts recorded on the first run: $REC_FILE"
    printf '      %s\n' "$(grep -c . "$REC_FILE") recorded values"
    return 0
  fi
  local orch
  orch="$(read_orch || true)"
  [ -n "$orch" ] || die "the orchestrator would not answer /api/state; refusing to migrate without a recorded marker count. Without it, post-flight cannot tell a successful move from a silently emptied database."
  if [ "$DRY" -eq 1 ]; then
    plan "write $REC_FILE with:"
    printf '%s\n' "$orch" | sed 's/^/        /'
    return 0
  fi
  { printf '# fsm pre-migration truth, recorded %s from the LIVE stack at %s\n' "$TS" "$ROOT_NOW"
    printf '%s\n' "$orch"
  } > "$REC_FILE"
  info "recorded -> $REC_FILE"
  sed 's/^/        /' "$REC_FILE"
}

backup_volume_to() {  # <volume> <dest-tgz>
  local vol="$1" dest="$2"
  act docker run --rm \
    -v "$vol":/v:ro -v "$BACKUP_DIR":/bk "$HELPER_IMAGE" \
    sh -c "tar -czf /bk/$(basename "$dest") -C /v . && chown $(id -u):$(id -g) /bk/$(basename "$dest")"
}

# VERIFY A BACKUP, and this is the step the brief is right to insist on: a tar that ran, exited 0 and
# produced an archive of nothing is worse than no backup, because it reads as one. Three questions:
# does the file exist, is it bigger than a plausible floor, and does it CONTAIN the file whose loss is
# the disaster.
verify_backup() {  # <tgz> <must-contain-regex> <label>
  local f="$1" want="$2" label="$3"
  if [ "$DRY" -eq 1 ]; then
    qmark "would verify $f is >= ${MIN_BACKUP_BYTES}B and lists /$want/  [skipped: --dry-run wrote nothing]"
    return 0
  fi
  [ -f "$f" ] || die "$label: backup file $f was not created"
  local size; size="$(wc -c < "$f" | tr -d ' ')"
  [ "$size" -ge "$MIN_BACKUP_BYTES" ] \
    || die "$label: backup $f is only ${size}B (floor ${MIN_BACKUP_BYTES}B). A backup that silently produced nothing is worse than none — not continuing."
  tar -tzf "$f" 2>/dev/null | grep -Eq "$want" \
    || die "$label: backup $f does not contain anything matching /$want/. It is not a backup of what this migration risks — not continuing."
  info "verified $label: $f (${size} bytes, contains /$want/)"
}

backups_hot() {
  STEP_NOW="step 2: backups (hot)"
  hdr "STEP 2/8  BACK UP THE THING THAT CANNOT BE RECREATED"
  act mkdir -p "$BACKUP_DIR"
  record_counts
  STEP_NOW="step 2: backups (hot)"   # record_counts moved it; a failure below belongs to the backup

  HOT_H2="$BACKUP_DIR/orchestrator-state.hot.$TS.tar.gz"

  info "hot copy first — it exists in case step 3 (the stop) is itself what goes wrong."
  info "the COLD copy taken after the stop is the authoritative one; rollback uses that."
  backup_volume_to "${LIVE_PROJECT}_${VOL_STATE}" "$HOT_H2"
  verify_backup "$HOT_H2" 'fsm\.mv\.db' "orchestrator H2 (hot)"
}

# ---------------------------------------------------------------------------------------------------
# STEP 3 — STOP, then COLD backups
# ---------------------------------------------------------------------------------------------------
stop_stack() {
  STEP_NOW="step 3: stop the stack"
  hdr "STEP 3/8  STOP THE STACK"
  # NEVER -v. `down -v` removes the named volumes, which is the single irreversible act available here.
  act docker compose "${COMPOSE_FILES[@]}" down
  if [ "$DRY" -eq 0 ]; then
    # A GUARD THAT PROTECTS THE RENAME, and here absence IS the goal: `compose down` REMOVES containers,
    # so "no such container" is the success shape and is reported as such rather than passed over. What
    # it must never do is fall silent — the names it loops over come from the compose file (step 1), so
    # a topology change cannot leave it checking an empty list and printing "all down".
    local c st
    for c in "${ALL_CTRS[@]}"; do
      st="$(ctr_state "$c")"
      ctr_running "$c" && die "$c is still running after \`compose down\` (state: $st) — not renaming anything under a live container. The container keeps a handle on the old inode and the next \`up -d\` rebinds to a path Docker will CREATE EMPTY rather than refuse, so this would end as a green \`docker ps\` over an empty /data."
      info "down: $c ($st)"
    done
    info "all ${#ALL_CTRS[@]} container(s) declared in $COMPOSE_FILE are down"
  fi

  STEP_NOW="step 3b: backups (cold)"
  hdr "STEP 3b   COLD BACKUP (the authoritative one)"
  COLD_H2="$BACKUP_DIR/orchestrator-state.cold.$TS.tar.gz"
  backup_volume_to "${LIVE_PROJECT}_${VOL_STATE}" "$COLD_H2"
  verify_backup "$COLD_H2" 'fsm\.mv\.db' "orchestrator H2 (cold)"

  # H2 leaves <db>.lock.db behind while a process holds the file. Its presence now means something still
  # has the store open, and copying a database out from under a live writer is how you get a file that
  # opens and is wrong.
  if [ "$DRY" -eq 0 ]; then
    qmark "docker run --rm -v ${LIVE_PROJECT}_${VOL_STATE}:/v:ro $HELPER_IMAGE ls /v"
    local listing
    listing="$(docker run --rm -v "${LIVE_PROJECT}_${VOL_STATE}":/v:ro "$HELPER_IMAGE" ls -la /v 2>/dev/null || true)"
    printf '%s\n' "$listing" | sed 's/^/        /'
    printf '%s\n' "$listing" | grep -q 'fsm\.mv\.db' || die "no fsm.mv.db in ${LIVE_PROJECT}_${VOL_STATE}"
    if printf '%s\n' "$listing" | grep -q 'fsm\.lock\.db'; then
      warn "fsm.lock.db is still present. With every container down this is a STALE lock (H2 removes it on"
      warn "a clean shutdown), which is expected after a kill; the backup above was still taken with"
      warn "nothing writing. If a process outside compose has this file open, stop it before continuing."
    fi
  fi
  info "backups on disk:"
  if [ "$DRY" -eq 0 ]; then ls -la "$BACKUP_DIR" | sed 's/^/        /'; fi
}

# ---------------------------------------------------------------------------------------------------
# STEP 4 — RENAME
# ---------------------------------------------------------------------------------------------------
rename_root() {
  STEP_NOW="step 4: rename the checkout"
  hdr "STEP 4/8  RENAME THE CHECKOUT"
  if [ "${RENAME_DONE:-0}" -eq 1 ]; then
    info "already at $NEW_ROOT — nothing to do"
  else
    [ -e "$NEW_ROOT" ] && die "$NEW_ROOT already exists"
    act mv "$OLD_ROOT" "$NEW_ROOT"
    if [ "$DRY" -eq 0 ]; then
      [ -d "$NEW_ROOT" ] || die "rename did not take"
      [ -e "$OLD_ROOT" ] && die "$OLD_ROOT still exists after the rename"
      info "renamed: $OLD_ROOT -> $NEW_ROOT"
    fi
  fi
  ROOT_NOW="$NEW_ROOT"
  COMPOSE_FILE="$NEW_ROOT/$REL_COMPOSE"
  COMPOSE_DIR="$(dirname "$COMPOSE_FILE")"
  # AND REBUILD THE FILE LIST, because it holds absolute paths under the OLD root. The array is built in
  # pre-flight, the directory moves here, and step 6's `up -d` is the next thing to use it — so leaving it
  # stale would restart the stack from a path that no longer exists (or, worse, from a leftover checkout
  # still sitting at the old path). Same rule as above: the override goes in whenever it is there.
  # Existence is asked of the copy that EXISTS RIGHT NOW (COMPOSE_READ's directory — under --dry-run the
  # mv did not happen, so the new path is empty), while the path that goes INTO the array is the new one,
  # which is where the file will be when step 6 runs it. Asking the new path under --dry-run would find
  # nothing and print a plan quietly missing the override — the exact class of wrong-but-plausible output
  # this script exists to avoid.
  COMPOSE_FILES=(-f "$COMPOSE_FILE")
  if [ -f "$(dirname "$COMPOSE_READ")/docker-compose.override.yml" ]; then
    COMPOSE_OVERRIDE="$COMPOSE_DIR/docker-compose.override.yml"
    COMPOSE_FILES+=(-f "$COMPOSE_OVERRIDE")
    info "override carried across the rename: $COMPOSE_OVERRIDE"
  fi
  # COMPOSE_READ is the copy that EXISTS RIGHT NOW, and it is not the same variable as COMPOSE_FILE for
  # one case that matters: under --dry-run the mv above did not happen, so steps 5a and 5b must still read
  # the file at the old path. Reading the new path there would find nothing, take the "the project name
  # changed" branch on an empty string, and print an alarming volume migration that a real run would never
  # perform — a dry run that lies about the plan is worse than no dry run.
  if [ -f "$COMPOSE_FILE" ]; then
    COMPOSE_READ="$COMPOSE_FILE"
    # The repo root IS a bind mount (/data, read-only, on the pipeline container and on the profile-gated
    # engine whenever it is started), so a rename that half-took gives every container an empty
    # root-owned directory and a green `docker ps`.
    # Checked by a file the stack actually reads rather than by `-d`, which Docker would create.
    [ -d "$NEW_ROOT/prompts" ] || die "$NEW_ROOT/prompts is not there — /data would come back EMPTY (Docker creates a missing bind source instead of failing), every stage would fall back to its compiled-in prompt and every ingest would answer 422. Roll back."
    info "the worktree followed the rename: $NEW_ROOT/prompts"
    [ -f "$COMPOSE_DIR/.env" ] || warn ".env is NOT at $COMPOSE_DIR/.env — QWEN_*/GITHUB_TOKEN would come up unset and every model stage would fail CLOSED (200, verdict downgraded, no error). Post-flight checks this."
  elif [ "$DRY" -eq 1 ]; then
    COMPOSE_READ="$OLD_ROOT/$REL_COMPOSE"
    info "(--dry-run) the mv did not happen, so step 5 reads the file where it still is: $COMPOSE_READ"
  else
    die "no compose file at $COMPOSE_FILE after the rename"
  fi
}

# ---------------------------------------------------------------------------------------------------
# STEP 5 — the volumes
#
# There is no host to rewrite any more. n8n's N8N_HOST / WEBHOOK_URL / N8N_EDITOR_BASE_URL were the only
# settings in the compose file that named this deployment's external address, and they went with the
# service; nothing in the live stack knows its own address at all. Caddy, which does, serves both names
# from one site block and is not this script's business.
# ---------------------------------------------------------------------------------------------------

# Every file's sha256, in one digest. Verifying the COPY by content is stronger than counting rows in it:
# it answers "is the new volume byte-for-byte the old one" for the whole tree, including the Batch tables
# and the trace files, not just for the one thing you remembered to count.
volume_digest() {  # <volume>
  docker run --rm -v "$1":/v:ro "$HELPER_IMAGE" \
    sh -c 'cd /v && find . -type f -print0 | sort -z | xargs -0 -r sha256sum | sha256sum | cut -d" " -f1' 2>/dev/null || true
}

# The physical name a volume block pins, or empty if it pins none.
#
# Reads the `name:` INSIDE one volume's block under the top-level `volumes:` mapping. Anchored on
# two-space indent for the key and four-space for its `name:`, which is the shape the compose file
# actually uses; a block with no `name:` returns empty, and that is the case step 5 refuses to proceed
# on. Deliberately NOT reusing compose_project_name(), which takes the FIRST `name:` in the file — the
# project name at the top — and would answer `fsm` for every volume.
volume_pinned_name() {  # <compose-file> <volume-key>
  awk -v key="$2" '
    /^volumes:[[:space:]]*$/ { inv = 1; next }
    /^[^[:space:]#]/         { inv = 0 }                       # any other top-level key ends the block
    inv && $0 ~ "^  " key ":[[:space:]]*$" { cur = 1; next }
    inv && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ { cur = 0 }      # the next sibling volume
    inv && cur && /^[[:space:]]+name:[[:space:]]*[^[:space:]]/ {
      sub(/^[[:space:]]*name:[[:space:]]*/, ""); gsub(/["\047]/, ""); sub(/[[:space:]].*$/, "");
      print; exit }
  ' "$1"
}

# STEP 5 — VERIFY THE PINS. It does not copy anything, and that is the point.
#
# This step used to migrate volumes between project prefixes, back when Compose derived
# `<project>_<key>` and renaming `name:` abandoned the store. The compose file now pins each volume's
# physical name, so that derivation no longer happens and the copy it used to perform would write a
# name Compose is guaranteed never to resolve — leaving "migrated" data in a volume nothing mounts
# while the stack ran on the original. Worse than a no-op: a green step that moved nothing.
#
# What is left is the invariant the safety actually rests on. If a volume pins a physical name, the
# rename cannot lose it. If one does NOT, the rename can silently mint it empty, and that is a hard
# stop BEFORE the stack is touched rather than a discovery afterwards.
assert_volumes_pinned() {
  STEP_NOW="step 5: volume pins"
  hdr "STEP 5   VOLUME PINS"
  DECLARED_PROJECT="$(compose_project_name "$COMPOSE_READ")"

  local v pinned bad=""
  for v in "$VOL_STATE" "$VOL_JAVA_CACHE"; do
    pinned="$(volume_pinned_name "$COMPOSE_READ" "$v")"
    if [ -z "$pinned" ]; then
      bad="$bad $v"
      warn "volume '$v' pins NO physical name in $COMPOSE_READ"
    else
      info "volume '$v' pins '$pinned'"
      vol_exists "$pinned" || warn "$pinned does not exist yet (first run on this host?)"
    fi
  done

  if [ -n "$bad" ]; then
    die "these volumes pin no physical \`name:\`:$bad
       Without a pin, Compose derives <project>_<key> — so renaming \`name: $DECLARED_PROJECT\` makes it
       look for a volume that does not exist, CREATE IT EMPTY, and start a healthy orchestrator on an
       empty backlog. schema.sql recreates the tables, so nothing errors and the dashboard simply reads
       zero. Add an explicit \`name:\` to each volume block matching the volume that exists TODAY
       (\`docker volume ls\`), then run this again. Nothing has been touched."
  fi

  cat <<PINNED

      NOTHING IS COPIED, AND THAT IS THE CORRECT OUTCOME.

      Both volumes pin their physical name, so the project name is not load-bearing data retention:
          $(volume_pinned_name "$COMPOSE_READ" "$VOL_STATE")   (H2: the markers, the artifacts, the drafted PR bodies)
          $(volume_pinned_name "$COMPOSE_READ" "$VOL_JAVA_CACHE")
      Renaming \`name:\`, renaming the directory, or both, leaves these resolving to the same bytes.
      Post-flight step 3 checks the running container actually mounted the pinned name, which is the
      only place that question can be answered honestly.

      ${LIVE_PROJECT}_${VOL_JS_CACHE} is deliberately not in that list. The service that mounted it
      (fsm-java-runner) is deleted, so nothing resolves it under any project name; it still exists on
      disk and still holds tokenized clone URLs. --wipe-runner-cache is what retires it.
PINNED
}

# ---------------------------------------------------------------------------------------------------
# --wipe-runner-cache
#
# THE DELETED NODE RUNNER'S CLONES HOLD A LIVE CREDENTIAL, AND DELETING THE SERVICE DID NOT RETIRE IT.
# Every checkout java-runner/src/server.js made was cloned with `https://<token>@github.com/...`, and
# git writes that URL verbatim into .git/config as remote.origin.url. That service is gone from
# docker-compose.yml and its container is gone from the host — but a `docker volume` outlives both, so
# ${LIVE_PROJECT}_${VOL_JS_CACHE} is still on disk with every one of those copies in it, and rotating
# GITHUB_TOKEN in .env does not reach into a .git/config on a volume.
#
# WHY IT IS STILL WORTH A STEP rather than a note: the prover ADOPTS an existing checkout instead of
# re-cloning it (prepareWs takes any directory with a .git), and adoption never rewrites
# remote.origin.url. So the one thing that must never happen is a service being pointed back at this
# volume — which is exactly why docker-compose.yml mounts `fsm-runner-cache` at /cache on the pipeline
# service and why DeploymentTest fails the build if that mount ever names java-runner again.
#
# The migration already has the stack down, which is the one moment when deleting the volume costs
# nothing at all. It is OPT-IN because it is a security cleanup that happens to be free right now, not
# part of moving a directory — and because a migration that silently deleted a volume would be a
# surprise. The cost is now zero rather than "a cold re-clone per repository": no live service mounts
# this volume, so nothing re-clones into it.
# ---------------------------------------------------------------------------------------------------
wipe_js_cache() {
  STEP_NOW="step 5b: retire the leaked clone credentials"
  hdr "STEP 5b   RETIRE THE TOKENIZED CLONES (--wipe-runner-cache)"
  local old="${LIVE_PROJECT}_${VOL_JS_CACHE}"
  if [ "$WIPE_JS_CACHE" -eq 0 ]; then
    cat <<OPTIN
      Not requested. Note for later, because it is a live credential and not housekeeping:
      the clones in $old carry https://<token>@github.com/... in their .git/config. The service that
      wrote them is deleted, but the VOLUME is not — and rotating GITHUB_TOKEN in .env does not touch a
      file on a volume. Nothing in the live stack mounts it, so deleting it costs nothing:
          docker volume rm $old
      Re-run this script with --wipe-runner-cache to have it done inside the window.
OPTIN
    return 0
  fi
  vol_exists "$old" || { info "$old does not exist — nothing to retire"; return 0; }
  if [ "$DRY" -eq 0 ]; then
    # Count the exposed checkouts WITHOUT printing any of them: the match is the secret.
    local n
    n="$(docker run --rm -v "$old":/v:ro "$HELPER_IMAGE" \
          sh -c 'grep -rlE "https://[^/@[:space:]]+@github\.com" /v --include=config 2>/dev/null | wc -l' 2>/dev/null | tr -d ' ' || echo "?")"
    info "$n checkout(s) in $old hold a tokenized remote URL (paths and values deliberately not printed)"
  fi
  act docker volume rm "$old"
  info "$old removed. No service in docker-compose.yml mounts it, so nothing recreates it."
  info "Nothing to roll back — a build cache is not data, and this one belonged to a deleted service."
}

# ---------------------------------------------------------------------------------------------------
# STEP 6 — START + WAIT FOR HEALTH
# ---------------------------------------------------------------------------------------------------
start_stack() {
  STEP_NOW="step 6: start from the new path"
  hdr "STEP 6/8  START FROM $NEW_ROOT AND WAIT FOR HEALTH"
  # No --build. The images already exist and rebuilding mid-migration adds a second variable to a change
  # that is supposed to be only a path. (A rebuilt image needs `up -d --no-deps <svc>` afterwards.)
  act docker compose "${COMPOSE_FILES[@]}" up -d
  if [ "$DRY" -eq 1 ]; then
    plan "wait until $MAIN_CTR reports healthy and the published port answers /healthz"
    return 0
  fi

  local deadline c st ok
  # WAIT FOR WHAT `up -d` ACTUALLY STARTED, which is $MAIN_CTR and nothing else. A profile-gated
  # container is only waited for if it is already running (an operator may have brought the engine up by
  # hand), because `docker inspect` on a container that does not exist never becomes `healthy` — the old
  # loop over three names spent the FULL per-target budget, 420 seconds each, staring at two containers
  # that no longer existed and then printed TIMEOUT for both. Seven wasted minutes reported as a fault.
  #
  # $MAIN_CTR's HEALTHCHECK runs a real query against the marker table, so `healthy` here already means
  # H2 answered — which is the migration's central question.
  #
  # THE BUDGET IS PER TARGET, not one shared deadline: the start-period is 90s and the way up does schema
  # work, so a shared clock would let the first target spend the whole allowance and leave the rest
  # nothing — reporting a service as unhealthy when it had simply not been waited for.
  local waitfor=("$MAIN_CTR") oc
  if [ "${#OPT_CTRS[@]}" -gt 0 ]; then
    for oc in "${OPT_CTRS[@]}"; do
      if ctr_running "$oc"; then waitfor+=("$oc")
      else info "not waiting for $oc: profile-gated and not running, which is what \`up -d\` is supposed to leave it as"; fi
    done
  fi
  for c in "${waitfor[@]}"; do
    printf '      waiting for %s ' "$c"
    ok=0
    deadline=$((SECONDS + HEALTH_TIMEOUT_S))
    while [ "$SECONDS" -lt "$deadline" ]; do
      # `| tr -d` for the same reason ctr_state has it: a failing `docker inspect` prints an empty line
      # to stdout first, so `|| echo missing` yields "\nmissing", which matches neither `missing` nor
      # anything else — and the loop would spend the whole budget on a container that is not there.
      st="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$c" 2>/dev/null | tr -d '[:space:]' || true)"
      [ -n "$st" ] || st=missing
      case "$st" in
        healthy) ok=1; break ;;
        none) ok=1; printf '(no healthcheck) '; break ;;
        # `missing` means `up -d` did not create it at all — waiting cannot fix that, so stop waiting and
        # let post-flight fail it by name rather than burning the whole budget on an absent container.
        missing) break ;;
        *) printf '.'; sleep 5 ;;
      esac
    done
    if [ "$ok" -eq 1 ]; then printf ' %s\n' "$st"; else printf ' NOT HEALTHY (%s) — post-flight will report it\n' "$st"; fi
  done
  # The PUBLISHED port, which is a different question from the container's health: the healthcheck asks
  # from inside, so it says nothing about the loopback publish that every `curl` in the post-flight — and
  # every operator on an SSH session — actually uses.
  printf '      waiting for the published port '
  ok=0
  deadline=$((SECONDS + HEALTH_TIMEOUT_S))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -fsS -m 5 -o /dev/null "$ORCH_HOST_URL/healthz" 2>/dev/null; then ok=1; break; fi
    printf '.'; sleep 3
  done
  if [ "$ok" -eq 1 ]; then printf ' ready\n'; else printf ' TIMEOUT after %ss (post-flight will report it)\n' "$HEALTH_TIMEOUT_S"; fi
  docker compose "${COMPOSE_FILES[@]}" ps | sed 's/^/      /'
}

# ---------------------------------------------------------------------------------------------------
# STEP 7 — POST-FLIGHT
#
# It ASKS THE SYSTEM. Every check below is a question put to a running process — the pipeline container,
# docker's own view of the mounts, the endpoints the stages call — and not a restatement of what this
# script believes it did. The script's beliefs are exactly what a migration can be wrong about.
#
# AND THE LIST IT PROMISES IS THE LIST IT PERFORMS. The --dry-run summary below used to advertise "all
# three containers running", an engine reachability check and a separate runner /health — three checks
# aimed at containers that had stopped existing. A summary listing checks the script does not perform is
# the same defect as a guard that skips silently: it reads as coverage. Whenever a check is added,
# removed or retargeted, the numbered list and the code move together.
#
# It REPORTS rather than judges where reporting is the honest verb: an endpoint that ANSWERS anything at
# all (200, 401, 404) passes, because which hosts are legitimate is not this script's business — a guard
# that made that call has already taken this production down once. Only "cannot resolve" and "cannot
# connect" fail, because those are wiring, they are permanent, and wiring is what a migration moves.
#
# WHAT IS ALLOWED TO BE ABSENT, and it is exactly one thing: a profile-gated service. `docker compose
# up -d` does not start it, so its container being missing is the CORRECT outcome and is reported as a
# skip with its reason — never as a PASS, and never as nothing at all. Everything else is asked of
# $MAIN_CTR, and if $MAIN_CTR is not there the checks that needed it FAIL by name.
# ---------------------------------------------------------------------------------------------------
postflight() {
  STEP_NOW="step 7: post-flight"
  hdr "STEP 7/8  POST-FLIGHT"
  if [ "$DRY" -eq 1 ]; then
    local optnote="none declared"
    if [ "${#OPT_CTRS[@]}" -gt 0 ]; then optnote="${OPT_CTRS[*]}"; fi
    cat <<DRYPF
  ?   every check below would be asked of the running system. $MAIN_CTR is REQUIRED — a check that
      cannot reach it FAILS by name. Profile-gated containers ($optnote) are optional: absent is the
      normal outcome of \`up -d\` and prints as a skip with its reason, never as a pass.

      1  $MAIN_CTR is running (the one container \`docker compose up -d\` starts); every other
         container the compose file declares is reported with its state
      2  no container mount still points at $OLD_ROOT — and if $MAIN_CTR could not be scanned, that is
         a FAIL rather than a clean sheet
      3  $MAIN_CTR's /state is the volume the compose file PINS, and that volume holds fsm.mv.db
      4  GET $ORCH_HOST_URL/healthz == 200 'ok'  (it runs a real query, so this proves H2 answers)
      5  marker count == the count recorded in step 2; artifact count == recorded; nothing un-settled
      6  the prover's workspace inside $MAIN_CTR: /cache is the PINNED cache volume and is writable
         (the prove runs in this process now — an unmounted /cache re-clones every repository)
      7  the prover's toolchain inside $MAIN_CTR: a non-empty JDK list, plus git and mvn on PATH
      8  QWEN_BASE_URL / SVACE_BASE_URL answer from $MAIN_CTR (any HTTP status counts; only "no answer"
         is a failure, because that is wiring)
      9  QWEN_*/GITHUB_TOKEN are non-empty in $MAIN_CTR (set/unset only, never the values)
      10 /data/data/svace resolves in $MAIN_CTR and /data/prompts/verdict.txt is readable
      11 the dashboard $MAIN_CTR serves itself answers /api/state on the published port
      12 the profile-gated engine, ONLY IF it is running: /health from inside $MAIN_CTR. Not running is
         the normal state and is printed as a skip — nothing in a run calls it
DRYPF
    return 0
  fi

  [ -f "$REC_FILE" ] || die "post-flight has nothing to compare against: $REC_FILE is missing"
  # shellcheck disable=SC1090
  eval "$(only_kv < "$REC_FILE")"
  REC_MARKERS="${MARKERS:-}"; REC_ARTIFACTS="${ARTIFACTS:-}"
  REC_NEW="${ST_new:-0}"; REC_PROVING="${ST_proving:-0}"
  info "recorded before the move: ${REC_MARKERS:-?} markers, ${REC_ARTIFACTS:-?} artifacts"

  # 1 — THE CONTAINER THAT MUST BE UP, and the ones whose absence is the correct outcome.
  #     `docker compose up -d` starts $MAIN_SVC and nothing else; a profile-gated service stays down
  #     until somebody names it. So this is one required check plus a report — not "all N are running",
  #     which is what it used to say, and which would have been a FAIL on every healthy stack the moment
  #     the engine went behind a profile.
  local c i
  if ctr_running "$MAIN_CTR"; then
    pass "$MAIN_CTR is running (the one container \`docker compose up -d\` starts)"
  else
    fail "$MAIN_CTR is $(ctr_state "$MAIN_CTR") — the pipeline, the dashboard and the prover are all this one container, so nothing is running at all"
  fi
  if [ "${#OPT_CTRS[@]}" -gt 0 ]; then
    for i in $(seq 0 $((${#OPT_CTRS[@]} - 1))); do
      c="${OPT_CTRS[$i]}"
      if ctr_running "$c"; then
        info "$c is also running (profile '${OPT_PROFS[$i]}', started by hand — nothing in a run calls it)"
      else
        info "$c is $(ctr_state "$c") — SKIPPED, not failed: it is gated behind profile '${OPT_PROFS[$i]}', so \`up -d\` deliberately does not start it. Start it with \`docker compose --profile ${OPT_PROFS[$i]} up -d ${OPT_SVCS[$i]}\`."
      fi
    done
  fi

  # 2 — no mount still points at the old path. This is the silent one: Docker CREATES a missing bind
  #     source instead of refusing to start, so a stale path gives a container an empty root-owned
  #     directory and a green `docker ps`.
  #     MATCHED AS A WHOLE PATH, not as a substring, and that is not pedantry here: the old name is a
  #     PREFIX of the new one (fix-java-svace-markers / fix-java-svace-markers-spring), so a
  #     `grep -q "$OLD_ROOT"` would match every correctly migrated mount and fail this check on every
  #     successful migration. A guard that cries wolf on the happy path is worse than no guard.
  #     AND A CLEAN SHEET ONLY COUNTS IF SOMETHING WAS ACTUALLY READ. The loop skips containers that are
  #     not running, which is right — a stopped container mounts nothing — but "I scanned nothing" and
  #     "I scanned everything and found nothing" print identically unless the skips are named. They are.
  local stale="" src scanned=0 skipped=""
  for c in "${ALL_CTRS[@]}"; do
    if ! ctr_running "$c"; then skipped="$skipped $c"; continue; fi
    scanned=$((scanned + 1))
    while IFS= read -r src; do
      [ -n "$src" ] || continue
      case "$src" in
        "$OLD_ROOT"|"$OLD_ROOT"/*) stale="$stale $c:$src" ;;
      esac
    done < <(docker inspect -f '{{range .Mounts}}{{.Source}}{{"\n"}}{{end}}' "$c" 2>/dev/null || true)
  done
  [ -n "$skipped" ] && info "not scanned for stale mounts (not running):$skipped"
  if [ -n "$stale" ]; then
    fail "these containers still mount the OLD path:$stale"
  elif ! ctr_running "$MAIN_CTR"; then
    # The one container that MUST have been scanned was not. Reporting "no stale mounts" here would be
    # the exact defect this file is about: a check that did not run, printed as a check that passed.
    fail "could not check for stale mounts: $MAIN_CTR is not running, so the container that binds $NEW_ROOT at /data was never inspected"
  else
    pass "no container mounts anything under $OLD_ROOT ($scanned container(s) inspected; matched as a path, not a prefix)"
  fi

  # 3 — the pipeline is running on the volume we think it is, and the H2 file is in it.
  local vol
  vol="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/state"}}{{.Name}}{{end}}{{end}}' "$MAIN_CTR" 2>/dev/null || true)"
  # THE PINNED NAME, not ${DECLARED_PROJECT}_${VOL_STATE}. The compose file pins each volume's physical
  # name, so Compose does not derive the project prefix — and a migration that renamed the project would
  # still (correctly) mount the pinned name. Deriving it here asserted the volume Compose would have
  # resolved under the OLD model, so this check failed on migrations that had actually succeeded, and
  # post-flight failing kills main() with "Do not start a prove. Roll back."
  local want_vol
  want_vol="$(volume_pinned_name "$COMPOSE_READ" "$VOL_STATE")"
  if [ -z "$want_vol" ]; then
    fail "no physical \`name:\` pinned for $VOL_STATE in $COMPOSE_READ — step 5 should have stopped this run"
  elif [ "$vol" = "$want_vol" ]; then pass "$MAIN_CTR /state -> volume $vol (the pinned name)"
  else fail "$MAIN_CTR /state -> '${vol:-<none>}' (expected the pinned $want_vol)"; fi
  if docker exec "$MAIN_CTR" test -f /state/fsm.mv.db 2>/dev/null; then pass "/state/fsm.mv.db exists in the running $MAIN_CTR"
  else fail "/state/fsm.mv.db is MISSING — the H2 store the markers live in is not there (or $MAIN_CTR is not running to be asked)"; fi

  # 4 — /healthz. It runs a real query against the marker table, so a 200 here is H2 answering, not a
  #     hardcoded string (that defect was fixed on 2026-07-29).
  # `|| true`, never `|| echo 000`: curl PRINTS its 000 before exiting non-zero, so a fallback echo
  # concatenates into "000000" — which is not 000, and would sail past a `= "000"` comparison.
  local body code
  code="$(curl -sS -o /dev/null -m 15 -w '%{http_code}' "$ORCH_HOST_URL/healthz" 2>/dev/null || true)"
  body="$(curl -sS -m 15 "$ORCH_HOST_URL/healthz" 2>/dev/null || true)"
  if [ "$code" = "200" ]; then pass "$ORCH_HOST_URL/healthz -> 200 '$body' (a real query against the marker table)"
  else fail "$ORCH_HOST_URL/healthz -> HTTP ${code:-000} '$body' — H2 is not answering on the migrated volume"; fi

  # 5 — THE COUNT. The one number the whole migration exists to preserve.
  local raw
  raw="$(read_orch || true)"
  if [ -n "$raw" ]; then
    unset MARKERS ARTIFACTS RUNNING_JOBS ST_new ST_proving
    eval "$raw"
    local now_markers="${MARKERS:-}" now_artifacts="${ARTIFACTS:-}"
    if [ -n "$REC_MARKERS" ] && [ "$now_markers" = "$REC_MARKERS" ]; then
      pass "marker count is $now_markers — equals what step 2 recorded"
    else
      fail "MARKER COUNT CHANGED: recorded ${REC_MARKERS:-?}, now ${now_markers:-?}. The stack may be running on an EMPTY volume. Roll back."
    fi
    if [ -n "$REC_ARTIFACTS" ] && [ "$now_artifacts" = "$REC_ARTIFACTS" ]; then
      pass "artifact count is $now_artifacts — equals what step 2 recorded"
    else
      fail "ARTIFACT COUNT CHANGED: recorded ${REC_ARTIFACTS:-?}, now ${now_artifacts:-?} (every drafted PR body lives in these rows)"
    fi
    # Nothing may have become UNSETTLED. 'proving' -> 'new' is expected and fine: StartupReconciler
    # releases a marker whose prover no longer exists, which is what the stop did to any in-flight one.
    local rec_unsettled=$((REC_NEW + REC_PROVING))
    local now_unsettled=$(( ${ST_new:-0} + ${ST_proving:-0} ))
    if [ "$now_markers" != "$REC_MARKERS" ]; then
      # Not comparable, and saying so matters: on an EMPTY database this arithmetic reads 0 <= 200 and
      # would print a reassuring PASS next to the failure that says every marker is gone.
      info "unsettled markers not compared — the total already disagrees, which is the failure above"
    elif [ "$now_unsettled" -le "$rec_unsettled" ]; then
      pass "unsettled markers: $now_unsettled (was $rec_unsettled) — no settled verdict was re-opened"
    else
      fail "unsettled markers went UP: $now_unsettled vs $rec_unsettled — settled verdicts have been lost"
    fi
    [ "${ST_proving:-0}" -eq 0 ] || info "note: ${ST_proving} marker(s) read 'proving'; the schedule may already have claimed one"
  else
    # A check that quietly reports nothing reads as a check that passed. It did not run.
    fail "could not read /api/state after the move (so the marker count is unverified)"
  fi

  # 6 — THE PROVER'S WORKSPACE. This replaces a check that posted to http://fsm-runner:8090/health from
  #     inside the orchestrator. There is no such container and no such hop: the prove runs in THIS
  #     process (fsm.runner.mode=local), so the question "can a marker be proved after the move" is not
  #     about a port any more — it is about the two things the prove needs from the filesystem.
  #
  #     /cache FIRST, because losing it is silent and expensive. The clones and every target/ live on the
  #     named volume mounted there; unmounted, /cache is the container's writable layer, every `up -d`
  #     throws away every checkout, and the only symptom is minutes per repository, over 282 markers,
  #     with nothing red anywhere.
  local cache_vol want_cache
  cache_vol="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/cache"}}{{.Name}}{{end}}{{end}}' "$MAIN_CTR" 2>/dev/null || true)"
  want_cache="$(volume_pinned_name "$COMPOSE_READ" "$VOL_JAVA_CACHE")"
  if [ -z "$want_cache" ]; then
    fail "no physical \`name:\` pinned for $VOL_JAVA_CACHE in $COMPOSE_READ — step 5 should have stopped this run"
  elif [ -z "$cache_vol" ]; then
    fail "$MAIN_CTR has NO named volume at /cache (expected the pinned $want_cache) — /cache is the writable layer, so every \`up -d\` re-clones every repository and drops every target/"
  elif [ "$cache_vol" = "$want_cache" ]; then
    pass "$MAIN_CTR /cache -> volume $cache_vol (the pinned name)"
  else
    # NOT a nitpick: the retired JS runner's volume still exists and still holds tokenized clone URLs,
    # and this process ADOPTS an existing checkout rather than re-cloning it — so a wrong name here
    # carries a credential forward past a rotation of GITHUB_TOKEN. See --wipe-runner-cache.
    fail "$MAIN_CTR /cache -> '$cache_vol' but the compose file pins '$want_cache'. If that is ${LIVE_PROJECT}_${VOL_JS_CACHE}, the prover is about to adopt checkouts whose .git/config still carries https://<token>@github.com/…"
  fi
  # WRITABLE BY THE UNPRIVILEGED USER, asked as that user rather than assumed: the process runs as uid
  # 10002 and a volume it cannot write is a prove that fails on its first clone.
  if docker exec "$MAIN_CTR" sh -c 'test -d /cache && test -w /cache' 2>/dev/null; then
    pass "/cache is present and writable by the user $MAIN_CTR runs as"
  else
    fail "/cache is missing or not writable inside $MAIN_CTR — every clone and every build fails, one marker at a time"
  fi

  # 7 — THE PROVER'S TOOLCHAIN, which is the half of the deleted runner /health check that carried real
  #     information: it reported {"ok":true,"jdks":[…]} and an EMPTY jdks list meant every build would
  #     fail later, reading as a broken repository rather than as a broken image. The same fact is now
  #     read straight off the filesystem of the container that runs the builds.
  local jdks="" rc=0
  jdks="$(docker exec "$MAIN_CTR" sh -c 'ls -1 /opt/jdk 2>/dev/null | tr "\n" " "' 2>/dev/null)" || rc=$?
  if [ "$rc" -ne 0 ]; then
    fail "could not list /opt/jdk in $MAIN_CTR (docker exec exited $rc) — the JDK toolchain is unverified"
  elif [ -z "${jdks// /}" ]; then
    fail "$MAIN_CTR has NO JDKs under /opt/jdk — Build.requiredJdk picks a major per repository, so every prove fails on a JDK that is not there"
  else
    pass "$MAIN_CTR has JDKs: $jdks"
  fi
  if docker exec "$MAIN_CTR" sh -c 'command -v git >/dev/null && command -v mvn >/dev/null' 2>/dev/null; then
    pass "git and mvn are on PATH in $MAIN_CTR (it clones and builds in this process)"
  else
    fail "git or mvn is NOT on PATH in $MAIN_CTR — this container clones the repository and runs Maven itself now"
  fi

  # 8 — the endpoints that travel in the request. Reported, not judged: any HTTP status means the route
  #      exists. Only 000 (no answer at all: DNS or connect) is a failure, and that is wiring.
  #
  #      ASKED FROM $MAIN_CTR, AND FAILED IF IT CANNOT BE ASKED. The loop used to run over the engine and
  #      the orchestrator with `ctr_running "$from" || continue`, so with the engine profile-gated and the
  #      orchestrator renamed it could iterate over two absent containers and print NOTHING — a silence
  #      that reads as "no problems found". These stages fail CLOSED, which is precisely why the check
  #      cannot be allowed to vanish: a marker settles with a downgraded verdict nobody made.
  local from to v bad
  if ! ctr_running "$MAIN_CTR"; then
    fail "cannot check QWEN_BASE_URL/SVACE_BASE_URL reachability: $MAIN_CTR is not running. These stages fail CLOSED, so an unreachable model endpoint produces a green run in which every marker settles with a verdict nobody made — this is not something to leave unchecked"
  else
    for v in QWEN_BASE_URL SVACE_BASE_URL; do
      to="$(docker exec "$MAIN_CTR" printenv "$v" 2>/dev/null || true)"
      if [ -z "$to" ]; then info "$v is unset in $MAIN_CTR — nothing to reach"; continue; fi
      code="$(probe_http "$MAIN_CTR" "$to")"
      if [ -z "$code" ]; then
        fail "$v: the question could not be PUT from $MAIN_CTR (no HTTP status came back from \`docker exec … curl\`), so reachability is unverified — which is not the same as reachable"
      elif [ "$code" = "000" ]; then
        fail "$v is NOT REACHABLE FROM $MAIN_CTR — every stage that calls it fails CLOSED (200 with a downgraded verdict, no error anywhere)"
      else
        pass "$v reachable from $MAIN_CTR (HTTP $code; any answer counts)"
      fi
    done
  fi
  # The same question of a profile-gated sidecar, only when one is up: it runs the same judgement code
  # and makes the same model calls, so a debugging tool off the model's network judges nothing and says
  # so nowhere. Its absence is normal and is reported as a skip, in check 12.
  if [ "${#OPT_CTRS[@]}" -gt 0 ]; then
    for from in "${OPT_CTRS[@]}"; do
      ctr_running "$from" || continue
      for v in QWEN_BASE_URL SVACE_BASE_URL; do
        to="$(docker exec "$from" printenv "$v" 2>/dev/null || true)"
        if [ -z "$to" ]; then info "$v is unset in $from — nothing to reach"; continue; fi
        code="$(probe_http "$from" "$to")"
        if [ -z "$code" ]; then
          fail "$v: the question could not be PUT from $from (no HTTP status came back) — unverified"
        elif [ "$code" = "000" ]; then
          fail "$v is NOT REACHABLE FROM $from — the stage you replay by hand would fail CLOSED and answer with a safe default"
        else
          pass "$v reachable from $from (HTTP $code; any answer counts)"
        fi
      done
    done
  fi

  # 9 — the secrets came along. .env is untracked and moves with the directory, so this is really "did
  #     the .env survive": an unset QWEN_* or GITHUB_TOKEN produces green runs that judge nothing.
  #
  #     ONE CONTAINER TAKES SECRETS AND IT IS $MAIN_CTR. GITHUB_TOKEN used to be checked a second time in
  #     fsm-runner, because the clone happened there; the clone happens in this process now, so that
  #     block was not "also true" — it was a check of a container that cannot exist, silently passing
  #     over. The profile-gated engine is deliberately NOT asked: it holds no secrets by design, they
  #     arrive in the request, and there is exactly one source of truth for them — the caller's.
  if ! ctr_running "$MAIN_CTR"; then
    fail "cannot check the secrets: $MAIN_CTR is not running. An unset QWEN_*/GITHUB_TOKEN is invisible at runtime — the stack starts, /healthz is green, and every stage fails CLOSED"
  else
    bad=0
    for v in QWEN_BASE_URL QWEN_API_KEY QWEN_MODEL GITHUB_TOKEN; do
      if [ -n "$(docker exec "$MAIN_CTR" printenv "$v" 2>/dev/null || true)" ]; then info "$MAIN_CTR: $v is set"
      else info "$MAIN_CTR: $v is UNSET"; bad=1; fi
    done
    if [ "$bad" -eq 0 ]; then pass "$MAIN_CTR has all four secrets set, GITHUB_TOKEN included — it clones as well as judges (values never printed)"
    else fail "$MAIN_CTR is missing at least one of QWEN_BASE_URL/QWEN_API_KEY/QWEN_MODEL/GITHUB_TOKEN — check $COMPOSE_DIR/.env"; fi
  fi

  # 10 — the repo mount actually resolved. Both of these are documented silent failures: Docker CREATES
  #      a missing bind source rather than refusing to start, so a half-taken rename gives the container
  #      an empty read-only directory and a green `docker ps`. THE MOUNT THIS MIGRATION MOVES IS THIS
  #      ONE, so `docker exec` is run unguarded on purpose: on an absent container it exits non-zero and
  #      this reports FAIL, which is the honest answer — never a skip.
  if docker exec "$MAIN_CTR" test -d /data/data/svace 2>/dev/null; then pass "$MAIN_CTR's /data/data/svace resolves (csv_path is read here)"
  else fail "$MAIN_CTR cannot see /data/data/svace — every ingest would answer 422 (or the container is not running to be asked)"; fi
  if [ "${#OPT_CTRS[@]}" -gt 0 ]; then
    for c in "${OPT_CTRS[@]}"; do
      ctr_running "$c" || continue      # reported as a skip in check 12; it binds the same root read-only
      if docker exec "$c" test -d /data/data/svace 2>/dev/null; then pass "$c's /data/data/svace resolves"
      else fail "$c cannot see /data/data/svace — a replayed \`Parse markers\` would answer 422"; fi
    done
  fi
  # FSM_PROMPTS_DIR=/data/prompts is the whole prompt-tuning loop, and its failure is the quiet kind: a
  # missing directory is not an error, it is a silent fall-through to the prompt compiled into the class,
  # so a 26-hour run comes back having judged everything with last month's wording.
  if docker exec "$MAIN_CTR" test -f /data/prompts/verdict.txt 2>/dev/null; then
    pass "$MAIN_CTR sees /data/prompts (the five stage prompts are read from the worktree)"
  else
    fail "$MAIN_CTR cannot see /data/prompts/verdict.txt — every stage silently falls back to its compiled-in prompt"
  fi

  # 11 — the dashboard, the one screen an operator watches. The Node service that served it is deleted;
  #      $MAIN_CTR serves it itself, on the port Caddy reverse-proxies /dashboard/ to.
  if curl -fsS -m 15 -o /dev/null "$ORCH_HOST_URL/api/state" 2>/dev/null; then
    pass "the dashboard's /api/state answers on $ORCH_HOST_URL"
  else
    fail "the dashboard does not answer $ORCH_HOST_URL/api/state"
  fi

  # 12 — THE PROFILE-GATED ENGINE, and this is the one check allowed to report an absence as fine.
  #      Nothing in a run calls it — the pipeline embeds the same judgement code as a library — so `up -d`
  #      not starting it is the CORRECT outcome, and failing it would fail every healthy migration. It is
  #      still worth asking when it IS up, because this service is the only way to replay one stage
  #      against a request written by hand instead of a 6-26 hour run around it, and a migration that
  #      quietly left it unreachable would be discovered the next time somebody needed it most.
  #
  #      WHAT IT IS NOT ALLOWED TO DO IS SAY NOTHING. The previous revision asked
  #      `docker exec fsm-orchestrator curl http://fsm-engine:8092/health` unconditionally, from a
  #      container that no longer existed, and the FAIL it produced was about the wrong thing entirely.
  local engine_says eurl
  if [ "${#OPT_CTRS[@]}" -eq 0 ]; then
    info "no profile-gated service is declared in $COMPOSE_READ — nothing optional to check"
  else
    for i in $(seq 0 $((${#OPT_CTRS[@]} - 1))); do
      c="${OPT_CTRS[$i]}"
      if ! ctr_running "$c"; then
        info "SKIPPED (and this is the expected state): $c is not running. It is gated behind profile '${OPT_PROFS[$i]}', \`docker compose up -d\` does not start it, and nothing in a run calls it. To use it: docker compose --profile ${OPT_PROFS[$i]} up -d ${OPT_SVCS[$i]}"
        continue
      fi
      if ! ctr_running "$MAIN_CTR"; then
        fail "$c is running but $MAIN_CTR is not, so its reachability cannot be asked from the container that would call it"
        continue
      fi
      eurl="$(engine_net_url "$c")"
      # Captured rather than left to print itself, so the answer arrives inside the PASS/FAIL line instead
      # of unindented in the middle of the report. `-sS` not `-fsS`: a 4xx/5xx still proves the name
      # resolved and something is listening, and the body is what says which.
      engine_says="$(docker exec "$MAIN_CTR" curl -sS -m 15 "$eurl/health" 2>&1 || true)"
      case "$engine_says" in
        *'"service"'*) pass "$c answers $eurl/health from inside $MAIN_CTR ($engine_says)" ;;
        *) fail "$c is running but does NOT answer $eurl/health from inside $MAIN_CTR: ${engine_says:-<nothing>}" ;;
      esac
    done
  fi

  hdr "POST-FLIGHT RESULT"
  if [ "$PF_FAIL" -eq 0 ]; then
    printf '  ALL %s CHECKS PASSED.\n' "$PF_CHECKS"
    printf '  The stack is live from %s with %s markers and %s artifacts intact.\n' \
      "$NEW_ROOT" "${MARKERS:-?}" "${ARTIFACTS:-?}"
    # No "retire the old volumes" advice, because there are no old volumes: nothing was copied. The
    # pinned `name:` means the stack came back up on the SAME volumes it went down on, so the rollback
    # is the backups taken in step 2 plus moving the directory back — not a second copy of the store.
    printf '\n  The volumes are the ones the stack was already running on (pinned `name:`), so there is\n'
    printf '  no second copy to retire. Your rollback is the backups in %s.\n' "$BACKUP_DIR"
  else
    printf '  %s of %s CHECKS FAILED.\n' "$PF_FAIL" "$PF_CHECKS"
    return 1
  fi
}

# ---------------------------------------------------------------------------------------------------
main() {
  hdr "fsm migration: $OLD_ROOT -> $NEW_ROOT"
  if [ "$DRY" -eq 1 ]; then
    info "--dry-run: nothing that changes state will run. '+' = would do, '?' = asked for real."
  fi
  info "backups + recorded counts: $BACKUP_DIR"

  preflight
  backups_hot
  stop_stack
  rename_root
  assert_volumes_pinned
  wipe_js_cache
  start_stack
  postflight || die "POST-FLIGHT FAILED. The stack is up from the new path but at least one check above says the migration is not sound. Do not start a prove. Roll back."

  STEP_NOW="done"
  hdr "STEP 8/8  DONE"
}

main "$@"
