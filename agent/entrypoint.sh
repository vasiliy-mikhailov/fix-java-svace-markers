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
    # ONE CHECKOUT PER WORKER. Four provers resetting and cleaning one tree would delete each
    # other's test between the write and the build.
    dir="$CHECKOUTS/$(echo "$repo" | sed 's|.*/||; s|\.git$||')${WORKER:+-$WORKER}"
    if [ -d "$dir/.git" ]; then
        # -x, NOT just -fd. clean skips ignored files by default, and target/ is ignored — so a
        # class compiled from the previous marker's PATCH survives a reset that restored its source,
        # and Maven decides by timestamp whether to recompile. The next marker's RED then runs
        # against the last marker's fix, which is a green that belongs to somebody else.
        #
        # The dependency cache lives in ~/.m2, outside the checkout, so this costs a recompile and
        # not a re-download.
        git -C "$dir" reset --hard -q
        git -C "$dir" clean -xfdq
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
        # slice <markers> [worker] [of] — one marker per line, each on a clean tree.
        #
        # IT RESUMES. A settled marker is skipped, because a restart that re-proves what is already
        # done spends the endpoint twice and writes a second settlement for a marker that has one —
        # and a run interrupted at marker 30 of 40 should cost ten proves to finish, not forty.
        #
        # IT STRIPES. With `worker` of `of`, this process takes every `of`-th marker starting at
        # `worker`. No claim, no lock: the partition is arithmetic, so two workers cannot pick the
        # same marker and none is left for nobody.
        WORKER="${3:-}"
        OF="${4:-1}"
        MINE="${WORKER:-1}"
        OUT="$RESULTS${WORKER:+/w$WORKER}"
        mkdir -p "$OUT"
        export WORKER

        # Settled anywhere counts, not settled by me: the resume must see every worker's file or a
        # marker one of them finished is proved again by the next run.
        settled() {
            grep -lF "\"suspicion_key\":\"$1\"" "$RESULTS"/settlements.jsonl \
                "$RESULTS"/w*/settlements.jsonl 2>/dev/null | while read -r f; do
                grep -F "\"suspicion_key\":\"$1\"" "$f" | grep -qv "\"state\":\"proving\"" && echo hit
            done | grep -q hit
        }

        n=0; done_already=0; skipped=0
        while IFS= read -r marker; do
            [ -z "$marker" ] && continue
            n=$((n + 1))
            if [ "$OF" -gt 1 ] && [ $(( (n - 1) % OF + 1 )) -ne "$MINE" ]; then
                skipped=$((skipped + 1))
                continue
            fi
            if settled "$marker"; then
                done_already=$((done_already + 1))
                continue
            fi
            echo "=== [$n] ${WORKER:+w$WORKER }$marker" | tee -a "$OUT/slice.log"
            dir=$(checkout "$(repo_of "$marker")")
            # A prove that dies must not end the slice: it records its own infra row and the next
            # marker is still worth attempting.
            java -cp "$CP" tech.mikhailov.fsm.agent.Prove "$dir" "$marker" "$OUT" \
                >> "$OUT/slice.log" 2>&1 || true
        done < "$2"
        echo "SLICE DONE ($n marker(s), $done_already already settled, $skipped другому)" \
            | sed "s/ другому/ not mine/" | tee -a "$OUT/slice.log"
        ;;

    parallel)
        # parallel <markers> [n] — n workers, n checkouts, one striped queue.
        #
        # The dependency cache is warmed FIRST, once. Four cold Maven builds racing to populate one
        # local repository is how a repo gets a half-written jar in it, and every worker then fails
        # on a corrupt artifact nobody wrote deliberately.
        n="${3:-4}"
        first=$(head -1 "$2")
        warm=$(checkout "$(repo_of "$first")")
        echo "warming the dependency cache in $warm"
        (cd "$warm" && mvn -B -q -DskipTests test-compile >/dev/null 2>&1) || true
        i=1
        while [ "$i" -le "$n" ]; do
            "$0" slice "$2" "$i" "$n" &
            i=$((i + 1))
        done
        wait
        echo "ALL WORKERS DONE"
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
        echo "usage: prove 'repo|file|line|checker' | slice <markers> [w] [of] | parallel <markers> [n] | test [cases] | seed [cases] | dashboard" >&2
        exit 2
        ;;
esac
