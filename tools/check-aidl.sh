#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
AIDL="$SDK_ROOT/build-tools/35.0.0/aidl"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
JAVA_OUT="$ROOT/build/aidl-java"
NDK_OUT="$ROOT/build/aidl-ndk"
CLASS_OUT="$ROOT/build/aidl-classes"

rm -rf "$JAVA_OUT" "$NDK_OUT" "$CLASS_OUT"
mkdir -p "$JAVA_OUT" "$NDK_OUT/include" "$NDK_OUT/src" "$CLASS_OUT"

set -- \
    "$ROOT"/android/aidl/com/example/globalagent/*.aidl \
    "$ROOT"/android/aidl/com/example/globalagent/v2/*.aidl

"$AIDL" --lang=java --structured -Werror \
    -I "$ROOT/android/aidl" -o "$JAVA_OUT" "$@"
"$AIDL" --lang=ndk --structured -Werror --min_sdk_version=34 \
    -I "$ROOT/android/aidl" -h "$NDK_OUT/include" -o "$NDK_OUT/src" "$@"

javac --release 17 -cp "$ANDROID_JAR" -d "$CLASS_OUT" \
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
    "$ROOT/tests/java/com/example/globalagent/GestureValidatorTest.java" \
    "$ROOT/tests/java/com/example/globalagent/GatewayPackageAuthorizerTest.java" \
    "$ROOT/tests/java/com/example/globalagent/SessionEntryPolicyTest.java" \
    "$ROOT/tests/java/com/example/globalagent/SessionStatusValidatorTest.java" \
    "$ROOT/tests/java/com/example/globalagent/V2SessionCapabilityPolicyTest.java"

java -cp "$CLASS_OUT:$ANDROID_JAR" \
    com.example.globalagent.GestureValidatorTest
java -cp "$CLASS_OUT:$ANDROID_JAR" \
    com.example.globalagent.GatewayPackageAuthorizerTest
java -cp "$CLASS_OUT:$ANDROID_JAR" \
    com.example.globalagent.SessionStatusValidatorTest
java -cp "$CLASS_OUT:$ANDROID_JAR" \
    com.example.globalagent.SessionEntryPolicyTest
java -cp "$CLASS_OUT:$ANDROID_JAR" \
    com.example.globalagent.V2SessionCapabilityPolicyTest
