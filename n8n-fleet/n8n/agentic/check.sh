#!/usr/bin/env bash
# Validate the generated workflows without needing node, eslint or python on the host.
#
#   ./check.sh                      # all workflow_*.json in this directory
#   ./check.sh workflow_prover.json
#
# check_workflows.py needs `node` (syntax) and `eslint` (no-undef / no-use-before-define) to catch the
# runtime ReferenceErrors that n8n otherwise swallows into "no findings". mh has neither, so both live
# in the fsm-tools image instead of being a host prerequisite.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
IMAGE="fsm-tools:latest"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "building $IMAGE ..."
  DOCKER_BUILDKIT=1 BUILDX_BUILDER=default docker build -q -t "$IMAGE" "$DIR/../../tools" >/dev/null
fi

exec docker run --rm -v "$DIR":/work -w /work "$IMAGE" python3 check_workflows.py "$@"
