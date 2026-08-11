#!/bin/bash
# THE POOL'S SKIP RULE, exercised as the pool exercises it.
set -u
RESULTS=$(mktemp -d)
mkdir -p "$RESULTS/m/a" "$RESULTS/m/b" "$RESULTS/m/c.infra-1"
K='https://x|F.java|1|C'
printf '{"suspicion_key":"%s","state":"proving"}\n{"suspicion_key":"%s","state":"by-design"}\n' "$K" "$K" > "$RESULTS/m/a/settlements.jsonl"
K2='https://x|G.java|2|C'
printf '{"suspicion_key":"%s","state":"proving"}\n{"suspicion_key":"%s","state":"infra"}\n' "$K2" "$K2" > "$RESULTS/m/b/settlements.jsonl"
K3='https://x|H.java|3|C'
printf '{"suspicion_key":"%s","state":"infra"}\n' "$K3" > "$RESULTS/m/c.infra-1/settlements.jsonl"

DISPOSITIONS='"state":"(false-positive|by-design|unprovable|reproduced|needs-review|verified/pr-ready|verified/pr-rejected)"'
settled() {
    grep -rlF "\"suspicion_key\":\"$1\"" "$RESULTS"/m/*/settlements.jsonl 2>/dev/null \
        | while read -r f; do
            grep -F "\"suspicion_key\":\"$1\"" "$f" | grep -qE "$DISPOSITIONS" && echo hit
          done | grep -q hit
}
fail=0
settled "$K"  && echo "  ok   a real disposition is settled"          || { echo "  FAIL by-design not seen"; fail=1; }
settled "$K2" && { echo "  FAIL infra counted as settled"; fail=1; }  || echo "  ok   infra is NOT settled — the prove threw, it decided nothing"
settled "$K3" && { echo "  FAIL an archived infra retired its marker"; fail=1; } || echo "  ok   an archived record does not answer for a marker"
rm -rf "$RESULTS"
exit $fail
