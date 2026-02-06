#!/usr/bin/env bash
#
# MirageVPN - VLESS/VMess WebSocket+TLS Setup (behind Cloudflare)
# Installs Xray-core + nginx reverse proxy for WebSocket transport.
#
# Prerequisites (must be done BEFORE running this script):
#   1. Buy a domain
#   2. Add domain to Cloudflare
#   3. Create A record pointing to this server's IP (orange cloud ON)
#   4. Cloudflare SSL mode: Full
#
# Usage:
#   sudo bash setup-cloudflare-ws.sh --domain yourdomain.com [OPTIONS]
#
# Options:
#   --domain DOMAIN     Your domain (required)
#   --protocol PROTO    vless or vmess (default: vless)
#   --path PATH         WebSocket path (default: auto-generated)
#   --port PORT         Internal Xray port (default: 8443)
#   --name NAME         Config name for URI (default: domain name)
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
PROTOCOL="vless"
WS_PATH=""
INTERNAL_PORT=8443
NAME=""
ADD_CLIENT=false
CONFIG_FILE="/usr/local/etc/xray/config.json"
CLIENTS_FILE="/usr/local/etc/xray/clients.json"

# ─── Parse Arguments ──────────────────────────────────────────────────
show_help() {
    sed -n '2,/^$/{ s/^# //; s/^#//; p }' "$0"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --domain)      DOMAIN="$2"; shift 2 ;;
        --protocol)    PROTOCOL="$2"; shift 2 ;;
        --path)        WS_PATH="$2"; shift 2 ;;
        --port)        INTERNAL_PORT="$2"; shift 2 ;;
        --name)        NAME="$2"; shift 2 ;;
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

if [[ "$PROTOCOL" != "vless" && "$PROTOCOL" != "vmess" ]]; then
    err "Protocol must be 'vless' or 'vmess'"
    exit 1
fi

if [[ -z "$NAME" ]]; then
    NAME="${PROTOCOL}-ws-${DOMAIN}"
fi

# ─── Root Check ───────────────────────────────────────────────────────
if [[ $EUID -ne 0 ]]; then
    err "This script must be run as root (use sudo)"
    exit 1
fi

echo -e "${BOLD}"
echo "╔══════════════════════════════════════════╗"
echo "║   MirageVPN - ${PROTOCOL^^} WS+TLS Setup          ║"
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

    UUID=$(xray uuid)
    CLIENT_NUM=$(jq '.inbounds[0].settings.clients | length' "$CONFIG_FILE")
    CLIENT_NUM=$((CLIENT_NUM + 1))
    CLIENT_LABEL="client${CLIENT_NUM}"

    # Add to Xray config
    if [[ "$PROTOCOL" == "vless" ]]; then
        jq --arg id "$UUID" --arg email "$CLIENT_LABEL" \
            '.inbounds[0].settings.clients += [{"id": $id, "email": $email, "level": 0}]' \
            "$CONFIG_FILE" > "${CONFIG_FILE}.tmp" && mv "${CONFIG_FILE}.tmp" "$CONFIG_FILE"
    else
        jq --arg id "$UUID" --arg email "$CLIENT_LABEL" \
            '.inbounds[0].settings.clients += [{"id": $id, "email": $email, "alterId": 0}]' \
            "$CONFIG_FILE" > "${CONFIG_FILE}.tmp" && mv "${CONFIG_FILE}.tmp" "$CONFIG_FILE"
    fi

    systemctl restart xray
    ok "Added ${CLIENT_LABEL} (UUID: ${UUID})"

    # Read WS path from config
    WS_PATH=$(jq -r '.inbounds[0].streamSettings.wsSettings.path' "$CONFIG_FILE")
    ENCODED_PATH=$(echo "$WS_PATH" | sed 's|/|%2F|g')

    # Generate URI
    if [[ "$PROTOCOL" == "vless" ]]; then
        URI="vless://${UUID}@${DOMAIN}:443?encryption=none&security=tls&type=ws&host=${DOMAIN}&sni=${DOMAIN}&path=${ENCODED_PATH}#${CLIENT_LABEL}"
    else
        VMESS_JSON=$(cat <<VMJSON
{"v":"2","ps":"${CLIENT_LABEL}","add":"${DOMAIN}","port":"443","id":"${UUID}","aid":"0","scy":"auto","net":"ws","type":"none","host":"${DOMAIN}","path":"${WS_PATH}","tls":"tls","sni":"${DOMAIN}"}
VMJSON
)
        VMESS_B64=$(echo -n "$VMESS_JSON" | base64 -w 0)
        URI="vmess://${VMESS_B64}"
    fi

    echo ""
    echo -e "${GREEN}${BOLD}New client URI:${NC}"
    echo -e "${YELLOW}${URI}${NC}"
    echo ""
    echo "$URI" >> "/root/${PROTOCOL}-ws-uri.txt"
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
apt-get install -y -qq curl wget jq nginx >/dev/null 2>&1
ok "Dependencies installed"

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

