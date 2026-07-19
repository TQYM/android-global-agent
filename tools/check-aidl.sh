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
"$AIDL" --lang=ndk --structured -Werror --min_sdk_version=34 \
    -I "$ROOT/android/aidl" -h "$NDK_OUT/include" -o "$NDK_OUT/src" "$@"

javac --release 17 -cp "$ANDROID_JAR" -d "$CLASS_OUT" \
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
    "$ROOT/tests/java/com/example/globalagent/GestureValidatorTest.java" \
    "$ROOT/tests/java/com/example/globalagent/SessionEntryPolicyTest.java" \
    "$ROOT/tests/java/com/example/globalagent/SessionStatusValidatorTest.java"

java -cp "$CLASS_OUT:$ANDROID_JAR" \
    com.example.globalagent.GestureValidatorTest
java -cp "$CLASS_OUT:$ANDROID_JAR" \
    com.example.globalagent.SessionStatusValidatorTest
java -cp "$CLASS_OUT:$ANDROID_JAR" \
    com.example.globalagent.SessionEntryPolicyTest
