#!/bin/sh
# 实验：手工构建引入 Material 3（AAR 依赖）——验证无 Gradle 环境下能否使用 M3 组件库
# 用法: sh build-with-m3.sh [/path/to/aar-dir]   默认 /tmp/m3lib/final
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)
AAPT2="$BT/aapt2"; D8="$BT/d8"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
ANDROID_JAR="$PLATFORM/android.jar"
LIBS="${1:-${M3_DEPS:-/tmp/m3lib/final}}"

BUILD=build-m3
OUT="$BUILD/agent-lite-m3.apk"
rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/libres" "$BUILD/classes" "$BUILD/gen" "$BUILD/dex" "$BUILD/libs"

echo "=== 引入 M3 依赖: $LIBS ==="
[ -d "$LIBS" ] || { echo "依赖目录不存在: $LIBS"; exit 1; }

# 0) 展开 AAR：取 classes.jar 与 res/，并收集包名（用于生成各库 R 类）
LIB_JARS=""; EXTRA_PKGS=""
# 阶段 A：解压全部 AAR
for f in "$LIBS"/*.aar; do
  [ -f "$f" ] || continue
  name=$(basename "$f" .aar)
  mkdir -p "$BUILD/libs/$name"
  unzip -o -q "$f" -d "$BUILD/libs/$name" || { echo "  ! 解压失败 $name"; continue; }
  [ -f "$BUILD/libs/$name/classes.jar" ] && LIB_JARS="$LIB_JARS:$BUILD/libs/$name/classes.jar"
  pkg=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$BUILD/libs/$name/AndroidManifest.xml" 2>/dev/null | head -1)
  [ -n "$pkg" ] && EXTRA_PKGS="$EXTRA_PKGS:$pkg"
done

# 阶段 B：资源冲突消解（必须在资源编译之前完成）
python3 - "$BUILD/libs" <<'PYEOF_INNER'
import glob, os, re, sys
base = sys.argv[1]
n = 0
# 保留 appcompat 的 SearchView 定义（attr 拥有者），移除 Material 的重复副本，
# 并把 Material 独有的平台属性并入 appcompat 定义，避免样式引用断裂。
mat = glob.glob(os.path.join(base, "*material*/res/values/*.xml"))
for f in mat:
    s = open(f).read()
    m = re.search(r'<declare-styleable name="SearchView">(.*?)</declare-styleable>', s, re.S)
    if m:
        open(f, "w").write(s[:m.start()] + s[m.end():])
        n += 1
print("  冲突消解: 移除 Material SearchView 重复定义 x%d" % n)
# 若 appcompat 缺失 Material 独有的 attr，则补声明（保底，避免引用断裂）
for f in glob.glob(os.path.join(base, "*appcompat*/res/values/*.xml")):
    s = open(f).read()
    if '<declare-styleable name="SearchView">' in s:
        extra = '<attr name="android:textAppearance" format="reference"/><attr name="android:text" format="string"/>'
        if 'android:textAppearance' not in s.split('declare-styleable name="SearchView"')[1][:2000]:
            s = s.replace('<declare-styleable name="SearchView">',
                          '<declare-styleable name="SearchView">\n        ' + extra, 1)
            open(f, "w").write(s)
            print("  已为 appcompat SearchView 补齐平台属性")
PYEOF_INNER

# 阶段 C：编译各库资源
for d in "$BUILD"/libs/*/res; do
  [ -d "$d" ] || continue
  name=$(basename "$(dirname "$d")")
  if "$AAPT2" compile --dir "$d" -o "$BUILD/libres/$name.zip" 2>"$BUILD/libres/$name.err"; then
    :
  else
    echo "  ! 资源编译失败 $name（见 $name.err）"
    rm -f "$BUILD/libres/$name.zip"
  fi
