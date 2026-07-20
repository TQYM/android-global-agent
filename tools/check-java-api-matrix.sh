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
    "$ROOT"/android/aidl/com/example/globalagent/*.aidl \
    "$ROOT"/android/aidl/com/example/globalagent/v2/*.aidl

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
        "$JAVA_OUT"/com/example/globalagent/*.java \
        "$JAVA_OUT"/com/example/globalagent/v2/*.java \
        "$ROOT/android/bridge/src/com/example/globalagent/AgentSessionClient.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/AgentSessionActivity.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/GestureValidator.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/GatewayPackageAuthorizer.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/SessionClientRegistry.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/SessionEntryPolicy.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/SessionStatusValidator.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/V2SessionCapability.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/V2SessionCapabilityFactory.java" \
        "$ROOT/android/bridge/src/com/example/globalagent/V2SessionCapabilityPolicy.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/AtomicPublicConfigStore.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/TextOnlyDryRunAdapter.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/EhviewerDryRunPolicy.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/DeepSeekV4TextAdapter.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/ModelGatewayPolicy.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/ModelGatewayV2Policy.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/ModelGatewayService.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/ProtocolV2Validator.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/PublicAgentConfigSchema.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/PublicConfigCallPolicy.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/PublicConfigImporter.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/PublicConfigProvider.java" \
        "$ROOT/android/model-gateway/src/com/example/globalagent/gateway/StrictJsonParser.java" \
        "$ROOT/tests/java/com/example/globalagent/GestureValidatorTest.java" \
        "$ROOT/tests/java/com/example/globalagent/GatewayPackageAuthorizerTest.java" \
        "$ROOT/tests/java/com/example/globalagent/SessionEntryPolicyTest.java" \
        "$ROOT/tests/java/com/example/globalagent/SessionStatusValidatorTest.java" \
        "$ROOT/tests/java/com/example/globalagent/V2SessionCapabilityPolicyTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/ModelGatewayPolicyTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/ModelGatewayV2PolicyTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/EhviewerDryRunPolicyTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/DeepSeekV4TextAdapterTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/DeepSeekConfigFixtureTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/ProtocolV2ValidatorTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/PublicAgentConfigSchemaTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/PublicConfigCallPolicyTest.java" \
        "$ROOT/tests/java/com/example/globalagent/gateway/PublicConfigImporterTest.java"

    java -cp "$class_out:$android_jar" \
        com.example.globalagent.GestureValidatorTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.GatewayPackageAuthorizerTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.SessionStatusValidatorTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.SessionEntryPolicyTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.V2SessionCapabilityPolicyTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.ModelGatewayPolicyTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.ModelGatewayV2PolicyTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.EhviewerDryRunPolicyTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.DeepSeekV4TextAdapterTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.DeepSeekConfigFixtureTest \
        "$ROOT/config/agent-config.deepseek-v4.ehviewer.json" >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.ProtocolV2ValidatorTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.PublicAgentConfigSchemaTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.PublicConfigCallPolicyTest >/dev/null
    java -cp "$class_out:$android_jar" \
        com.example.globalagent.gateway.PublicConfigImporterTest >/dev/null

    printf 'java_api_%s=compiled-and-policy-tests-passed\n' "$api"
done
