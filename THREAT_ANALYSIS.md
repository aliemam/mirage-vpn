# Threat Analysis: How Iran Could Block MirageVPN

## Current Detection Vectors

### 1. Domain Blocking (EASY - Already Mitigated)

**Attack**: Block DNS queries for `*.savethenameofthekillers.com`

**Current Status**: ✅ MITIGATED
- We query public resolvers (1.1.1.1, 8.8.8.8), not Iranian DNS
- Iran cannot see the actual query content when using DoH/DoT resolvers
- However, they CAN see we're querying these resolvers

**Risk Level**: LOW

---

### 2. Block Public DNS Resolvers (MEDIUM)

**Attack**: Block access to 1.1.1.1, 8.8.8.8, 9.9.9.9

**Current Status**: ⚠️ PARTIALLY VULNERABLE
- If Iran blocks these IPs, slipstream cannot send queries
- This would break many legitimate services too (collateral damage)

**Countermeasures**:
- [ ] Add more resolver options (less common ones)
- [ ] Use DoH (DNS over HTTPS) to port 443 - harder to block
- [ ] Allow custom resolver configuration

**Risk Level**: MEDIUM (high collateral damage makes this unlikely)

---

### 3. Deep Packet Inspection - Query Pattern Analysis (HARD)

**Attack**: Detect unusual DNS query patterns:
- Very long subdomain labels (base32 encoded data)
- High volume of queries to single domain
- Unusual query timing patterns
- Large DNS responses (TXT records with encoded data)

**Current Status**: ⚠️ VULNERABLE

**Detection Signatures**:
```
# Suspicious patterns:
1. Subdomain length > 30 characters
2. Query rate > 10/second to same domain
3. TXT record responses > 255 bytes
4. Non-standard characters in subdomains (base32 = A-Z, 2-7)
5. Sequential/predictable query patterns
```

**Countermeasures**:
- [ ] Randomize query timing (add jitter)
- [ ] Use shorter encoding (base64 vs base32)
- [ ] Split data across multiple queries
- [ ] Add decoy/dummy DNS queries to normal domains
- [ ] Implement query padding to normalize sizes

**Risk Level**: HIGH (this is the main detection vector)

---

### 4. Statistical Traffic Analysis (HARD)

**Attack**: Correlate DNS query volume with actual data usage
- Normal user: ~100 DNS queries/hour
- MirageVPN user: ~10,000+ DNS queries/hour

**Current Status**: ⚠️ VULNERABLE

**Countermeasures**:
- [ ] Implement traffic shaping to reduce query volume
- [ ] Use compression before encoding
- [ ] Batch multiple requests into single queries
- [ ] Limit bandwidth to appear more "normal"

**Risk Level**: MEDIUM-HIGH

---

### 5. Domain Reputation / Blocklist (EASY)

**Attack**: Add tunnel domains to national blocklist once discovered

**Current Status**: ⚠️ VULNERABLE

**Countermeasures**:
- [ ] Domain rotation system (multiple backup domains)
- [ ] Use common/innocent-looking domain names
- [ ] Implement domain-fronting techniques
- [ ] Dynamic domain generation algorithm (DGA)

**Risk Level**: HIGH (easy to implement once domain is known)

---

### 6. VPS IP Blocking (EASY)

**Attack**: Block the VPS IP address (77.42.23.165)

**Current Status**: ⚠️ VULNERABLE
- If they identify the NS record points to this IP, they can block it

**Countermeasures**:
- [ ] Use CDN/proxy in front of VPS (Cloudflare)
- [ ] Multiple VPS servers with failover
- [ ] Dynamic IP rotation
- [ ] Use cloud functions (AWS Lambda, Cloudflare Workers)

**Risk Level**: HIGH

---

### 7. TLS Fingerprinting of QUIC (MEDIUM)

**Attack**: Identify QUIC traffic patterns used by slipstream

**Current Status**: ⚠️ POTENTIALLY VULNERABLE
- QUIC has distinctive patterns
- Though traffic goes through DNS, the internal QUIC might be detectable

**Countermeasures**:
- [ ] Implement QUIC obfuscation
- [ ] Use different transport internally

**Risk Level**: MEDIUM

---

## Priority Improvements

### Phase 1: Essential Hardening (Do First)

1. **Multiple Domains**
   - Register 5-10 backup domains
   - Implement automatic failover when one is blocked
   - Store domain list in app, updatable via out-of-band channel

2. **Query Obfuscation**
   - Add random padding to queries
   - Randomize timing between queries
   - Mix in legitimate DNS queries to popular domains

3. **Multiple Resolvers**
   - Add more DNS resolvers (OpenDNS, Quad9, etc.)
   - Implement DoH (443) as primary, fall back to UDP/53
   - Random resolver selection

### Phase 2: Advanced Evasion

4. **Traffic Normalization**
   - Limit query rate to appear normal
   - Implement bandwidth throttling option
   - Add "stealth mode" with slower but safer patterns

5. **Domain Fronting**
   - Route through CDN (Cloudflare, Fastly)
   - Use legitimate-looking domain as front

6. **Decentralized Infrastructure**
   - Multiple VPS in different countries
   - Automatic server selection based on availability
   - P2P relay network option

### Phase 3: Future Resilience

7. **Protocol Agility**
   - Support multiple tunnel types (DNS, HTTPS, ICMP)
   - Automatic protocol switching when one is blocked

8. **Out-of-Band Updates**
   - Ability to push new domains/servers via:
     - Telegram bot
     - Twitter/social media
     - Email
     - QR codes

---

## Implementation Recommendations

### Immediate (Before Distribution)

```kotlin
// 1. Add multiple resolvers with rotation
val resolvers = listOf(
    "1.1.1.1",      // Cloudflare
    "8.8.8.8",      // Google
    "9.9.9.9",      // Quad9
    "208.67.222.222", // OpenDNS
    "185.228.168.9",  // CleanBrowsing
    "76.76.19.19",    // Alternate DNS
)

// 2. Add query jitter
val jitter = Random.nextLong(50, 200) // ms
delay(jitter)

// 3. Multiple domain support
val domains = listOf(
    "s.savethenameofthekillers.com",
    "t.backup-domain-1.com",
    "d.backup-domain-2.net",
)
```

### Configuration Options to Add

```kotlin
data class TunnelConfig(
    val domains: List<String>,           // Multiple domains
    val resolvers: List<String>,         // Multiple resolvers
    val useDoH: Boolean = true,          // DNS over HTTPS
    val stealthMode: Boolean = false,    // Slower but safer
    val maxQueryRate: Int = 50,          // Queries per second limit
    val enableDecoyQueries: Boolean = true,
)
```

---

## Risk Assessment Summary

| Vector | Risk | Effort to Block | Collateral Damage | Priority |
|--------|------|-----------------|-------------------|----------|
| Domain blocking | HIGH | LOW | LOW | P1 |
| VPS IP blocking | HIGH | LOW | LOW | P1 |
| DNS resolver blocking | MEDIUM | MEDIUM | HIGH | P2 |
| DPI query patterns | HIGH | HIGH | LOW | P1 |
| Traffic analysis | MEDIUM | MEDIUM | LOW | P2 |
| QUIC fingerprinting | MEDIUM | HIGH | LOW | P3 |

---

## Conclusion

The current implementation is functional but has several detection vectors. The most critical improvements are:

1. **Multiple backup domains** - Single point of failure
2. **Query pattern obfuscation** - Most likely detection method
3. **Multiple VPS/CDN** - Single point of failure

With these improvements, MirageVPN would be significantly harder to block without causing major collateral damage to legitimate DNS infrastructure.
