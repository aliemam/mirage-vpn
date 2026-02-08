#!/usr/bin/env bash
# Build MirageVPN Scanner as a standalone executable.
#
# This script:
# 1. Creates/activates Python venv and installs dependencies
# 2. Downloads xray-core for the current platform
# 3. Runs PyInstaller to bundle everything into one file
# 4. Outputs: dist/mirage-scanner
#
# Usage:
#   cd tools/scanner
#   bash build-standalone.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== MirageVPN Scanner — Standalone Build ==="

# 1. Setup venv and install dependencies
if [ ! -d .venv ]; then
    echo "Creating Python virtual environment..."
    python3 -m venv .venv
fi
source .venv/bin/activate
echo "Installing dependencies..."
pip install --quiet -r requirements.txt
pip install --quiet pyinstaller

# 2. Ensure xray binary exists
if [ ! -f bin/xray ]; then
    echo "Downloading xray-core..."

    ARCH=$(uname -m)
    case "$ARCH" in
        x86_64|amd64)  XRAY_ARCH="linux-64" ;;
        aarch64|arm64) XRAY_ARCH="linux-arm64-v8a" ;;
        *)             echo "Unsupported arch: $ARCH"; exit 1 ;;
    esac

    OS=$(uname -s | tr '[:upper:]' '[:lower:]')
    if [ "$OS" = "darwin" ]; then
        case "$ARCH" in
            x86_64|amd64)  XRAY_ARCH="macos-64" ;;
            aarch64|arm64) XRAY_ARCH="macos-arm64-v8a" ;;
        esac
    fi

    mkdir -p bin
    TMPFILE=$(mktemp /tmp/xray-XXXXXX.zip)
    curl -L -o "$TMPFILE" \
        "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-${XRAY_ARCH}.zip"
    unzip -o "$TMPFILE" xray -d bin/
    chmod +x bin/xray
    rm -f "$TMPFILE"
    echo "Downloaded xray-core (${XRAY_ARCH}) to bin/xray"
else
    echo "Using existing bin/xray"
fi

# 3. Verify xray will be bundled
if [ ! -f bin/xray ]; then
    echo "ERROR: bin/xray not found — binary would not include xray-core"
    exit 1
fi
XRAY_SIZE=$(du -h bin/xray | cut -f1)
echo "Bundling xray-core ($XRAY_SIZE) into binary..."

# 4. Build
python -m PyInstaller mirage-scanner.spec --noconfirm --clean

# 5. Report (bin/xray is kept for Python venv usage)
BINARY="dist/mirage-scanner"
if [ -f "$BINARY" ]; then
    SIZE=$(du -h "$BINARY" | cut -f1)
    echo ""
    echo "=== Build complete ==="
    echo "Binary: $BINARY ($SIZE)"
    echo "Test:   ./dist/mirage-scanner --help"
else
    echo "ERROR: Build failed, binary not found at $BINARY"
    exit 1
fi
