#!/bin/bash
# prove | slice | serve | overwatch | test | seed | dashboard. See spec/ for what all of this is.
#
# bash, not sh: the worker pool waits on `wait -n` and counts with `jobs -p`, neither of which is
# POSIX. A pool built without them either polls on a sleep or spawns everything at once.
set -eu

CP="/opt/agent/agent.jar:/opt/agent/lib/*"
RESULTS="${RESULTS:-/results}"
CHECKOUTS="${CHECKOUTS:-/work/checkouts}"

# THE SPEC, PUT WHERE THE AGENTS CAN ACTUALLY REACH IT.
#
# Every agent's file tools are rooted at the RESULTS directory. That is deliberate — it is where the
# record is and it is the only tree they may open — but it means a prompt naming `/opt/agent/spec`
# would name a file none of them can read, and would teach the model to report that its tools are
# broken. So the spec is copied in, and the prompts name `spec/` relative to the root they have.
#
# Refreshed on every start rather than copied once, so a deploy updates it; the old copy is removed
# first, so a chapter deleted upstream does not linger as a chapter the watcher still cites.
# IT SAYS SO WHEN IT FAILS, and it used to fail in silence. Every line here ended in
# `2>/dev/null || true`, so a full disk or an unwritable volume left no spec and no word about it —
# and the symptom is an agent answering "I don't see spec.md", which reads as a missing file rather
# than a failed copy. Found exactly that way: a container came up clean with an empty spec directory
# because the host had run out of disk, and nothing anywhere said so.
#
# Still not fatal. A dashboard with no spec is worth more than no dashboard.
if [ -d /opt/agent/spec ]; then
    rm -rf "$RESULTS/spec" 2>/dev/null || true
    if mkdir -p "$RESULTS/spec" 2>/dev/null && cp -R /opt/agent/spec/. "$RESULTS/spec/" 2>/dev/null
    then
        :
    else
        echo "WARNING: could not copy the spec into $RESULTS/spec — the agents will not be able to" >&2
        echo "         read it, and will report it missing. Cause:" >&2
        cp -R /opt/agent/spec/. "$RESULTS/spec/" 2>&1 | sed 's/^/         /' >&2 || true
    fi
fi

