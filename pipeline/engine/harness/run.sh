#!/bin/sh
# Differential harnesses for the engine — the MANUAL entry point, for reading the long reports.
#
# THE ENFORCEMENT IS `mvn test`: the three comparisons are JUnit tests in
# src/test/java/tech/mikhailov/fsm/harness, so a divergence is a RED TEST on every build rather than a
# report somebody remembered to run. This script only adds the readable corpora and the full reports.
#
#   sh harness/run.sh            # unpack the frozen corpora, run all three comparisons, show them
#
# See harness/README.md for what each family's fixtures cover, what the recorded answers are answers OF,
# and — read this before promising anybody a refresh — why they cannot be regenerated.
set -eu
cd "$(dirname "$0")/.."

echo "== unpacking the frozen reference answers (readable copies, nothing depends on them)"
node harness/unpack-fixtures.cjs

echo "== running the three comparisons"
mvn -B -q -f ../pom.xml -pl engine test \
  -Dtest='NodeFamilyHarnessTest,InputFamilyHarnessTest,JsonFamilyHarnessTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

for f in node-family input-family json-family; do
  echo
  echo "================ $f ================"
  cat "target/harness/$f-expected-report.txt"
done
