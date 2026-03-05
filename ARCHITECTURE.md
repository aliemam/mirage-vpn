# MirageVPN Architecture Documentation

## Overview

MirageVPN is a multi-protocol VPN application that routes all device traffic through obfuscated channels, making it extremely difficult to detect and block. It supports three tunneling modes that race concurrently:

1. **Xray Mode** (Primary): Uses Xray-core supporting VLESS (WebSocket+TLS, REALITY), VMess, Trojan, and Shadowsocks protocols
2. **dnstt Mode** (DNS Tunnel): Encodes TCP traffic into DNS queries via dnstt-client, with microsocks SOCKS5 on the server
3. **slipstream Mode** (DNS Tunnel): Routes traffic through DNS queries using slipstream (legacy)

When multiple modes have configs available, the app races them concurrently and uses whichever connects first. Priority: Xray > dnstt > slipstream.

## System Architecture

### Xray Mode

Xray handles multiple protocols through a single SOCKS5 port. Two main transport options:

**WebSocket+TLS (via Cloudflare CDN):**

```
+-----------------------------------------------------------------+
|                         USER'S PHONE                             |
|  +-------------+    +--------------+    +---------------------+  |
|  |   Any App   |--->| VPN Interface|--->|  tun2socks (JNI)    |  |
|  | (Telegram)  |    |  (tun0)      |    |  Packets -> SOCKS5  |  |
|  +-------------+    +--------------+    +----------+----------+  |
|                                                    |             |
|                                         +----------v----------+  |
|                                         |   Xray-core         |  |
|                                         |   SOCKS5 -> VLESS   |  |
|                                         |   127.0.0.1:10808   |  |
|                                         +----------+----------+  |
+-----------------------------------------------------------------+
                                                     |
                                          TLS/HTTPS to Cloudflare
                                          (looks like normal HTTPS)
                                                     |
                                                     v
+-----------------------------------------------------------------+
|                     CLOUDFLARE CDN                                |
|   Receives HTTPS, routes via Host header to your origin server   |
|   SSL mode: Flexible (CF terminates TLS, connects to origin      |
|   on HTTP port 80)                                                |
+-----------------------------------------------------------------+
                                                     |
                                                     v
+-----------------------------------------------------------------+
|                     YOUR VPS (Origin Server)                     |
|  +-----------------------------------------------------------+  |
|  |                    Xray Server (port 80)                   |  |
|  |  - Receives VLESS WebSocket connections from Cloudflare    |  |
|  |  - Decrypts and forwards traffic to real destinations      |  |
|  +-----------------------------------------------------------+  |
+-----------------------------------------------------------------+
```

**REALITY (direct connection):**

```
+-----------------------------------------------------------------+
|                         USER'S PHONE                             |
|  +-------------+    +--------------+    +---------------------+  |
|  |   Any App   |--->| VPN Interface|--->|  tun2socks (JNI)    |  |
|  +-------------+    +--------------+    +----------+----------+  |
|                                                    |             |
|                                         +----------v----------+  |
|                                         |   Xray-core         |  |
|                                         |   127.0.0.1:10808   |  |
|                                         +----------+----------+  |
+-----------------------------------------------------------------+
                                                     |
                                          TLS to server IP:443
                                          SNI = irna.ir / google.com
                                          (looks like visiting news site)
                                                     |
                                                     v
+-----------------------------------------------------------------+
|                     YOUR VPS (port 443)                          |
|  +-----------------------------------------------------------+  |
|  |                    Xray Server (REALITY)                   |  |
|  |  - Validates client via public key + short ID              |  |
|  |  - TLS fingerprint mimics real browsers                    |  |
|  |  - No real TLS certificate needed                          |  |
|  +-----------------------------------------------------------+  |
+-----------------------------------------------------------------+
```

### dnstt Mode (DNS Tunnel)

