#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
AIDL="$SDK_ROOT/build-tools/35.0.0/aidl"
OUT="$ROOT/build/api-matrix"
JAVA_OUT="$OUT/aidl-java"

rm -rf "$OUT"
mkdir -p "$JAVA_OUT"

set -- \
    "$ROOT/android/aidl/com/example/globalagent/PointerSample.aidl" \
    "$ROOT/android/aidl/com/example/globalagent/GestureFrame.aidl" \
    "$ROOT/android/aidl/com/example/globalagent/GestureSpec.aidl" \
    "$ROOT/android/aidl/com/example/globalagent/SessionStatus.aidl" \
    "$ROOT/android/aidl/com/example/globalagent/SessionTrigger.aidl" \
    "$ROOT/android/aidl/com/example/globalagent/TranscriptUpdate.aidl" \
    "$ROOT/android/aidl/com/example/globalagent/WindowSnapshot.aidl" \
    "$ROOT/android/aidl/com/example/globalagent/IAgentBridge.aidl" \
    "$ROOT/android/aidl/com/example/globalagent/IAgentService.aidl"

"$AIDL" --lang=java --structured -Werror \
    -I "$ROOT/android/aidl" -o "$JAVA_OUT" "$@"

for api in 34 35 36; do
    android_jar="$SDK_ROOT/platforms/android-$api/android.jar"
    class_out="$OUT/classes-$api"
    test -f "$android_jar" || {
        echo "missing SDK platform android-$api" >&2
        exit 1
    }
    mkdir -p "$class_out"

    javac --release 17 -cp "$android_jar" -d "$class_out" \
        "$JAVA_OUT/com/example/globalagent/PointerSample.java" \
        "$JAVA_OUT/com/example/globalagent/GestureFrame.java" \
        "$JAVA_OUT/com/example/globalagent/GestureSpec.java" \
        "$JAVA_OUT/com/example/globalagent/SessionStatus.java" \
        "$JAVA_OUT/com/example/globalagent/SessionTrigger.java" \
        "$JAVA_OUT/com/example/globalagent/TranscriptUpdate.java" \
        "$JAVA_OUT/com/example/globalagent/WindowSnapshot.java" \
        "$JAVA_OUT/com/example/globalagent/IAgentBridge.java" \
        "$JAVA_OUT/com/example/globalagent/IAgentService.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/AgentSessionClient.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/AgentSessionActivity.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/GestureValidator.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/SessionClientRegistry.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/SessionEntryPolicy.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/SessionStatusValidator.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/AtomicPublicConfigStore.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/ModelGatewayPolicy.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/PublicAgentConfigSchema.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/PublicConfigCallPolicy.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/PublicConfigImporter.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/PublicConfigProvider.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/StrictJsonParser.java" \
        "$ROOT/tests/java/com/example/globalagent/GestureValidatorTest.java" \
        "$ROOT/tests/java/com/example/globalagent/SessionEntryPolicyTest.java" \
        "$ROOT/tests/java/com/example/globalagent/SessionStatusValidatorTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/ModelGatewayPolicyTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/PublicAgentConfigSchemaTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/PublicConfigCallPolicyTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/PublicConfigImporterTest.java"

    java -cp "$class_out:$android_jar" \
        com.example.globalagent.GestureValidatorTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.SessionStatusValidatorTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.SessionEntryPolicyTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.ModelGatewayPolicyTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.PublicAgentConfigSchemaTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.PublicConfigCallPolicyTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.PublicConfigImporterTest >/dev/null

    printf 'java_api_%s=compiled-and-policy-tests-passed\n' "$api"
done
