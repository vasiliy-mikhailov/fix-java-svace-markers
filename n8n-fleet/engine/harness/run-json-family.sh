#!/bin/sh
# Differential harness for the JSON/reply-parsing family: json-extract, parse-test, parse-fix.
#
# Runs the ORIGINAL JavaScript and the PORTED Java over the same generated cases and reports every
# divergence, type-tagged. Not part of `mvn test` — it is the evidence for a port, run by hand when
# the ported classes change.
#
#   sh harness/run-json-family.sh
set -eu
cd "$(dirname "$0")/.."

echo "== compiling the engine"
javac -d target/classes --release 25 -Xlint:all -Werror -encoding UTF-8 \
  $(find src/main/java -name '*.java')

echo "== compiling the harness driver"
javac -d harness/out/classes -cp target/classes --release 25 -encoding UTF-8 \
  harness/java/tech/mikhailov/fsm/harness/DiffJsonFamily.java

echo "== generating the cases and running the JavaScript"
node harness/json-family-js-side.cjs

echo "== running the Java"
java -cp target/classes:harness/out/classes tech.mikhailov.fsm.harness.DiffJsonFamily \
  harness/out/json-family-cases.json harness/out/json-family-java-results.json

echo "== comparing"
node harness/json-family-compare.cjs