```
+-----------------------------------------------------------------+
|                         USER'S PHONE                             |
|  +-------------+    +--------------+    +---------------------+  |
|  |   Any App   |--->| VPN Interface|--->|  tun2socks (JNI)    |  |
|  | (Telegram)  |    |  (tun0)      |    |  Packets -> SOCKS5  |  |
|  +-------------+    +--------------+    +----------+----------+  |
|                                                    |             |
|                                         +----------v----------+  |
|                                         |   dnstt-client       |  |
|                                         |   SOCKS5 -> DNS      |  |
|                                         |   127.0.0.1:5202     |  |
|                                         +----------+----------+  |
+-----------------------------------------------------------------+
                                                     |
                                          DNS Queries (UDP/53 or DoH)
                                          to public resolvers
                                          (1.1.1.1, 8.8.8.8, etc.)
                                                     |
                                                     v
+-----------------------------------------------------------------+
|                     DNS RESOLVER (1.1.1.1)                       |
|   Queries look like: <base32>.d1.yourdomain.com                 |
|   Resolver follows NS record to your server                     |
+-----------------------------------------------------------------+
                                                     |
                                                     v
+-----------------------------------------------------------------+
|                     YOUR VPS (port 53)                           |
|  +-----------------------------------------------------------+  |
|  | iptables: port 53 -> 5300                                  |  |
|  |                                                             |  |
|  |  dnstt-server (port 5300/udp)                              |  |
|  |  - Authoritative NS for d1.yourdomain.com                 |  |
|  |  - Decodes data from DNS queries                           |  |
|  |  - Forwards to microsocks (127.0.0.1:1080)                |  |
|  |                                                             |  |
|  |  microsocks (127.0.0.1:1080)                               |  |
|  |  - Lightweight SOCKS5 proxy                                |  |
|  |  - Forwards traffic to real internet                       |  |
|  +-----------------------------------------------------------+  |
+-----------------------------------------------------------------+
```

### slipstream Mode (DNS Tunnel - Legacy)

```
+-----------------------------------------------------------------+
|                         USER'S PHONE                             |
|  +-------------+    +--------------+    +---------------------+  |
|  |   Any App   |--->| VPN Interface|--->|  tun2socks (JNI)    |  |
|  | (Telegram)  |    |  (tun0)      |    |  Packets -> SOCKS5  |  |
|  +-------------+    +--------------+    +----------+----------+  |
|                                                    |             |
|                                         +----------v----------+  |
|                                         | slipstream-client   |  |
|                                         | SOCKS5 -> DNS       |  |
|                                         | 127.0.0.1:5201      |  |
|                                         +----------+----------+  |
+-----------------------------------------------------------------+
                                                     |
                                          DNS Queries (UDP/53)
                                          to public resolvers
                                                     |
                                                     v
+-----------------------------------------------------------------+
|                     DNS RESOLVER (1.1.1.1)                       |
|   Queries look like: <encoded>.s.yourdomain.com                 |
|   Resolver forwards to authoritative NS (your VPS)              |
+-----------------------------------------------------------------+
                                                     |
                                                     v
+-----------------------------------------------------------------+
|                     YOUR VPS                                     |
|  +-----------------------------------------------------------+  |
|  |                 slipstream-server                          |  |
|  |  - Authoritative DNS for yourdomain.com                   |  |
|  |  - Decodes data from DNS queries                          |  |
|  |  - QUIC streams for multiplexing                          |  |
|  |  - Forwards traffic to real internet                      |  |
|  +-----------------------------------------------------------+  |
+-----------------------------------------------------------------+
```

## Connection Racing

When multiple modes have configs available, the app races them concurrently:

```
connect()
  |
  +-- hasXray? -----> async { connectXray() }    --> SOCKS5 on :10808
  |
  +-- hasDnstt? ----> async { connectDnstt() }   --> SOCKS5 on :5202
  |
  +-- hasDns? ------> async { connectDns() }     --> SOCKS5 on :5201
  |
  Wait for first success (select)
  |
  Winner establishes VPN + tun2socks on winner's port
  Losers are cleaned up
```

Priority when multiple succeed: XRAY > DNSTT > DNS

If only one mode is available, it's called directly (no racing overhead).

## Component Details

### 1. Android VPN Service (`MirageVpnService.kt`)

Creates a virtual network interface (tun0) that captures all device traffic.

```kotlin
Builder()
    .addAddress("10.0.0.2", 32)      // VPN gets this IP
    .addRoute("0.0.0.0", 0)          // Route ALL traffic through VPN
    .addDnsServer("8.8.8.8")         // DNS for apps
    .setMtu(1500)
    .addDisallowedApplication(packageName)  // Prevent infinite loops
```

### 2. tun2socks (`hev-socks5-tunnel` - JNI)

Converts raw IP packets from the VPN interface into SOCKS5 protocol.

- Runs as native code via JNI for performance
- Receives the VPN file descriptor directly
- Forwards all TCP/UDP to the local SOCKS5 proxy (port depends on winning mode)

### 3. Xray-core (Xray Mode)

Provides multi-protocol proxy with several transport options:

**WebSocket + TLS (via Cloudflare CDN):**
- Connects to Cloudflare edge IPs on port 443
- All traffic encrypted in TLS (Cloudflare terminates, connects to origin on port 80)
- Uses SNI and Host headers for routing to origin server
- Indistinguishable from visiting any Cloudflare-hosted website

