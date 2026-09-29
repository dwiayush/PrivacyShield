#!/bin/sh
# Compiles the pure-Kotlin core with a standalone kotlinc, runs tests, and regenerates docs/risk-model.md.
# Temporary until the Gradle build exists (needs network access to Maven). Usage: KOTLINC=/path/to/kotlinc ./tools/check.sh
set -e
KOTLINC="${KOTLINC:-kotlinc}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
"$KOTLINC" -nowarn "$ROOT/android/core/model/src/main/kotlin" "$ROOT/android/core/risk/src/main/kotlin" \
  "$ROOT/android/core/risk/src/test/kotlin" "$ROOT/tools/GenRiskModelDoc.kt" -d "$OUT"
STD="$(dirname "$(command -v "$KOTLINC")")/../lib/kotlin-stdlib.jar"
java -cp "$OUT:$STD" app.privacyshield.core.risk.RiskEngineTestsKt
{
  cat "$ROOT/docs/risk-model.header.md"
  java -cp "$OUT:$STD" GenRiskModelDocKt
} > "$ROOT/docs/risk-model.md"
echo "Regenerated docs/risk-model.md"
