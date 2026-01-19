# Top-level Android.mk for MirageVPN
TOP_PATH := $(call my-dir)

# Set package name for JNI class lookup
APP_CFLAGS += -DPKGNAME=net/mirage/vpn -DCLSNAME=TunnelNative

# Include hev-socks5-tunnel
include $(TOP_PATH)/hev-socks5-tunnel/Android.mk
