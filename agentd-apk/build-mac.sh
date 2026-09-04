#!/bin/sh
# Build agentd-ui.apk on macOS without Gradle: aapt2 + javac + d8 + apksigner.
# Requires: JDK (javac on PATH), $HOME/Library/Android/sdk build-tools 36 +
# platforms;android-34. Output: agentd-apk/build/agentd-ui.apk
set -e
root="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
bt="$sdk/build-tools/36.0.0"
plat="$sdk/platforms/android-34/android.jar"

out="$root/build"
rm -rf "$out"
mkdir -p "$out" "$out/classes" "$out/dex"
cd "$root"

echo "== 1. aapt2 compile resources =="
"$bt/aapt2" compile --dir res -o "$out/res.zip"

echo "== 2. aapt2 link =="
"$bt/aapt2" link -o "$out/base.apk" -I "$plat" \
    --manifest AndroidManifest.xml -R "$out/res.zip" \
    --java "$out/gen" --auto-add-overlay

echo "== 3. javac =="
find src "$out/gen" -name '*.java' -print0 |
  xargs -0 javac --release 11 -encoding UTF-8 \
      -classpath "$plat" -d "$out/classes"

echo "== 4. d8 dex =="
find "$out/classes" -name '*.class' -print0 |
  xargs -0 "$bt/d8" --release --lib "$plat" --min-api 29 \
      --output "$out/dex"

echo "== 5. package dex into apk =="
jar -uf "$out/base.apk" -C "$out/dex" classes.dex

echo "== 6. zipalign =="
"$bt/zipalign" -f 4 "$out/base.apk" "$out/aligned.apk"

echo "== 7. sign =="
ks="$root/debug.keystore"
if [ ! -f "$ks" ]; then
    keytool -genkeypair -keystore "$ks" -alias agentd \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android -dname "CN=agentd"
fi
"$bt/apksigner" sign --ks "$ks" --ks-pass pass:android \
    --ks-key-alias agentd --key-pass pass:android \
    --out "$out/agentd-ui.apk" "$out/aligned.apk"

echo "DONE: $out/agentd-ui.apk $(stat -f%z "$out/agentd-ui.apk") bytes"
