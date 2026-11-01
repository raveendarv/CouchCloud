#!/usr/bin/env bash
# The fork's tests: the on-device scorer and the TMDB outbox (plain Java, no Android: compiled with your JDK) and the profile builder
# (Python, stdlib unittest). Run before you push: tools/test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
"$JAVAC" -nowarn -d "$out" app/src/io/freemytv/Profile.java app/src/io/freemytv/Scorer.java app/src/io/freemytv/Outbox.java app/src/io/freemytv/Titles.java \
    tests/java/ScorerTest.java tests/java/OutboxTest.java tests/java/TitlesTest.java
"$JAVA" -cp "$out" io.freemytv.ScorerTest
"$JAVA" -cp "$out" io.freemytv.OutboxTest
"$JAVA" -cp "$out" io.freemytv.TitlesTest
python3 -I -m unittest discover -s tools/recs/tests -t tools/recs -p 'test_*.py'