# THE CHECKOUT IS THIS SCRIPT'S JOB, not the agent's. A prove needs a tree that is exactly the
# upstream one: a previous prove leaves a test and a patch behind, and inheriting them would let a
# marker be "reproduced" by the fix for the marker before it.
checkout() {
    repo="$1"
    # ONE CHECKOUT PER WORKER. Four provers resetting and cleaning one tree would delete each
    # other's test between the write and the build.
    dir="$CHECKOUTS/$(echo "$repo" | sed 's|.*/||; s|\.git$||')"

    # AN UPLOADED TREE IS NOT CLONED. A source zip is for a subject that is not in a repository this
    # container can reach — an export, a vendor drop, something behind a VPN — and when one is there
    # it IS the subject, so nothing goes to the network at all.
    if [ -f "$RESULTS/source.zip" ]; then
        if [ ! -d "$dir" ] || [ "$RESULTS/source.zip" -nt "$dir" ]; then
            rm -rf "$dir"; mkdir -p "$dir"
            # `jar` RATHER THAN `unzip`, because the JDK is already here and unzip is not. A jar
            # IS a zip, and the tool that reads one reads the other; adding a package to the image
            # to avoid noticing that would be a dependency bought with nothing.
            ( cd "$dir" && jar xf "$RESULTS/source.zip" ) 2>/dev/null \
                || unzip -q "$RESULTS/source.zip" -d "$dir" 2>/dev/null || true
            # A zip of a project usually contains one directory holding it. Unwrap that, so the
            # marker paths — which start at src/ — resolve against the tree rather than one above it.
            if [ ! -f "$dir/pom.xml" ] && [ ! -f "$dir/build.gradle" ]; then
                inner=$(find "$dir" -maxdepth 2 -name pom.xml -o -maxdepth 2 -name build.gradle \
                    | head -1 | xargs -r dirname)
                [ -n "$inner" ] && [ "$inner" != "$dir" ] && mv "$inner"/* "$inner"/.[!.]* "$dir/" 2>/dev/null
            fi
            # A tree with no history is still a tree, and the worktree machinery wants a repository.
            if [ ! -d "$dir/.git" ]; then
                git -C "$dir" init -q 2>/dev/null || true
                git -C "$dir" add -A >/dev/null 2>&1 || true
                git -C "$dir" -c user.email=a@b -c user.name=fsm commit -qm "uploaded" >/dev/null 2>&1 || true
            fi
        fi
        echo "$dir"
        return 0 2>/dev/null || { echo "$dir"; }
    fi

    # A CREDENTIAL GOES IN GIT'S STORE, NOT IN THE URL. A token pasted into the clone address is in
    # the process list every prover can read and in the log this writes.
    if [ -f "$RESULTS/git-credentials" ]; then
        git config --global credential.helper "store --file=$RESULTS/git-credentials" 2>/dev/null || true
    fi

    # THE MIRROR, FOR A MACHINE THAT CANNOT REACH THE HOST THE MARKERS NAME.
    #
    # A marker's first field is where its code lives, and 356 of them name the same public host. On a
    # network that cannot reach it, the choice was to rewrite every marker or to run one `git config`
    # by hand after every deploy — the second silently reverts the moment the container is recreated,
    # which is on each deploy, and nothing says so.
    #
    # `insteadOf` is git's own answer: the markers keep naming the canonical repository, which is what
    # a settlement should record, and the fetch goes wherever this network can actually reach. One
    # `<from> <to>` pair per line, applied before anything clones.
    if [ -f "$RESULTS/git-mirror" ]; then
        while read -r from to _rest; do
            [ -n "$from" ] && [ -n "$to" ] || continue
            case "$from" in \#*) continue;; esac
            git config --global "url.$to.insteadOf" "$from" 2>/dev/null || true
        done < "$RESULTS/git-mirror"
    fi

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
        # WHAT GIT SAID, KEPT. This discarded both streams, so a machine with no route to the host
        # cloned nothing, said nothing, and left an empty directory — and the failure surfaced twenty
        # seconds later as `no pom.xml and no build.gradle`, which points at the build system for a
        # problem that was the network. Somebody lost an afternoon to that.
        #
        # To STDERR and not stdout: this function returns the checkout path by command substitution,
        # so a word printed on stdout becomes part of the path its caller uses.
        if ! clone_said=$(git clone --depth 1 "$repo" "$dir" 2>&1); then
            {
                echo "fsm: could not clone $repo"
                echo "$clone_said" | sed 's/^/    /'
                echo "    Nothing here reaches that host. Three ways round it, in spec/18 and the"
                echo "    README: put the tree at $RESULTS/source.zip, place a clone at $dir, or"
                echo "    set a mirror in $RESULTS/git-mirror as '<from> <to>'."
            } >&2
            rm -rf "$dir"
        fi
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

        # The same question asked of ONE lane, which is all the sweep below knows about.
        settled_lane() { grep -qE "$DISPOSITIONS" "$RESULTS/m/$1/settlements.jsonl" 2>/dev/null; }

        # A directory named for the marker, so a reader finds a prove by what it proved.
        slug() { echo "$1" | sed 's|.*/||; s|[^A-Za-z0-9._-]|_|g' | cut -c1-80; }

        # THE CLAIMS LEFT BY EVERY RUN BEFORE THIS ONE, WHICH IS WHERE THE STRANDED MARKERS ARE.
        #
        # Releasing claims from here on fixes the markers this pass strands; it does nothing for the
        # ones already stranded, because their stale claim still fails the mkdir and they are skipped
        # exactly as before. The volume outlives the fix, so the fix has to reach back.
        #
        # A claim is stale when no prove is behind it, and the prove is identifiable because the pool
        # gives each one a worktree named for the marker — the same string the supervisor kills by.
        # Anything still running keeps its claim, so a second pool started alongside this one is not
        # robbed of its work; the one race left is a claim made microseconds ago whose JVM has not
        # started yet, and that costs a marker proved twice rather than a marker lost.
        swept=0
        for claim in "$RESULTS"/claims/*; do
            [ -d "$claim" ] || continue
            held=$(basename "$claim")
            pgrep -f "tree-$held " >/dev/null 2>&1 && continue
            if ! settled_lane "$held"; then
                # It stopped without answering. Its record is kept and its lane cleared, because
                # Prove APPENDS: retrying on top of the old trace makes one prove that changed its
                # mind rather than two attempts with a line between them.
                mkdir -p "$RESULTS/dead"
                tried=$(java -cp "$CP" tech.mikhailov.fsm.agent.Pace --tries "$RESULTS" "$held")
                mv "$RESULTS/m/$held" "$RESULTS/dead/$held.attempt-$((tried + 1))" 2>/dev/null || true
            fi
            rm -rf "$claim"
            swept=$((swept + 1))
        done
        [ "$swept" -gt 0 ] && echo "released $swept claim(s) left by earlier runs"

        # HOW MANY TIMES THE POOL WILL PUT A MARKER BACK BEFORE LEAVING IT FOR A PERSON.
        #
        # Three, because the first retry tests "was that transient", the second tests "was it
        # transient twice", and a marker that has failed to settle three times is failing for a
        # reason no further attempt will discover. Without a bound this is a loop: the claim is
        # released, the marker is unsettled, the next pass takes it, and it dies in the same place.
        TRIES=3

        # THE CLAIM IS RELEASED WHEN THE PROVE ENDS, and the record of a prove that did not settle
        # goes to dead/ so the next attempt starts on a clean trace instead of appending to the last.
        #
        # The claim used to outlive the prove, and that quietly cancelled the disposition rule above.
        # `settled` was taught to say NO for a marker that ended in `infra` precisely so the pool
        # would take it again — and then the next line, `mkdir claims/$id || continue`, skipped it
        # because the claim from the dead attempt was still there. Every marker whose prove threw was
        # therefore retired by the very gate that exists to stop double-proving, the README's promise
        # that "a marker already settled anywhere is skipped" became "already attempted is skipped",
        # and the fix that was supposed to revisit them never ran once.
        release() {
            _id=$2
            if ! settled "$1"; then
                mkdir -p "$RESULTS/dead"
                _try=$(java -cp "$CP" tech.mikhailov.fsm.agent.Pace --tries "$RESULTS" "$_id")
                mv "$RESULTS/m/$_id" "$RESULTS/dead/$_id.attempt-$((_try + 1))" 2>/dev/null || true
            fi
            rm -rf "$RESULTS/claims/$_id"
        }

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
            # SET ASIDE, NOT SKIPPED. A marker taking much longer than the others is postponed so it
            # stops holding a quarter of the pool while three hundred wait; the pass below picks it
            # up again once the queue is otherwise done, when its slot costs nothing.
            [ "$(java -cp "$CP" tech.mikhailov.fsm.agent.Pace --postponed "$RESULTS" "$id")" = yes ] \
                && continue
            # AND A MARKER THAT HAS FAILED ITS WAY THROUGH ITS GOES IS LEFT ALONE. This is the gate
            # the leaked claim was doing by accident, done on purpose and with a count that says so.
            tries=$(java -cp "$CP" tech.mikhailov.fsm.agent.Pace --tries "$RESULTS" "$id")
            if [ "$tries" -ge "$TRIES" ] 2>/dev/null; then
                echo "=== $id has not settled in $tries attempt(s); leaving it for a person"
                continue
            fi
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
                release "$marker" "$id"
            ) &
        done < "$2"
        wait

        # AND NOW THE ONES THAT WERE SET ASIDE, with the pool to themselves. Each gets released and
        # proved in turn; a marker postponed again during this pass stays postponed, because the second
        # time it is not competing with anything and taking long is all it is doing.
        held=$(java -cp "$CP" tech.mikhailov.fsm.agent.Pace --list-postponed "$RESULTS" | wc -l)
        if [ "$held" -gt 0 ]; then
            echo "=== the queue is done; $held marker(s) were set aside for taking much longer"
            for id in $(java -cp "$CP" tech.mikhailov.fsm.agent.Pace --list-postponed "$RESULTS"); do
                marker=$(grep -F "$(echo "$id" | sed 's/_[0-9]*_[A-Z].*//')" "$2" | head -1)
                [ -z "$marker" ] && continue
                rm -f "$RESULTS/postponed/$id"
                rm -rf "$RESULTS/claims/$id"
                settled "$marker" && continue
                mkdir "$RESULTS/claims/$id" 2>/dev/null || continue
                out="$RESULTS/m/$id"
                # THE FIRST ATTEMPT IS PUT ASIDE, NOT WRITTEN OVER. Prove appends, so without this
                # the second attempt lands on top of the first in one trace.jsonl and the record
                # reads as a single prove that changed its mind — two reproducers, two verdicts,
                # no line between them. What comes back is a fresh attempt and the record says so.
                if [ -d "$out" ]; then
                    mkdir -p "$RESULTS/dead"
                    mv "$out" "$RESULTS/dead/$id.before-postponing" 2>/dev/null || rm -rf "$out"
                fi
                mkdir -p "$out"
                echo "=== proving again after the queue: $marker" | tee -a "$out/slice.log"
                tree="$CHECKOUTS/tree-$id"
                rm -rf "$tree"
                git -C "$reference" worktree add --detach -f "$tree" HEAD >> "$out/slice.log" 2>&1 \
                    || cp -a "$reference/." "$tree/"
                java -cp "$CP" tech.mikhailov.fsm.agent.Prove "$tree" "$marker" "$out" \
                    >> "$out/slice.log" 2>&1 || true
                git -C "$reference" worktree remove --force "$tree" >/dev/null 2>&1 || rm -rf "$tree"
                release "$marker" "$id"
            done
        fi
        echo "SLICE DONE ($n marker(s))"
        ;;

    interpret)
        # interpret — write the plain-English account for every settled lane that has none.
        #
        # The prompts are data and they change. A summary already on disk is the OLD prompt's answer,
        # and the supervisor's own loop would take most of a day to work through a run eight at a
        # time. Delete the summaries you want redone, then run this.
        exec java -cp "$CP" tech.mikhailov.fsm.agent.Interpreter "$RESULTS"
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

    serve)
        # serve [seconds] — the dashboard AND the supervisor, in one container.
        #
        # THEY WERE TWO CONTAINERS OFF THE SAME IMAGE, which is two things to deploy, two sets of
        # environment to keep in step, and one of them silently missing its PROMPTS and TUNING for a
        # deploy or two because I updated the other. They share a volume, a configuration and a
        # lifetime; there was never a reason for them to be separate but the order I built them in.
        #
        # THE WATCHER IN THE BACKGROUND, THE DASHBOARD IN THE FOREGROUND, and the asymmetry is
        # deliberate. A supervisor that dies must not take the record with it — its own loop already
        # survives a failed pass, and if the process goes the dashboard keeps serving what is there.
        # A dashboard that dies SHOULD end the container, so the restart policy brings both back.
        java -cp "$CP" tech.mikhailov.fsm.agent.Overwatch "$RESULTS" "${2:-900}" \
            >> "$RESULTS/overwatch.log" 2>&1 &
        exec java -cp "$CP" tech.mikhailov.fsm.agent.Dashboard \
            "$RESULTS/settlements.jsonl" "${PORT:-8087}"
        ;;

    dashboard)
        exec java -cp "$CP" tech.mikhailov.fsm.agent.Dashboard \
            "$RESULTS/settlements.jsonl" "${PORT:-8087}"
        ;;

    *)
        echo "usage: interpret | prove 'repo|file|line|checker' | slice <markers> [concurrency] | serve [seconds] | overwatch [seconds] | test [cases] | seed [cases] | dashboard" >&2
        exit 2
        ;;
esac
