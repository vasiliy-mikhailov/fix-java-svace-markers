#!/bin/sh
# Differential harnesses for the engine — the MANUAL entry point. The enforcement is `mvn test`.
#
# WHAT CHANGED, 2026-07-31. Three shell scripts used to run the ORIGINAL JavaScript node bodies from
# n8n/agentic/src/{lib,nodes} and this port over the same generated cases, then diff them with two Node
# scripts. That JavaScript has been deleted. Its ANSWERS are frozen under harness/fixtures, and the
# three comparisons are now JUnit tests in src/test/java/tech/mikhailov/fsm/harness — so a divergence
# is a RED TEST on every build rather than a report somebody remembered to run.
#
#   sh harness/run.sh            # unpack the frozen corpora, run all three comparisons, show them
#
# See harness/README.md for what each family's fixtures cover, when they were generated, and — read
# this one before promising anybody a refresh — what it would take to regenerate them.
set -eu
cd "$(dirname "$0")/.."

echo "== unpacking the frozen JavaScript answers (readable copies, nothing depends on them)"
node harness/js-side.cjs

echo "== running the three comparisons"
mvn -B -q -f ../pom.xml -pl engine test \
  -Dtest='NodeFamilyHarnessTest,InputFamilyHarnessTest,JsonFamilyHarnessTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

for f in node-family input-family json-family; do
  echo
  echo "================ $f ================"
  cat "target/harness/$f-expected-report.txt"
done
