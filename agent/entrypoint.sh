#!/bin/bash
# prove | slice | test | seed | dashboard. See SPEC.md.
#
# bash, not sh: the worker pool waits on `wait -n` and counts with `jobs -p`, neither of which is
# POSIX. A pool built without them either polls on a sleep or spawns everything at once.
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
    dir="$CHECKOUTS/$(echo "$repo" | sed 's|.*/||; s|\.git$||')"
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

    slice|parallel)
        # slice <markers> [n] — prove every marker, at most n at a time.
        #
        # A POOL, NOT A PARTITION. Striping markers across fixed workers means the one that drew the
        # slow half is still going while the others idle; a pool hands the next marker to whichever
        # prover is free, and the run finishes when the work does rather than when the unluckiest
        # slice does.
        #
        # THE CLAIM IS A MKDIR. It is the one filesystem operation that is atomic and tells the
        # loser it lost, so two provers cannot take the same marker and no lock file is left behind
        # by a process that died holding it.
        # THE WIDTH IS RE-READ EVERY TIME ROUND THE LOOP, so it can be changed while a run is
        # going. It used to be this argument and nothing else, fixed for the life of the pool, so
        # widening a run meant killing it — which orphans every claim in flight and throws away
        # whatever those markers had done.
        #
        # CLAMPED HERE AS WELL AS IN JAVA. This is the side that starts JVMs, and a file edited by
        # hand or left behind by an older version must not be able to start ninety of them.
        # CAPTURED OUT HERE. Inside the function $3 is the FUNCTION's third argument, which there
        # is never one of, so the fallback would have silently become the empty string.
        asked="${3:-4}"
        width() {
            w=$(cat "$RESULTS/workers" 2>/dev/null | tr -cd '0-9')
            [ -n "$w" ] || w="$asked"
            [ "$w" -ge 1 ] 2>/dev/null || w=4
            [ "$w" -le 16 ] 2>/dev/null || w=16
            echo "$w"
        }
        limit=$(width)
        mkdir -p "$RESULTS/claims" "$RESULTS/m"

        # THE REFERENCE IS PREPARED ONCE AND THEN READ ONLY. Every prove used to reset and clean it
        # on the way past, which meant four subshells mutating the repository the others were adding
        # worktrees from — and a worktree taken mid-clean is a directory with no pom.xml in it, which
        # this program reports as "nothing can run the test" for a marker that was fine.
        reference=$(checkout "$(repo_of "$(head -1 "$2")")")
        echo "reference clone ready at $reference; worktrees are per marker"

        # WHAT COUNTS AS SETTLED IS A DISPOSITION, NOT "ANYTHING THAT IS NOT PROVING".
        #
        # This used to skip a marker whose settlements file held any state but `proving`, which
        # meant `infra` — the state a prove writes when it THROWS — counted as an answer. A prove
        # killed by the tool ceiling therefore retired its own marker, and nothing would revisit it.
        # These seven are the states this program actually decides; everything else is a prove that
        # did not finish, and a marker that did not finish goes back in the queue.
        DISPOSITIONS='"state":"(false-positive|by-design|unprovable|reproduced|needs-review|verified/pr-ready|verified/pr-rejected)"'

        settled() {
            grep -rlF "\"suspicion_key\":\"$1\"" "$RESULTS"/m/*/settlements.jsonl 2>/dev/null \
                | while read -r f; do
                    grep -F "\"suspicion_key\":\"$1\"" "$f" \
                        | grep -qE "$DISPOSITIONS" && echo hit
                  done | grep -q hit
        }

        # A directory named for the marker, so a reader finds a prove by what it proved.
        slug() { echo "$1" | sed 's|.*/||; s|[^A-Za-z0-9._-]|_|g' | cut -c1-80; }

        n=0
        while IFS= read -r marker; do
            [ -z "$marker" ] && continue
            n=$((n + 1))
            id=$(slug "$marker")

            # Wait for a free slot before claiming, so a claim always becomes a prove.
            limit=$(width)
            while [ "$(jobs -p | wc -l)" -ge "$limit" ]; do
                wait -n 2>/dev/null || sleep 2
                limit=$(width)
            done

            settled "$marker" && continue
            mkdir "$RESULTS/claims/$id" 2>/dev/null || continue

            (
                out="$RESULTS/m/$id"
                mkdir -p "$out"
                echo "=== [$n] $marker" | tee -a "$out/slice.log"
                # A WORKTREE PER MARKER. It shares the reference clone's objects, so this costs a
                # file copy and no network, and it is thrown away afterwards — which is a stronger
                # isolation than resetting a tree, because nothing ignored survives it either.
                tree="$CHECKOUTS/tree-$id"
                rm -rf "$tree"
                git -C "$reference" worktree add --detach -f "$tree" HEAD >> "$out/slice.log" 2>&1 \
                    || cp -a "$reference/." "$tree/"
                # A tree with no build file cannot prove anything, and saying so here names the
                # cause; letting it through reports it as a marker that could not be built.
                if [ ! -f "$tree/pom.xml" ] && [ ! -f "$tree/build.gradle" ]; then
                    echo "WORKTREE FAILED for $marker — no build file in $tree" >> "$out/slice.log"
                else
                    java -cp "$CP" tech.mikhailov.fsm.agent.Prove "$tree" "$marker" "$out" \
                        >> "$out/slice.log" 2>&1 || true
                fi
                git -C "$reference" worktree remove --force "$tree" >/dev/null 2>&1 || rm -rf "$tree"
            ) &
        done < "$2"
        wait
        echo "SLICE DONE ($n marker(s))"
        ;;

    overwatch)
        # overwatch [seconds between passes] — the supervisor. Reads the whole run, reports what is
        # going wrong with the PIPELINE, and may kill a stuck prove so the pool takes it again.
        #
        # ITS OWN PROCESS, not a stage in a prove. A prove sees one marker by design; the patterns
        # worth catching are only visible across three hundred of them, and an agent that could see
        # them from inside a prove would be an agent that could rewrite the order it runs in.
        exec java -cp "$CP" tech.mikhailov.fsm.agent.Overwatch "$RESULTS" "${2:-900}"
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
        echo "usage: prove 'repo|file|line|checker' | slice <markers> [concurrency] | overwatch [seconds] | test [cases] | seed [cases] | dashboard" >&2
        exit 2
        ;;
esac
