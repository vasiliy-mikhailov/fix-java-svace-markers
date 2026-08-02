#!/bin/sh
# Differential harness for the runner — the MANUAL entry point. The enforcement is `mvn test`.
#
# WHAT CHANGED, 2026-07-31. This script used to run the ORIGINAL JavaScript (java-runner/lib/edit.js,
# lib/build.js, src/server.js) and this port over the same generated cases, then diff them with a Node
# script. That JavaScript has been deleted. Its ANSWERS are frozen under harness/fixtures, and the
# comparison is now a JUnit test — src/test/java/tech/mikhailov/fsm/runner/DifferentialHarnessTest.java
# — so a divergence is a RED TEST on every build rather than a report somebody remembered to run.
#
#   sh harness/run.sh            # unpack the frozen corpus, run the comparison, show the report
#
# See harness/README.md for what the fixtures cover, when they were generated, and what it would take
# to regenerate them (it would take rewriting the deleted service — say so out loud before promising
# anyone a refresh).
set -eu
cd "$(dirname "$0")/.."

echo "== unpacking the frozen JavaScript answers (readable copies, nothing depends on them)"
node harness/js-side.cjs

echo "== running the comparison"
mvn -B -q -f ../pom.xml -pl runner -am test \
  -Dtest=DifferentialHarnessTest -Dsurefire.failIfNoSpecifiedTests=false

echo
cat target/harness/report.txt
echo
echo "catalogue asserted : harness/fixtures/expected.json"
echo "full report        : runner/target/harness/report.txt"
