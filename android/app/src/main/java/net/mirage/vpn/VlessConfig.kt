package net.mirage.vpn

/**
 * VLESS Configuration - supports both WebSocket+TLS and REALITY protocols
 */
sealed class VlessConfig {
    abstract val connectHost: String
    abstract val port: Int
    abstract val uuid: String
    abstract val name: String

    /**
     * VLESS + WebSocket + TLS Configuration (for Cloudflare CDN)
     */
    data class WebSocket(
        override val connectHost: String,  // Cloudflare IP to connect to
        override val port: Int,            // Port (443, 2053, etc.)
        val sni: String,                   // Server Name Indication
        val host: String,                  // HTTP Host header
        val path: String,                  // WebSocket path
        override val uuid: String,         // VLESS UUID
        override val name: String = ""
    ) : VlessConfig()

    /**
     * VLESS + REALITY Configuration (direct connection)
     */
    data class Reality(
        override val connectHost: String,
        override val port: Int,
        val sni: String,
        val publicKey: String,
        val shortId: String,
        val fingerprint: String,
        val flow: String = "xtls-rprx-vision",
        override val uuid: String,
        override val name: String = ""
    ) : VlessConfig()
}

// Backwards compatibility alias
typealias VlessServerConfig = VlessConfig.WebSocket

/**
 * VLESS configurations for bypassing Iranian censorship
 * WebSocket configs via Cloudflare CDN + REALITY configs for direct connection
 */
object VlessConfigs {

    // Our VLESS UUID
    private const val OUR_UUID = "b7693600-2762-4761-a116-74eed10ed272"

    // === Our 3 VPS servers (WebSocket via Cloudflare) ===
    private val OUR_WS_SERVERS = listOf(
        ServerConfig("karbala.hajibasijmoghavematpirooz.xyz", "/hajivpn", "Karbala"),
        ServerConfig("karbala.hajibasijmoghavematpirooz.xyz", "/download", "Karbala2"),
        ServerConfig("atlanta.hajibasijmoghavematpirooz.xyz", "/hajivpn", "Atlanta"),
        ServerConfig("haifa.hajibasijmoghavematpirooz.xyz", "/hajivpn", "Haifa")
    )

    // === Cloudflare IPs (confirmed working from Iran) ===
    private val CLOUDFLARE_IPS = listOf(
        "104.17.71.206",
        "172.64.152.23",
        "141.101.113.119",
        "162.159.129.44",
        "104.17.74.206",
        "104.18.32.47",
        "104.16.24.14",
        "172.64.155.209",
        "162.159.153.4",
        "104.21.47.184",
        "172.67.171.206",
        "104.16.132.229",
        "104.17.96.22",
        "162.159.140.1",
        "141.101.114.1"
    )

    // === Cloudflare HTTPS ports ===
    private val CLOUDFLARE_PORTS = listOf(443, 2053, 8443, 2083, 2087, 2096)

    // === Azure REALITY server (confirmed ping from Iran) ===
    private const val AZURE_IP = "20.163.6.54"
    private const val AZURE_PORT = 443
    private const val AZURE_PUB_KEY = "NnEEa1fdLATKmqFYNCjgx52U-1Q_B22V91DQN099k0M"
    private const val AZURE_SHORT_ID = "1001826960fb5c74"
    private const val AZURE_UUID = "9bd307ba-c5f4-4152-93f7-da66e62ff297"

    // === Azure REALITY server 2 ===
    private const val AZURE2_IP = "20.38.42.55"
    private const val AZURE2_PUB_KEY = "bu7KqTInAx7azEaYxuiaJdzI6W0G8orlHRUgwbuYvAs"
    private const val AZURE2_SHORT_ID = "a73518c042c38bfd"
    private const val AZURE2_UUID = "cecd320b-8d04-4f1d-96ce-49675385f967"

    // === Kamatera Rosh REALITY server 1 ===
    private const val ROSH1_IP = "212.115.111.185"
    private const val ROSH1_PUB_KEY = "gLTFmvip0eG5oe9LhxvBZGrUY-yDmDUkas3Thb0vcxA"
    private const val ROSH1_SHORT_ID = "1ba199a7859ef206"
    private const val ROSH1_UUID = "2a88c3ea-76c7-4c89-855e-9c5024558256"


    // SNIs: high-traffic foreign sites Iran can't block
    private val REALITY_SNIS = listOf(
        "www.google.com",
        "www.google-analytics.com",
        "clients3.google.com",
        "www.bing.com",
        "www.microsoft.com",
        "update.microsoft.com",
        "login.live.com",
        "www.cloudflare.com",
        "cdn.jsdelivr.net",
        "fonts.gstatic.com",
        "ocsp.apple.com",
        "www.amazon.com",
        "www.speedtest.net",
        "chatgpt.com"
    )

    private val REALITY_FINGERPRINTS = listOf("chrome", "firefox", "safari")

    // REALITY servers
    private data class RealityServer(
        val ip: String,
        val port: Int,
        val publicKey: String,
        val shortId: String,
        val uuid: String,
        val label: String
    )

    private val REALITY_SERVERS = listOf(
        RealityServer(AZURE_IP, AZURE_PORT, AZURE_PUB_KEY, AZURE_SHORT_ID, AZURE_UUID, "Azure1"),
        RealityServer(AZURE2_IP, AZURE_PORT, AZURE2_PUB_KEY, AZURE2_SHORT_ID, AZURE2_UUID, "Azure2"),
        RealityServer(ROSH1_IP, AZURE_PORT, ROSH1_PUB_KEY, ROSH1_SHORT_ID, ROSH1_UUID, "Rosh1")
    )

