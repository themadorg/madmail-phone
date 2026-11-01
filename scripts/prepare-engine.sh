#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 28.2.13676358 or newer}"
case "$(uname -s)" in Darwin) host=darwin-x86_64 ;; Linux) host=linux-x86_64 ;; *) exit 1 ;; esac
out=app/src/main/jniLibs/arm64-v8a
mkdir -p "$out"
file="$out/libmadmail_v2_30_5.so"
expected=$(uv run python -c 'import json; print(json.load(open("app/src/main/assets/engine/releases.json"))[0]["sha256"])')
if [ ! -f "$file" ] || [ "$(shasum -a 256 "$file" | cut -d ' ' -f 1)" != "$expected" ]; then
    bash scripts/build-engine.sh
fi
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android26-clang" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 native/launcher.c -o "$out/libengine_launcher.so"
