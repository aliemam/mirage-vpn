package net.mirage.vpn

import android.util.Log

/**
 * Generates all IP × backend combinations for CDN-routed configs (ws, xhttp).
 *
 * Given existing configs that pair CDN IPs with backend servers,
 * this generates the full cross-product so every IP is tried with every backend.
 * Only applies to CDN transport configs — REALITY/VMess/Trojan/SS
 * connect directly to server IPs, so IP swapping doesn't apply.
 */
class GridConfigGenerator {

    companion object {
        private const val TAG = "GridConfigGenerator"
        private val CDN_TRANSPORTS = setOf("ws", "xhttp")
    }

    private data class CdnBackend(
        val host: String,
        val path: String,
        val uuid: String,
        val port: Int,
        val network: String,
        val fingerprint: String,
        val alpn: String,
        val mode: String
    )

    /**
     * Generate missing IP × backend combinations from existing CDN configs.
     * Returns only NEW configs that don't already exist.
     */
    fun generate(configs: List<ProxyConfig>): List<ProxyConfig.VlessTls> {
        val cdnConfigs = configs.filterIsInstance<ProxyConfig.VlessTls>()
            .filter { it.network in CDN_TRANSPORTS }
        if (cdnConfigs.size < 2) return emptyList()

        // CDN IP = appears with 2+ different backends (host values)
        val ipToHosts = cdnConfigs.groupBy { it.connectHost }
            .mapValues { (_, cfgs) -> cfgs.map { it.host }.distinct().size }
        val cdnIps = ipToHosts.filter { it.value >= 2 }.keys.toList()

        if (cdnIps.isEmpty()) {
            Log.d(TAG, "No CDN IPs detected (need IP used with 2+ backends)")
            return emptyList()
        }

        // Extract unique backends (only from configs that use CDN IPs)
        val backends = cdnConfigs
            .filter { it.connectHost in cdnIps }
            .map { CdnBackend(it.host, it.path, it.uuid, it.port, it.network, it.fingerprint, it.alpn, it.mode) }
            .distinct()

        // Build set of existing combos for fast dedup
        val existing = cdnConfigs
            .map { "${it.connectHost}|${it.host}|${it.path}" }
            .toSet()

        // Generate cross-product, skip existing
        val generated = mutableListOf<ProxyConfig.VlessTls>()
        for (backend in backends) {
            for (ip in cdnIps) {
                val key = "$ip|${backend.host}|${backend.path}"
                if (key !in existing) {
                    generated.add(ProxyConfig.VlessTls(
                        connectHost = ip,
                        port = backend.port,
                        sni = backend.host,
                        host = backend.host,
                        path = backend.path,
                        uuid = backend.uuid,
                        network = backend.network,
                        fingerprint = backend.fingerprint,
                        alpn = backend.alpn,
                        mode = backend.mode,
                        name = "${backend.host.substringBefore('.')}-${ip.substringAfterLast('.')}"
                    ))
                }
            }
        }

        Log.i(TAG, "Grid: ${cdnIps.size} IPs × ${backends.size} backends = " +
                "${cdnIps.size * backends.size} total, ${generated.size} new")
        return generated
    }
}