    /**
     * Generate all REALITY configs: Servers × SNIs × Fingerprints
     */
    private fun generateRealityConfigs(): List<VlessConfig.Reality> {
        val configs = mutableListOf<VlessConfig.Reality>()
        for (server in REALITY_SERVERS) {
            for (sni in REALITY_SNIS) {
                for (fp in REALITY_FINGERPRINTS) {
                    configs.add(
                        VlessConfig.Reality(
                            connectHost = server.ip,
                            port = server.port,
                            sni = sni,
                            publicKey = server.publicKey,
                            shortId = server.shortId,
                            fingerprint = fp,
                            uuid = server.uuid,
                            name = "${server.label}-${sni.substringAfter("www.").substringBefore(".")}-$fp"
                        )
                    )
                }
            }
        }
        return configs
    }

    private data class ServerConfig(
        val domain: String,
        val path: String,
        val name: String
    )

    /**
     * Generate WebSocket combinations: Cloudflare IPs × Servers × Ports
     */
    private fun generateWebSocketConfigs(): List<VlessConfig.WebSocket> {
        val configs = mutableListOf<VlessConfig.WebSocket>()

        // Priority 1: Port 443 with all IPs and servers
        for (server in OUR_WS_SERVERS) {
            for (ip in CLOUDFLARE_IPS) {
                configs.add(
                    VlessConfig.WebSocket(
                        connectHost = ip,
                        port = 443,
                        sni = server.domain,
                        host = server.domain,
                        path = server.path,
                        uuid = OUR_UUID,
                        name = "${server.name}-$ip-443"
                    )
                )
            }
        }

        // Priority 2: Other ports with all IPs and servers
        for (port in CLOUDFLARE_PORTS.filter { it != 443 }) {
            for (server in OUR_WS_SERVERS) {
                for (ip in CLOUDFLARE_IPS) {
                    configs.add(
                        VlessConfig.WebSocket(
                            connectHost = ip,
                            port = port,
                            sni = server.domain,
                            host = server.domain,
                            path = server.path,
                            uuid = OUR_UUID,
                            name = "${server.name}-$ip-$port"
                        )
                    )
                }
            }
        }

        return configs
    }

    /**
     * Get prioritized configs - interleaved for faster discovery
     * Strategy: try a few REALITY, then some WebSocket, repeat
     */
    fun getPrioritizedConfigs(): List<VlessConfig> {
        val reality = generateRealityConfigs().shuffled()
        val ws = generateWebSocketConfigs()
        val result = mutableListOf<VlessConfig>()

        // Interleave: 3 REALITY, then 4 WebSocket (one per server), repeat
        val realityIter = reality.iterator()
        val wsIter = ws.iterator()

        while (realityIter.hasNext() || wsIter.hasNext()) {
            repeat(3) { if (realityIter.hasNext()) result.add(realityIter.next()) }
            repeat(4) { if (wsIter.hasNext()) result.add(wsIter.next()) }
        }

        return result
    }

    /**
     * Get quick probe configs - just 1 per REALITY server + 1 per WS server on port 443
     * Total: ~7 configs for fast initial check
     */
    fun getQuickProbeConfigs(): List<VlessConfig> {
        val quickConfigs = mutableListOf<VlessConfig>()

        // One REALITY config per server (chrome + google.com)
        for (server in REALITY_SERVERS) {
            quickConfigs.add(
                VlessConfig.Reality(
                    connectHost = server.ip,
                    port = server.port,
                    sni = "www.google.com",
                    publicKey = server.publicKey,
                    shortId = server.shortId,
                    fingerprint = "chrome",
                    uuid = server.uuid,
                    name = "${server.label}-google-chrome"
                )
            )
        }

        // One WebSocket config per server (first CF IP, port 443)
        val firstIp = CLOUDFLARE_IPS.first()
        for (server in OUR_WS_SERVERS) {
            quickConfigs.add(
                VlessConfig.WebSocket(
                    connectHost = firstIp,
                    port = 443,
                    sni = server.domain,
                    host = server.domain,
                    path = server.path,
                    uuid = OUR_UUID,
                    name = "${server.name}-$firstIp-443"
                )
            )
        }

        return quickConfigs
    }

    /**
     * Generate VLESS URI for sharing/importing
     */
    fun toVlessUri(config: VlessConfig): String {
        return when (config) {
            is VlessConfig.WebSocket -> {
                val encodedPath = config.path.replace("/", "%2F")
                "vless://${config.uuid}@${config.connectHost}:${config.port}" +
                        "?encryption=none&security=tls&type=ws" +
                        "&host=${config.host}&sni=${config.sni}&path=$encodedPath" +
                        "#${config.name}"
            }
            is VlessConfig.Reality -> {
                "vless://${config.uuid}@${config.connectHost}:${config.port}" +
                        "?security=reality&encryption=none" +
                        "&pbk=${config.publicKey}&headerType=none" +
                        "&fp=${config.fingerprint}&type=tcp" +
                        "&flow=${config.flow}" +
                        "&sni=${config.sni}&sid=${config.shortId}" +
                        "#${config.name}"
            }
        }
    }
}
