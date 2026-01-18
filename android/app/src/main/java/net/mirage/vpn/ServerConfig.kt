package net.mirage.vpn

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Server configuration for the DNS tunnel.
 * Can be loaded from assets or updated via QR code/deep link.
 */
data class ServerConfig(
    val dnsZone: String,
    val serverKey: String,
    val dohResolver: String = "https://cloudflare-dns.com/dns-query",
    val serverName: String = "Mirage VPN"
) {
    companion object {
        private const val CONFIG_FILE = "server_config.json"
        private const val ASSETS_CONFIG = "config.json"

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
                // Return default config if nothing found
                ServerConfig(
                    dnsZone = "t.example.com",
                    serverKey = "YOUR_SERVER_KEY_HERE"
                )
            }
        }

        fun save(context: Context, config: ServerConfig) {
            val configFile = File(context.filesDir, CONFIG_FILE)
            configFile.writeText(config.toJson())
        }

        private fun fromJson(json: String): ServerConfig {
            val obj = JSONObject(json)
            return ServerConfig(
                dnsZone = obj.getString("dns_zone"),
                serverKey = obj.getString("server_key"),
                dohResolver = obj.optString("doh_resolver", "https://cloudflare-dns.com/dns-query"),
                serverName = obj.optString("server_name", "Mirage VPN")
            )
        }
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("dns_zone", dnsZone)
            put("server_key", serverKey)
            put("doh_resolver", dohResolver)
            put("server_name", serverName)
        }.toString(2)
    }
}
