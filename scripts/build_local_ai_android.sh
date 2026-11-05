#!/usr/bin/env bash
set -euo pipefail
# Build the native inference library on GitHub Actions, not on the owner's PC.
# Commit pin matches the llama.cpp API used in chk_local_ai.cpp.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LLAMA_COMMIT="620d2225cc436ab0b001e3200e590e3b286ef9ab"
WORK="$ROOT/.ci-local-ai"
LLAMA="$WORK/llama.cpp-$LLAMA_COMMIT"
if [ ! -f "$LLAMA/CMakeLists.txt" ]; then
  mkdir -p "$WORK"
  curl --fail --location --retry 3 --connect-timeout 15 --max-time 240 \
    "https://github.com/ggml-org/llama.cpp/archive/$LLAMA_COMMIT.tar.gz" \
    -o "$WORK/llama.tar.gz"
  tar -xzf "$WORK/llama.tar.gz" -C "$WORK"
  rm -f "$WORK/llama.tar.gz"
fi
ANDROID_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_SDK_ROOT" ]; then echo "ANDROID_HOME requis" >&2; exit 1; fi
NDK="${ANDROID_NDK_HOME:-$ANDROID_SDK_ROOT/ndk/27.2.12479018}"
test -f "$NDK/build/cmake/android.toolchain.cmake"
cmake -S "$ROOT/app/src/main/cpp" -B "$WORK/build-arm64" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
  -DLLAMA_SOURCE_DIR="$LLAMA"
cmake --build "$WORK/build-arm64" --target chk_local_ai -j2
NATIVE="$(find "$WORK/build-arm64" -name libchk_local_ai.so -type f -print -quit)"
test -n "$NATIVE"
mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a"
cp "$NATIVE" "$ROOT/app/src/main/jniLibs/arm64-v8a/libchk_local_ai.so"
echo "Moteur IA compilé : $(stat -c%s "$NATIVE") octets (arm64-v8a)"
