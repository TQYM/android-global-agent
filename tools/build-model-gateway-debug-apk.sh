#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}"
TOOLS="$SDK_ROOT/build-tools/$BUILD_TOOLS"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
DEBUG_KEYSTORE="${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
OUT="$ROOT/build/model-gateway-debug"
AIDL_OUT="$OUT/aidl-java"
CLASS_OUT="$OUT/classes"
DEX_OUT="$OUT/dex"
RES_ZIP="$OUT/resources.zip"
BASE_APK="$OUT/base.apk"
UNALIGNED_APK="$OUT/unaligned.apk"
ALIGNED_APK="$OUT/aligned.apk"
SIGNED_APK="$OUT/GlobalAgentModelGateway-debug.apk"

for tool in aapt2 aidl apksigner d8 zipalign; do
    test -x "$TOOLS/$tool" || {
        echo "missing Android build tool: $TOOLS/$tool" >&2
        exit 1
    }
done
test -f "$ANDROID_JAR" || {
    echo "missing Android SDK platform: $ANDROID_JAR" >&2
    exit 1
}
test -f "$DEBUG_KEYSTORE" || {
    echo "missing debug keystore: $DEBUG_KEYSTORE" >&2
    exit 1
}

rm -rf "$OUT"
mkdir -p "$AIDL_OUT" "$CLASS_OUT" "$DEX_OUT"

set -- "$ROOT"/android/aidl/com/example/globalagent/v2/*.aidl
"$TOOLS/aidl" --lang=java --structured -Werror \
    -I "$ROOT/android/aidl" -o "$AIDL_OUT" "$@"

"$TOOLS/aapt2" compile --dir "$ROOT/android/model-gateway/res" \
    -o "$RES_ZIP"
"$TOOLS/aapt2" link -o "$BASE_APK" -I "$ANDROID_JAR" \
    --manifest "$ROOT/android/model-gateway/AndroidManifest.xml" \
    --min-sdk-version 34 --target-sdk-version 35 \
    --version-code 1 --version-name 0.1 "$RES_ZIP"

javac --release 17 -cp "$ANDROID_JAR" -d "$CLASS_OUT" \
    "$AIDL_OUT"/com/example/globalagent/v2/*.java \
    "$ROOT"/android/model-gateway/src/com/example/globalagent/gateway/*.java
jar --create --file "$OUT/classes.jar" -C "$CLASS_OUT" .
"$TOOLS/d8" --lib "$ANDROID_JAR" --min-api 34 \
    --output "$DEX_OUT" "$OUT/classes.jar"

cp "$BASE_APK" "$UNALIGNED_APK"
zip -q -j "$UNALIGNED_APK" "$DEX_OUT/classes.dex"
"$TOOLS/zipalign" -f 4 "$UNALIGNED_APK" "$ALIGNED_APK"
"$TOOLS/apksigner" sign \
    --ks "$DEBUG_KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$SIGNED_APK" "$ALIGNED_APK"
"$TOOLS/apksigner" verify --verbose --print-certs "$SIGNED_APK" >/dev/null

echo "$SIGNED_APK"
