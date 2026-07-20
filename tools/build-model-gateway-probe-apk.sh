#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}"
TOOLS="$SDK_ROOT/build-tools/$BUILD_TOOLS"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
DEBUG_KEYSTORE="${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
OUT="$ROOT/build/model-gateway-probe"
CLASS_OUT="$OUT/classes"
DEX_OUT="$OUT/dex"

rm -rf "$OUT"
mkdir -p "$CLASS_OUT" "$DEX_OUT"
"$TOOLS/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
    --manifest "$ROOT/android/model-gateway-probe/AndroidManifest.xml" \
    --min-sdk-version 34 --target-sdk-version 35 \
    --version-code 1 --version-name 0.1
javac --release 17 -cp "$ANDROID_JAR" -d "$CLASS_OUT" \
    "$ROOT/android/model-gateway-probe/src/com/example/globalagent/gatewayprobe/GatewayBindProbeActivity.java"
jar --create --file "$OUT/classes.jar" -C "$CLASS_OUT" .
"$TOOLS/d8" --lib "$ANDROID_JAR" --min-api 34 \
    --output "$DEX_OUT" "$OUT/classes.jar"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
zip -q -j "$OUT/unaligned.apk" "$DEX_OUT/classes.dex"
"$TOOLS/zipalign" -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" sign \
    --ks "$DEBUG_KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/GlobalAgentModelGatewayProbe-debug.apk" "$OUT/aligned.apk"
echo "$OUT/GlobalAgentModelGatewayProbe-debug.apk"
