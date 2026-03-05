FROM golang:1.21-alpine AS builder

RUN apk add --no-cache git gcc musl-dev make

# Build dnstt-server
WORKDIR /build
RUN git clone https://www.bamsoftware.com/git/dnstt.git
WORKDIR /build/dnstt/dnstt-server
RUN go build -o /dnstt-server .

# Build microsocks (lightweight SOCKS5 proxy)
WORKDIR /build
RUN git clone https://github.com/rofl0r/microsocks.git
WORKDIR /build/microsocks
RUN make

FROM alpine:3.19

RUN apk add --no-cache iptables ip6tables

COPY --from=builder /dnstt-server /usr/local/bin/dnstt-server
COPY --from=builder /build/microsocks/microsocks /usr/local/bin/microsocks
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 5300/udp

ENTRYPOINT ["/entrypoint.sh"]
