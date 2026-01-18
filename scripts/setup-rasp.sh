#!/bin/bash
set -e

echo "======================================"
echo "  Mirage VPN - Raspberry Pi Setup"
echo "======================================"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    log_error "Please run as root (sudo ./setup-rasp.sh)"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# 1. Install Docker if not present
log_info "Checking Docker installation..."
if ! command -v docker &> /dev/null; then
    log_info "Installing Docker..."
    curl -fsSL https://get.docker.com | sh
    usermod -aG docker $SUDO_USER
    systemctl enable docker
    systemctl start docker
    log_info "Docker installed successfully"
else
    log_info "Docker already installed"
fi

# 2. Install Docker Compose if not present
if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    log_info "Installing Docker Compose..."
    apt-get update
    apt-get install -y docker-compose-plugin
    log_info "Docker Compose installed successfully"
else
    log_info "Docker Compose already installed"
fi

# 3. Configure firewall
log_info "Configuring firewall..."

# Install iptables-persistent if not present
apt-get install -y iptables-persistent

# Open port 53 UDP for DNS
iptables -A INPUT -p udp --dport 53 -j ACCEPT
iptables -A INPUT -p udp --dport 5300 -j ACCEPT

# Port forward 53 -> 5300 (for dnstt/slipstream)
iptables -t nat -A PREROUTING -p udp --dport 53 -j REDIRECT --to-port 5300

# Save iptables rules
netfilter-persistent save

log_info "Firewall configured"

# 4. Disable systemd-resolved if it's using port 53
if systemctl is-active --quiet systemd-resolved; then
    log_warn "systemd-resolved is using port 53, disabling..."
    systemctl stop systemd-resolved
    systemctl disable systemd-resolved
    # Update resolv.conf to use external DNS
    rm -f /etc/resolv.conf
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
    echo "nameserver 1.1.1.1" >> /etc/resolv.conf
    log_info "systemd-resolved disabled"
fi

# 5. Create .env if not exists
if [ ! -f "$PROJECT_DIR/.env" ]; then
    log_info "Creating .env file..."
    cp "$PROJECT_DIR/.env.example" "$PROJECT_DIR/.env"
    log_warn "Please edit $PROJECT_DIR/.env with your settings"
fi

# 6. Build and start dnstt (immediate solution)
log_info "Building dnstt server..."
cd "$PROJECT_DIR/server/dnstt"
docker compose build

log_info "Starting dnstt server..."
docker compose up -d

# 7. Wait for keys to be generated and display them
sleep 3
log_info "Waiting for key generation..."

if [ -f "$PROJECT_DIR/server/dnstt/keys/server.pub" ]; then
    echo ""
    echo "======================================"
    echo "  DNSTT SERVER READY!"
    echo "======================================"
    echo ""
    echo "Domain: $(grep DNSTT_DOMAIN $PROJECT_DIR/.env | cut -d= -f2)"
    echo ""
    echo "Public Key (give to clients):"
    cat "$PROJECT_DIR/server/dnstt/keys/server.pub"
    echo ""
    echo "======================================"
else
    log_warn "Keys not yet generated, check logs: docker logs dnstt-server"
fi

# 8. Show status
echo ""
log_info "Setup complete! Current status:"
docker ps

echo ""
echo "======================================"
echo "  NEXT STEPS"
echo "======================================"
echo ""
echo "1. Configure DNS records for your domain:"
echo "   - NS record: t.aemirage.ddns.net -> ns-t.aemirage.ddns.net"
echo "   - A record:  ns-t.aemirage.ddns.net -> 62.78.179.172"
echo ""
echo "2. Or use a real domain with proper NS delegation"
echo ""
echo "3. Test with: dig @62.78.179.172 test.t.aemirage.ddns.net"
echo ""
echo "4. Give users the DarkTunnel app and connection details"
echo ""
