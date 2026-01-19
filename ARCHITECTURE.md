# MirageVPN Architecture Documentation

## Overview

MirageVPN is a DNS tunneling VPN application that routes all device traffic through DNS queries, making it extremely difficult to detect and block. It's designed to bypass internet censorship in restrictive environments like Iran.

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           USER'S PHONE                                   │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────────────────────────┐ │
│  │   Any App   │───▶│ VPN Interface│───▶│      tun2socks (JNI)        │ │
│  │ (Telegram)  │    │  (tun0)      │    │  Converts packets to SOCKS5 │ │
│  └─────────────┘    └──────────────┘    └──────────────┬──────────────┘ │
│                                                        │                 │
│                                         ┌──────────────▼──────────────┐ │
│                                         │   slipstream-client         │ │
│                                         │   SOCKS5 ──▶ DNS queries    │ │
│                                         │   Listening on 127.0.0.1:5201│ │
│                                         └──────────────┬──────────────┘ │
└────────────────────────────────────────────────────────┼─────────────────┘
                                                         │
                                          DNS Queries (UDP/53 or TCP/53)
                                          to public resolvers (1.1.1.1, 8.8.8.8)
                                                         │
                                                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        INTERNET / ISP                                    │
│                                                                          │
│   DNS queries look like normal DNS traffic to:                          │
│   - 1.1.1.1 (Cloudflare)                                                │
│   - 8.8.8.8 (Google)                                                    │
│   - 9.9.9.9 (Quad9)                                                     │
│                                                                          │
│   Query format: <encoded-data>.s.savethenameofthekillers.com            │
└─────────────────────────────────────────────────────────────────────────┘
                                                         │
                                                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     DNS RESOLVER (1.1.1.1)                               │
│                                                                          │
│   Resolver doesn't know the answer, so it queries the authoritative     │
│   nameserver for savethenameofthekillers.com                            │
└─────────────────────────────────────────────────────────────────────────┘
                                                         │
                                          Standard DNS resolution
                                                         │
                                                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     YOUR VPS (77.42.23.165)                              │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    slipstream-server                             │    │
│  │                                                                  │    │
│  │  - Listens on UDP/53 as authoritative DNS for the domain        │    │
│  │  - Decodes data from DNS queries                                 │    │
│  │  - Establishes QUIC streams for each connection                  │    │
│  │  - Forwards actual traffic to the real internet                  │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                              │                                           │
│                              ▼                                           │
│                     Real Internet Access                                 │
│                   (Telegram, Google, etc.)                              │
└─────────────────────────────────────────────────────────────────────────┘
```

## Component Details

### 1. Android VPN Service (`MirageVpnService.kt`)

The Android VPN Service creates a virtual network interface (tun0) that captures all device traffic.

```kotlin
// Key configuration
Builder()
    .addAddress("10.0.0.2", 32)      // VPN gets this IP
    .addRoute("0.0.0.0", 0)          // Route ALL traffic through VPN
    .addDnsServer("8.8.8.8")         // DNS for apps
    .setMtu(1500)
    .addDisallowedApplication(packageName)  // Don't route our own traffic!
```

**Critical**: The app excludes itself from VPN routing to prevent infinite loops.

### 2. tun2socks (`hev-socks5-tunnel` - JNI)

Converts raw IP packets from the VPN interface into SOCKS5 protocol.

- Runs as native code via JNI for performance
- Receives the VPN file descriptor directly
- Forwards all TCP/UDP to the local SOCKS5 proxy

Configuration (`tun2socks.yml`):
```yaml
tunnel:
  mtu: 8500
socks5:
  port: 5201
  address: '127.0.0.1'
  udp: 'udp'
```

### 3. slipstream-client (DNS Tunnel Client)

Provides a SOCKS5 proxy interface that encodes all traffic into DNS queries.

```
slipstream-client \
  --domain s.savethenameofthekillers.com \
  --tcp-listen-port 5201 \
  --resolver 1.1.1.1 \
  --resolver 8.8.8.8 \
  --resolver 9.9.9.9
```

**How data is encoded**:
1. Takes incoming SOCKS5 connection data
2. Encodes it (base32/base64) into DNS subdomain labels
3. Sends as DNS query: `<encoded-chunk>.s.savethenameofthekillers.com`
4. Receives response data in DNS TXT/NULL records

### 4. slipstream-server (On VPS)

Authoritative DNS server that:
1. Receives DNS queries for `*.s.savethenameofthekillers.com`
2. Decodes the data from subdomain labels
3. Uses QUIC protocol internally for multiplexing
4. Forwards decrypted traffic to real destinations
5. Encodes responses back into DNS responses

## Data Flow Example

**User opens Telegram**:

```
1. Telegram tries to connect to telegram.org:443

2. VPN interface captures the TCP SYN packet

3. tun2socks converts it to SOCKS5:
   CONNECT telegram.org:443

4. slipstream-client receives SOCKS5 request:
   - Encodes "CONNECT telegram.org:443" + TLS handshake
   - Creates DNS query:
     "gezdgnbvgy3tqojq.gi2dknrxha4tin.s.savethenameofthekillers.com"

5. Query goes to 1.1.1.1 (looks like normal DNS!)

6. 1.1.1.1 doesn't know the answer, queries authoritative NS

7. Your VPS receives the query, decodes it:
   - Extracts "CONNECT telegram.org:443"
   - Connects to real telegram.org:443
   - Gets response, encodes into DNS response

8. Response flows back through the same path

