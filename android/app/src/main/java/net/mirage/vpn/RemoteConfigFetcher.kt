package net.mirage.vpn

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Fetches VLESS config URIs from a remote endpoint and parses them into VlessConfig objects.
 * Also provides utility for generating config IDs (base64 of URI without fragment).
 */
object RemoteConfigFetcher {

    private const val TAG = "RemoteConfigFetcher"
    private const val TIMEOUT_SECONDS = 15L

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch configs from a remote URL. Each line should be a VLESS URI.
     * Returns list of (originalUri, parsedConfig) pairs.
     */
    fun fetch(url: String): List<Pair<String, VlessConfig>> {
        return try {
            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Fetch failed: HTTP ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val results = mutableListOf<Pair<String, VlessConfig>>()

            for (line in body.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val config = parseVlessUri(trimmed)
                if (config != null) {
                    results.add(Pair(trimmed, config))
                } else {
                    Log.d(TAG, "Failed to parse: ${trimmed.take(60)}...")
                }
            }

            Log.i(TAG, "Fetched ${results.size} configs from $url")
            results
        } catch (e: Exception) {
            Log.w(TAG, "Fetch error from $url: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse a VLESS URI into a VlessConfig.
     * Supports both WebSocket+TLS and REALITY protocols.
     *
     * Format: vless://uuid@host:port?params#name
     */
    fun parseVlessUri(uri: String): VlessConfig? {
        try {
            if (!uri.startsWith("vless://")) return null

            // Parse the URI manually since java.net.URI chokes on some VLESS URIs
            val withoutScheme = uri.removePrefix("vless://")

            // Split fragment (name)
            val fragmentIndex = withoutScheme.lastIndexOf('#')
            val mainPart: String
            val name: String
            if (fragmentIndex >= 0) {
                mainPart = withoutScheme.substring(0, fragmentIndex)
                name = withoutScheme.substring(fragmentIndex + 1)
            } else {
                mainPart = withoutScheme
                name = ""
            }

            // Split query params
            val queryIndex = mainPart.indexOf('?')
            val authority: String
            val params: Map<String, String>
            if (queryIndex >= 0) {
                authority = mainPart.substring(0, queryIndex)
                params = parseQueryParams(mainPart.substring(queryIndex + 1))
            } else {
                authority = mainPart
                params = emptyMap()
            }

            // Parse uuid@host:port
            val atIndex = authority.indexOf('@')
            if (atIndex < 0) return null

            val uuid = authority.substring(0, atIndex)
            val hostPort = authority.substring(atIndex + 1)

            val colonIndex = hostPort.lastIndexOf(':')
            if (colonIndex < 0) return null

            val host = hostPort.substring(0, colonIndex)
            val port = hostPort.substring(colonIndex + 1).toIntOrNull() ?: return null

            // Determine protocol type
            val security = params["security"] ?: ""
            val type = params["type"] ?: "tcp"

            return when {
                security == "reality" -> {
                    VlessConfig.Reality(
                        connectHost = host,
                        port = port,
                        sni = params["sni"] ?: "",
                        publicKey = params["pbk"] ?: "",
                        shortId = params["sid"] ?: "",
                        fingerprint = params["fp"] ?: "chrome",
                        flow = params["flow"] ?: "xtls-rprx-vision",
                        uuid = uuid,
                        name = name
                    )
                }
                security == "tls" && type == "ws" -> {
                    val path = (params["path"] ?: "/").replace("%2F", "/")
                    VlessConfig.WebSocket(
                        connectHost = host,
                        port = port,
                        sni = params["sni"] ?: host,
                        host = params["host"] ?: host,
                        path = path,
                        uuid = uuid,
                        name = name
                    )
                }
                else -> {
                    Log.d(TAG, "Unsupported VLESS config: security=$security, type=$type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Parse error: ${e.message}")
            return null
        }
    }

    /**
     * Generate a stable config ID from a VLESS URI.
     * Strips the #fragment (name) before base64 encoding for stability.
     */
    fun configId(uri: String): String {
        val stripped = uri.substringBefore('#')
        return Base64.encodeToString(stripped.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    /**
     * Generate a stable config ID from a VlessConfig object.
     */
    fun configId(config: VlessConfig): String {
        val uri = VlessConfigs.toVlessUri(config)
        return configId(uri)
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        for (param in query.split('&')) {
            val eqIndex = param.indexOf('=')
            if (eqIndex > 0) {
                val key = param.substring(0, eqIndex)
                val value = param.substring(eqIndex + 1)
                params[key] = value
            }
        }
        return params
    }
}
