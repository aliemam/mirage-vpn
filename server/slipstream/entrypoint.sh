#!/bin/bash
set -e

# Generate keys if they don't exist
if [ ! -f /keys/server.key ]; then
    echo "Generating server keys..."
    mkdir -p /keys
    # Generate a random 32-byte key for slipstream
    openssl rand -hex 32 > /keys/server.key
    echo ""
    echo "=========================================="
    echo "SERVER KEY (give this to clients):"
    cat /keys/server.key
    echo "=========================================="
    echo ""
fi

SERVER_KEY=$(cat /keys/server.key)

# Start SOCKS5 proxy in background
echo "Starting SOCKS5 proxy on 127.0.0.1:1080..."
danted -D

echo "Starting slipstream-server..."
echo "Domain: ${SLIPSTREAM_DOMAIN}"
echo "Forwarding to SOCKS5 proxy on 127.0.0.1:1080"

exec slipstream-server \
    --listen-udp "0.0.0.0:5300" \
    --dns-zone "${SLIPSTREAM_DOMAIN}" \
    --upstream "127.0.0.1:1080" \
    --key "${SERVER_KEY}"
