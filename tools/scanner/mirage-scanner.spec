# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for MirageVPN Scanner standalone binary.

Bundles:
- All Python source modules
- All pip dependencies
- xray-core binary (from bin/xray)

Build:
    pyinstaller mirage-scanner.spec

Output:
    dist/mirage-scanner  (single executable)
"""

import os
import glob
import platform

block_cipher = None

# Detect xray binary path — downloaded during CI build
machine = platform.machine().lower()
xray_binary = os.path.join('bin', 'xray')

# Bundle xray as a data file (extracted to _MEIPASS at runtime)
datas = []
if os.path.isfile(xray_binary):
    datas.append((xray_binary, '.'))

# Rich unicode data modules have hyphens in names — PyInstaller can't discover them
import rich._unicode_data
rich_unicode_dir = os.path.dirname(rich._unicode_data.__file__)
for f in glob.glob(os.path.join(rich_unicode_dir, '*.py')):
    datas.append((f, 'rich/_unicode_data'))

a = Analysis(
    ['scanner.py'],
    pathex=[],
    binaries=[],
    datas=datas,
    hiddenimports=[
        'uri_parser',
        'config_generator',
        'tester',
        'grid_search',
        'report',
        'clean_ip',
        'xray_downloader',
        'wizard',
        'ssh_manager',
        'aiohttp',
        'aiohttp_socks',
        'questionary',
        'rich',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='mirage-scanner',
    debug=False,
    bootloader_ignore_signals=False,
    strip=True,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
