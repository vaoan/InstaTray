#!/bin/bash
# Compiles src/ and test/ and runs every test/*Test.java main (no JUnit: the build has no dependencies).
set -euo pipefail
cd "$(dirname "$0")"

JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
OUT=build/test-classes
rm -rf "$OUT"
mkdir -p "$OUT"
"$JDK/bin/javac" --release 21 -d "$OUT" src/*.java test/*.java

failed=0
for f in test/*Test.java; do
    name=$(basename "$f" .java)
    "$JDK/bin/java" -Djava.awt.headless=true -cp "$OUT" "$name" || failed=1
done
exit $failed
