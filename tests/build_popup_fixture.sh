#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# 构建三功能端到端测试夹具 APK（无 Gradle、零依赖）。
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
SRC="$ROOT/tests/popup_fixture"
OUT="$ROOT/.check-tmp/popup-fixture"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
ANDROID_JAR="$PLATFORM/android.jar"
KEYSTORE="$ROOT/agent-lite/debug.keystore"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"
find "$SRC/res" -type f | while IFS= read -r f; do "$AAPT2" compile "$f" -o "$OUT/res/"; done
"$AAPT2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 30 --target-sdk-version 34 --auto-add-overlay "$OUT"/res/*.flat
javac --release 11 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$OUT/classes" \
  "$SRC/src/com/dsh/fixture/PopupFixtureActivity.java" "$OUT/gen/com/dsh/fixture/R.java"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --release --lib "$ANDROID_JAR" --min-api 30 --output "$OUT/dex" "$OUT/classes.jar"
cp "$OUT/unsigned.apk" "$OUT/aligned.apk"
(cd "$OUT/dex" && zip -q -u ../aligned.apk classes*.dex)
"$ZIPALIGN" -f 4 "$OUT/aligned.apk" "$OUT/aligned4.apk"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/popup-fixture.apk" "$OUT/aligned4.apk"
echo "$OUT/popup-fixture.apk"
