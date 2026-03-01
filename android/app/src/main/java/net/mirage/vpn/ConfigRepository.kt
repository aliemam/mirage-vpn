package net.mirage.vpn

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Central repository for proxy configs across all protocols.
 *
 * Load order per protocol folder:
 * 1. Local cache (filesDir/protocols/{protocol}/configs.txt)
 * 2. Bundled asset (assets/protocols/{protocol}/configs.txt)
 * 3. Legacy fallback: flat assets/configs.txt (backwards compatibility)
 *
 * Remote fetch mirrors the folder structure:
 *   {baseUrl}/protocols/{protocol}/configs.txt
 */
class ConfigRepository(private val context: Context) {

    companion object {
        private const val TAG = "ConfigRepository"

        // Xray protocol folders (DNS is handled separately by DnsConfig)
        val XRAY_PROTOCOLS = listOf("vless", "vmess", "trojan", "shadowsocks")
    }

    // uri -> ProxyConfig (unified across all protocols)
    private val configs = mutableMapOf<String, ProxyConfig>()

    // Track which protocols have configs (for logging/debugging)
    private val availableProtocols = mutableSetOf<String>()

    init {
        loadAllLocal()
        if (configs.isEmpty()) {
            loadAllBundledAssets()
        }
        expandWithGridSearch()
        Log.i(TAG, "Initialized with ${configs.size} configs from protocols: $availableProtocols")
    }

