#!/bin/sh
# Differential harness for the input-building family: prep prover, build reproduce input,
# build fix input.
#
# Runs the ORIGINAL JavaScript and the PORTED Java over the same generated cases and reports every
# divergence, type-tagged. Not part of `mvn test` — it is the evidence for a port, run by hand when
# the ported nodes change.
#
#   sh harness/input-family-run.sh
set -eu
cd "$(dirname "$0")/.."

echo "== compiling the engine"
javac -d target/classes --release 25 -Xlint:all -Werror -encoding UTF-8 \
  $(find src/main/java -name '*.java')

echo "== compiling the harness driver"
javac -d harness/out/classes -cp target/classes --release 25 -encoding UTF-8 \
  harness/java/tech/mikhailov/fsm/harness/InputFamilyDiff.java

echo "== running the JavaScript"
node harness/input-family-js-side.cjs

echo "== running the Java"
java -cp target/classes:harness/out/classes tech.mikhailov.fsm.harness.InputFamilyDiff \
  harness/out/input-family-cases.json harness/out/input-family-java-results.json

echo "== comparing"
node harness/compare.cjs input-family-
