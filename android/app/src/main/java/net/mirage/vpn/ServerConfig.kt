package net.mirage.vpn

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Active tunnel mode (runtime state, not user-configured).
 */
enum class TunnelMode {
    DNS,    // DNS tunneling via slipstream
    XRAY,   // Xray-core (VLESS, VMess, Trojan, Shadowsocks)
    AUTO    // Try Xray first, fall back to DNS
}

/**
 * Simplified server configuration.
 * Only contains server identity and remote config URL.
 * Protocol-specific settings live in their own config files (e.g., DnsConfig).
 */
data class ServerConfig(
    val serverName: String = "",
    val remoteConfigUrl: String = ""
) {
    /**
     * Display name shown in notifications and VPN session.
     * "MirageVPN" if no custom name, "MirageVPN - {name}" otherwise.
     */
    val displayName: String
        get() = if (serverName.isBlank()) "MirageVPN" else "MirageVPN - $serverName"

    fun toJson(): String {
        return JSONObject().apply {
            put("server_name", serverName)
            put("remote_config_url", remoteConfigUrl)
        }.toString(2)
    }

    companion object {
        private const val TAG = "ServerConfig"
        private const val CONFIG_FILE = "server_config.json"
        private const val ASSETS_CONFIG = "config.json"

        // Operational constants
        const val XRAY_SOCKS_PORT = 10808
        const val DNS_LISTEN_PORT = 5201
        const val DOH_PORT = 5353

        fun load(context: Context): ServerConfig {
            val configFile = File(context.filesDir, CONFIG_FILE)
            if (configFile.exists()) {
                try {
                    val json = configFile.readText()
                    val obj = JSONObject(json)

                    // Detect old format: has "domains" or "tunnel_mode" keys
                    if (obj.has("domains") || obj.has("tunnel_mode")) {
                        Log.i(TAG, "Detected old config format, migrating...")
                        return migrateOldConfig(context, obj)
                    }

                    return fromJson(json)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load config: ${e.message}")
                }
            }

            // Fall back to bundled asset
            return try {
                val json = context.assets.open(ASSETS_CONFIG).bufferedReader().readText()
                val obj = JSONObject(json)

                // Handle old-format bundled asset
                if (obj.has("domains") || obj.has("tunnel_mode")) {
                    return migrateFromBundledAsset(context, obj)
                }

                fromJson(json)
            } catch (e: Exception) {
                ServerConfig()
            }
        }

        fun save(context: Context, config: ServerConfig) {
            val configFile = File(context.filesDir, CONFIG_FILE)
            configFile.writeText(config.toJson())
        }

        private fun fromJson(json: String): ServerConfig {
            val obj = JSONObject(json)
            return ServerConfig(
                serverName = obj.optString("server_name", ""),
                remoteConfigUrl = obj.optString("remote_config_url", "")
            )
        }

        /**
         * Migrate old-format config from internal storage.
         * Extracts DNS fields to DnsConfig, splits configs by protocol.
         */
        private fun migrateOldConfig(context: Context, obj: JSONObject): ServerConfig {
            val config = ServerConfig(
                serverName = obj.optString("server_name", ""),
                remoteConfigUrl = obj.optString("remote_config_url", "")
            )

            // Extract DNS config
            if (obj.has("domains")) {
                val dnsConfig = DnsConfig.fromLegacyJson(obj)
                DnsConfig.saveLocal(context, dnsConfig)
                Log.i(TAG, "Migrated DNS config: ${dnsConfig.domains.size} domains")
            }

            // Migrate old flat configs.txt into protocol folders
            migrateOldConfigs(context)

            // Save new format
            save(context, config)
            Log.i(TAG, "Migration complete")

            return config
        }

        /**
         * Handle old-format bundled asset (first install with old config.json).
         * Only extracts ServerConfig fields — bundled DNS config is loaded separately by DnsConfig.load().
         */
        private fun migrateFromBundledAsset(context: Context, obj: JSONObject): ServerConfig {
            val config = ServerConfig(
                serverName = obj.optString("server_name", ""),
                remoteConfigUrl = obj.optString("remote_config_url", "")
            )

            // If bundled asset has DNS fields, save them to protocols/dns/config.json
            // so DnsConfig.load() can find them
            if (obj.has("domains")) {
                val dnsConfig = DnsConfig.fromLegacyJson(obj)
                if (dnsConfig.isAvailable) {
                    DnsConfig.saveLocal(context, dnsConfig)
                }
            }

            return config
        }

        /**
         * Redistribute old flat configs.txt into protocol-specific folders.
         */
        private fun migrateOldConfigs(context: Context) {
            val oldFile = File(context.filesDir, "configs.txt")
            if (!oldFile.exists()) return

            try {
                val lines = oldFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
                val buckets = mutableMapOf<String, MutableList<String>>()

                for (line in lines) {
                    val trimmed = line.trim()
                    val protocol = when {
                        trimmed.startsWith("vless://") -> "vless"
                        trimmed.startsWith("vmess://") -> "vmess"
                        trimmed.startsWith("trojan://") -> "trojan"
                        trimmed.startsWith("ss://") -> "shadowsocks"
                        else -> null
                    }
                    if (protocol != null) {
                        buckets.getOrPut(protocol) { mutableListOf() }.add(trimmed)
                    }
                }

                for ((protocol, uris) in buckets) {
                    val dir = File(context.filesDir, "protocols/$protocol")
                    dir.mkdirs()
                    File(dir, "configs.txt").writeText(uris.joinToString("\n"))
                    Log.d(TAG, "Migrated ${uris.size} configs to $protocol folder")
                }

                oldFile.delete()
                Log.i(TAG, "Migrated old configs.txt into protocol folders")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to migrate old configs: ${e.message}")
            }
        }
    }
}
