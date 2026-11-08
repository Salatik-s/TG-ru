#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK r27c}"
SOURCE=${FAMILYGRAM_UPSTREAM:-"$ROOT/upstream"}
ABI=${ANDROID_ABI:-arm64-v8a}
BUILD_ROOT=${FAMILYGRAM_BUILD_ROOT:-"$ROOT/build"}
GENERATED="$BUILD_ROOT/$ABI/generated"
node "$ROOT/prepare.mjs" --source "$SOURCE" --out "$GENERATED" --abi "$ABI"
cmake -S "$ROOT" -B "$BUILD_ROOT/$ABI" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DFAMILYGRAM_UPSTREAM="$SOURCE" -DFAMILYGRAM_GENERATED="$GENERATED"
cmake --build "$BUILD_ROOT/$ABI" --parallel "${JOBS:-4}"
node "$ROOT/verify-elf.mjs" --readelf "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" --library "$BUILD_ROOT/$ABI/libfamilytgnet.so" --abi "$ABI"
