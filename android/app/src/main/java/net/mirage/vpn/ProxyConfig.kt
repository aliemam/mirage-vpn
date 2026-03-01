package net.mirage.vpn

/**
 * Proxy Configuration - supports VLESS, VMess, Trojan, and Shadowsocks protocols.
 * All protocols use Xray-core as the tunneling engine.
 */
sealed class ProxyConfig {
    abstract val connectHost: String
    abstract val port: Int
    abstract val name: String

    // ========== VLESS Configs ==========

    /**
     * VLESS + TLS Configuration (supports ws, xhttp, grpc, h2, tcp transports)
     */
    data class VlessTls(
        override val connectHost: String,  // CDN IP or server IP to connect to
        override val port: Int,            // Port (443, 2053, etc.)
        val sni: String,                   // Server Name Indication
        val host: String,                  // HTTP Host header
        val path: String,                  // Path (WebSocket/xhttp/h2) or gRPC serviceName
        val uuid: String,                  // VLESS UUID
        val network: String = "ws",        // transport: ws, xhttp, grpc, h2, tcp
        val fingerprint: String = "",      // TLS fingerprint (chrome, firefox, safari, etc.)
        val alpn: String = "",             // ALPN negotiation (h2, http/1.1, etc.)
        val mode: String = "",             // xhttp mode: auto, packet-up, stream-up
        override val name: String = ""
    ) : ProxyConfig()

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
        val uuid: String,
        override val name: String = ""
    ) : ProxyConfig()

    // ========== VMess Config ==========

    /**
     * VMess Configuration
     * Supports WebSocket+TLS, TCP+TLS, gRPC, and HTTP/2 transports
     */
    data class VMess(
        override val connectHost: String,
        override val port: Int,
        val uuid: String,
        val alterId: Int = 0,
        val security: String = "auto",     // encryption: auto, aes-128-gcm, chacha20-poly1305, none
        val network: String = "ws",         // transport: ws, tcp, grpc, h2
        val tls: String = "tls",            // security: tls, none, ""
        val sni: String = "",
        val host: String = "",              // WebSocket/HTTP host header
        val path: String = "/",             // WebSocket/HTTP path, or gRPC serviceName
        val fingerprint: String = "",       // TLS fingerprint
        val alpn: String = "",              // ALPN negotiation
        override val name: String = ""
    ) : ProxyConfig()

    // ========== Trojan Config ==========

    /**
     * Trojan Configuration
     * Supports TCP+TLS, WebSocket+TLS, and gRPC transports
     */
    data class Trojan(
        override val connectHost: String,
        override val port: Int,
        val password: String,
        val sni: String = "",
        val network: String = "tcp",        // transport: tcp, ws, grpc
        val host: String = "",              // WebSocket host header
        val path: String = "",              // WebSocket path or gRPC serviceName
        val tls: String = "tls",            // security: tls, none
        val fingerprint: String = "",
        val alpn: String = "",
        override val name: String = ""
    ) : ProxyConfig()

    // ========== Shadowsocks Config ==========

    /**
     * Shadowsocks Configuration (SIP002 format)
     */
    data class Shadowsocks(
        override val connectHost: String,
        override val port: Int,
        val method: String,                 // encryption: aes-256-gcm, chacha20-ietf-poly1305, 2022-blake3-aes-256-gcm, etc.
        val password: String,
        override val name: String = ""
    ) : ProxyConfig()
}
