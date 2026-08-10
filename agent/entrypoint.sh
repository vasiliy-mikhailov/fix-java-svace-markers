#!/bin/sh
# prove | slice | dashboard. See SPEC.md.
set -eu

CP="/opt/agent/agent.jar:/opt/agent/lib/*"
RESULTS="${RESULTS:-/results}"
CHECKOUTS="${CHECKOUTS:-/work/checkouts}"

# THE CHECKOUT IS THIS SCRIPT'S JOB, not the agent's. A prove needs a tree that is exactly the
# upstream one: a previous prove leaves a test and a patch behind, and inheriting them would let a
# marker be "reproduced" by the fix for the marker before it.
checkout() {
    repo="$1"
    dir="$CHECKOUTS/$(echo "$repo" | sed 's|.*/||; s|\.git$||')"
    if [ -d "$dir/.git" ]; then
        git -C "$dir" reset --hard -q
        git -C "$dir" clean -fdq
    else
        mkdir -p "$CHECKOUTS"
        git clone --depth 1 "$repo" "$dir" >/dev/null 2>&1
    fi
    echo "$dir"
}

# repo|file|line|checker — the repository is the first field.
repo_of() { echo "$1" | cut -d'|' -f1; }

case "${1:-dashboard}" in

    prove)
        # prove 'repo|file|line|checker'
        marker="$2"
        dir=$(checkout "$(repo_of "$marker")")
        exec java -cp "$CP" tech.mikhailov.fsm.agent.Prove "$dir" "$marker" "$RESULTS"
        ;;

    slice)
        # slice /markers.txt — one marker per line, each on a clean tree
        n=0
        while IFS= read -r marker; do
            [ -z "$marker" ] && continue
            n=$((n + 1))
            echo "=== [$n] $marker"
            dir=$(checkout "$(repo_of "$marker")")
            # A prove that dies must not end the slice: it records its own infra row and the next
            # marker is still worth attempting.
            java -cp "$CP" tech.mikhailov.fsm.agent.Prove "$dir" "$marker" "$RESULTS" || true
        done < "$2"
        echo "SLICE DONE ($n marker(s))"
        ;;

    dashboard)
        exec java -cp "$CP" tech.mikhailov.fsm.agent.Dashboard \
            "$RESULTS/settlements.jsonl" "${PORT:-8087}"
        ;;

    *)
        echo "usage: prove 'repo|file|line|checker' | slice <markers-file> | dashboard" >&2
        exit 2
        ;;
esac
