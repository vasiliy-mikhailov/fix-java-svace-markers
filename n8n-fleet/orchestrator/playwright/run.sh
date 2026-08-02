#!/usr/bin/env bash
#
# The dashboard's browser tests: build the image, run the suite, report.
#
#   orchestrator/playwright/run.sh                      # from anywhere in the worktree
#   orchestrator/playwright/run.sh -Dtest=ThePageIsNotBlankTest
#
# Anything after the script name is appended to the Maven command inside the container, so a single
# class, `-o`, or `-X` all work without rebuilding.
#
# WHY THIS IS NOT A COMPOSE SERVICE. n8n/docker-compose.yml declares exactly three services — engine,
# orchestrator, runner — and DeploymentTest pins that list, because a fourth prover in the stack shares
# no lock with the runner and two processes patching one workspace is not a thing anybody debugs
# quickly. A test suite is also just not a long-lived service: it starts, asserts, exits, and has
# nothing to restart. `docker run --rm` is the shape that matches, and it keeps the deployment's
# service list a statement about what is deployed.
#
# --shm-size=1g: Docker's default 64 MB /dev/shm is not enough for Chromium's renderer, which crashes
# part-way through a test when it fills. The image also passes --disable-dev-shm-usage for the case
# where this flag is forgotten; both are cheap and the failure without either is confusing.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
reactor="$(cd "${here}/../.." && pwd)"

image="${FSM_UI_IMAGE:-fsm-orchestrator-ui:latest}"

# The Nexus mirror is opt-in and only reachable on its own network — see the Dockerfile. Set
# MAVEN_MIRROR_URL on the deployment host; leave it unset anywhere else and the build uses Central.
#
# THE ARRAYS ARE EXPANDED WITH A DEFAULT, and that is not decoration. `set -u` plus bash 3.2 — which is
# the bash every macOS ships — treats "${empty[@]}" as an unbound variable and aborts:
#
#     orchestrator/playwright/run.sh: line 38: network_args[@]: unbound variable
#
# So on the machine of anybody who has not set MAVEN_MIRROR_URL (i.e. everybody not on the deployment
# host) the documented command for the whole browser suite failed before it built anything. Bash 4.4+
# fixed the expansion, which is why this was invisible on Linux and in CI.
build_args=()
network_args=()
if [[ -n "${MAVEN_MIRROR_URL:-}" ]]; then
  build_args+=(--build-arg "MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL}")
  network_args+=(--network "${MAVEN_MIRROR_NETWORK:-mvn-cache}")
fi

echo "==> building ${image} from ${reactor}"
docker build ${network_args[@]+"${network_args[@]}"} ${build_args[@]+"${build_args[@]}"} \
  -f "${reactor}/orchestrator/playwright/Dockerfile" \
  -t "${image}" \
  "${reactor}"

echo "==> running the browser suite (every @Tag(\"ui\") class)"
exec docker run --rm --shm-size=1g "${image}" "$@"
