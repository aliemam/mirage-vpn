package net.mirage.vpn

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Server configuration for the slipstream DNS tunnel.
 * Supports multiple domains for failover and extensive resolver list.
 */
data class ServerConfig(
    val domains: List<String>,    // Multiple domains for failover
    val resolvers: List<String>,  // DNS resolvers (non-Iranian!)
    val listenPort: Int = 5201,   // Local TCP proxy port
    val serverName: String = "Mirage VPN",
    val currentDomainIndex: Int = 0,  // Track which domain we're using
    val useDoH: Boolean = false,   // DoH with IP donors needs SNI fix - disabled for now
    val dohPort: Int = 5353,      // Local DoH proxy port
    val dohEndpoints: List<String> = DEFAULT_DOH_ENDPOINTS  // DoH servers
) {
    // For backward compatibility
    val domain: String get() = domains.getOrElse(currentDomainIndex) { domains.first() }

    companion object {
        private const val CONFIG_FILE = "server_config.json"
        private const val ASSETS_CONFIG = "config.json"

        // DoH endpoints - traffic looks like normal HTTPS to these domains
        val DEFAULT_DOH_ENDPOINTS = listOf(
            "https://cloudflare-dns.com/dns-query",
            "https://dns.google/dns-query",
            "https://dns.quad9.net/dns-query",
            "https://doh.opendns.com/dns-query",
        )

        // Default domains - add your backup domains here
        private val DEFAULT_DOMAINS = listOf(
            "s.savethenameofthekillers.com"
            // Add backup domains as you register them:
            // "t.backup-domain-1.com",
            // "d.backup-domain-2.net",
        )

        // Safe DNS resolvers - NOT controlled by Iranian government
        // These are all international resolvers that Iran cannot manipulate
        private val DEFAULT_RESOLVERS = listOf(
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

        fun load(context: Context): ServerConfig {
            // First try to load from internal storage (user-updated config)
            val configFile = File(context.filesDir, CONFIG_FILE)
            if (configFile.exists()) {
                return fromJson(configFile.readText())
            }

            // Fall back to bundled config in assets
            return try {
                val json = context.assets.open(ASSETS_CONFIG).bufferedReader().readText()
                fromJson(json)
            } catch (e: Exception) {
                // Return default config
                ServerConfig(
                    domains = DEFAULT_DOMAINS,
                    resolvers = DEFAULT_RESOLVERS
                )
            }
        }

        fun save(context: Context, config: ServerConfig) {
            val configFile = File(context.filesDir, CONFIG_FILE)
            configFile.writeText(config.toJson())
        }

        private fun fromJson(json: String): ServerConfig {
            val obj = JSONObject(json)

            // Support both old "domain" (single) and new "domains" (list) format
            val domains = when {
                obj.has("domains") -> {
                    val arr = obj.getJSONArray("domains")
                    (0 until arr.length()).map { arr.getString(it) }
                }
                obj.has("domain") -> listOf(obj.getString("domain"))
                else -> DEFAULT_DOMAINS
            }

            val resolversArray = obj.optJSONArray("resolvers")
            val resolvers = if (resolversArray != null) {
                (0 until resolversArray.length()).map { resolversArray.getString(it) }
            } else {
                DEFAULT_RESOLVERS
            }

            // Parse DoH endpoints
            val dohEndpointsArray = obj.optJSONArray("doh_endpoints")
            val dohEndpoints = if (dohEndpointsArray != null) {
                (0 until dohEndpointsArray.length()).map { dohEndpointsArray.getString(it) }
            } else {
                DEFAULT_DOH_ENDPOINTS
            }

            return ServerConfig(
                domains = domains,
                resolvers = resolvers,
                listenPort = obj.optInt("listen_port", 5201),
                serverName = obj.optString("server_name", "Mirage VPN"),
                currentDomainIndex = obj.optInt("current_domain_index", 0),
                useDoH = obj.optBoolean("use_doh", true),
                dohPort = obj.optInt("doh_port", 5353),
                dohEndpoints = dohEndpoints
            )
        }
    }

    /** Get next domain to try after current one fails */
    fun nextDomain(): ServerConfig {
        val nextIndex = (currentDomainIndex + 1) % domains.size
        return copy(currentDomainIndex = nextIndex)
    }

    /** Check if we've tried all domains */
    fun hasMoreDomains(): Boolean = currentDomainIndex < domains.size - 1

    fun toJson(): String {
        return JSONObject().apply {
            put("domains", org.json.JSONArray(domains))
            put("resolvers", org.json.JSONArray(resolvers))
            put("listen_port", listenPort)
            put("server_name", serverName)
            put("current_domain_index", currentDomainIndex)
            put("use_doh", useDoH)
            put("doh_port", dohPort)
            put("doh_endpoints", org.json.JSONArray(dohEndpoints))
        }.toString(2)
    }
}
