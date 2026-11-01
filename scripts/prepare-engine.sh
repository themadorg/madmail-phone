#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 28.2.13676358 or newer}"
case "$(uname -s)" in Darwin) host=darwin-x86_64 ;; Linux) host=linux-x86_64 ;; *) exit 1 ;; esac
out=app/src/main/jniLibs/arm64-v8a
mkdir -p "$out"
file="$out/libmadmail_v2_30_5.so"
expected=70ef60fac0e68f1bcb108da26206f3f9147321b381a953eec4f3db8b88a38d98
if [ ! -f "$file" ] || [ "$(shasum -a 256 "$file" | cut -d ' ' -f 1)" != "$expected" ]; then
    curl --fail --location --proto '=https' --tlsv1.2 --retry 2 'https://github.com/themadorg/madmail/releases/download/v2.30.5/madmail-linux-arm64-musl' -o "$file.part"
    [ "$(shasum -a 256 "$file.part" | cut -d ' ' -f 1)" = "$expected" ] || { rm -f "$file.part"; exit 1; }
    mv "$file.part" "$file"
fi
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android26-clang" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 native/launcher.c -o "$out/libengine_launcher.so"
