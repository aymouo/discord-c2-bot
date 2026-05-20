#!/bin/bash
# Build XMRig for Android (arm64-v8a)
# Requires: Android NDK, cmake, git

set -e

XMRIG_VERSION="6.22.2"
NDK_PATH="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/27.0.12077973}"
BUILD_DIR="/tmp/xmrig-android-build"
OUTPUT_DIR="$(pwd)/app/src/main/jniLibs/arm64-v8a"

echo "=== XMRig Android Build Script ==="
echo "NDK: $NDK_PATH"
echo "Output: $OUTPUT_DIR"

# Check NDK
if [ ! -d "$NDK_PATH" ]; then
    echo "ERROR: NDK not found at $NDK_PATH"
    echo "Install via Android Studio SDK Manager or set ANDROID_NDK env var"
    exit 1
fi

TOOLCHAIN="$NDK_PATH/build/cmake/android.toolchain.cmake"

mkdir -p "$BUILD_DIR" "$OUTPUT_DIR"
cd "$BUILD_DIR"

# Clone XMRig
if [ ! -d "xmrig" ]; then
    echo "Cloning XMRig v$XMRIG_VERSION..."
    git clone --depth 1 --branch v$XMRIG_VERSION https://github.com/xmrig/xmrig.git
fi

cd xmrig

# Apply Android patches
cat > android_patch.diff << 'PATCH'
--- a/src/base/kernel/Platform_unix.cpp
+++ b/src/base/kernel/Platform_unix.cpp
@@ -45,7 +45,7 @@
 
 void xmrig::Platform::setProcessPriority(int) {}
 
-void xmrig::Platform::setThreadPriority(int) {}
+void xmrig::Platform::setThreadPriority(int priority) {}
PATCH

# Configure for Android arm64
mkdir -p build && cd build

cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-21 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DWITH_HWLOC=OFF \
    -DWITH_TLS=ON \
    -DWITH_HTTP=OFF \
    -DWITH_EMBEDDED_CONFIG=ON \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_EXE_LINKER_FLAGS="-static -Wl,--strip-all"

# Build
make -j$(nproc)

# Copy binary
if [ -f "xmrig" ]; then
    cp xmrig "$OUTPUT_DIR/libxmrig.so"
    echo "SUCCESS: Binary built at $OUTPUT_DIR/libxmrig.so"
    ls -lh "$OUTPUT_DIR/libxmrig.so"
else
    echo "ERROR: Build failed"
    exit 1
fi

echo "=== Build Complete ==="
