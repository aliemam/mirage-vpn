package net.mirage.vpn

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches proxy config URIs from a remote endpoint and parses them into ProxyConfig objects.
 * Supports VLESS, VMess, Trojan, and Shadowsocks URI formats.
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
     * Fetch raw text content from a URL. Returns null on failure.
     * Used for fetching non-URI config files (e.g., DNS config JSON).
     */
    fun fetchRaw(url: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Raw fetch failed: HTTP ${response.code} from $url")
                return null
            }
            response.body?.string()
        } catch (e: Exception) {
            Log.w(TAG, "Raw fetch error from $url: ${e.message}")
            null
        }
    }

    /**
     * Fetch configs from a remote URL. Each line should be a proxy URI.
     * Supports vless://, vmess://, trojan://, ss:// URIs.
     * Returns list of (originalUri, parsedConfig) pairs.
     */
    fun fetch(url: String): List<Pair<String, ProxyConfig>> {
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
            val results = mutableListOf<Pair<String, ProxyConfig>>()

            for (line in body.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val config = parseUri(trimmed)
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

    // ========== Multi-Protocol URI Dispatcher ==========

    /**
     * Parse any supported proxy URI into a ProxyConfig.
     * Dispatches to protocol-specific parsers based on URI scheme.
     */
    fun parseUri(uri: String): ProxyConfig? {
        return when {
            uri.startsWith("vless://")  -> parseVlessUri(uri)
            uri.startsWith("vmess://")  -> parseVmessUri(uri)
            uri.startsWith("trojan://") -> parseTrojanUri(uri)
            uri.startsWith("ss://")     -> parseShadowsocksUri(uri)
            else -> {
                Log.d(TAG, "Unknown protocol: ${uri.take(20)}...")
                null
            }
        }
    }

    // ========== VLESS Parser ==========

    /**
     * Parse a VLESS URI into a ProxyConfig.
     * Supports both WebSocket+TLS and REALITY protocols.
     *
     * Format: vless://uuid@host:port?params#name
     */
    fun parseVlessUri(uri: String): ProxyConfig? {
        try {
            if (!uri.startsWith("vless://")) return null

            val withoutScheme = uri.removePrefix("vless://")

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

            val atIndex = authority.indexOf('@')
            if (atIndex < 0) return null

            val uuid = authority.substring(0, atIndex)
            val hostPort = authority.substring(atIndex + 1)

            val colonIndex = hostPort.lastIndexOf(':')
            if (colonIndex < 0) return null

            val host = hostPort.substring(0, colonIndex)
            val port = hostPort.substring(colonIndex + 1).toIntOrNull() ?: return null

            val security = params["security"] ?: ""
            val type = params["type"] ?: "tcp"

            return when {
                security == "reality" -> {
                    ProxyConfig.Reality(
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
                security == "tls" || security == "" || security == "none" -> {
                    val path = (params["path"] ?: "/").replace("%2F", "/")
                    ProxyConfig.VlessTls(
                        connectHost = host,
                        port = port,
                        sni = params["sni"] ?: host,
                        host = params["host"] ?: host,
                        path = path,
                        uuid = uuid,
                        network = type,
                        fingerprint = params["fp"] ?: "",
                        alpn = params["alpn"] ?: "",
                        mode = params["mode"] ?: "",
                        name = name
                    )
                }
                else -> {
                    Log.d(TAG, "Unsupported VLESS config: security=$security, type=$type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "VLESS parse error: ${e.message}")
            return null
        }
    }

    // ========== VMess Parser ==========

    /**
     * Parse a VMess URI into a ProxyConfig.
     * VMess URIs encode a JSON object in base64: vmess://[base64-json]
     */
    fun parseVmessUri(uri: String): ProxyConfig? {
        try {
            if (!uri.startsWith("vmess://")) return null
            val base64Part = uri.removePrefix("vmess://")

            // Try standard base64 first, then URL-safe
            val jsonStr = try {
                String(Base64.decode(base64Part, Base64.DEFAULT))
            } catch (e: Exception) {
                try {
                    String(Base64.decode(base64Part, Base64.URL_SAFE))
                } catch (e2: Exception) {
                    Log.d(TAG, "VMess base64 decode failed")
                    return null
                }
            }

            val json = JSONObject(jsonStr)

            return ProxyConfig.VMess(
                connectHost = json.optString("add", ""),
                port = json.optString("port", "443").toIntOrNull() ?: 443,
                uuid = json.optString("id", ""),
                alterId = json.optString("aid", "0").toIntOrNull() ?: 0,
                security = json.optString("scy", "auto"),
                network = json.optString("net", "ws"),
                tls = json.optString("tls", ""),
                sni = json.optString("sni", ""),
                host = json.optString("host", ""),
                path = json.optString("path", "/"),
                fingerprint = json.optString("fp", ""),
                alpn = json.optString("alpn", ""),
                name = json.optString("ps", "")
            )
        } catch (e: Exception) {
            Log.d(TAG, "VMess parse error: ${e.message}")
            return null
        }
    }

    // ========== Trojan Parser ==========

    /**
     * Parse a Trojan URI into a ProxyConfig.
     * Format: trojan://password@host:port?params#name
     */
    fun parseTrojanUri(uri: String): ProxyConfig? {
        try {
            if (!uri.startsWith("trojan://")) return null
            val withoutScheme = uri.removePrefix("trojan://")

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

            val atIndex = authority.indexOf('@')
            if (atIndex < 0) return null

            val password = authority.substring(0, atIndex)
            val hostPort = authority.substring(atIndex + 1)

            val colonIndex = hostPort.lastIndexOf(':')
            if (colonIndex < 0) return null

            val host = hostPort.substring(0, colonIndex)
            val port = hostPort.substring(colonIndex + 1).toIntOrNull() ?: return null

            return ProxyConfig.Trojan(
                connectHost = host,
                port = port,
                password = password,
                sni = params["sni"] ?: host,
                network = params["type"] ?: "tcp",
                host = params["host"] ?: "",
                path = (params["path"] ?: "").replace("%2F", "/"),
                tls = params["security"] ?: "tls",
                fingerprint = params["fp"] ?: "",
                alpn = params["alpn"] ?: "",
                name = name
            )
        } catch (e: Exception) {
            Log.d(TAG, "Trojan parse error: ${e.message}")
            return null
        }
    }

    // ========== Shadowsocks Parser ==========

    /**
     * Parse a Shadowsocks URI into a ProxyConfig.
     * SIP002 format: ss://[base64(method:password)]@host:port#name
     * Legacy format: ss://[base64(method:password@host:port)]#name
     */
    fun parseShadowsocksUri(uri: String): ProxyConfig? {
        try {
            if (!uri.startsWith("ss://")) return null
            val withoutScheme = uri.removePrefix("ss://")

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

            val atIndex = mainPart.lastIndexOf('@')

            if (atIndex < 0) {
                // Legacy format: entire thing is base64 encoded
                val decoded = decodeBase64(mainPart) ?: return null
                val parts = decoded.split("@")
                if (parts.size != 2) return null

                val methodPassword = parts[0].split(":", limit = 2)
                if (methodPassword.size != 2) return null

                val hostPort = parts[1]
                val colonIndex = hostPort.lastIndexOf(':')
                if (colonIndex < 0) return null

                return ProxyConfig.Shadowsocks(
                    connectHost = hostPort.substring(0, colonIndex),
                    port = hostPort.substring(colonIndex + 1).toIntOrNull() ?: return null,
                    method = methodPassword[0],
                    password = methodPassword[1],
                    name = name
                )
            }

            // SIP002 format: base64(method:password)@host:port
            val userInfo = mainPart.substring(0, atIndex)
            val hostPort = mainPart.substring(atIndex + 1)

            // Strip query params if present (some SS URIs have ?plugin=...)
            val queryIndex = hostPort.indexOf('?')
            val cleanHostPort = if (queryIndex >= 0) hostPort.substring(0, queryIndex) else hostPort

            val colonIndex = cleanHostPort.lastIndexOf(':')
            if (colonIndex < 0) return null

            val host = cleanHostPort.substring(0, colonIndex)
            val port = cleanHostPort.substring(colonIndex + 1).toIntOrNull() ?: return null

            // Decode userinfo - could be base64 or plain text (AEAD-2022)
            val decoded = decodeBase64(userInfo)
            if (decoded != null) {
                val methodPassword = decoded.split(":", limit = 2)
                if (methodPassword.size == 2) {
                    return ProxyConfig.Shadowsocks(
                        connectHost = host,
                        port = port,
                        method = methodPassword[0],
                        password = methodPassword[1],
                        name = name
                    )
                }
            }

            // AEAD-2022 plain text format: method:password (URL-encoded)
            val plainUserInfo = java.net.URLDecoder.decode(userInfo, "UTF-8")
            val methodPassword = plainUserInfo.split(":", limit = 2)
            if (methodPassword.size == 2) {
                return ProxyConfig.Shadowsocks(
                    connectHost = host,
                    port = port,
                    method = methodPassword[0],
                    password = methodPassword[1],
                    name = name
                )
            }

            return null
        } catch (e: Exception) {
            Log.d(TAG, "Shadowsocks parse error: ${e.message}")
            return null
        }
    }

    // ========== Config ID Generation ==========

    /**
     * Generate a stable config ID from a URI string.
     * Strips the #fragment (name) before base64 encoding for stability.
     */
    fun configId(uri: String): String {
        val stripped = uri.substringBefore('#')
        return Base64.encodeToString(stripped.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    /**
     * Generate a stable config ID from a ProxyConfig object.
     */
    fun configId(config: ProxyConfig): String {
        val uri = toUri(config)
        return configId(uri)
    }

    // ========== URI Generation ==========

    /**
     * Convert a ProxyConfig back to its standard URI format.
     */
    fun toUri(config: ProxyConfig): String {
        return when (config) {
            is ProxyConfig.VlessTls -> toVlessTlsUri(config)
            is ProxyConfig.Reality -> toVlessRealityUri(config)
            is ProxyConfig.VMess -> toVmessUri(config)
            is ProxyConfig.Trojan -> toTrojanUri(config)
            is ProxyConfig.Shadowsocks -> toShadowsocksUri(config)
        }
    }

    private fun toVlessTlsUri(config: ProxyConfig.VlessTls): String {
        val encodedPath = config.path.replace("/", "%2F")
        val params = mutableListOf(
            "encryption=none",
            "security=tls",
            "type=${config.network}"
        )
        if (config.host.isNotEmpty()) params.add("host=${config.host}")
        if (config.sni.isNotEmpty()) params.add("sni=${config.sni}")
        if (encodedPath.isNotEmpty()) params.add("path=$encodedPath")
        if (config.fingerprint.isNotEmpty()) params.add("fp=${config.fingerprint}")
        if (config.alpn.isNotEmpty()) params.add("alpn=${config.alpn}")
        if (config.mode.isNotEmpty()) params.add("mode=${config.mode}")

        return "vless://${config.uuid}@${config.connectHost}:${config.port}" +
                "?${params.joinToString("&")}" +
                "#${config.name}"
    }

    private fun toVlessRealityUri(config: ProxyConfig.Reality): String {
        return "vless://${config.uuid}@${config.connectHost}:${config.port}" +
                "?security=reality&encryption=none" +
                "&pbk=${config.publicKey}&headerType=none" +
                "&fp=${config.fingerprint}&type=tcp" +
                "&flow=${config.flow}" +
                "&sni=${config.sni}&sid=${config.shortId}" +
                "#${config.name}"
    }

    private fun toVmessUri(config: ProxyConfig.VMess): String {
        val json = JSONObject().apply {
            put("v", "2")
            put("ps", config.name)
            put("add", config.connectHost)
            put("port", config.port.toString())
            put("id", config.uuid)
            put("aid", config.alterId.toString())
            put("scy", config.security)
            put("net", config.network)
            put("type", "none")
            put("host", config.host)
            put("path", config.path)
            put("tls", config.tls)
            put("sni", config.sni)
            put("alpn", config.alpn)
            put("fp", config.fingerprint)
        }
        val encoded = Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
        return "vmess://$encoded"
    }

    private fun toTrojanUri(config: ProxyConfig.Trojan): String {
        val encodedPath = config.path.replace("/", "%2F")
        val params = mutableListOf<String>()
        if (config.sni.isNotEmpty()) params.add("sni=${config.sni}")
        if (config.network != "tcp") params.add("type=${config.network}")
        if (config.host.isNotEmpty()) params.add("host=${config.host}")
        if (encodedPath.isNotEmpty()) params.add("path=$encodedPath")
        if (config.tls != "tls") params.add("security=${config.tls}")
        if (config.fingerprint.isNotEmpty()) params.add("fp=${config.fingerprint}")
        if (config.alpn.isNotEmpty()) params.add("alpn=${config.alpn}")

        val queryStr = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        return "trojan://${config.password}@${config.connectHost}:${config.port}$queryStr#${config.name}"
    }

    private fun toShadowsocksUri(config: ProxyConfig.Shadowsocks): String {
        val userInfo = "${config.method}:${config.password}"
        val encoded = Base64.encodeToString(userInfo.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        return "ss://$encoded@${config.connectHost}:${config.port}#${config.name}"
    }

    // ========== Helpers ==========

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

    private fun decodeBase64(input: String): String? {
        return try {
            String(Base64.decode(input, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        } catch (e: Exception) {
            try {
                String(Base64.decode(input, Base64.DEFAULT))
            } catch (e2: Exception) {
                null
            }
        }
    }
}
