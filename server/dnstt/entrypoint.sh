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

# Setup SSH server for tunneling
if [ ! -f /etc/ssh/ssh_host_rsa_key ]; then
    ssh-keygen -A
fi

# Create tunnel user if not exists
if ! id "tunnel" &>/dev/null; then
    adduser -D -s /bin/sh tunnel
    echo "tunnel:${TUNNEL_PASSWORD:-changeme}" | chpasswd
fi

# Start SSH daemon in background
/usr/sbin/sshd

echo "Starting dnstt-server..."
echo "Domain: ${DNSTT_DOMAIN}"
echo "Forwarding to: 127.0.0.1:22 (SSH)"

exec dnstt-server \
    -udp ":5300" \
    -privkey-file /keys/server.key \
    "${DNSTT_DOMAIN}" \
    127.0.0.1:22
