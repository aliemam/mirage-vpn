FROM golang:1.21-alpine AS builder

RUN apk add --no-cache git

WORKDIR /build
RUN git clone https://www.bamsoftware.com/git/dnstt.git
WORKDIR /build/dnstt/dnstt-server
RUN go build -o /dnstt-server .

FROM alpine:3.19

RUN apk add --no-cache iptables ip6tables openssh-server

COPY --from=builder /dnstt-server /usr/local/bin/dnstt-server
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 5300/udp
EXPOSE 22/tcp

ENTRYPOINT ["/entrypoint.sh"]
