package net.mirage.vpn

import android.util.Log

/**
 * Generates all IP × backend combinations for WebSocket (Cloudflare CDN) configs.
 *
 * Given existing configs that pair Cloudflare IPs with backend servers,
 * this generates the full cross-product so every IP is tried with every backend.
 * Only applies to WebSocket configs routed through CDN — REALITY/VMess/Trojan/SS
 * connect directly to server IPs, so IP swapping doesn't apply.
 */
class GridConfigGenerator {

    companion object {
        private const val TAG = "GridConfigGenerator"
    }

    private data class WsBackend(
        val host: String,
        val path: String,
        val uuid: String,
        val port: Int
    )

    /**
     * Generate missing IP × backend combinations from existing WebSocket configs.
     * Returns only NEW configs that don't already exist.
     */
    fun generate(configs: List<ProxyConfig>): List<ProxyConfig.WebSocket> {
        val wsConfigs = configs.filterIsInstance<ProxyConfig.WebSocket>()
        if (wsConfigs.size < 2) return emptyList()

        // Cloudflare IP = appears with 2+ different backends (host values)
        val ipToHosts = wsConfigs.groupBy { it.connectHost }
            .mapValues { (_, cfgs) -> cfgs.map { it.host }.distinct().size }
        val cloudflareIps = ipToHosts.filter { it.value >= 2 }.keys.toList()

        if (cloudflareIps.isEmpty()) {
            Log.d(TAG, "No CDN IPs detected (need IP used with 2+ backends)")
            return emptyList()
        }

        // Extract unique backends (only from configs that use CDN IPs)
        val backends = wsConfigs
            .filter { it.connectHost in cloudflareIps }
            .map { WsBackend(it.host, it.path, it.uuid, it.port) }
            .distinct()

        // Build set of existing combos for fast dedup
        val existing = wsConfigs
            .map { "${it.connectHost}|${it.host}|${it.path}" }
            .toSet()

        // Generate cross-product, skip existing
        val generated = mutableListOf<ProxyConfig.WebSocket>()
        for (backend in backends) {
            for (ip in cloudflareIps) {
                val key = "$ip|${backend.host}|${backend.path}"
                if (key !in existing) {
                    generated.add(ProxyConfig.WebSocket(
                        connectHost = ip,
                        port = backend.port,
                        sni = backend.host,
                        host = backend.host,
                        path = backend.path,
                        uuid = backend.uuid,
                        name = "${backend.host.substringBefore('.')}-${ip.substringAfterLast('.')}"
                    ))
                }
            }
        }

        Log.i(TAG, "Grid: ${cloudflareIps.size} IPs × ${backends.size} backends = " +
                "${cloudflareIps.size * backends.size} total, ${generated.size} new")
        return generated
    }
}
