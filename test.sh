#!/bin/bash
# Compiles the engine library (core/), src/ and the tests, and runs every test main (no JUnit: the build has no
# dependencies): test/*Test.java (the app) and core/test/instatray/core/*Test.java (the library).
set -euo pipefail
cd "$(dirname "$0")"

JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
OUT=build/test-classes
rm -rf "$OUT"
mkdir -p "$OUT"
"$JDK/bin/javac" --release 21 -d "$OUT" core/src/instatray/core/*.java core/test/instatray/core/*.java src/*.java test/*.java

cat lang/en/*.tsv > "$OUT/en.tsv"

failed=0
for f in core/test/instatray/core/*Test.java; do
    "$JDK/bin/java" -Djava.awt.headless=true -cp "$OUT" "instatray.core.$(basename "$f" .java)" || failed=1
done
for f in test/*Test.java; do
    name=$(basename "$f" .java)
    "$JDK/bin/java" -Djava.awt.headless=true -cp "$OUT" "$name" || failed=1
done
exit $failed
