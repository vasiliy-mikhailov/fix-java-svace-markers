#!/bin/sh
# Differential harness for the runner — the MANUAL entry point, for reading the long report.
#
# THE ENFORCEMENT IS `mvn test`: the comparison is a JUnit test,
# src/test/java/tech/mikhailov/fsm/runner/DifferentialHarnessTest.java, so a divergence is a RED TEST on
# every build rather than a report somebody remembered to run. This script only adds the readable corpus
# and the full report on top of it.
#
#   sh harness/run.sh            # unpack the frozen corpus, run the comparison, show the report
#
# See harness/README.md for what the fixtures cover, what the recorded answers are answers OF, and why
# they cannot be regenerated — read that before promising anybody a refresh.
set -eu
cd "$(dirname "$0")/.."

echo "== unpacking the frozen reference answers (readable copies, nothing depends on them)"
node harness/unpack-fixtures.cjs

echo "== running the comparison"
mvn -B -q -f ../pom.xml -pl runner -am test \
  -Dtest=DifferentialHarnessTest -Dsurefire.failIfNoSpecifiedTests=false

echo
cat target/harness/report.txt
echo
echo "catalogue asserted : harness/fixtures/expected.json"
echo "full report        : runner/target/harness/report.txt"
