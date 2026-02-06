package net.mirage.vpn

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DNS tunneling configuration.
 * Extracted from ServerConfig to keep protocol-specific settings separate.
 * Loaded from protocols/dns/config.json (local cache or bundled asset).
 */
data class DnsConfig(
    val domains: List<String> = emptyList(),
    val resolvers: List<String> = DEFAULT_RESOLVERS,
    val listenPort: Int = ServerConfig.DNS_LISTEN_PORT,
    val currentDomainIndex: Int = 0,
    val useDoH: Boolean = true,
    val dohPort: Int = ServerConfig.DOH_PORT,
    val dohEndpoints: List<String> = DEFAULT_DOH_ENDPOINTS
) {
    /** Current active domain for DNS tunneling. */
    val domain: String get() = domains.getOrElse(currentDomainIndex) { domains.firstOrNull() ?: "" }

    /** Whether DNS tunneling is available (has at least one domain configured). */
    val isAvailable: Boolean get() = domains.isNotEmpty()

    /** Get next domain to try after current one fails. */
    fun nextDomain(): DnsConfig {
        val nextIndex = (currentDomainIndex + 1) % domains.size
        return copy(currentDomainIndex = nextIndex)
    }

    /** Check if we've tried all domains. */
    fun hasMoreDomains(): Boolean = currentDomainIndex < domains.size - 1

    fun toJson(): String {
        return JSONObject().apply {
            put("domains", JSONArray(domains))
            put("resolvers", JSONArray(resolvers))
            put("listen_port", listenPort)
            put("use_doh", useDoH)
            put("doh_port", dohPort)
            put("doh_endpoints", JSONArray(dohEndpoints))
        }.toString(2)
    }

    companion object {
        private const val TAG = "DnsConfig"
        private const val LOCAL_PATH = "protocols/dns/config.json"
        private const val ASSET_PATH = "protocols/dns/config.json"

        // DoH endpoints - traffic looks like normal HTTPS to these domains
        val DEFAULT_DOH_ENDPOINTS = listOf(
            "https://cloudflare-dns.com/dns-query",
            "https://dns.google/dns-query",
            "https://dns.quad9.net/dns-query",
            "https://doh.opendns.com/dns-query",
        )

        // Safe DNS resolvers - NOT controlled by Iranian government
        val DEFAULT_RESOLVERS = listOf(
            // Cloudflare - Fast, privacy-focused
            "1.1.1.1",
            "1.0.0.1",
            // Google - Reliable, widely used
            "8.8.8.8",
            "8.8.4.4",
            // Quad9 - Security-focused, blocks malware
            "9.9.9.9",
            "149.112.112.112",
            // OpenDNS - Cisco owned
            "208.67.222.222",
            "208.67.220.220",
            // AdGuard DNS
            "94.140.14.14",
            "94.140.15.15",
            // CleanBrowsing
            "185.228.168.9",
            "185.228.169.9",
            // Comodo Secure DNS
            "8.26.56.26",
            "8.20.247.20",
        )

        /**
         * Load DNS config. Tries local cache first, then bundled asset, then defaults.
         */
        fun load(context: Context): DnsConfig {
            // 1. Try local cached file
            val localFile = File(context.filesDir, LOCAL_PATH)
            if (localFile.exists()) {
                try {
                    val config = fromJson(localFile.readText())
                    Log.d(TAG, "Loaded DNS config from local: ${config.domains.size} domains")
                    return config
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load local DNS config: ${e.message}")
                }
            }

            // 2. Try bundled asset
            try {
                val json = context.assets.open(ASSET_PATH).bufferedReader().readText()
                val config = fromJson(json)
                Log.d(TAG, "Loaded DNS config from asset: ${config.domains.size} domains")
                return config
            } catch (e: Exception) {
                Log.d(TAG, "No bundled DNS config: ${e.message}")
            }

            // 3. Defaults (no domains = DNS unavailable)
            return DnsConfig()
        }

        /**
         * Save DNS config to local cache.
         */
        fun saveLocal(context: Context, config: DnsConfig) {
            try {
                val dir = File(context.filesDir, "protocols/dns")
                dir.mkdirs()
                File(dir, "config.json").writeText(config.toJson())
                Log.d(TAG, "Saved DNS config locally: ${config.domains.size} domains")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save DNS config: ${e.message}")
            }
        }

        fun fromJson(json: String): DnsConfig {
            val obj = JSONObject(json)

            // Support both old "domain" (single) and new "domains" (list) format
            val domains = when {
                obj.has("domains") -> {
                    val arr = obj.getJSONArray("domains")
                    (0 until arr.length()).map { arr.getString(it) }
                }
                obj.has("domain") -> listOf(obj.getString("domain"))
                else -> emptyList()
            }

            val resolversArr = obj.optJSONArray("resolvers")
            val resolvers = if (resolversArr != null) {
                (0 until resolversArr.length()).map { resolversArr.getString(it) }
            } else DEFAULT_RESOLVERS

            val dohArr = obj.optJSONArray("doh_endpoints")
            val dohEndpoints = if (dohArr != null) {
                (0 until dohArr.length()).map { dohArr.getString(it) }
            } else DEFAULT_DOH_ENDPOINTS

            return DnsConfig(
                domains = domains,
                resolvers = resolvers,
                listenPort = obj.optInt("listen_port", ServerConfig.DNS_LISTEN_PORT),
                useDoH = obj.optBoolean("use_doh", true),
                dohPort = obj.optInt("doh_port", ServerConfig.DOH_PORT),
                dohEndpoints = dohEndpoints
            )
        }

        /**
         * Parse DNS fields from old-format ServerConfig JSON (for migration).
         */
        fun fromLegacyJson(obj: JSONObject): DnsConfig {
            return fromJson(obj.toString())
        }
    }
}
