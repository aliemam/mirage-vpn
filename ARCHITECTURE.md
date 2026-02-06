# MirageVPN Architecture Documentation

## Overview

MirageVPN is a multi-protocol VPN application that routes all device traffic through obfuscated channels, making it extremely difficult to detect and block. It supports two primary tunneling modes:

1. **Xray Mode** (Primary): Uses Xray-core supporting VLESS (WebSocket+TLS, REALITY), VMess, Trojan, and Shadowsocks protocols
2. **DNS Tunneling Mode**: Routes traffic through DNS queries using slipstream

## System Architecture

### VLESS Mode (Primary)

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
+-----------------------------------------------------------------+
                                                     |
                                                     v
+-----------------------------------------------------------------+
|                     YOUR VPS (Origin Server)                     |
|  +-----------------------------------------------------------+  |
|  |                    Xray Server                             |  |
|  |  - Receives VLESS connections via WebSocket or REALITY     |  |
|  |  - Decrypts and forwards traffic to real destinations      |  |
|  +-----------------------------------------------------------+  |
+-----------------------------------------------------------------+
```

### DNS Tunneling Mode

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
- Forwards all TCP/UDP to the local SOCKS5 proxy

### 3. Xray-core (VLESS Mode)

Provides VLESS proxy protocol with two transport options:

**WebSocket + TLS (via Cloudflare CDN):**
- Connects to Cloudflare edge IPs
- All traffic encrypted in TLS
- Uses SNI and Host headers for routing to origin server
- Indistinguishable from visiting any Cloudflare-hosted website

**REALITY Protocol:**
- Direct connection to server
- TLS fingerprint obfuscation (chrome, firefox, safari)
- Pretends to be visiting legitimate sites via SNI spoofing
- No certificate required on server

### 4. slipstream (DNS Tunneling Mode)

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
| (internal storage)|     | (configs.txt)    |     | (remote URL)     |
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
                     | Connect       |
                     +---------------+
```

### Config Loading Order
1. **Local file** (internal storage) - cached from last remote fetch
2. **Bundled asset** (configs.txt) - bootstrap for first install
3. **Remote fetch** - on-demand refresh from configured URL

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

Zero-downtime config switching:

```
Step 1: Start secondary Xray on alternate port (e.g., 5202)
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
Tries backup domains automatically if primary is blocked.

### 4. Multi-Resolver Support
Uses 10+ international DNS resolvers to avoid pattern detection.

## Health Monitoring

- Checks SOCKS port and tunnel process every 15 seconds
- Reconnection with exponential backoff: 2s, 5s, 10s, 20s, 30s
- Network-aware: detects WiFi/mobile switches, resets backoff
- Up to 5 reconnection attempts before giving up

## File Structure

```
android/app/src/main/
├── java/net/mirage/vpn/
│   ├── MainActivity.kt           # UI and user interaction
│   ├── MirageVpnService.kt       # Main VPN service (orchestrates everything)
│   ├── XrayManager.kt            # Xray-core lifecycle, probing, hot-swap
│   ├── ProxyConfig.kt            # Proxy config data classes (VLESS, VMess, Trojan, Shadowsocks, custom)
│   ├── ConfigRepository.kt       # Central config management
│   ├── ConfigScoreManager.kt     # Learning-based config scoring
│   ├── RemoteConfigFetcher.kt    # Remote config fetching and VLESS URI parsing
│   ├── BackgroundOptimizer.kt    # Auto-optimization while connected
│   ├── DohProxy.kt               # DNS over HTTPS proxy
│   ├── ServerConfig.kt           # App-level configuration
│   └── TunnelNative.kt           # JNI wrapper for tun2socks
├── jni/
│   └── hev-socks5-tunnel/        # Native tun2socks (C)
├── jniLibs/arm64-v8a/
│   └── libslipstream.so          # Pre-compiled slipstream client
├── assets/
│   ├── config.json               # App configuration
│   └── configs.txt               # Bootstrap VLESS configs
└── res/
    └── values*/strings.xml       # UI strings (EN + Persian)
```

## Technology Stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Language | Kotlin | Android app development |
| Min SDK | API 24 (Android 7.0) | Broad device compatibility |
| Target SDK | API 34 (Android 14) | Latest optimizations |
| VPN Layer | Android VpnService API | Create tun interface, route traffic |
| Packet Conversion | hev-socks5-tunnel (C/JNI) | Convert IP packets to SOCKS5 |
| VLESS Proxy | Xray-core (libv2ray) | WebSocket+TLS and REALITY protocols |
| DNS Tunnel | slipstream | DNS-based tunneling |
| HTTP Client | OkHttp3 | Remote config fetching, DoH |
| JSON | org.json | Config parsing |
| Async | Kotlin Coroutines | Background tasks, health monitor |
| Persistence | SharedPreferences | Cache configs, scores, state |
| Build System | Gradle 8.7+, AGP 8.5+ | Android builds |

## Build Requirements

- JDK 17+
- Android SDK (API 34)
- Android NDK (for native libraries)
- Gradle 8.7+
- Architecture: arm64-v8a