done
for f in "$LIBS"/*.jar; do
  [ -f "$f" ] && LIB_JARS="$LIB_JARS:$f"
done
PKGS=$(echo "$EXTRA_PKGS" | sed 's/^://')
echo "库 jar 数: $(echo "$LIB_JARS" | tr ':' '\n' | grep -c jar)  资源包数: $(ls "$BUILD/libres"/*.zip 2>/dev/null | wc -l | tr -d ' ')"

# 1) 我们的资源
find res -name "*.xml" -o -name "*.png" -o -name "*.webp" | while read -r f; do
  "$AAPT2" compile "$f" -o "$BUILD/res/"
done

# 2) link：并入库资源 + 为每个库生成 R 类（--extra-packages）
"$AAPT2" link -o "$BUILD/unsigned.apk" \
  -I "$ANDROID_JAR" \
  -A assets \
  --manifest AndroidManifest.xml \
  --java "$BUILD/gen" \
  --min-sdk-version 30 \
  --target-sdk-version 34 \
  --auto-add-overlay \
  --extra-packages "$PKGS" \
  "$BUILD"/res/*.flat "$BUILD"/libres/*.zip

# 3) 生成 DevConfig / PinnedCa（与原脚本一致）
API_KEY=""; BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"; MODEL="qwen3.5-omni-plus"
SYNC_URL="https://8.133.225.105:18443"
[ -f dev.local ] && . ./dev.local
mkdir -p "$BUILD/gen/com/dsh/agentlite"
cat > "$BUILD/gen/com/dsh/agentlite/DevConfig.java" <<EOF
package com.dsh.agentlite;
public final class DevConfig {
    public static final String API_KEY = "${API_KEY}";
    public static final String BASE_URL = "${BASE_URL}";
    public static final String MODEL = "${MODEL}";
    public static final String SYNC_URL = "${SYNC_URL}";
}
EOF
if [ -f assets/agentlite_sync_ca.pem ]; then
  {
    echo 'package com.dsh.agentlite;'
    echo 'public final class PinnedCa {'
    echo '    public static final String PEM ='
    lines=$(wc -l < assets/agentlite_sync_ca.pem | tr -d ' ')
    i=0
    while IFS= read -r line; do
      i=$((i+1))
      if [ "$i" -eq "$lines" ]; then printf '        "%s\\n";\n' "$line"
      else printf '        "%s\\n" +\n' "$line"; fi
    done < assets/agentlite_sync_ca.pem
    echo '}'
  } > "$BUILD/gen/com/dsh/agentlite/PinnedCa.java"
fi

# 4) javac：classpath 含所有库 jar（+ 平台 jar），源文件含各库生成的 R.java
find src -name "*.java" > "$BUILD/sources.txt"
find "$BUILD/gen" -name "*.java" >> "$BUILD/sources.txt"
echo "编译源文件: $(wc -l < "$BUILD/sources.txt" | tr -d ' ') 个（含各库 R.java）"
tr '\n' '\0' < "$BUILD/sources.txt" | xargs -0 javac \
  --release 11 -encoding UTF-8 \
  -classpath "$ANDROID_JAR$LIB_JARS" \
  -d "$BUILD/classes"

# 5) dex：我们的类 + 全部库 jar（Kotlin/AndroidX/Material）。minSdk 30 支持多 dex。
find "$BUILD/classes" -name "*.class" > "$BUILD/classes.txt"
echo "dex 输入: 我们的 class $(wc -l < "$BUILD/classes.txt" | tr -d ' ') 个 + 库 jar $(echo "$LIB_JARS" | tr ':' '\n' | grep -c jar) 个"
"$D8" --release --lib "$ANDROID_JAR" --min-api 30 --output "$BUILD/dex" \
  $(cat "$BUILD/classes.txt" | tr '\n' ' ') $(echo "$LIB_JARS" | tr ':' ' ')
echo "生成 dex: $(ls "$BUILD/dex" | tr '\n' ' ')"

# 6) 打包（多 dex 全部塞入）
cp "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
(cd "$BUILD/dex" && zip -q -u ../aligned.apk classes*.dex)

# 7) 对齐与签名
"$ZIPALIGN" -f 4 "$BUILD/aligned.apk" "$BUILD/aligned4.apk"
KEYSTORE="${AGENT_KEYSTORE:-$(pwd)/debug.keystore}"
[ -f "$KEYSTORE" ] || keytool -genkeypair -v -keystore "$KEYSTORE" -alias agent \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=agent-lite"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$BUILD/aligned4.apk"

echo "DONE: $(pwd)/$OUT $(stat -f%z "$OUT") bytes"
