package net.mirage.vpn

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Server configuration for the slipstream DNS tunnel.
 * Can be loaded from assets or updated via QR code/deep link.
 */
data class ServerConfig(
    val domain: String,           // Slipstream domain (e.g., s.savethenameofthekillers.com)
    val resolvers: List<String>,  // DNS resolvers (e.g., 1.1.1.1, 8.8.8.8)
    val listenPort: Int = 5201,   // Local TCP proxy port
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
                // Return default config for slipstream
                ServerConfig(
                    domain = "s.savethenameofthekillers.com",
                    resolvers = listOf("1.1.1.1", "8.8.8.8")
                )
            }
        }

        fun save(context: Context, config: ServerConfig) {
            val configFile = File(context.filesDir, CONFIG_FILE)
            configFile.writeText(config.toJson())
        }

        private fun fromJson(json: String): ServerConfig {
            val obj = JSONObject(json)
            val resolversArray = obj.optJSONArray("resolvers")
            val resolvers = if (resolversArray != null) {
                (0 until resolversArray.length()).map { resolversArray.getString(it) }
            } else {
                listOf("1.1.1.1", "8.8.8.8")
            }
            return ServerConfig(
                domain = obj.getString("domain"),
                resolvers = resolvers,
                listenPort = obj.optInt("listen_port", 5201),
                serverName = obj.optString("server_name", "Mirage VPN")
            )
        }
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("domain", domain)
            put("resolvers", org.json.JSONArray(resolvers))
            put("listen_port", listenPort)
            put("server_name", serverName)
        }.toString(2)
    }
}
