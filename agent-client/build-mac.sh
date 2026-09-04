#!/bin/sh
# Build agent-client APK on macOS with the stock SDK cmdline tools only
# (aapt2 + javac + d8 + zipalign + apksigner). No Gradle, no Kotlin, no deps.
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
ANDROID_JAR="$PLATFORM/android.jar"

PKG_PATH="com/dsh/agent"
BUILD=build
OUT="$BUILD/agent-client.apk"

echo "SDK: $SDK"
echo "build-tools: $(basename "$BT")  platform: $(basename "$PLATFORM")"

rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/classes" "$BUILD/gen" "$BUILD/dex"

# 1) compile resources
find res -name "*.xml" -o -name "*.png" | while read -r f; do
  "$AAPT2" compile "$f" -o "$BUILD/res/"
done

# 2) link -> unsigned apk with R.java
"$AAPT2" link -o "$BUILD/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java "$BUILD/gen" \
  --min-sdk-version 30 \
  --target-sdk-version 34 \
  "$BUILD"/res/*.flat

# 3) compile java (R.java + sources)
find src -name "*.java" > "$BUILD/sources.txt"
find "$BUILD/gen" -name "*.java" >> "$BUILD/sources.txt"
# xargs -0 for paths with spaces; @argfile breaks on them
tr '\n' '\0' < "$BUILD/sources.txt" | xargs -0 javac \
  --release 11 -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes"

# 4) dex
find "$BUILD/classes" -name "*.class" > "$BUILD/classes.txt"
"$D8" --release --lib "$ANDROID_JAR" --min-api 30 --output "$BUILD/dex" \
  $(cat "$BUILD/classes.txt" | tr '\n' ' ')

# 5) add classes.dex to apk
cp "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
(cd "$BUILD/dex" && zip -q -u ../aligned.apk classes.dex)

# 6) zipalign + sign (debug key, generated once at repo root build/)
"$ZIPALIGN" -f 4 "$BUILD/aligned.apk" "$BUILD/aligned4.apk"
KEYSTORE="${AGENT_KEYSTORE:-$BUILD/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -v -keystore "$KEYSTORE" -alias agent \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=agent-client"
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --key-pass pass:android --out "$OUT" "$BUILD/aligned4.apk"

echo "DONE: $(pwd)/$OUT $(stat -f%z "$OUT") bytes"
