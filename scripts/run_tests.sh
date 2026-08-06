#!/usr/bin/env bash
# ==================================================================
#  ReplyMate test harness (BLUEPRINT §9.1)
#  Compiles every source file that has NO platform import (core,
#  provider, platform-neutral data classes) + tests on the JVM,
#  then runs the JUnit suites. Real SQLite via JDBC for migrations.
#  Usage:  bash /home/user/ReplyMate/scripts/run_tests.sh
# ==================================================================
set -euo pipefail

ROOT=/home/user/ReplyMate
TOOLS="$ROOT/tools"
OUT="$ROOT/build/test-classes"
TMPD="$ROOT/build/java-tmp"
mkdir -p "$TOOLS" "$OUT" "$TMPD"

JUNIT="$TOOLS/junit-4.13.2.jar"
HAMCREST="$TOOLS/hamcrest-core-1.3.jar"
SQLITEJ="$TOOLS/sqlite-jdbc-3.36.0.3.jar"

fetch() { # url dest
    if [ ! -s "$2" ]; then
        echo "fetching $(basename "$2")…"
        curl -sL --max-time 300 -o "$2" "$1"
    fi
}
fetch "https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar" "$JUNIT"
fetch "https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar" "$HAMCREST"
fetch "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.36.0.3/sqlite-jdbc-3.36.0.3.jar" "$SQLITEJ"

CP="$JUNIT:$HAMCREST:$SQLITEJ"

echo "selecting testable sources (whitelist: core, provider, platform-neutral data)…"
TESTABLE=$(find \
    "$ROOT/src/com/replymate/core" \
    "$ROOT/src/com/replymate/provider" \
    "$ROOT/src/com/replymate/data/db" \
    "$ROOT/tests/src" \
    -name '*.java' \
    ! -name 'DbHelper.java' ! -name 'Pragmas.java' ! -name 'DbHealth.java')
for f in $TESTABLE; do
    if grep -q '^import android' "$f"; then
        echo "ERROR: $f imports platform classes but is in a platform-neutral package" >&2
        exit 1
    fi
done

echo "compiling $(echo $TESTABLE | wc -w) files…"
rm -rf "$OUT"; mkdir -p "$OUT"
javac -encoding UTF-8 -nowarn -cp "$CP" -d "$OUT" $TESTABLE

echo "running suites…"
java -Djava.io.tmpdir="$TMPD" -Dreplymate.src="$ROOT/src" -cp "$OUT:$CP" org.junit.runner.JUnitCore \
    com.replymate.core.json.JsonTest \
    com.replymate.core.util.ResultTest \
    com.replymate.core.prompt.PromptBuilderTest \
    com.replymate.core.budget.TokenBudgeterTest \
    com.replymate.core.usecase.DraftServiceTest \
    com.replymate.core.usecase.ContactServiceTest \
    com.replymate.core.listener.IdentityResolverTest \
    com.replymate.core.listener.TextIdsTest \
    com.replymate.core.listener.MessageClassifierTest \
    com.replymate.core.listener.ListenerFilterTest \
    com.replymate.core.listener.BatchWindowTest \
    com.replymate.core.listener.IngestCoordinatorTest \
    com.replymate.provider.RetryPolicyTest \
    com.replymate.provider.ApiErrorTest \
    com.replymate.provider.GeminiPayloadTest \
    com.replymate.provider.GeminiParserTest \
    com.replymate.data.MigrationTest \
    com.replymate.data.PragmaRowBehaviorTest \
    com.replymate.data.PlatformGuardTest

echo "ALL SUITES PASSED"
