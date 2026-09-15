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
# 3.0) dev.local（gitignore，不入库）提供**非密**构建默认值：endpoint / 模型 / 同步服务地址。
#
# ⚠️ API_KEY **禁止**内联进 APK（2026-09-13 起）。
#    原因：写进 DevConfig 的字符串会以明文留在 classes.dex，
#    任何拿到 APK 的人 `strings classes.dex | grep sk-` 即可提取（apk_readability.py 会报硬违规）。
#    更糟的是 BASE_URL 同样可被覆盖——把它指向攻击者域名，等于把密钥主动送出去。
#    密钥改为运行时录入：App 内「设置 → 配置」手填，或用 tests/push_dev_key.sh 由 adb 推送。
API_KEY=""
# 默认上游改为 DeepSeek（2026-09-15 用户决策）：换上游只需改这一行，
# 密钥仍由用户运行时录入（绝不内联进 APK）。
BASE_URL="https://api.deepseek.com/v1"; MODEL="deepseek-flash"
# 远程信息同步服务（agentlite-server）。自签证书随包打入 assets/agentlite_sync_ca.pem。
SYNC_URL="https://8.133.225.105:18443"
if [ -f dev.local ]; then
  # 在子 shell 里读，避免 dev.local 里的其它变量污染构建环境
  _DEV_API_KEY="$(. ./dev.local 2>/dev/null; printf '%s' "${API_KEY:-}")"
  _DEV_BASE_URL="$(. ./dev.local 2>/dev/null; printf '%s' "${BASE_URL:-}")"
  _DEV_MODEL="$(. ./dev.local 2>/dev/null; printf '%s' "${MODEL:-}")"
  _DEV_SYNC_URL="$(. ./dev.local 2>/dev/null; printf '%s' "${SYNC_URL:-}")"

  if [ -n "$_DEV_API_KEY" ]; then
    cat >&2 <<'EOF'

  ✗ 构建已中止：dev.local 里有 API_KEY，内联进 APK 会造成明文密钥泄露。

    APK 是会被分发/上传/备份的文件，写进 DevConfig 的密钥会以明文留在 classes.dex 里，
    任何人 strings 一下就能拿走，而这把密钥能花你的钱。

    正确做法：
      1) 删掉 dev.local 里的 API_KEY 行（BASE_URL / MODEL 仍会生效）；
      2) 装机后在 App 内「设置 → 配置 → API Key」手填一次即可（Prefs 会持久化，
         之后重装只要不清理应用数据就一直在）。

    为什么不做「adb 自动推送密钥」：本包是 release（无 android:debuggable），
    run-as 用不了、改不了 shared_prefs；而用 am start 把密钥塞进 intent extra
    会把它暴露给 logcat / dumpsys 和任何已装应用——为一个便利引入更难查的泄露不划算。

    另需自查 BASE_URL 是否指向可信域名——被改写的 BASE_URL 会把密钥直接发给攻击者，
    所以「只防 API_KEY」是不够的。

EOF
    exit 1
  fi

  [ -n "$_DEV_BASE_URL" ] && BASE_URL="$_DEV_BASE_URL"
  [ -n "$_DEV_MODEL" ] && MODEL="$_DEV_MODEL"
  [ -n "$_DEV_SYNC_URL" ] && SYNC_URL="$_DEV_SYNC_URL"
fi
if [ ! -f assets/agentlite_sync_ca.pem ]; then
  echo "!! 缺少 assets/agentlite_sync_ca.pem —— 同步功能将无法建立 TLS 连接" >&2
fi
mkdir -p "$BUILD/gen/com/dsh/agentlite"
cat > "$BUILD/gen/com/dsh/agentlite/DevConfig.java" <<EOF
package com.dsh.agentlite;
/**
 * 构建期生成（build-mac.sh），请勿手动编辑。
 *
 * API_KEY 恒为空串：**密钥绝不内联进 APK**（会以明文留在 classes.dex，可被 strings 提取）。
 * 密钥在运行时由用户录入并经 Android Keystore 保护，见 AkStore / Prefs.setApiKey。
 * endpoint 与模型名不是秘密，随包提供合理默认值即可。
 */
public final class DevConfig {
    public static final String API_KEY = "";
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
