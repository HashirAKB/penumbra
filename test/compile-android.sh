#!/usr/bin/env bash
# Type-checks all app sources against the Android SDK API stubs (no Gradle or SDK needed).
# Usage: test/compile-android.sh [path/to/android.jar]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${1:-${ANDROID_JAR:-/root/tools/sdkjar/android-35.jar}}"
KOTLINC="${KOTLINC:-/root/tools/kotlinc/bin/kotlinc}"
OUT="${OUT_DIR:-$(mktemp -d)}"
find "$ROOT/app/src/main/java" -name '*.kt' > "$OUT/sources.txt"
"$KOTLINC" -cp "$JAR" -jvm-target 17 -nowarn -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
  | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
test -d "$OUT/classes" && echo "COMPILED $(find "$OUT/classes" -name '*.class' | wc -l) classes"
