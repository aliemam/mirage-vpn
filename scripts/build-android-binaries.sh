#!/bin/bash
set -e

# Build slipstream-client for Android architectures
# Requires: Rust, Android NDK

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_DIR/android/app/src/main/assets/bin"

echo "======================================"
echo "  Building slipstream for Android"
echo "======================================"

# Check for required tools
if ! command -v rustup &> /dev/null; then
    echo "Error: Rust is not installed. Install from https://rustup.rs"
    exit 1
fi

if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "Error: ANDROID_NDK_HOME is not set"
    echo "Install Android NDK and set: export ANDROID_NDK_HOME=/path/to/ndk"
    exit 1
fi

# Install Rust targets for Android
echo "Installing Rust Android targets..."
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android

# Clone slipstream-rust if not exists
SLIPSTREAM_DIR="/tmp/slipstream-rust"
if [ ! -d "$SLIPSTREAM_DIR" ]; then
    echo "Cloning slipstream-rust..."
    git clone https://github.com/Mygod/slipstream-rust.git "$SLIPSTREAM_DIR"
fi

cd "$SLIPSTREAM_DIR"
git pull

# Set up cargo config for Android NDK
mkdir -p .cargo
cat > .cargo/config.toml << EOF
[target.aarch64-linux-android]
linker = "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"

[target.armv7-linux-androideabi]
linker = "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi24-clang"

[target.x86_64-linux-android]
linker = "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android24-clang"
EOF

# Build for each architecture
mkdir -p "$OUTPUT_DIR"

echo "Building for arm64-v8a..."
cargo build --release --target aarch64-linux-android --bin slipstream-client
cp target/aarch64-linux-android/release/slipstream-client "$OUTPUT_DIR/slipstream-client-arm64"

echo "Building for armeabi-v7a..."
cargo build --release --target armv7-linux-androideabi --bin slipstream-client
cp target/armv7-linux-androideabi/release/slipstream-client "$OUTPUT_DIR/slipstream-client-arm"

echo "Building for x86_64..."
cargo build --release --target x86_64-linux-android --bin slipstream-client
cp target/x86_64-linux-android/release/slipstream-client "$OUTPUT_DIR/slipstream-client-x86_64"

echo ""
echo "======================================"
echo "  Build complete!"
echo "======================================"
echo "Binaries saved to: $OUTPUT_DIR"
ls -la "$OUTPUT_DIR"
