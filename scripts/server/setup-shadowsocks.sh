#!/usr/bin/env bash
#
# MirageVPN - Shadowsocks Server Setup
# Installs Shadowsocks via Docker with a single command.
# No domain or certificates needed.
#
# Usage:
#   sudo bash setup-shadowsocks.sh [OPTIONS]
#
# Options:
#   --port PORT       Server port (default: 8388)
#   --method METHOD   Encryption method (default: aes-256-gcm)
#   --password PASS   Use specific password (default: auto-generated)
#   --name NAME       Config name for URI (default: Shadowsocks)
#   --help            Show this help
#

set -euo pipefail

# ─── Colors ───────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ─── Defaults ─────────────────────────────────────────────────────────
PORT=8388
METHOD="aes-256-gcm"
PASSWORD=""
NAME="Shadowsocks"
CONTAINER_NAME="ss-server"

# ─── Parse Arguments ──────────────────────────────────────────────────
show_help() {
    sed -n '2,/^$/{ s/^# //; s/^#//; p }' "$0"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --port)     PORT="$2"; shift 2 ;;
        --method)   METHOD="$2"; shift 2 ;;
        --password) PASSWORD="$2"; shift 2 ;;
        --name)     NAME="$2"; shift 2 ;;
        --help|-h)  show_help ;;
        *)          err "Unknown option: $1"; show_help ;;
    esac
done

# ─── Root Check ───────────────────────────────────────────────────────
if [[ $EUID -ne 0 ]]; then
    err "This script must be run as root (use sudo)"
    exit 1
fi

echo -e "${BOLD}"
echo "╔══════════════════════════════════════════╗"
echo "║     MirageVPN - Shadowsocks Setup        ║"
echo "╚══════════════════════════════════════════╝"
echo -e "${NC}"

# ─── Detect Server IP ────────────────────────────────────────────────
info "Detecting server IP..."
SERVER_IP=$(curl -s --max-time 10 https://api.ipify.org || curl -s --max-time 10 https://ifconfig.me || echo "")
if [[ -z "$SERVER_IP" ]]; then
    err "Could not detect server IP. Check internet connection."
    exit 1
fi
ok "Server IP: $SERVER_IP"

# ─── Generate Password ───────────────────────────────────────────────
if [[ -z "$PASSWORD" ]]; then
    PASSWORD=$(openssl rand -base64 24)
    info "Generated random password"
fi

# ─── Install Docker ──────────────────────────────────────────────────
if ! command -v docker &>/dev/null; then
    info "Installing Docker..."
    curl -fsSL https://get.docker.com | sh
    systemctl enable --now docker
    ok "Docker installed"
else
    ok "Docker already installed"
fi

# ─── Stop Existing Container ─────────────────────────────────────────
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    warn "Removing existing container '${CONTAINER_NAME}'..."
    docker rm -f "$CONTAINER_NAME" &>/dev/null || true
fi

# ─── Run Shadowsocks ─────────────────────────────────────────────────
info "Starting Shadowsocks server on port ${PORT}..."
docker run -d \
    --name "$CONTAINER_NAME" \
    --restart always \
    -p "${PORT}:${PORT}" \
    -p "${PORT}:${PORT}/udp" \
    ghcr.io/shadowsocks/ssserver-rust:latest \
    ssserver -s "[::]:${PORT}" -m "$METHOD" -k "$PASSWORD" -U

ok "Shadowsocks container started"

# ─── Network Optimizations ───────────────────────────────────────────
info "Applying network optimizations..."

apply_sysctl() {
    local key="$1" value="$2"
    sysctl -w "${key}=${value}" &>/dev/null || true
    # Persist: remove old entry, add new
    sed -i "/^${key}/d" /etc/sysctl.conf 2>/dev/null || true
    echo "${key}=${value}" >> /etc/sysctl.conf
}

apply_sysctl net.core.default_qdisc fq
apply_sysctl net.ipv4.tcp_congestion_control bbr
apply_sysctl net.ipv4.tcp_fastopen 3
apply_sysctl net.core.rmem_max 16777216
apply_sysctl net.core.wmem_max 16777216
apply_sysctl net.ipv4.ip_forward 1

ok "BBR and TCP optimizations applied"

# ─── Generate URI ─────────────────────────────────────────────────────
ENCODED=$(echo -n "${METHOD}:${PASSWORD}" | base64 -w 0)
URI="ss://${ENCODED}@${SERVER_IP}:${PORT}#${NAME}"

# ─── Save URI ─────────────────────────────────────────────────────────
URI_FILE="/root/shadowsocks-uri.txt"
echo "$URI" > "$URI_FILE"
chmod 600 "$URI_FILE"

# ─── Output ───────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}╔══════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║          Setup Complete!                  ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════╝${NC}"
echo ""
echo -e "${CYAN}Server:${NC}     $SERVER_IP"
echo -e "${CYAN}Port:${NC}       $PORT"
echo -e "${CYAN}Method:${NC}     $METHOD"
echo -e "${CYAN}Password:${NC}   $PASSWORD"
echo ""
echo -e "${GREEN}${BOLD}URI:${NC}"
echo -e "${YELLOW}${URI}${NC}"
echo ""
echo -e "URI saved to: ${URI_FILE}"
echo ""

# QR code if available
if command -v qrencode &>/dev/null; then
    echo -e "${CYAN}QR Code:${NC}"
    qrencode -t ANSIUTF8 "$URI"
    echo ""
fi

echo -e "${CYAN}Add this URI to:${NC}"
echo "  android/app/src/main/assets/protocols/shadowsocks/configs.txt"
echo ""
echo -e "${CYAN}Or add to your remote config server.${NC}"
