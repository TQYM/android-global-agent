#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# build-asr-libs.sh —— 构建**离线语音转写**所需的 Android 原生库（whisper.cpp）。
#
#   sh tools/build-asr-libs.sh            # 全量：下载源码 → NDK 交叉编译 → 产出 .so
#   sh tools/build-asr-libs.sh --jni-only # 只重链 JNI（whisper.cpp 已编好时）
#
# 产出（被 build-mac.sh 打进 APK 的 lib/arm64-v8a/）：
#   libagentasr.so   本项目的 JNI 层（agent-lite/jni/whisper_jni.cpp）
#   libwhisper.so     whisper.cpp
#   libggml*.so       ggml 运行时（base / cpu / 顶层）
#   libc++_shared.so  NDK 的 C++ 运行时（系统不带，必须打包）
#
# ⚠ 两个必须记住的坑（都实际踩过，2026-09-18）：
#  1. **必须 -DGGML_OPENMP=OFF**：whisper.cpp 默认开 OpenMP，NDK 不提供 libomp.so，
#     结果是运行期 `UnsatisfiedLinkError: dlopen failed: library "libomp.so" not found`，
#     表现为「本地转写库未打包（ABI 不匹配）」这种误导性日志。
#  2. **必须 android:extractNativeLibs="true"**：ggml 的 CPU 后端是运行时 dlopen 的，
#     若系统把 .so 留在 APK 内不解压，它找不到文件。
#  另外：本机 make/git 走 Xcode 工具链时会因未同意 license 失败，故导出 DEVELOPER_DIR。
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/26.1.10909125}"
CMAKE_BIN="${ANDROID_CMAKE:-$HOME/Library/Android/sdk/cmake/3.22.1/bin}"
SRC="${WHISPER_SRC:-/tmp/whisper.cpp}"
OUT="$ROOT/agent-lite/vendor/asr/lib/arm64-v8a"
API=30

export DEVELOPER_DIR=/Library/Developer/CommandLineTools
export PATH="$CMAKE_BIN:$PATH"

[ -x "$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang++" ] || {
  echo "✗ 找不到 NDK：$NDK（可用 ANDROID_NDK 指定）" >&2; exit 1; }
CXX="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android${API}-clang++"
SYSROOT_LIB="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android"

if [ "${1:-}" != "--jni-only" ]; then
  if [ ! -d "$SRC" ]; then
    echo "· 下载 whisper.cpp 源码到 $SRC"
    curl -sL -o /tmp/whisper-src.tar.gz \
      "https://github.com/ggerganov/whisper.cpp/archive/refs/heads/master.tar.gz"
    rm -rf /tmp/whisper.cpp-master && tar xzf /tmp/whisper-src.tar.gz -C /tmp
    mv /tmp/whisper.cpp-master "$SRC"
  fi
  echo "· 交叉编译 whisper.cpp（arm64-v8a, OpenMP 关闭）"
  rm -rf "$SRC/build-android"
  cmake -B "$SRC/build-android" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API -DANDROID_STL=c++_shared \
    -DGGML_OPENMP=OFF -DBUILD_SHARED_LIBS=ON -DWHISPER_BUILD_EXAMPLES=OFF \
    -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF > /tmp/whisper-cmake.log 2>&1
  cmake --build "$SRC/build-android" -j4 > /tmp/whisper-build.log 2>&1
  mkdir -p "$OUT"
  cp "$SRC"/build-android/bin/libwhisper.so "$SRC"/build-android/bin/libggml*.so "$OUT"/
  echo "✓ whisper.cpp 编译产出已复制"
fi

mkdir -p "$OUT"
echo "· 编译 JNI 层"
"$CXX" -shared -fPIC -O2 -std=c++17 -fno-openmp \
  -I"$SRC/include" -I"$SRC/ggml/include" \
  -o "$OUT/libagentasr.so" "$ROOT/agent-lite/jni/whisper_jni.cpp" \
  -L"$SRC/build-android/bin" -lwhisper -lggml -lggml-base -llog -lz -lm
cp -f "$SYSROOT_LIB/libc++_shared.so" "$OUT/"

echo "✓ 原生库就绪："
ls -la "$OUT" | awk 'NR>3 {printf "    %-24s %.1f MB\n", $9, $5/1048576}'
echo "下一步：cd agent-lite && sh build-mac.sh   （会自动打进 APK 的 lib/arm64-v8a/）"
