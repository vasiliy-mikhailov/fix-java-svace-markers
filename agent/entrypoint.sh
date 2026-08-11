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
        # slice /markers.txt — one marker per line, each on a clean tree.
        #
        # IT RESUMES. A settled marker is skipped, because a restart that re-proves what is already
        # done spends the endpoint twice and writes a second settlement for a marker that has one —
        # and a run interrupted at marker 30 of 40 should cost ten proves to finish, not forty.
        settled() {
            [ -f "$RESULTS/settlements.jsonl" ] || return 1
            grep -F "\"suspicion_key\":\"$1\"" "$RESULTS/settlements.jsonl" 2>/dev/null \
                | grep -qv "\"state\":\"proving\""
        }
        n=0; done_already=0
        while IFS= read -r marker; do
            [ -z "$marker" ] && continue
            n=$((n + 1))
            if settled "$marker"; then
                done_already=$((done_already + 1))
                continue
            fi
            echo "=== [$n] $marker" | tee -a "$RESULTS/slice.log"
            dir=$(checkout "$(repo_of "$marker")")
            # A prove that dies must not end the slice: it records its own infra row and the next
            # marker is still worth attempting.
            # STDERR TO A FILE. A slice started with `docker exec -d` sends its output nowhere,
            # so every stack trace this program printed has been discarded — which is why a
            # NullPointerException in the record had no line number anywhere to find it.
            java -cp "$CP" tech.mikhailov.fsm.agent.Prove "$dir" "$marker" "$RESULTS" \
                >> "$RESULTS/slice.log" 2>&1 || true
        done < "$2"
        echo "SLICE DONE ($n marker(s), $done_already already settled)"
        ;;

    seed)
        # seed cases from a trace you have read
        java -cp "$CP" tech.mikhailov.fsm.agent.ModelTest --seed "$RESULTS/trace.jsonl" \
            "${2:-$RESULTS/cases.jsonl}"
        ;;

    test)
        # model tests: an agent, an input it has seen, the property its answer must still have
        dir=$(checkout "${REPO:-https://github.com/WebGoat/WebGoat.git}")
        exec java -cp "$CP" tech.mikhailov.fsm.agent.ModelTest \
            "$dir" "${2:-$RESULTS/cases.jsonl}" "$RESULTS"
        ;;

    dashboard)
        exec java -cp "$CP" tech.mikhailov.fsm.agent.Dashboard \
            "$RESULTS/settlements.jsonl" "${PORT:-8087}"
        ;;

    *)
        echo "usage: prove 'repo|file|line|checker' | slice <markers> | test [cases] | seed [cases] | dashboard" >&2
        exit 2
        ;;
esac
