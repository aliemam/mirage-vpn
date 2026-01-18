FROM rust:1.75-slim-bookworm AS builder

RUN apt-get update && apt-get install -y \
    cmake \
    pkg-config \
    libssl-dev \
    git \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
RUN git clone https://github.com/Mygod/slipstream-rust.git .
RUN cargo build --release

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y \
    libssl3 \
    ca-certificates \
    openssh-server \
    dante-server \
    iptables \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/release/slipstream-server /usr/local/bin/
COPY --from=builder /build/target/release/slipstream-client /usr/local/bin/
COPY entrypoint.sh /entrypoint.sh
COPY danted.conf /etc/danted.conf
RUN chmod +x /entrypoint.sh

EXPOSE 5300/udp
EXPOSE 1080/tcp

ENTRYPOINT ["/entrypoint.sh"]
