#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
CMAKE="$SDK_ROOT/cmake/3.22.1/bin/cmake"
NINJA="$SDK_ROOT/cmake/3.22.1/bin/ninja"
BUILD_TOOLS_PROPERTIES="$SDK_ROOT/build-tools/35.0.0/source.properties"
NDK_PROPERTIES="$SDK_ROOT/ndk/26.1.10909125/source.properties"

property_value() {
  sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" "$2" | sed -n '1p'
}

first_line() { sed -n '1p'; }

printf 'validation_utc=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
printf 'git_commit=%s\n' "$(git -C "$ROOT" rev-parse HEAD)"
if test -n "$(git -C "$ROOT" status --porcelain=v1)"; then
  printf 'git_worktree=dirty\n'
else
  printf 'git_worktree=clean\n'
fi
printf 'host=%s\n' "$(uname -srm)"
if command -v sw_vers >/dev/null 2>&1; then
  printf 'host_os_version=%s\n' "$(sw_vers -productVersion)"
fi
printf 'cmake=%s\n' "$("$CMAKE" --version | first_line)"
printf 'ninja=%s\n' "$("$NINJA" --version)"
printf 'host_cxx=%s\n' "$(c++ --version | first_line)"
printf 'javac=%s\n' "$(javac -version 2>&1)"
printf 'android_build_tools=%s\n' \
  "$(property_value Pkg.Revision "$BUILD_TOOLS_PROPERTIES")"
printf 'android_ndk=%s\n' \
  "$(property_value Pkg.Revision "$NDK_PROPERTIES")"
printf 'android_stub_target=android-34/arm64-v8a\n'
for api in 34 35 36; do
  if [ -f "$SDK_ROOT/platforms/android-$api/android.jar" ]; then
    printf 'android_sdk_%s=installed\n' "$api"
  else
    printf 'android_sdk_%s=missing\n' "$api"
  fi
done

if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
  printf 'device_serial=%s\n' "$(adb get-serialno)"
  printf 'device_fingerprint=%s\n' \
    "$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
  printf 'device_spl=%s\n' \
    "$(adb shell getprop ro.build.version.security_patch | tr -d '\r')"
  printf 'device_selinux=%s\n' "$(adb shell getenforce | tr -d '\r')"
else
  printf 'device=unavailable\n'
fi
