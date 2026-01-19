# Mirage VPN - Android Client

Android VPN client for slipstream DNS tunneling.

## Status

- ✅ UI and VPN service framework complete
- ✅ Server configuration (config.json) ready
- ⏳ Native slipstream-client binary needs to be built with Android NDK

## Building the Native Binary

The slipstream-client binary must be cross-compiled for Android using the Android NDK.

### Prerequisites
- Android NDK r26b or later
- CMake 3.13+
- OpenSSL source (for cross-compilation)

### Build Steps

1. Set up Android NDK toolchain
2. Cross-compile OpenSSL for android-arm64
3. Cross-compile picotls for android-arm64
4. Cross-compile picoquic for android-arm64
5. Build slipstream-client

Place the compiled binary in: `app/src/main/assets/bin/slipstream-client-arm64`

## Alternative: Termux

Users can also run slipstream-client through Termux:

```bash
# In Termux
pkg install git cmake ninja
git clone --recursive https://github.com/EndPositive/slipstream.git
cd slipstream
# Build and run
```

## Server Configuration

The app is pre-configured to connect to:
- **Domain:** s.savethenameofthekillers.com
- **Resolvers:** 1.1.1.1, 8.8.8.8, 9.9.9.9

## Building the APK

```bash
./gradlew assembleRelease
```

The APK will be in `app/build/outputs/apk/release/`
