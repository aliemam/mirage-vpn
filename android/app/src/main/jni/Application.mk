# Application.mk for MirageVPN
APP_OPTIM := release
APP_PLATFORM := android-24
APP_ABI := arm64-v8a
APP_CFLAGS := -O3 -DPKGNAME=net/mirage/vpn -DCLSNAME=TunnelNative
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
NDK_TOOLCHAIN_VERSION := clang
