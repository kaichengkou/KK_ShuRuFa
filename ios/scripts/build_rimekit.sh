#!/bin/sh
# Builds librime, its dependencies and KK's bridge (RimeKit/) for the iPhone and the simulator,
# from the same CMake project as the Android build (app/src/main/jni), and the host tool that
# compiles the dictionaries the keyboard ships. Needs macOS with Xcode and CMake.
#   ios/scripts/build_rimekit.sh            → ios/build/rimekit/<platform>/libRimeKit.a,
#                                             ios/build/host/kk_bridge/kk_rime_deploy
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
JNI="$ROOT/app/src/main/jni"
BUILD="$ROOT/ios/build"
JOBS=$(sysctl -n hw.ncpu 2>/dev/null || echo 4)
COMMON="-DKK_BRIDGE_DIR=$ROOT/ios/RimeKit -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5"

# the host tool (runs here, deploys the data)
cmake -S "$JNI" -B "$BUILD/host" $COMMON
cmake --build "$BUILD/host" --target kk_rime_deploy -j "$JOBS"

for PLATFORM in iphoneos iphonesimulator; do
  OUT="$BUILD/cmake-$PLATFORM"
  # executables would be app bundles on iOS (CMake then wants a bundle destination): none are built
  # LUA_USE_IOS: Lua's os.execute uses system(), which iOS does not have
  cmake -S "$JNI" -B "$OUT" $COMMON \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="$PLATFORM" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 \
    -DCMAKE_C_FLAGS="-DLUA_USE_IOS" \
    -DCMAKE_MACOSX_BUNDLE=OFF -DENABLE_TOOLS=OFF \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO
  cmake --build "$OUT" --target kk_rime_ios -j "$JOBS"
  mkdir -p "$BUILD/rimekit/$PLATFORM"
  # every static library the bridge needs, merged into one
  find "$OUT" -name '*.a' -print > "$OUT/libs.txt"
  libtool -static -no_warning_for_no_symbols -o "$BUILD/rimekit/$PLATFORM/libRimeKit.a" $(cat "$OUT/libs.txt")
  echo "$PLATFORM: $(du -h "$BUILD/rimekit/$PLATFORM/libRimeKit.a" | cut -f1)"
done
