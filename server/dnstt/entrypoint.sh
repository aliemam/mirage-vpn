#!/bin/sh
set -e

# Generate keys if they don't exist
if [ ! -f /keys/server.key ]; then
    echo "Generating server keys..."
    mkdir -p /keys
    dnstt-server -gen-key -privkey-file /keys/server.key -pubkey-file /keys/server.pub
    echo ""
    echo "=========================================="
    echo "PUBLIC KEY (give this to clients):"
    cat /keys/server.pub
    echo "=========================================="
    echo ""
fi

# Start microsocks SOCKS5 proxy on localhost
# dnstt-server forwards decoded tunnel traffic to this
echo "Starting microsocks on 127.0.0.1:1080..."
microsocks -i 127.0.0.1 -p 1080 &

echo "Starting dnstt-server..."
echo "Domain: ${DNSTT_DOMAIN}"
echo "Forwarding to: 127.0.0.1:1080 (microsocks SOCKS5)"

exec dnstt-server \
    -udp ":5300" \
    -privkey-file /keys/server.key \
    "${DNSTT_DOMAIN}" \
    127.0.0.1:1080
