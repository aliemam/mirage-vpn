package net.mirage.vpn

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Central repository for VLESS configs.
 *
 * Load order:
 * 1. Local file (internal storage configs.txt — persisted from last remote fetch)
 * 2. Remote fetch (Pi endpoint) — on success, saves to local file
 * 3. Bundled asset (app/src/main/assets/configs.txt — bootstrap for first install)
 * 4. Hardcoded VlessConfigs (emergency fallback)
 */
class ConfigRepository(private val context: Context) {

    companion object {
        private const val TAG = "ConfigRepository"
        private const val LOCAL_CONFIGS_FILE = "configs.txt"
    }

    // In-memory: uri -> VlessConfig
    private val configs = mutableMapOf<String, VlessConfig>()

    init {
        loadLocal()
        if (configs.isEmpty()) {
            loadBundledAsset()
        }
        if (configs.isEmpty()) {
            loadHardcodedFallback()
        }
        Log.i(TAG, "Initialized with ${configs.size} configs")
    }

    /**
     * Fetch configs from remote URL, merge into memory, save to local file.
     * Returns number of new configs added.
     */
    fun refreshFromRemote(url: String): Int {
        val fetched = RemoteConfigFetcher.fetch(url)
        if (fetched.isEmpty()) {
            Log.w(TAG, "Remote fetch returned 0 configs")
            return 0
        }

        var newCount = 0
        val allUris = mutableListOf<String>()

        // Collect existing URIs
        allUris.addAll(configs.keys)

        for ((uri, config) in fetched) {
            if (!configs.containsKey(uri)) {
                configs[uri] = config
                newCount++
            }
            if (uri !in allUris) {
                allUris.add(uri)
            }
        }

        // Save all URIs to local file (remote configs replace local file entirely)
        saveLocalFile(fetched.map { it.first })

        Log.i(TAG, "Remote refresh: ${fetched.size} fetched, $newCount new, ${configs.size} total")
        return newCount
    }

    /**
     * Get all configs currently in memory.
     */
    fun getAllConfigs(): List<VlessConfig> {
        return configs.values.toList()
    }

    /**
     * Get all config URIs currently in memory.
     */
    fun getAllUris(): List<String> {
        return configs.keys.toList()
    }

    /**
     * Get quick-probe configs: top 25 scored, padded with random if < 25.
     */
    fun getQuickProbeConfigs(scoreManager: ConfigScoreManager): List<VlessConfig> {
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
        val topConfigs = mutableListOf<VlessConfig>()
        val remaining = mutableListOf<VlessConfig>()

        for ((uri, config) in allEntries) {
            val id = RemoteConfigFetcher.configId(uri)
            if (id in topIds) {
                topConfigs.add(config)
            } else {
                remaining.add(config)
            }
        }

        // Pad with random configs if needed
        if (topConfigs.size < targetCount && remaining.isNotEmpty()) {
            val needed = targetCount - topConfigs.size
            topConfigs.addAll(remaining.shuffled().take(needed))
        }

        Log.d(TAG, "Quick probe: ${topConfigs.size} configs (${topIds.size} top-scored)")
        return topConfigs
    }

    /**
     * Get the config ID for a VlessConfig by finding its URI in the repository.
     * Falls back to generating from toVlessUri if not found.
     */
    fun getConfigId(config: VlessConfig): String {
        // Try to find the original URI in our map
        for ((uri, stored) in configs) {
            if (stored == config) {
                return RemoteConfigFetcher.configId(uri)
            }
        }
        // Fallback: generate URI and compute ID
        return RemoteConfigFetcher.configId(config)
    }

    /**
     * Get config count.
     */
    fun size(): Int = configs.size

    // ========== Private loading methods ==========

    private fun loadLocal() {
        try {
            val file = File(context.filesDir, LOCAL_CONFIGS_FILE)
            if (!file.exists()) {
                Log.d(TAG, "No local configs file")
                return
            }

            val lines = file.readLines()
            var count = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val config = RemoteConfigFetcher.parseVlessUri(trimmed)
                if (config != null) {
                    configs[trimmed] = config
                    count++
                }
            }
            Log.d(TAG, "Loaded $count configs from local file")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load local configs: ${e.message}")
        }
    }

    private fun loadBundledAsset() {
        try {
            val input = context.assets.open("configs.txt")
            val lines = input.bufferedReader().readLines()
            input.close()

            var count = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val config = RemoteConfigFetcher.parseVlessUri(trimmed)
                if (config != null) {
                    configs[trimmed] = config
                    count++
                }
            }
            Log.d(TAG, "Loaded $count configs from bundled asset")
        } catch (e: Exception) {
            Log.d(TAG, "No bundled configs.txt asset: ${e.message}")
        }
    }

    private fun loadHardcodedFallback() {
        Log.w(TAG, "Using hardcoded fallback configs")
        val quickProbe = VlessConfigs.getQuickProbeConfigs()
        for (config in quickProbe) {
            val uri = VlessConfigs.toVlessUri(config)
            configs[uri] = config
        }
        Log.d(TAG, "Loaded ${quickProbe.size} hardcoded fallback configs")
    }

    private fun saveLocalFile(uris: List<String>) {
        try {
            val file = File(context.filesDir, LOCAL_CONFIGS_FILE)
            file.writeText(uris.joinToString("\n"))
            Log.d(TAG, "Saved ${uris.size} URIs to local file")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save local configs: ${e.message}")
        }
    }
}
