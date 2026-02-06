FROM debian:bookworm-slim AS builder

RUN apt-get update && apt-get install -y \
    meson \
    ninja-build \
    cmake \
    pkg-config \
    libssl-dev \
    git \
    build-essential \
    python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
RUN git clone --recursive https://github.com/EndPositive/slipstream.git .
# Disable werror in meson.build
RUN sed -i "s/werror=true/werror=false/g" meson.build || true
RUN sed -i "s/'werror', true/'werror', false/g" meson.build || true

# Fix missing picotls dependencies - add explicit picotls libraries to link
RUN sed -i "s/picoquic_proj.dependency('picoquic-core'),/picoquic_proj.dependency('picoquic-core'),\\n  picoquic_proj.dependency('picotls-core'),\\n  picoquic_proj.dependency('picotls-openssl'),\\n  picoquic_proj.dependency('picotls-minicrypto'),/g" meson.build

RUN meson setup builddir --buildtype=release --warnlevel=0
RUN meson compile -C builddir

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y \
    libssl3 \
    ca-certificates \
    openssl \
    dante-server \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/builddir/slipstream-server /usr/local/bin/
COPY --from=builder /build/builddir/slipstream-client /usr/local/bin/
COPY entrypoint.sh /entrypoint.sh
COPY danted.conf /etc/danted.conf
RUN chmod +x /entrypoint.sh

EXPOSE 5300/udp
EXPOSE 1080/tcp

ENTRYPOINT ["/entrypoint.sh"]