9. Telegram works!
```

## DNS Record Configuration

```
; Domain: savethenameofthekillers.com
; Nameserver configuration

s    IN    NS    ns1.savethenameofthekillers.com.
ns1  IN    A     77.42.23.165
```

The `s` subdomain is delegated to your VPS, which runs slipstream-server as the authoritative DNS.

## Anti-Detection Mechanisms

### 1. Decoy DNS Queries (Traffic Camouflage)

The app sends fake DNS queries to popular Iranian websites to make traffic look normal:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           What ISP/DPI Sees                                  │
│                                                                              │
│   Timeline of DNS queries from the device:                                   │
│                                                                              │
│   09:00:01  DNS → 1.1.1.1: xyz123.s.savethenameofthekillers.com  (tunnel)   │
│   09:00:03  DNS → 8.8.8.8: digikala.com                          (DECOY)    │
│   09:00:05  DNS → 1.1.1.1: abc456.s.savethenameofthekillers.com  (tunnel)   │
│   09:00:08  DNS → 9.9.9.9: aparat.com                            (DECOY)    │
│   09:00:09  DNS → 8.8.8.8: def789.s.savethenameofthekillers.com  (tunnel)   │
│   09:00:15  DNS → 1.1.1.1: varzesh3.com                          (DECOY)    │
│                                                                              │
│   Tunnel traffic is mixed with normal Iranian website queries!               │
└─────────────────────────────────────────────────────────────────────────────┘
```

**How it works:**
1. Every 2-10 seconds (random interval), a decoy DNS query is sent
2. Domains are popular Iranian sites: digikala.com, aparat.com, divar.ir, etc.
3. Queries go through the SAME resolvers as tunnel traffic (1.1.1.1, 8.8.8.8)
4. Socket is "protected" so decoys bypass VPN (sent directly, not through tunnel)

**Why Iranian sites:**
- If only foreign sites were queried, traffic looks suspicious
- Queries to digikala.com, varzesh3.com look like normal Iranian browsing
- These sites are NOT blocked in Iran

**Code location:** `MirageVpnService.kt` - `startDecoyDns()`, `sendDecoyDnsQuery()`

### 2. DNS over HTTPS (DoH) - Optional

When enabled, DNS queries are sent via HTTPS instead of UDP/53:

```
Standard DNS (default):
  slipstream → UDP:53 → 1.1.1.1 → VPS

With DoH enabled:
  slipstream → 127.0.0.1:5353 → DoH Proxy → HTTPS:443 → cloudflare-dns.com → VPS
```

**Advantage:** Traffic looks like normal HTTPS to Cloudflare, not DNS queries.

**Disadvantage:** Extra latency, more complexity.

**Configuration:** Set `use_doh: true` in config.json (disabled by default).

**Code location:** `DohProxy.kt`, `MirageVpnService.kt`

### 3. Multi-Domain Failover

If the primary domain is blocked, the app tries backup domains:

```kotlin
domains: [
  "s.savethenameofthekillers.com",   // Primary
  "t.backup-domain-1.com",            // Backup 1
  "d.backup-domain-2.net",            // Backup 2
]
```

### 4. Multi-Resolver Support

Uses 10+ international DNS resolvers (non-Iranian):

```kotlin
resolvers: [
  "1.1.1.1",          // Cloudflare
  "8.8.8.8",          // Google
  "9.9.9.9",          // Quad9
  "208.67.222.222",   // OpenDNS
  // ... and more
]
```

## Security Considerations

### What's Encrypted
- QUIC streams between client and server (encrypted)
- Data encoding provides obfuscation but not encryption
- TLS traffic (like HTTPS) remains encrypted end-to-end

### What's Visible to ISP
- DNS queries to 1.1.1.1, 8.8.8.8, 9.9.9.9
- Query names like `*.s.savethenameofthekillers.com`
- Query/response sizes and timing patterns
- **With decoys:** Also sees normal Iranian website queries mixed in

### What's Hidden
- With DoH: DNS queries look like HTTPS traffic
- With decoys: Tunnel traffic pattern is obscured

## File Structure

```
android/
├── app/src/main/
│   ├── java/net/mirage/vpn/
│   │   ├── MainActivity.kt        # UI
│   │   ├── MirageVpnService.kt    # VPN service + decoy DNS
│   │   ├── TunnelNative.kt        # JNI wrapper for tun2socks
│   │   ├── ServerConfig.kt        # Configuration management
│   │   └── DohProxy.kt            # DNS over HTTPS proxy (optional)
│   ├── jni/
│   │   ├── hev-socks5-tunnel/     # tun2socks native library
│   │   ├── Android.mk
│   │   └── Application.mk
│   ├── jniLibs/arm64-v8a/
│   │   └── libslipstream.so       # Pre-compiled slipstream client
│   └── assets/
│       └── config.json            # Default configuration
```

## Configuration

Configuration is stored in `config.json`:

```json
{
  "domains": ["s.savethenameofthekillers.com"],
  "resolvers": ["1.1.1.1", "8.8.8.8", "9.9.9.9", ...],
  "listen_port": 5201,
  "server_name": "Mirage VPN - Iran",
  "use_doh": false,
  "doh_port": 5353,
  "doh_endpoints": [
    "https://cloudflare-dns.com/dns-query",
    "https://dns.google/dns-query"
  ]
}
```

## Build Requirements

- Android NDK (for native libraries)
- JDK 17+
- Gradle 8.7+
- AGP 8.5+
- Target: Android API 24+ (arm64-v8a)
