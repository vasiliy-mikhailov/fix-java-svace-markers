#!/bin/bash
# THE WIDTH RULE, exercised the way the pool exercises it.
RESULTS=$(mktemp -d); asked="${1:-4}"
width() {
    w=$(cat "$RESULTS/workers" 2>/dev/null | tr -cd '0-9')
    [ -n "$w" ] || w="$asked"
    [ "$w" -ge 1 ] 2>/dev/null || w=4
    [ "$w" -le 16 ] 2>/dev/null || w=16
    echo "$w"
}
fail=0
check() { got=$(width); [ "$got" = "$2" ] && echo "  ok   $1 → $2" || { echo "  FAIL $1 → $got, wanted $2"; fail=1; }; }
check "no file"        4
echo 8    > $RESULTS/workers; check "8"            8
echo 99   > $RESULTS/workers; check "99 clamped"  16
echo 0    > $RESULTS/workers; check "0 refused"    4
echo "x"  > $RESULTS/workers; check "junk"         4
echo " 6 " > $RESULTS/workers; check "spaces"      6
rm -rf "$RESULTS"; exit $fail