**REALITY Protocol:**
- Direct connection to server on port 443
- TLS fingerprint obfuscation (chrome, firefox, safari)
- Pretends to be visiting legitimate sites via SNI spoofing (irna.ir, isna.ir, mehrnews.com, google.com)
- No certificate required on server

**Also supports:** VMess, Trojan, Shadowsocks protocols with flexible transport.

### 4. dnstt-client (dnstt Mode)

Encodes TCP connections into DNS queries. The client runs as a native binary (`libdnstt.so`).

**How it works:**
1. dnstt-client provides a local SOCKS5 proxy on `127.0.0.1:5202`
2. Encodes outgoing data into DNS subdomain labels
3. Sends queries to public resolvers: `<base32>.d1.yourdomain.com`
4. Resolver follows NS record to your server's dnstt-server
5. dnstt-server decodes and forwards to microsocks (SOCKS5 proxy to internet)

**Transport options:**
- UDP (`-udp 1.1.1.1:53`) — standard DNS over UDP
- DoH (`-doh https://cloudflare-dns.com/dns-query`) — DNS over HTTPS

### 5. slipstream (slipstream Mode)

Provides a SOCKS5 proxy interface that encodes all traffic into DNS queries.

**How data is encoded:**
1. Takes incoming SOCKS5 connection data
2. Encodes it into DNS subdomain labels
3. Sends as DNS query: `<encoded-chunk>.s.yourdomain.com`
4. Receives response data in DNS TXT/NULL records

## Config Management Pipeline

```
+-------------------+     +------------------+     +------------------+
| 1. Local Cache    |---->| 2. Bundled Asset |---->| 3. Remote Fetch  |
| (internal storage)|     | (protocol dirs)  |     | (remote URL)     |
+-------------------+     +------------------+     +------------------+
        |                         |                         |
        +-----------+-------------+-----------+-------------+
                    |                         |
            +-------v-------+         +-------v-------+
            | Config Scoring|         | Background    |
            | (learn best)  |         | Optimizer     |
            +-------+-------+         | (find faster) |
                    |                 +-------+-------+
                    +--------+--------+
                             |
                     +-------v-------+
                     | Quick Probe   |
                     | (top scored)  |
                     +-------+-------+
                             |
                     +-------v-------+
                     | Race Connect  |
                     +---------------+
```

### Config Loading Order
1. **Local file** (internal storage) - cached from last remote fetch
2. **Bundled asset** (per-protocol `configs.txt`) - bootstrap for first install
3. **Remote fetch** - on-demand refresh from configured URL

### Per-Protocol Config Sources

| Protocol | Config File | URI Scheme | Remote Path |
|----------|------------|------------|-------------|
| VLESS | `protocols/vless/configs.txt` | `vless://` | `/protocols/vless/configs.txt` |
| VMess | `protocols/vmess/configs.txt` | `vmess://` | `/protocols/vmess/configs.txt` |
| Trojan | `protocols/trojan/configs.txt` | `trojan://` | `/protocols/trojan/configs.txt` |
| Shadowsocks | `protocols/shadowsocks/configs.txt` | `ss://` | `/protocols/shadowsocks/configs.txt` |
| dnstt | `protocols/dnstt/configs.txt` | `dns://` | `/protocols/dnstt/configs.txt` |
| slipstream | `protocols/dns/config.json` | JSON | `/protocols/dns/config.json` |

### Dynamic Config Scoring
- Success: +10 points + uptime bonus (up to +20 per connection)
- Failure: -1 point (with 3-hour cooldown to prevent over-penalizing)
- New configs start at score 5
- Scores persist across app restarts via SharedPreferences

### Background Optimizer
- Starts 30s after connection
- Every 5 minutes: tests 15 alternatives in batches of 3
- Only switches if new config is 500ms+ faster
- Every 30 minutes: refreshes remote config list

## Hot-Swap Technology

Zero-downtime config switching (Xray mode only):

```
Step 1: Start secondary Xray on alternate port
Step 2: Wait for SOCKS server ready
Step 3: Verify secondary responds to test connection
Step 4: Atomically restart tun2socks pointing to new port (~100ms)
Step 5: Promote secondary to primary, stop old instance
```

## Anti-Detection Mechanisms

### 1. Decoy DNS Queries
Mixes real tunnel queries with fake queries to popular local websites:
- Random 2-10 second intervals
- Queries bypass VPN (sent directly) to look realistic
- Makes tunnel traffic statistically indistinguishable from normal usage

### 2. DNS over HTTPS (DoH) - Optional
When enabled, DNS queries are wrapped in HTTPS:
```
Standard:  slipstream -> UDP:53 -> resolver -> VPS
With DoH:  slipstream -> 127.0.0.1:5353 -> HTTPS:443 -> cloudflare-dns.com -> VPS
```