    /**
     * Refresh configs from remote for all protocol folders.
     * URL pattern: {baseUrl}/protocols/{protocol}/configs.txt
     * Falls back to fetching baseUrl directly as flat file if per-protocol fetches are all empty.
     * Returns total number of new configs added.
     */
    fun refreshFromRemote(baseUrl: String): Int {
        if (baseUrl.isBlank()) return 0
        val normalizedBase = baseUrl.trimEnd('/')
        var totalNew = 0
        var totalFetched = 0

        for (protocol in XRAY_PROTOCOLS) {
            val url = "$normalizedBase/protocols/$protocol/configs.txt"
            try {
                val fetched = RemoteConfigFetcher.fetch(url)
                if (fetched.isEmpty()) continue

                totalFetched += fetched.size
                var newCount = 0
                for ((uri, config) in fetched) {
                    if (!configs.containsKey(uri)) {
                        configs[uri] = config
                        newCount++
                    }
                }

                saveProtocolConfigs(protocol, fetched.map { it.first })
                availableProtocols.add(protocol)
                totalNew += newCount

                Log.d(TAG, "Remote $protocol: ${fetched.size} fetched, $newCount new")
            } catch (e: Exception) {
                Log.w(TAG, "Remote fetch failed for $protocol: ${e.message}")
            }
        }

        // Fallback: if per-protocol fetches all returned empty, try base URL as flat file
        if (totalFetched == 0) {
            try {
                val fetched = RemoteConfigFetcher.fetch(normalizedBase)
                if (fetched.isNotEmpty()) {
                    Log.i(TAG, "Fallback: fetched ${fetched.size} configs from flat URL")
                    for ((uri, config) in fetched) {
                        if (!configs.containsKey(uri)) {
                            configs[uri] = config
                            totalNew++
                        }
                    }
                    // Save to appropriate protocol folders
                    distributeAndSave(fetched)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback flat fetch failed: ${e.message}")
            }
        }

        expandWithGridSearch()
        Log.i(TAG, "Remote refresh total: $totalNew new, ${configs.size} total")
        return totalNew
    }

    /**
     * Refresh DNS config from remote.
     * URL: {baseUrl}/protocols/dns/config.json
     */
    fun refreshDnsConfigFromRemote(context: Context, baseUrl: String): DnsConfig? {
        if (baseUrl.isBlank()) return null
        val normalizedBase = baseUrl.trimEnd('/')
        val url = "$normalizedBase/protocols/dns/config.json"

        return try {
            val json = RemoteConfigFetcher.fetchRaw(url) ?: return null
            val dnsConfig = DnsConfig.fromJson(json)
            DnsConfig.saveLocal(context, dnsConfig)
            Log.i(TAG, "Refreshed DNS config from remote: ${dnsConfig.domains.size} domains")
            dnsConfig
        } catch (e: Exception) {
            Log.w(TAG, "DNS config remote fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Get all configs currently in memory.
     */
    fun getAllConfigs(): List<ProxyConfig> = configs.values.toList()

    /**
     * Get all config URIs currently in memory.
     */
    fun getAllUris(): List<String> = configs.keys.toList()

    /**
     * Check if any Xray protocol configs are available.
     */
    fun hasXrayConfigs(): Boolean = configs.isNotEmpty()

    /**
     * Get set of protocol names that have configs loaded.
     */
    fun getAvailableProtocols(): Set<String> = availableProtocols.toSet()

    /**
     * Get quick-probe configs: top 25 scored, padded with shuffled new configs.
     */
    fun getQuickProbeConfigs(scoreManager: ConfigScoreManager): List<ProxyConfig> {
        val targetCount = 25
        val allEntries = configs.entries.toList()

        // Ensure all configs have score entries
        for ((uri, _) in allEntries) {
            val id = RemoteConfigFetcher.configId(uri)
            scoreManager.ensureEntry(id)
        }

        // Get top scored IDs
        val topIds = scoreManager.getTopScoredIds(targetCount).toSet()

        // Map top IDs back to configs
        val topConfigs = mutableListOf<ProxyConfig>()
        val remaining = mutableListOf<ProxyConfig>()

        for ((uri, config) in allEntries) {
            val id = RemoteConfigFetcher.configId(uri)
            if (id in topIds) {
                topConfigs.add(config)
            } else {
                remaining.add(config)
            }
        }

        // Pad with shuffled new configs for fair rotation
        if (topConfigs.size < targetCount && remaining.isNotEmpty()) {
            val needed = targetCount - topConfigs.size
            topConfigs.addAll(remaining.shuffled().take(needed))
        }

        Log.d(TAG, "Quick probe: ${topConfigs.size} configs (${topIds.size} top-scored)")
        return topConfigs
    }

    /**
     * Get the config ID for a ProxyConfig by finding its URI in the repository.
     * Falls back to generating from toUri if not found.
     */
    fun getConfigId(config: ProxyConfig): String {
        // Try to find the original URI in our map
        for ((uri, stored) in configs) {
            if (stored == config) {
                return RemoteConfigFetcher.configId(uri)
            }
        }
        // Fallback: generate URI from config and compute ID
        val uri = RemoteConfigFetcher.toUri(config)
        return RemoteConfigFetcher.configId(uri)
    }

    /**
     * Get config count.
     */
    fun size(): Int = configs.size

    // ========== Grid Search ==========

    private fun expandWithGridSearch() {
        val gridConfigs = GridConfigGenerator().generate(configs.values.toList())
        for (config in gridConfigs) {
            val uri = RemoteConfigFetcher.toUri(config)
            if (uri !in configs) {
                configs[uri] = config
                availableProtocols.add("vless")
            }
        }
    }

    // ========== Private loading methods ==========

    private fun loadAllLocal() {
        for (protocol in XRAY_PROTOCOLS) {
            loadLocalProtocol(protocol)
        }
    }

    private fun loadLocalProtocol(protocol: String) {
        try {
            val file = File(context.filesDir, "protocols/$protocol/configs.txt")
            if (!file.exists()) return

            val lines = file.readLines()
            var count = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val config = RemoteConfigFetcher.parseUri(trimmed)
                if (config != null) {
                    configs[trimmed] = config
                    count++
                }
            }
            if (count > 0) {
                availableProtocols.add(protocol)
                Log.d(TAG, "Loaded $count configs from local $protocol")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load local $protocol: ${e.message}")
        }
    }

    private fun loadAllBundledAssets() {
        for (protocol in XRAY_PROTOCOLS) {
            loadBundledProtocol(protocol)
        }

        // Legacy fallback: try flat configs.txt if no protocol folders found
        if (configs.isEmpty()) {
            loadLegacyBundledAsset()
        }
    }

    private fun loadBundledProtocol(protocol: String) {
        try {
            val input = context.assets.open("protocols/$protocol/configs.txt")
            val lines = input.bufferedReader().readLines()
            input.close()

            var count = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val config = RemoteConfigFetcher.parseUri(trimmed)
                if (config != null) {
                    configs[trimmed] = config
                    count++
                }
            }
            if (count > 0) {
                availableProtocols.add(protocol)
                Log.d(TAG, "Loaded $count configs from bundled $protocol")
            }
        } catch (e: Exception) {
            // Expected if folder doesn't exist for this protocol
        }
    }

    /** Backwards-compatible: load legacy flat configs.txt if no protocol folders found. */
    private fun loadLegacyBundledAsset() {
        try {
            val input = context.assets.open("configs.txt")
            val lines = input.bufferedReader().readLines()
            input.close()

            var count = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val config = RemoteConfigFetcher.parseUri(trimmed)
                if (config != null) {
                    configs[trimmed] = config
                    count++
                    // Detect protocol for availability tracking
                    val protocol = when (config) {
                        is ProxyConfig.VlessTls, is ProxyConfig.Reality -> "vless"
                        is ProxyConfig.VMess -> "vmess"
                        is ProxyConfig.Trojan -> "trojan"
                        is ProxyConfig.Shadowsocks -> "shadowsocks"
                    }
                    availableProtocols.add(protocol)
                }
            }
            if (count > 0) {
                Log.d(TAG, "Loaded $count configs from legacy bundled configs.txt")
            }
        } catch (e: Exception) {
            Log.d(TAG, "No legacy bundled configs.txt: ${e.message}")
        }
    }

    private fun saveProtocolConfigs(protocol: String, uris: List<String>) {
        try {
            val dir = File(context.filesDir, "protocols/$protocol")
            dir.mkdirs()
            File(dir, "configs.txt").writeText(uris.joinToString("\n"))
            Log.d(TAG, "Saved ${uris.size} URIs to $protocol folder")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save $protocol configs: ${e.message}")
        }
    }

    /** Distribute fetched configs into protocol-specific folders. */
    private fun distributeAndSave(fetched: List<Pair<String, ProxyConfig>>) {
        val buckets = mutableMapOf<String, MutableList<String>>()
        for ((uri, config) in fetched) {
            val protocol = when (config) {
                is ProxyConfig.VlessTls, is ProxyConfig.Reality -> "vless"
                is ProxyConfig.VMess -> "vmess"
                is ProxyConfig.Trojan -> "trojan"
                is ProxyConfig.Shadowsocks -> "shadowsocks"
            }
            buckets.getOrPut(protocol) { mutableListOf() }.add(uri)
            availableProtocols.add(protocol)
        }
        for ((protocol, uris) in buckets) {
            saveProtocolConfigs(protocol, uris)
        }
    }
}
