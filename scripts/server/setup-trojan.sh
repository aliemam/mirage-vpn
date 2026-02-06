#!/usr/bin/env bash
#
# MirageVPN - Trojan Server Setup
# Installs Xray-core with Trojan protocol and Let's Encrypt TLS.
#
# Prerequisites:
#   1. A domain with DNS A record pointing to this server's IP
#   2. Port 80 and 443 must be free (no other web server running)
#
# Usage:
#   sudo bash setup-trojan.sh --domain yourdomain.com [OPTIONS]
#
# Options:
#   --domain DOMAIN     Your domain (required)
#   --port PORT         Server port (default: 443)
#   --password PASS     Use specific password (default: auto-generated)
#   --name NAME         Config name for URI (default: domain name)
#   --email EMAIL       Email for Let's Encrypt (default: none)
#   --add-client        Add a new client to existing setup
#   --help              Show this help
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
DOMAIN=""
PORT=443
PASSWORD=""
NAME=""
EMAIL=""
ADD_CLIENT=false
CONFIG_FILE="/usr/local/etc/xray/config.json"

# ─── Parse Arguments ──────────────────────────────────────────────────
show_help() {
    sed -n '2,/^$/{ s/^# //; s/^#//; p }' "$0"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --domain)      DOMAIN="$2"; shift 2 ;;
        --port)        PORT="$2"; shift 2 ;;
        --password)    PASSWORD="$2"; shift 2 ;;
        --name)        NAME="$2"; shift 2 ;;
        --email)       EMAIL="$2"; shift 2 ;;
        --add-client)  ADD_CLIENT=true; shift ;;
        --help|-h)     show_help ;;
        *)             err "Unknown option: $1"; show_help ;;
    esac
done

# ─── Validation ───────────────────────────────────────────────────────
if [[ -z "$DOMAIN" ]]; then
    err "Domain is required. Use: --domain yourdomain.com"
    exit 1
fi

if [[ -z "$NAME" ]]; then
    NAME="trojan-${DOMAIN}"
fi

# ─── Root Check ───────────────────────────────────────────────────────
if [[ $EUID -ne 0 ]]; then
    err "This script must be run as root (use sudo)"
    exit 1
fi

echo -e "${BOLD}"
echo "╔══════════════════════════════════════════╗"
echo "║     MirageVPN - Trojan Setup             ║"
echo "╚══════════════════════════════════════════╝"
echo -e "${NC}"

# ─── Add Client Mode ─────────────────────────────────────────────────
if [[ "$ADD_CLIENT" == true ]]; then
    if [[ ! -f "$CONFIG_FILE" ]]; then
        err "No existing config found. Run without --add-client first."
        exit 1
    fi

    if ! command -v jq &>/dev/null; then
        apt-get install -y jq >/dev/null 2>&1
    fi

    NEW_PASSWORD=$(openssl rand -base64 24)
    CLIENT_NUM=$(jq '.inbounds[0].settings.clients | length' "$CONFIG_FILE")
    CLIENT_NUM=$((CLIENT_NUM + 1))
    CLIENT_LABEL="client${CLIENT_NUM}"

    jq --arg pw "$NEW_PASSWORD" --arg email "$CLIENT_LABEL" \
        '.inbounds[0].settings.clients += [{"password": $pw, "email": $email}]' \
        "$CONFIG_FILE" > "${CONFIG_FILE}.tmp" && mv "${CONFIG_FILE}.tmp" "$CONFIG_FILE"

    systemctl restart xray
    ok "Added ${CLIENT_LABEL}"

    URI="trojan://${NEW_PASSWORD}@${DOMAIN}:${PORT}?security=tls&sni=${DOMAIN}&type=tcp#${CLIENT_LABEL}"

    echo ""
    echo -e "${GREEN}${BOLD}New client URI:${NC}"
    echo -e "${YELLOW}${URI}${NC}"
    echo ""
    echo "$URI" >> "/root/trojan-uri.txt"
    exit 0
fi

