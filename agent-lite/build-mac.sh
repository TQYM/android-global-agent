#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Build agent-lite APK on macOS with the stock SDK cmdline tools only
# (aapt2 + javac + d8 + zipalign + apksigner). No Gradle, no Kotlin, no deps.
# Lite：无沙盒守护进程，纯无障碍 + 可选 root 加速。
# 说明：界面已全面使用 Material 3 组件（MaterialButton / MaterialSwitch），
#      因此构建需要 M3 依赖（AAR）。依赖不入库，首次构建前执行：
#          python3 scripts/fetch-m3-deps.py     # 下载到 .m3-deps/（阿里云镜像，秒级）
#      或设置 M3_DEPS 指向已有依赖目录。
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
M3_DEPS="${M3_DEPS:-$HOME/.agent-lite-m3-deps}"   # 无空格路径：d8 参数不接受含空格的 jar 路径

echo "SDK: $SDK"
echo "build-tools: $(basename "$BT")  platform: $(basename "$PLATFORM")"

rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/classes" "$BUILD/gen" "$BUILD/dex"

# 0) Material 3 依赖处理（无 Gradle：手工展开 AAR、消解资源冲突、编译库资源）
LIB_JARS=""; EXTRA_PKGS=""
if [ -d "$M3_DEPS" ] && ls "$M3_DEPS"/*.aar >/dev/null 2>&1; then
  mkdir -p "$BUILD/libs" "$BUILD/libres"
  for f in "$M3_DEPS"/*.aar; do
    name=$(basename "$f" .aar)
    mkdir -p "$BUILD/libs/$name"
    unzip -o -q "$f" -d "$BUILD/libs/$name" || { echo "  ! 解压失败 $name"; continue; }
    [ -f "$BUILD/libs/$name/classes.jar" ] && LIB_JARS="$LIB_JARS:$BUILD/libs/$name/classes.jar"
    pkg=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$BUILD/libs/$name/AndroidManifest.xml" 2>/dev/null | head -1)
    [ -n "$pkg" ] && EXTRA_PKGS="$EXTRA_PKGS:$pkg"
  done
  for f in "$M3_DEPS"/*.jar; do
    [ -f "$f" ] && LIB_JARS="$LIB_JARS:$f"
  done
  # 资源冲突消解：Material 与 appcompat 重复定义 declare-styleable/SearchView（必须在编译资源前）
  python3 - "$BUILD/libs" <<'PYEOF_INNER'
import glob, os, re, sys
base = sys.argv[1]; n = 0
for f in glob.glob(os.path.join(base, "*material*/res/values/*.xml")):
    s = open(f).read()
    m = re.search(r'<declare-styleable name="SearchView">.*?</declare-styleable>', s, re.S)
    if m:
        open(f, "w").write(s[:m.start()] + s[m.end():]); n += 1
for f in glob.glob(os.path.join(base, "*appcompat*/res/values/*.xml")):
    s = open(f).read()
    if '<declare-styleable name="SearchView">' in s and 'android:textAppearance' not in s.split('declare-styleable name="SearchView"')[1][:2000]:
        s = s.replace('<declare-styleable name="SearchView">',
                      '<declare-styleable name="SearchView">\n        <attr name="android:textAppearance" format="reference"/><attr name="android:text" format="string"/>', 1)
        open(f, "w").write(s)
print("  依赖: 冲突消解（SearchView）x%d" % n)
PYEOF_INNER
  for d in "$BUILD"/libs/*/res; do
    [ -d "$d" ] || continue
    nm=$(basename "$(dirname "$d")")
    "$AAPT2" compile --dir "$d" -o "$BUILD/libres/$nm.zip" 2>/dev/null || rm -f "$BUILD/libres/$nm.zip"
  done
  PKGS=$(echo "$EXTRA_PKGS" | sed 's/^://')
  echo "M3 依赖: jar $(echo "$LIB_JARS" | tr ':' '\n' | grep -c jar) 个，资源包 $(ls "$BUILD/libres"/*.zip 2>/dev/null | wc -l | tr -d ' ') 个"
else
  echo "!! 未找到 M3 依赖目录 $M3_DEPS —— 布局中的 MaterialButton 将无法解析" >&2
  echo "   请先运行: python3 scripts/fetch-m3-deps.py" >&2
  PKGS=""
fi

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
  ${PKGS:+--extra-packages "$PKGS"} \
  "$BUILD"/res/*.flat "$BUILD"/libres/*.zip

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
  -classpath "$ANDROID_JAR$LIB_JARS" \
  -d "$BUILD/classes"

# 4) dex
find "$BUILD/classes" -name "*.class" > "$BUILD/classes.txt"
"$D8" --release --lib "$ANDROID_JAR" --min-api 30 --output "$BUILD/dex" \
  $(cat "$BUILD/classes.txt" | tr '\n' ' ') $(echo "$LIB_JARS" | tr ':' ' ')

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
