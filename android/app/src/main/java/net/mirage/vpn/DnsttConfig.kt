package net.mirage.vpn

import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * Individual dnstt DNS tunnel configuration.
 * Each instance represents a specific (nsDomain, resolver, pubkey, transport) combination.
 *
 * dnstt-client creates a TCP tunnel through DNS queries.
 * On the server side, dnstt-server forwards to a local SOCKS5 proxy (microsocks).
 *
 * Chain: tun2socks → dnstt-client (local TCP) → DNS tunnel → dnstt-server → microsocks → internet
 */
data class DnsttConfig(
    val nsDomain: String,           // NS domain for tunnel (e.g., "d1.pdigikalasmart.ir")
    val resolver: String,           // DNS resolver (e.g., "8.8.8.8" or "https://cloudflare-dns.com/dns-query")
    val pubkey: String,             // Server public key (hex string)
    val transport: String = "udp",  // Transport: "udp", "doh", "dot"
    val name: String = "",          // Display name
    val sshUser: String? = null,    // SSH user (Net Mode compat, optional)
    val sshPass: String? = null     // SSH pass (Net Mode compat, optional)
) {
    companion object {
        private const val TAG = "DnsttConfig"

        /**
         * Parse a dns:// URI into a DnsttConfig.
         * Format: dns://base64({"ps":"name","ns":"d.domain.com","pubkey":"hex...","addr":"8.8.8.8"})
         *
         * Fields:
         *   ps      - display name (optional)
         *   ns      - NS domain for tunnel (required)
         *   pubkey  - server public key hex (required)
         *   addr    - resolver address (required)
         *   transport - "udp", "doh", "dot" (optional, auto-detected from addr)
         *   user    - SSH user (optional, Net Mode compat)
         *   pass    - SSH pass (optional, Net Mode compat)
         */
        fun parseUri(uri: String): DnsttConfig? {
            try {
                if (!uri.startsWith("dns://")) return null
                val base64Part = uri.removePrefix("dns://").trim()

                val jsonStr = try {
                    String(Base64.decode(base64Part, Base64.DEFAULT))
                } catch (e: Exception) {
                    try {
                        String(Base64.decode(base64Part, Base64.URL_SAFE))
                    } catch (e2: Exception) {
                        Log.d(TAG, "dns:// base64 decode failed")
                        return null
                    }
                }

                val json = JSONObject(jsonStr)

                val ns = json.optString("ns", "")
                val pubkey = json.optString("pubkey", "")
                if (ns.isEmpty() || pubkey.isEmpty()) {
                    Log.d(TAG, "dns:// URI missing required ns or pubkey")
                    return null
                }

                val addr = json.optString("addr", "8.8.8.8")
                val explicitTransport = json.optString("transport", "")
                val transport = when {
                    explicitTransport.isNotEmpty() -> explicitTransport
                    addr.startsWith("https://") -> "doh"
                    addr.contains(":853") -> "dot"
                    else -> "udp"
                }

                val name = json.optString("ps", "").ifEmpty {
                    val domainShort = ns.substringBefore('.').takeLast(6)
                    val resolverShort = when {
                        addr.contains("cloudflare") -> "cf"
                        addr.contains("google") -> "gg"
                        addr.contains("quad9") -> "q9"
                        addr.contains("adguard") -> "ag"
                        addr.startsWith("1.1.1") -> "cf"
                        addr.startsWith("8.8.8") -> "gg"
                        addr.startsWith("9.9.9") -> "q9"
                        else -> addr.take(6)
                    }
                    "$domainShort-$resolverShort-$transport"
                }

                return DnsttConfig(
                    nsDomain = ns,
                    resolver = addr,
                    pubkey = pubkey,
                    transport = transport,
                    name = name,
                    sshUser = json.optString("user", "").ifEmpty { null },
                    sshPass = json.optString("pass", "").ifEmpty { null }
                )
            } catch (e: Exception) {
                Log.d(TAG, "dns:// parse error: ${e.message}")
                return null
            }
        }

        /**
         * Generate a dns:// URI from a DnsttConfig.
         */
        fun toUri(config: DnsttConfig): String {
            val json = JSONObject().apply {
                put("ps", config.name)
                put("ns", config.nsDomain)
                put("pubkey", config.pubkey)
                put("addr", config.resolver)
                put("transport", config.transport)
                if (config.sshUser != null) put("user", config.sshUser)
                if (config.sshPass != null) put("pass", config.sshPass)
            }
            val encoded = Base64.encodeToString(
                json.toString().toByteArray(),
                Base64.NO_WRAP
            )
            return "dns://$encoded"
        }

        /**
         * Generate a stable config ID for scoring.
         * Uses ns+resolver+pubkey+transport as identity (name changes don't affect ID).
         */
        fun configId(config: DnsttConfig): String {
            val identity = "${config.nsDomain}|${config.resolver}|${config.pubkey}|${config.transport}"
            return Base64.encodeToString(
                identity.toByteArray(),
                Base64.NO_WRAP or Base64.URL_SAFE
            )
        }
    }
}
