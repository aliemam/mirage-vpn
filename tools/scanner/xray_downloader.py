"""Download xray-core binary if not found locally.

Search order:
1. PyInstaller bundle (when running as standalone binary)
2. ./bin/xray (local to scanner)
3. xray on $PATH
4. Download from GitHub releases (fallback, needs internet)
"""

import os
import platform
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

import urllib.request

GITHUB_RELEASE_URL = (
    "https://github.com/XTLS/Xray-core/releases/latest/download"
)

# When running as PyInstaller bundle, _MEIPASS points to the temp extract dir
if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
    SCANNER_DIR = Path(sys._MEIPASS)
else:
    SCANNER_DIR = Path(__file__).parent

BIN_DIR = SCANNER_DIR / "bin"


def _detect_platform() -> str:
    """Detect platform for xray download: linux-64, linux-arm64-v8a, etc."""
    system = platform.system().lower()
    machine = platform.machine().lower()

    if system == "linux":
        if machine in ("x86_64", "amd64"):
            return "linux-64"
        elif machine in ("aarch64", "arm64"):
            return "linux-arm64-v8a"
        else:
            raise RuntimeError(f"Unsupported Linux architecture: {machine}")
    elif system == "darwin":
        if machine in ("x86_64", "amd64"):
            return "macos-64"
        elif machine in ("arm64", "aarch64"):
            return "macos-arm64-v8a"
        else:
            raise RuntimeError(f"Unsupported macOS architecture: {machine}")
    else:
        raise RuntimeError(
            f"Unsupported platform: {system}. "
            "Please download xray manually and place it in tools/scanner/bin/"
        )


def _find_in_path() -> str | None:
    """Check if xray is already on $PATH."""
    return shutil.which("xray")


def _find_local() -> str | None:
    """Check if xray exists in ./bin/."""
    local = BIN_DIR / "xray"
    if local.is_file() and os.access(local, os.X_OK):
        return str(local)
    return None


def _download(platform_str: str, target_dir: Path) -> str:
    """Download xray-core from GitHub releases."""
    filename = f"Xray-{platform_str}.zip"
    url = f"{GITHUB_RELEASE_URL}/{filename}"

    target_dir.mkdir(parents=True, exist_ok=True)
    target_path = target_dir / "xray"

    with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as tmp:
        tmp_path = tmp.name

    try:
        urllib.request.urlretrieve(url, tmp_path)

        with zipfile.ZipFile(tmp_path, "r") as zf:
            # Find the xray binary inside the zip
            xray_name = None
            for name in zf.namelist():
                if name.lower() in ("xray", "xray.exe"):
                    xray_name = name
                    break

            if not xray_name:
                raise RuntimeError(
                    f"xray binary not found in downloaded archive. "
                    f"Contents: {zf.namelist()}"
                )

            with zf.open(xray_name) as src, open(target_path, "wb") as dst:
                dst.write(src.read())

        # Make executable
        target_path.chmod(target_path.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
        return str(target_path)
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


def _find_bundled() -> str | None:
    """Check if xray is bundled inside a PyInstaller binary."""
    if not (getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS")):
        return None
    bundled = Path(sys._MEIPASS) / "xray"
    if bundled.is_file() and os.access(bundled, os.X_OK):
        return str(bundled)
    return None


def ensure_xray(quiet: bool = False) -> str:
    """Find or download xray-core. Returns path to binary.

    Search order:
    1. PyInstaller bundle (standalone binary mode)
    2. ./bin/xray (local to scanner)
    3. xray on $PATH
    4. Download from GitHub releases (needs internet)
    """
    # 1. Check PyInstaller bundle
    bundled = _find_bundled()
    if bundled:
        return bundled

    # 2. Check local bin
    local = _find_local()
    if local:
        return local

    # 3. Check PATH
    on_path = _find_in_path()
    if on_path:
        return on_path

    # 4. Download
    if not quiet:
        plat = _detect_platform()
        print(f"  Downloading xray-core ({plat})...", end=" ", flush=True)
        path = _download(plat, BIN_DIR)
        print("done")
        return path
    else:
        plat = _detect_platform()
        return _download(plat, BIN_DIR)