# ─── Generate Credentials ────────────────────────────────────────────
UUID=$(xray uuid)
if [[ -z "$WS_PATH" ]]; then
    WS_PATH="/$(openssl rand -hex 6)"
fi
info "UUID: ${UUID}"
info "WebSocket path: ${WS_PATH}"

# ─── Write Xray Config ───────────────────────────────────────────────
info "Writing Xray config..."

if [[ "$PROTOCOL" == "vless" ]]; then
    CLIENT_BLOCK='"clients": [{"id": "'"$UUID"'", "email": "client1", "level": 0}], "decryption": "none"'
else
    CLIENT_BLOCK='"clients": [{"id": "'"$UUID"'", "email": "client1", "alterId": 0}]'
fi

cat > "$CONFIG_FILE" <<XRAYEOF
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "port": ${INTERNAL_PORT},
    "listen": "127.0.0.1",
    "protocol": "${PROTOCOL}",
    "settings": {
      ${CLIENT_BLOCK}
    },
    "streamSettings": {
      "network": "ws",
      "wsSettings": {
        "path": "${WS_PATH}"
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

# ─── Configure nginx ─────────────────────────────────────────────────
info "Configuring nginx..."

# Remove default site
rm -f /etc/nginx/sites-enabled/default 2>/dev/null || true

cat > /etc/nginx/sites-available/xray <<NGINXEOF
server {
    listen 80;
    server_name ${DOMAIN};

    location ${WS_PATH} {
        proxy_redirect off;
        proxy_pass http://127.0.0.1:${INTERNAL_PORT};
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    }

    location / {
        return 444;
    }
}
NGINXEOF

ln -sf /etc/nginx/sites-available/xray /etc/nginx/sites-enabled/xray
nginx -t 2>/dev/null
ok "nginx configured"

# ─── Start Services ──────────────────────────────────────────────────
info "Starting services..."
systemctl enable --now xray
systemctl restart nginx
ok "Xray and nginx running"

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
ENCODED_PATH=$(echo "$WS_PATH" | sed 's|/|%2F|g')

if [[ "$PROTOCOL" == "vless" ]]; then
    URI="vless://${UUID}@${DOMAIN}:443?encryption=none&security=tls&type=ws&host=${DOMAIN}&sni=${DOMAIN}&path=${ENCODED_PATH}#${NAME}"
else
    VMESS_JSON=$(cat <<VMJSON
{"v":"2","ps":"${NAME}","add":"${DOMAIN}","port":"443","id":"${UUID}","aid":"0","scy":"auto","net":"ws","type":"none","host":"${DOMAIN}","path":"${WS_PATH}","tls":"tls","sni":"${DOMAIN}"}
VMJSON
)
    VMESS_B64=$(echo -n "$VMESS_JSON" | base64 -w 0)
    URI="vmess://${VMESS_B64}"
fi

# ─── Save URI ─────────────────────────────────────────────────────────
URI_FILE="/root/${PROTOCOL}-ws-uri.txt"
echo "$URI" > "$URI_FILE"
chmod 600 "$URI_FILE"

# ─── Output ───────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}╔══════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║          Setup Complete!                  ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════╝${NC}"
echo ""
echo -e "${CYAN}Protocol:${NC}   ${PROTOCOL^^}"
echo -e "${CYAN}Domain:${NC}     ${DOMAIN}"
echo -e "${CYAN}Server IP:${NC}  ${SERVER_IP}"
echo -e "${CYAN}UUID:${NC}       ${UUID}"
echo -e "${CYAN}WS Path:${NC}    ${WS_PATH}"
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
if [[ "$PROTOCOL" == "vless" ]]; then
    echo "  android/app/src/main/assets/protocols/vless/configs.txt"
else
    echo "  android/app/src/main/assets/protocols/vmess/configs.txt"
fi
echo ""
echo -e "${CYAN}To add more clients:${NC}"
echo "  sudo bash $0 --domain ${DOMAIN} --protocol ${PROTOCOL} --add-client"
echo ""
echo -e "${YELLOW}Important:${NC} Make sure your Cloudflare settings are correct:"
echo "  - DNS A record: ${DOMAIN} -> ${SERVER_IP} (orange cloud ON)"
echo "  - SSL/TLS mode: Full"
