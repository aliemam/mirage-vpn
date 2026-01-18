#!/bin/bash
set -e

mkdir -p /keys

# Generate TLS certificates if they don't exist
if [ ! -f /keys/cert.pem ]; then
    echo "Generating TLS certificates..."
    openssl req -x509 -newkey rsa:2048 -nodes \
        -keyout /keys/key.pem -out /keys/cert.pem -days 3650 \
        -subj "/CN=slipstream"
    echo "Certificates generated"
fi

# Start SOCKS5 proxy in background
echo "Starting SOCKS5 proxy on 127.0.0.1:1080..."
danted -D

echo ""
echo "=========================================="
echo "SLIPSTREAM SERVER STARTING"
echo "Domain: ${SLIPSTREAM_DOMAIN}"
echo "Forwarding to SOCKS5 proxy on 127.0.0.1:1080"
echo "=========================================="
echo ""

exec slipstream-server \
    --dns-listen-port 5300 \
    --target-address "127.0.0.1:1080" \
    --domain "${SLIPSTREAM_DOMAIN}" \
    --cert /keys/cert.pem \
    --key /keys/key.pem