### 3. Multi-Domain Failover
Tries backup domains automatically if primary is blocked. dnstt configs span multiple domains.

### 4. Multi-Resolver Support
Uses 10+ international DNS resolvers to avoid pattern detection. dnstt configs include multiple resolver/transport combinations per server.

### 5. Iranian State Media SNIs (REALITY)
REALITY configs use SNIs of Iranian state-affiliated media (irna.ir, isna.ir, mehrnews.com) that the regime would never block, making the TLS handshake look like visiting a government news site.

## Health Monitoring

- Checks SOCKS port and tunnel process every 15 seconds
- Reconnection with exponential backoff: 2s, 5s, 10s, 20s, 30s
- Network-aware: detects WiFi/mobile switches, resets backoff
- Up to 5 reconnection attempts before giving up
- Mode-specific health checks (Xray process, dnstt process, slipstream process)

## File Structure

```
android/app/src/main/
├── java/net/mirage/vpn/
│   ├── MainActivity.kt           # UI and user interaction
│   ├── MirageVpnService.kt       # Main VPN service (orchestrates everything)
│   ├── XrayManager.kt            # Xray-core lifecycle, probing, hot-swap
│   ├── ProxyConfig.kt            # Proxy config data classes (VLESS, VMess, Trojan, Shadowsocks)
│   ├── DnsttConfig.kt            # dnstt tunnel configuration data class
│   ├── ConfigRepository.kt       # Central config management (all protocols + dnstt)
│   ├── ConfigScoreManager.kt     # Learning-based config scoring
│   ├── RemoteConfigFetcher.kt    # Remote config fetching and URI parsing
│   ├── BackgroundOptimizer.kt    # Auto-optimization while connected
│   ├── DohProxy.kt               # DNS over HTTPS proxy
│   ├── DnsConfig.kt              # slipstream DNS tunnel configuration
│   ├── ServerConfig.kt           # App-level configuration and constants
│   └── TunnelNative.kt           # JNI wrapper for tun2socks
├── jni/
│   └── hev-socks5-tunnel/        # Native tun2socks (C)
├── jniLibs/arm64-v8a/
│   ├── libslipstream.so          # Pre-compiled slipstream client
│   └── libdnstt.so               # Pre-compiled dnstt-client
├── assets/
│   ├── config.json               # App configuration (server_name, remote_config_url)
│   └── protocols/
│       ├── vless/configs.txt     # VLESS URIs (WS+TLS and REALITY)
│       ├── vmess/configs.txt     # VMess URIs
│       ├── trojan/configs.txt    # Trojan URIs
│       ├── shadowsocks/configs.txt # Shadowsocks URIs
│       ├── dnstt/configs.txt     # dnstt tunnel configs (dns:// URIs)
│       └── dns/config.json       # slipstream DNS tunnel config
└── res/
    └── values*/strings.xml       # UI strings (EN + Persian)
```

### Server-Side Files

```
server/
├── dnstt/
│   ├── Dockerfile              # Builds dnstt-server + microsocks
│   ├── entrypoint.sh           # Starts microsocks + dnstt-server
│   └── docker-compose.yml      # Docker deployment
└── slipstream/
    ├── Dockerfile              # Builds slipstream-server
    ├── entrypoint.sh           # Starts slipstream-server
    └── docker-compose.yml      # Docker deployment
```

## Technology Stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Language | Kotlin | Android app development |
| Min SDK | API 24 (Android 7.0) | Broad device compatibility |
| Target SDK | API 34 (Android 14) | Latest optimizations |
| VPN Layer | Android VpnService API | Create tun interface, route traffic |
| Packet Conversion | hev-socks5-tunnel (C/JNI) | Convert IP packets to SOCKS5 |
| Xray Proxy | Xray-core (libv2ray) | VLESS, VMess, Trojan, Shadowsocks |
| DNS Tunnel (primary) | dnstt | TCP-over-DNS tunneling |
| DNS Tunnel (legacy) | slipstream | DNS-based SOCKS5 tunneling |
| SOCKS5 (server) | microsocks | Lightweight SOCKS5 for dnstt |
| HTTP Client | OkHttp3 | Remote config fetching, DoH |
| JSON | org.json + Gson | Config parsing |
| Async | Kotlin Coroutines | Concurrent racing, health monitor |
| Persistence | SharedPreferences | Cache configs, scores, state |
| Build System | Gradle 8.7+, AGP 8.5+ | Android builds |

## Build Requirements

- JDK 17+
- Android SDK (API 34)
- Android NDK (for native libraries)
- Gradle 8.7+
- Architecture: arm64-v8a
