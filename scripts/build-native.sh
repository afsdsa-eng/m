#!/usr/bin/env bash
# NDKで native/mouseremapd.c をビルドし、APKに同梱する assets/ へ出力する。
set -euo pipefail
cd "$(dirname "$0")/.."

NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  [ -n "$SDK" ] && NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -n1 || true)"
fi
if { [ -z "$NDK" ] || [ ! -d "$NDK" ]; } && [ -n "$SDK" ]; then
  SDKM="$(ls "$SDK"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | tail -n1 || true)"
  if [ -n "$SDKM" ]; then
    echo "NDKが見つからないのでインストールします..."
    yes | "$SDKM" --licenses >/dev/null || true
    "$SDKM" "ndk;27.2.12479018"
    NDK="$SDK/ndk/27.2.12479018"
  fi
fi
[ -d "${NDK:-}" ] || { echo "NDKが見つかりません。ANDROID_NDK_HOME を設定してください。" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64 ;;
  *)      HOST_TAG=linux-x86_64 ;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
API=28
echo "NDK: $NDK"

build() { # <abi> <clang triple>
  local out="app/src/main/assets/mouseremapd/$1"
  mkdir -p "$out"
  "$BIN/$2$API-clang" -O2 -Wall -fPIE -pie -Wl,-z,max-page-size=16384 \
    -o "$out/mouseremapd" native/mouseremapd.c
  "$BIN/llvm-strip" "$out/mouseremapd" || true
  echo "built: $out/mouseremapd"
}

rm -rf app/src/main/assets/mouseremapd
build arm64-v8a   aarch64-linux-android
build armeabi-v7a armv7a-linux-androideabi
build x86_64      x86_64-linux-android
build x86         i686-linux-android
