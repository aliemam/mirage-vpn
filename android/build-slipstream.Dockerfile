FROM debian:bookworm-slim

# Install build dependencies
RUN apt-get update && apt-get install -y \
    wget \
    unzip \
    openjdk-17-jdk \
    git \
    cmake \
    ninja-build \
    python3 \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Download and setup Android NDK
ENV ANDROID_NDK_VERSION=r26b
ENV ANDROID_NDK_HOME=/opt/android-ndk
RUN wget -q https://dl.google.com/android/repository/android-ndk-${ANDROID_NDK_VERSION}-linux.zip -O /tmp/ndk.zip && \
    unzip -q /tmp/ndk.zip -d /opt && \
    mv /opt/android-ndk-${ANDROID_NDK_VERSION} ${ANDROID_NDK_HOME} && \
    rm /tmp/ndk.zip

# Set toolchain path
ENV PATH="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin:${PATH}"

WORKDIR /build

# Clone slipstream with submodules
RUN git clone --recursive https://github.com/EndPositive/slipstream.git .

# Create Android toolchain file
RUN cat > android-arm64.cmake << 'EOF'
set(CMAKE_SYSTEM_NAME Android)
set(CMAKE_SYSTEM_VERSION 24)
set(CMAKE_ANDROID_ARCH_ABI arm64-v8a)
set(CMAKE_ANDROID_NDK /opt/android-ndk)
set(CMAKE_ANDROID_STL_TYPE c++_static)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fPIC")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC")
EOF

# Build OpenSSL for Android first
WORKDIR /build/openssl
RUN wget -q https://www.openssl.org/source/openssl-3.0.12.tar.gz && \
    tar xzf openssl-3.0.12.tar.gz && \
    cd openssl-3.0.12 && \
    export ANDROID_NDK_ROOT=${ANDROID_NDK_HOME} && \
    export PATH="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin:${PATH}" && \
    ./Configure android-arm64 -D__ANDROID_API__=24 --prefix=/build/android-sysroot --openssldir=/build/android-sysroot/ssl no-shared && \
    make -j$(nproc) && \
    make install_sw

WORKDIR /build

# Build picotls for Android
WORKDIR /build/picotls
RUN git clone https://github.com/h2o/picotls.git . && \
    git checkout 5a4461d8a3948d9d26bf861e7d90cb80d8093515 && \
    mkdir build && cd build && \
    cmake .. \
        -DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-24 \
        -DCMAKE_BUILD_TYPE=Release \
        -DOPENSSL_ROOT_DIR=/build/android-sysroot \
        -DOPENSSL_INCLUDE_DIR=/build/android-sysroot/include \
        -DOPENSSL_SSL_LIBRARY=/build/android-sysroot/lib/libssl.a \
        -DOPENSSL_CRYPTO_LIBRARY=/build/android-sysroot/lib/libcrypto.a \
        -DCMAKE_INSTALL_PREFIX=/build/android-sysroot \
        -DBUILD_SHARED_LIBS=OFF && \
    make -j$(nproc) && \
    make install

# Build picoquic for Android
WORKDIR /build/picoquic
RUN git clone https://github.com/EndPositive/slipstream-picoquic . && \
    mkdir build && cd build && \
    cmake .. \
        -DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-24 \
        -DCMAKE_BUILD_TYPE=Release \
        -DOPENSSL_ROOT_DIR=/build/android-sysroot \
        -DPTLS_INCLUDE_DIRS=/build/android-sysroot/include \
        -DPTLS_CORE_LIBRARY=/build/android-sysroot/lib/libpicotls-core.a \
        -DPTLS_OPENSSL_LIBRARY=/build/android-sysroot/lib/libpicotls-openssl.a \
        -DPTLS_MINICRYPTO_LIBRARY=/build/android-sysroot/lib/libpicotls-minicrypto.a \
        -DCMAKE_INSTALL_PREFIX=/build/android-sysroot \
        -DPICOQUIC_FETCH_PTLS=OFF \
        -DBUILD_DEMO=OFF \
        -DBUILD_TESTING=OFF \
        -DBUILD_HTTP=OFF && \
    make -j$(nproc) && \
    make install || true

# Now build slipstream client with the cross-compiled dependencies
WORKDIR /build
RUN cat > CMakeLists.txt << 'EOF'
cmake_minimum_required(VERSION 3.13)
project(slipstream-android)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 11)

# Source files for client
set(CLIENT_SOURCES
    src/slipstream_client.c
    src/slipstream_client_cli.cpp
    src/slipstream_sockloop.c
    src/slipstream_utils.c
    src/slipstream_inline_dots.c
    extern/lua-resty-base-encoding/base32.c
    extern/SPCDNS/src/codec.c
    extern/SPCDNS/src/mappings.c
    extern/SPCDNS/src/netsimple.c
    extern/SPCDNS/src/output.c
)

add_executable(slipstream-client ${CLIENT_SOURCES})

target_include_directories(slipstream-client PRIVATE
    include
    extern
    extern/quick_arg_parser
    extern/SPCDNS/src
    extern/lua-resty-base-encoding
    /build/android-sysroot/include
)

target_link_libraries(slipstream-client
    /build/android-sysroot/lib/libpicoquic-core.a
    /build/android-sysroot/lib/libpicotls-core.a
    /build/android-sysroot/lib/libpicotls-openssl.a
    /build/android-sysroot/lib/libpicotls-minicrypto.a
    /build/android-sysroot/lib/libssl.a
    /build/android-sysroot/lib/libcrypto.a
    log
)
EOF

RUN mkdir -p cmake-build && cd cmake-build && \
    cmake .. \
        -DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-24 \
        -DCMAKE_BUILD_TYPE=Release && \
    make -j$(nproc) || echo "Build may have failed"

# Output directory
RUN mkdir -p /output && \
    cp cmake-build/slipstream-client /output/slipstream-client-arm64 2>/dev/null || \
    echo "Binary not found, check build logs"

CMD ["ls", "-la", "/output/"]