# ─── Detect Server IP ────────────────────────────────────────────────
info "Detecting server IP..."
SERVER_IP=$(curl -s --max-time 10 https://api.ipify.org || curl -s --max-time 10 https://ifconfig.me || echo "")
if [[ -z "$SERVER_IP" ]]; then
    err "Could not detect server IP."
    exit 1
fi
ok "Server IP: $SERVER_IP"

# ─── Check if Already Installed ──────────────────────────────────────
if [[ -f "$CONFIG_FILE" ]]; then
    warn "Xray config already exists at ${CONFIG_FILE}"
    warn "Use --add-client to add a new client, or remove the config first."
    exit 1
fi

# ─── Install Dependencies ────────────────────────────────────────────
info "Installing dependencies..."
apt-get update -qq
apt-get install -y -qq curl wget jq certbot >/dev/null 2>&1
ok "Dependencies installed"

# ─── Obtain TLS Certificate ──────────────────────────────────────────
info "Obtaining TLS certificate for ${DOMAIN}..."
CERT_DIR="/etc/letsencrypt/live/${DOMAIN}"

if [[ -d "$CERT_DIR" ]]; then
    ok "Certificate already exists"
else
    # Stop anything on port 80
    systemctl stop nginx 2>/dev/null || true

    CERTBOT_ARGS=(certonly --standalone -d "$DOMAIN" --agree-tos --non-interactive)
    if [[ -n "$EMAIL" ]]; then
        CERTBOT_ARGS+=(--email "$EMAIL")
    else
        CERTBOT_ARGS+=(--register-unsafely-without-email)
    fi

    if ! certbot "${CERTBOT_ARGS[@]}"; then
        err "Failed to obtain certificate. Make sure:"
        err "  1. DNS A record for ${DOMAIN} points to ${SERVER_IP}"
        err "  2. Port 80 is not blocked by firewall"
        exit 1
    fi
    ok "TLS certificate obtained"
fi

# ─── Install Xray ────────────────────────────────────────────────────
info "Installing Xray-core..."
bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install >/dev/null 2>&1
ok "Xray-core installed"

# ─── Download Iran Geo Data ──────────────────────────────────────────
info "Downloading Iran routing rules..."
XRAY_DATA="/usr/local/share/xray"
mkdir -p "$XRAY_DATA"
wget -qO "${XRAY_DATA}/geoip.dat" \
    "https://cdn.jsdelivr.net/gh/chocolate4u/Iran-v2ray-rules@release/geoip.dat" || true
wget -qO "${XRAY_DATA}/geosite.dat" \
    "https://cdn.jsdelivr.net/gh/chocolate4u/Iran-v2ray-rules@release/geosite.dat" || true
ok "Iran geo data downloaded"

# ─── Grant Capabilities ──────────────────────────────────────────────
setcap 'cap_net_bind_service=+ep' /usr/local/bin/xray 2>/dev/null || true

# ─── Generate Password ───────────────────────────────────────────────
if [[ -z "$PASSWORD" ]]; then
    PASSWORD=$(openssl rand -base64 24)
    info "Generated random password"
fi

# ─── Write Xray Config ───────────────────────────────────────────────
info "Writing Xray config..."

cat > "$CONFIG_FILE" <<XRAYEOF
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "port": ${PORT},
    "listen": "0.0.0.0",
    "protocol": "trojan",
    "settings": {
      "clients": [{
        "password": "${PASSWORD}",
        "email": "client1"
      }]
    },
    "streamSettings": {
      "network": "tcp",
      "security": "tls",
      "tlsSettings": {
        "certificates": [{
          "certificateFile": "${CERT_DIR}/fullchain.pem",
          "keyFile": "${CERT_DIR}/privkey.pem"
        }]
      }
    },
    "sniffing": {
      "enabled": true,
      "destOverride": ["http", "tls"]
    }
  }],
  "routing": {
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      {"type": "field", "outboundTag": "block", "domain": ["geosite:category-ads-all", "geosite:malware", "geosite:phishing", "geosite:cryptominers"]},
      {"type": "field", "outboundTag": "block", "ip": ["geoip:malware", "geoip:phishing"]},
      {"type": "field", "outboundTag": "direct", "domain": ["geosite:ir"]},
      {"type": "field", "outboundTag": "direct", "ip": ["geoip:ir", "geoip:private"]}
    ]
  },
  "outbounds": [
    {"tag": "direct", "protocol": "freedom", "settings": {}},
    {"tag": "block", "protocol": "blackhole", "settings": {}}
  ]
}
XRAYEOF

ok "Xray config written"

# ─── Cert Renewal Hook ───────────────────────────────────────────────
info "Setting up certificate auto-renewal..."
mkdir -p /etc/letsencrypt/renewal-hooks/post
cat > /etc/letsencrypt/renewal-hooks/post/restart-xray.sh <<'HOOKEOF'
#!/bin/bash
systemctl restart xray
HOOKEOF
chmod +x /etc/letsencrypt/renewal-hooks/post/restart-xray.sh
ok "Cert renewal will auto-restart Xray"

# ─── Start Services ──────────────────────────────────────────────────
info "Starting Xray..."
systemctl enable --now xray
ok "Xray running"

# ─── Network Optimizations ───────────────────────────────────────────
info "Applying network optimizations..."

apply_sysctl() {
    local key="$1" value="$2"
    sysctl -w "${key}=${value}" &>/dev/null || true
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
URI="trojan://${PASSWORD}@${DOMAIN}:${PORT}?security=tls&sni=${DOMAIN}&type=tcp#${NAME}"

# ─── Save URI ─────────────────────────────────────────────────────────
URI_FILE="/root/trojan-uri.txt"
echo "$URI" > "$URI_FILE"
chmod 600 "$URI_FILE"

# ─── Output ───────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}╔══════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║          Setup Complete!                  ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════╝${NC}"
echo ""
echo -e "${CYAN}Protocol:${NC}   Trojan"
echo -e "${CYAN}Domain:${NC}     ${DOMAIN}"
echo -e "${CYAN}Server IP:${NC}  ${SERVER_IP}"
echo -e "${CYAN}Port:${NC}       ${PORT}"
echo -e "${CYAN}Password:${NC}   ${PASSWORD}"
echo ""
echo -e "${GREEN}${BOLD}URI:${NC}"
echo -e "${YELLOW}${URI}${NC}"
echo ""
echo -e "URI saved to: ${URI_FILE}"
echo ""

if command -v qrencode &>/dev/null; then
    echo -e "${CYAN}QR Code:${NC}"
    qrencode -t ANSIUTF8 "$URI"
    echo ""
fi

echo -e "${CYAN}Add this URI to:${NC}"
echo "  android/app/src/main/assets/protocols/trojan/configs.txt"
echo ""
echo -e "${CYAN}To add more clients:${NC}"
echo "  sudo bash $0 --domain ${DOMAIN} --add-client"
echo ""
echo -e "${CYAN}Certificate auto-renews via certbot timer.${NC}"
echo -e "${CYAN}Next renewal check:${NC} $(systemctl list-timers certbot.timer --no-pager 2>/dev/null | tail -1 || echo 'check with: certbot renew --dry-run')"
