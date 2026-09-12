#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Build agent-lite APK on macOS with the stock SDK cmdline tools only
# (aapt2 + javac + d8 + zipalign + apksigner). No Gradle, no Kotlin, no deps.
# 纯平台控件 + 自定义 drawable，**不依赖任何第三方库**（无 Gradle、无 AAR、无 AndroidX）。
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

BUILD=build
OUT="$BUILD/agent-lite.apk"

echo "SDK: $SDK"
echo "build-tools: $(basename "$BT")  platform: $(basename "$PLATFORM")"

rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/classes" "$BUILD/gen" "$BUILD/dex"

# 1) compile resources
find res -name "*.xml" -o -name "*.png" -o -name "*.webp" | while read -r f; do
  "$AAPT2" compile "$f" -o "$BUILD/res/"
done

# 2) link -> unsigned apk with R.java
"$AAPT2" link -o "$BUILD/unsigned.apk" \
  -I "$ANDROID_JAR" \
  -A assets \
  --manifest AndroidManifest.xml \
  --java "$BUILD/gen" \
  --min-sdk-version 30 \
  --target-sdk-version 34 \
  --auto-add-overlay \
  "$BUILD"/res/*.flat

# 3) compile java (R.java + sources)
# 3.0) 测试便利：dev.local（gitignore，不入库）存在则把 API 配置编译进 APK。
#      密钥不落 git；发布构建时删掉 dev.local 即可回到空默认。
API_KEY=""; BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"; MODEL="qwen3.5-omni-plus"
# 远程信息同步服务（agentlite-server）。自签证书随包打入 assets/agentlite_sync_ca.pem。
SYNC_URL="https://8.133.225.105:18443"
if [ -f dev.local ]; then . ./dev.local; fi
if [ ! -f assets/agentlite_sync_ca.pem ]; then
  echo "!! 缺少 assets/agentlite_sync_ca.pem —— 同步功能将无法建立 TLS 连接" >&2
fi
mkdir -p "$BUILD/gen/com/dsh/agentlite"
cat > "$BUILD/gen/com/dsh/agentlite/DevConfig.java" <<EOF
package com.dsh.agentlite;
/** 构建期生成（build-mac.sh）。dev.local 存在时内置测试用 API 配置，请勿手动编辑。 */
public final class DevConfig {
    public static final String API_KEY = "${API_KEY}";
    public static final String BASE_URL = "${BASE_URL}";
    public static final String MODEL = "${MODEL}";
    public static final String SYNC_URL = "${SYNC_URL}";
}
EOF
# 3.0b) 内置同步服务自签证书 → PinnedCa.java（供 TlsPin 使用，免 Context）
if [ -f assets/agentlite_sync_ca.pem ]; then
  {
    echo 'package com.dsh.agentlite;'
    echo '/** 构建期生成（build-mac.sh）：内置的同步服务自签 CA，供证书 pin 使用。请勿手动编辑。 */'
    echo 'public final class PinnedCa {'
    echo '    public static final String PEM ='
    lines=$(wc -l < assets/agentlite_sync_ca.pem | tr -d ' ')
    i=0
    while IFS= read -r line; do
      i=$((i+1))
      if [ "$i" -eq "$lines" ]; then
        printf '        "%s\\n";\n' "$line"
      else
        printf '        "%s\\n" +\n' "$line"
      fi
    done < assets/agentlite_sync_ca.pem
    echo '}'
  } > "$BUILD/gen/com/dsh/agentlite/PinnedCa.java"
  echo "PinnedCa: $(grep -c '"' "$BUILD/gen/com/dsh/agentlite/PinnedCa.java") 行 PEM"
else
  echo "!! 缺少 assets/agentlite_sync_ca.pem —— 证书 pin 将无法编译" >&2
fi

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
(cd "$BUILD/dex" && zip -q -u ../aligned.apk classes*.dex)

# 6) zipalign + sign (debug key persisted at agent-lite/debug.keystore — build/ 每次被清空，
#    密钥若放 build/ 内会每轮换随机密钥导致设备无法覆盖安装)
"$ZIPALIGN" -f 4 "$BUILD/aligned.apk" "$BUILD/aligned4.apk"
KEYSTORE="${AGENT_KEYSTORE:-$(pwd)/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -v -keystore "$KEYSTORE" -alias agent \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=agent-lite"
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --key-pass pass:android --out "$OUT" "$BUILD/aligned4.apk"

echo "DONE: $(pwd)/$OUT $(stat -f%z "$OUT") bytes"
