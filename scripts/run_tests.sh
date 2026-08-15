#!/usr/bin/env bash
# ==================================================================
#  ReplyMate test harness (BLUEPRINT §9.1)
#  Compiles every source file that has NO platform import (core,
#  provider, platform-neutral data classes) + tests on the JVM,
#  then runs the JUnit suites. Real SQLite via JDBC for migrations.
#
#  P-release-1: repo-relative — a fresh clone runs it from anywhere.
#  Usage:  bash scripts/run_tests.sh
# ==================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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
    com.replymate.core.ui.SplashChoreoTest \
    com.replymate.core.assistant.AssistantPlannerTest \
    com.replymate.core.assistant.JobCoalescerTest \
    com.replymate.core.assistant.AssistantEventTest \
    com.replymate.core.assistant.AssistantLearningTest \
    com.replymate.core.listener.ConversationMatchTest \
    com.replymate.core.convo.ParticipantRegistryTest \
    com.replymate.core.convo.TopicTrackerTest \
    com.replymate.core.convo.EngagementClassifierTest \
    com.replymate.core.usecase.ConversationStateServiceTest \
    com.replymate.core.usecase.GroupEngagementGateTest \
    com.replymate.core.prompt.GroupPromptWireTest \
    com.replymate.data.db.SchemaV8Test \
    com.replymate.data.db.SchemaV9Test \
    com.replymate.core.listener.ItemClassifierTest \
    com.replymate.core.listener.FailClosedIngestTest \
    com.replymate.core.assistant.DeliveryGuardTest \
    com.replymate.core.assistant.TargetRulesTest \
    com.replymate.core.usecase.NonReplyableStopTest \
    com.replymate.core.usecase.ChatRankerTest \
    com.replymate.core.usecase.LearningToPromptTest \
    com.replymate.core.understanding.BurstSignalsTest \
    com.replymate.core.understanding.ConversationContextBuilderTest \
    com.replymate.core.model.ToneTransformTest \
    com.replymate.core.model.ProviderTypeTest \
    com.replymate.core.model.ContentKindTest \
    com.replymate.core.prompt.PromptBuilderTest \
    com.replymate.core.prompt.BurstTaskTest \
    com.replymate.core.prompt.PromptUnderstandingTest \
    com.replymate.core.prompt.CustomizationEffectTest \
    com.replymate.core.prompt.P4PromptTest \
    com.replymate.core.prompt.ThreadMapperTest \
    com.replymate.core.prompt.PromptAuditCompletenessTest \
    com.replymate.core.prompt.VoiceCharterTest \
    com.replymate.core.style.StyleSettingsTest \
    com.replymate.core.style.StyleServiceTest \
    com.replymate.core.style.LearningPrecedenceTest \
    com.replymate.core.learning.ManualSendLearnerTest \
    com.replymate.core.learning.LearningEngineTest \
    com.replymate.core.learning.LearningServiceTest \
    com.replymate.core.learning.StyleProfilerTest \
    com.replymate.core.style.VoiceControlsOffTest \
    com.replymate.core.privacy.ProviderPrivacyTest \
    com.replymate.core.privacy.BuiltInSetupTest \
    com.replymate.core.privacy.SecretsTest \
    com.replymate.core.memory.LongChatMemoryTest \
    com.replymate.core.memory.FactNormalizerTest \
    com.replymate.core.memory.MemoryMergeTest \
    com.replymate.core.memory.IsolationSuite \
    com.replymate.core.memory.ThreadSummarizerTest \
    com.replymate.core.memory.MemoryContinuityTest \
    com.replymate.core.budget.TokenBudgeterTest \
    com.replymate.core.usecase.DraftServiceTest \
    com.replymate.core.usecase.DraftServiceToneTest \
    com.replymate.core.usecase.ContactServiceTest \
    com.replymate.core.usecase.ContactPurgeTest \
    com.replymate.core.usecase.LiveContextPromptTest \
    com.replymate.core.usecase.LiveSearchPromptTest \
    com.replymate.core.usecase.BackgroundDraftGuardTest \
    com.replymate.core.usecase.GenerationHonestyTest \
    com.replymate.core.usecase.OneDraftPerMessageTest \
    com.replymate.core.usecase.VoicePromptProofTest \
    com.replymate.core.memory.HistoryRetrieverTest \
    com.replymate.core.memory.YearsMemoryRetrievalTest \
    com.replymate.core.search.SearchGateTest \
    com.replymate.core.search.SearchCacheTest \
    com.replymate.core.caps.ModelCapsTest \
    com.replymate.core.reason.ReasoningTest \
    com.replymate.core.model.ProviderBaseUrlTest \
    com.replymate.core.live.LiveContextTest \
    com.replymate.core.plan.ReplyPlannerTest \
    com.replymate.core.usecase.PlanningDepthPromptTest \
    com.replymate.core.prompt.BurstWatermarkTest \
    com.replymate.core.usecase.PendingDraftContextTest \
    com.replymate.core.memory.LongChatCorrectionTest \
    com.replymate.core.memory.MemoryRestartTest \
    com.replymate.core.usecase.IntentionalComposeTest \
    com.replymate.core.usecase.AutoFollowUpTest \
    com.replymate.core.usecase.AllDialsWireProofTest \
    com.replymate.core.listener.GroupHistoryTest \
    com.replymate.core.usecase.GroupChatUnderstandingTest \
    com.replymate.core.usecase.EditContactWireProofTest \
    com.replymate.core.prompt.PromptSecurityTest \
    com.replymate.core.auth.AuthFlowTest \
    com.replymate.core.listener.StatusFilterTest \
    com.replymate.core.listener.IdentityResolverTest \
    com.replymate.core.listener.TextIdsTest \
    com.replymate.core.listener.MessageClassifierTest \
    com.replymate.core.listener.ListenerFilterTest \
    com.replymate.core.listener.BatchWindowTest \
    com.replymate.core.listener.IngestCoordinatorTest \
    com.replymate.core.listener.NoiseGateTest \
    com.replymate.core.listener.NoiseIngestTest \
    com.replymate.core.assistant.AssistantCatchUpTest \
    com.replymate.core.assistant.CatchupPolicyTest \
    com.replymate.core.listener.SystemLinesTest \
    com.replymate.core.listener.GroupPolicyTest \
    com.replymate.core.listener.GroupOptInTest \
    com.replymate.core.listener.NoiseEndToEndTest \
    com.replymate.core.listener.MessagingStyleParserTest \
    com.replymate.core.listener.TitleTextParserTest \
    com.replymate.core.listener.ParserRegistryTest \
    com.replymate.core.listener.ContentSignalsTest \
    com.replymate.core.listener.ChatLinkTest \
    com.replymate.core.supabase.SupabaseConfigTest \
    com.replymate.provider.RetryPolicyTest \
    com.replymate.provider.HttpDefaultsTest \
    com.replymate.provider.ApiErrorTest \
    com.replymate.provider.GeminiPayloadTest \
    com.replymate.provider.GeminiParserTest \
    com.replymate.provider.OpenAiDialectTest \
    com.replymate.provider.AnthropicApiTest \
    com.replymate.provider.GeminiDiscoveryTest \
    com.replymate.provider.DiagnosticsTest \
    com.replymate.provider.GeminiCandidatesFallbackTest \
    com.replymate.provider.GeminiGroundingTest \
    com.replymate.provider.AnthropicCapsTest \
    com.replymate.provider.ResponsesApiTest \
    com.replymate.provider.OpenAiExtrasTest \
    com.replymate.provider.KimiSearchLoopTest \
    com.replymate.provider.WikimediaRetrievalTest \
    com.replymate.provider.RetrievalBudgetTest \
    com.replymate.provider.OutputBudgetTest \
    com.replymate.provider.ModelClassifierTest \
    com.replymate.core.usecase.ReplyContextHonestyTest \
    com.replymate.core.usecase.ContextIsolationTest \
    com.replymate.data.MigrationTest \
    com.replymate.data.MigrationV2Test \
    com.replymate.data.MigrationV3Test \
    com.replymate.data.MigrationV4Test \
    com.replymate.data.MigrationV5Test \
    com.replymate.data.MigrationV6Test \
    com.replymate.data.PragmaRowBehaviorTest \
    com.replymate.data.PlatformGuardTest \
    com.replymate.data.ManifestCatalogTest

echo "ALL SUITES PASSED"
