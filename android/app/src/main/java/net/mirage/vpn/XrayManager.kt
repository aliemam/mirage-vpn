package net.mirage.vpn

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages Xray-core for VLESS connections
 * Supports both WebSocket+TLS (Cloudflare) and REALITY protocols
 *
 * Features:
 * - Parallel config probing (race multiple configs concurrently)
 * - Config caching via SharedPreferences (skip probe if last config <24h old)
 * - Configurable timeouts (10s per test vs 30s default)
 */
class XrayManager(
    private val context: Context,
    private val socksPort: Int = 10808,
    private val scoreManager: ConfigScoreManager? = null,
    private val configRepository: ConfigRepository? = null
) {
    companion object {
        private const val TAG = "XrayManager"
        private const val PREFS_NAME = "xray_config_cache"
        private const val KEY_CACHED_CONFIG_JSON = "cached_config_json"
        private const val KEY_CACHED_CONFIG_TYPE = "cached_config_type"
        private const val KEY_CACHED_TIMESTAMP = "cached_timestamp"
        private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val TEST_TIMEOUT_MS = 10_000L
        private const val PARALLEL_BATCH_SIZE = 5
    }

    interface ProbeListener {
        fun onProbeProgress(current: Int, total: Int, currentConfig: String)
        fun onProbeSuccess(config: VlessConfig)
        fun onProbeFailed()
    }

    @Volatile
    private var workingConfig: VlessConfig? = null
    private var coreController: CoreController? = null
    private var probeListener: ProbeListener? = null
    private var initialized = false
    private var connectionStartMs: Long = 0L

    // For hot-swapping: secondary instance runs on alternate port
    private var secondaryCoreController: CoreController? = null
    private var currentPort: Int = socksPort

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun setProbeListener(listener: ProbeListener?) {
        probeListener = listener
    }

    private fun initXray() {
        if (initialized) return

        try {
            val assetsDir = context.filesDir.absolutePath
            Libv2ray.initCoreEnv(assetsDir, "")
            copyAssetsIfNeeded()
            initialized = true
            Log.i(TAG, "Xray initialized. Version: ${Libv2ray.checkVersionX()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Xray", e)
        }
    }

    private fun copyAssetsIfNeeded() {
        try {
            Log.d(TAG, "Geo files should be included in the library")
        } catch (e: Exception) {
            Log.w(TAG, "Error with geo files: ${e.message}")
        }
    }

    // ========== Config Caching ==========

    /**
     * Save a working config to SharedPreferences with timestamp.
     */
    private fun saveCachedConfig(config: VlessConfig) {
        try {
            val editor = prefs.edit()
            when (config) {
                is VlessConfig.WebSocket -> {
                    editor.putString(KEY_CACHED_CONFIG_TYPE, "websocket")
                    editor.putString(KEY_CACHED_CONFIG_JSON, JSONObject().apply {
                        put("connectHost", config.connectHost)
                        put("port", config.port)
                        put("sni", config.sni)
                        put("host", config.host)
                        put("path", config.path)
                        put("uuid", config.uuid)
                        put("name", config.name)
                    }.toString())
                }
                is VlessConfig.Reality -> {
                    editor.putString(KEY_CACHED_CONFIG_TYPE, "reality")
                    editor.putString(KEY_CACHED_CONFIG_JSON, JSONObject().apply {
                        put("connectHost", config.connectHost)
                        put("port", config.port)
                        put("sni", config.sni)
                        put("publicKey", config.publicKey)
                        put("shortId", config.shortId)
                        put("fingerprint", config.fingerprint)
                        put("flow", config.flow)
                        put("uuid", config.uuid)
                        put("name", config.name)
                    }.toString())
                }
            }
            editor.putLong(KEY_CACHED_TIMESTAMP, System.currentTimeMillis())
            editor.apply()
            Log.i(TAG, "Cached working config: ${config.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache config: ${e.message}")
        }
    }

    /**
     * Load cached config if it exists and is less than 24 hours old.
     */
    private fun loadCachedConfig(): VlessConfig? {
        try {
            val timestamp = prefs.getLong(KEY_CACHED_TIMESTAMP, 0)
            if (System.currentTimeMillis() - timestamp > CACHE_MAX_AGE_MS) {
                Log.d(TAG, "Cached config expired")
                return null
            }

            val type = prefs.getString(KEY_CACHED_CONFIG_TYPE, null) ?: return null
            val jsonStr = prefs.getString(KEY_CACHED_CONFIG_JSON, null) ?: return null
            val json = JSONObject(jsonStr)

            val config = when (type) {
                "websocket" -> VlessConfig.WebSocket(
                    connectHost = json.getString("connectHost"),
                    port = json.getInt("port"),
                    sni = json.getString("sni"),
                    host = json.getString("host"),
                    path = json.getString("path"),
                    uuid = json.getString("uuid"),
                    name = json.getString("name")
                )
                "reality" -> VlessConfig.Reality(
                    connectHost = json.getString("connectHost"),
                    port = json.getInt("port"),
                    sni = json.getString("sni"),
                    publicKey = json.getString("publicKey"),
                    shortId = json.getString("shortId"),
                    fingerprint = json.getString("fingerprint"),
                    flow = json.optString("flow", "xtls-rprx-vision"),
                    uuid = json.getString("uuid"),
                    name = json.getString("name")
                )
                else -> null
            }

            if (config != null) {
                Log.i(TAG, "Loaded cached config: ${config.name} (age: ${(System.currentTimeMillis() - timestamp) / 1000}s)")
            }
            return config
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached config: ${e.message}")
            return null
        }
    }

    // ========== Parallel Probing ==========

    /**
     * Test a single config with a 10s timeout (down from 30s default).
     * Returns the delay in ms if successful, or -1 on failure.
     */
    private suspend fun testConfigWithTimeout(config: VlessConfig, port: Int = socksPort): Long = withContext(Dispatchers.IO) {
        try {
            val result = withTimeoutOrNull(TEST_TIMEOUT_MS) {
                val xrayConfig = generateXrayConfig(config, port)
                val testUrl = "https://www.google.com/generate_204"
                Libv2ray.measureOutboundDelay(xrayConfig, testUrl)
            }

            if (result != null && result > 0 && result < TEST_TIMEOUT_MS) {
                Log.d(TAG, "  SUCCESS: ${config.name} - delay: ${result}ms")
                result
            } else {
                Log.d(TAG, "  FAILED: ${config.name} - ${if (result == null) "timeout" else "delay: ${result}ms"}")
                -1L
            }
        } catch (e: Exception) {
            Log.d(TAG, "  ERROR: ${config.name} - ${e.message}")
            -1L
        }
    }

    /**
     * Race a batch of configs in parallel. Returns the first successful one.
     * Cancels remaining tests once a winner is found.
     * Records success/failure scores when scoreManager is available.
     */
    private suspend fun raceConfigs(configs: List<VlessConfig>): VlessConfig? = coroutineScope {
        if (configs.isEmpty()) return@coroutineScope null

        val result = CompletableDeferred<VlessConfig?>()
        val testPort = (50000..60000).random() // Random high port to avoid conflicts
        val failedConfigs = mutableListOf<VlessConfig>()

        val jobs = configs.map { config ->
            async {
                val delay = testConfigWithTimeout(config, testPort)
                if (delay > 0) {
                    result.complete(config)
                    config
                } else {
                    synchronized(failedConfigs) { failedConfigs.add(config) }
                    null
                }
            }
        }

        // Wait for first success or all failures
        launch {
            jobs.forEach { it.await() }
            // All done, if nobody completed yet, complete with null
            result.complete(null)
        }

        val winner = result.await()
        // Cancel remaining jobs
        jobs.forEach { it.cancel() }

        // Record scores
        if (scoreManager != null) {
            if (winner != null) {
                val id = configRepository?.getConfigId(winner) ?: RemoteConfigFetcher.configId(winner)
                scoreManager.recordSuccess(id, 0)
            }
            for (failed in failedConfigs) {
                if (failed != winner) {
                    val id = configRepository?.getConfigId(failed) ?: RemoteConfigFetcher.configId(failed)
                    scoreManager.recordFailure(id)
                }
            }
        }

        winner
    }

    /**
     * Quick probe - try cached config first, then race all quick configs in parallel.
     * Total worst case: ~10s (vs 140s sequential).
     */
    suspend fun quickProbe(): Boolean = withContext(Dispatchers.IO) {
        initXray()

        // Step 1: Try cached config
        val cached = loadCachedConfig()
        if (cached != null) {
            Log.i(TAG, "Testing cached config: ${cached.name}")
            probeListener?.onProbeProgress(0, 1, "cached: ${cached.name}")

            val testPort = (50000..60000).random()
            val delay = testConfigWithTimeout(cached, testPort)
            if (delay > 0) {
                Log.i(TAG, "Cached config still works: ${cached.name} (${delay}ms)")
                workingConfig = cached
                probeListener?.onProbeSuccess(cached)
                return@withContext true
            }
            Log.i(TAG, "Cached config failed, trying quick probe")
        }

        // Step 2: Race all quick configs in parallel (use repository if available)
        val configs = if (configRepository != null && scoreManager != null) {
            configRepository.getQuickProbeConfigs(scoreManager)
        } else {
            VlessConfigs.getQuickProbeConfigs()
        }
        Log.i(TAG, "Quick probe: racing ${configs.size} configs in parallel")
        probeListener?.onProbeProgress(1, 2, "Testing configs...")

        val winner = raceConfigs(configs)
        if (winner != null) {
            Log.i(TAG, "Quick probe SUCCESS: ${winner.name}")
            workingConfig = winner
            saveCachedConfig(winner)
            probeListener?.onProbeSuccess(winner)
            return@withContext true
        }

        // Step 3: Fall back to full probe
        Log.w(TAG, "Quick probe failed, trying full probe")
        return@withContext probe()
    }

    /**
     * Full probe - process configs in batches of 5 in parallel.
     * Total worst case: ~120s (vs 1200s sequential). Max 60 configs.
     */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        initXray()

        val configs = configRepository?.getAllConfigs() ?: VlessConfigs.getPrioritizedConfigs()
        val total = configs.size.coerceAtMost(60)
        val batches = configs.take(total).chunked(PARALLEL_BATCH_SIZE)

        Log.i(TAG, "Starting full Xray probe: $total configs in ${batches.size} batches")

        for ((batchIndex, batch) in batches.withIndex()) {
            probeListener?.onProbeProgress(batchIndex + 1, batches.size, "Batch ${batchIndex + 1}/${batches.size}")

            val winner = raceConfigs(batch)
            if (winner != null) {
                Log.i(TAG, "Full probe SUCCESS in batch ${batchIndex + 1}: ${winner.name}")
                workingConfig = winner
                saveCachedConfig(winner)
                probeListener?.onProbeSuccess(winner)
                return@withContext true
            }
        }

        Log.e(TAG, "No working config found")
        probeListener?.onProbeFailed()
        return@withContext false
    }

    /**
     * Start Xray with the working config
     */
    fun start(): Boolean {
        val config = workingConfig
        if (config == null) {
            Log.e(TAG, "No working config. Run probe() first.")
            return false
        }

        if (coreController?.isRunning == true) {
            Log.w(TAG, "Xray already running")
            return true
        }

        try {
            initXray()

            Log.i(TAG, "Starting Xray with config: ${config.name}")

            val xrayConfig = generateXrayConfig(config)
            Log.d(TAG, "Xray config generated")

            val callbackHandler = object : CoreCallbackHandler {
                override fun onEmitStatus(l: Long, s: String?): Long {
                    Log.d(TAG, "Xray status: $s")
                    return 0
                }

                override fun shutdown(): Long {
                    Log.d(TAG, "Xray shutdown callback")
                    return 0
                }

                override fun startup(): Long {
                    Log.d(TAG, "Xray startup callback")
                    return 0
                }
            }

            coreController = Libv2ray.newCoreController(callbackHandler)
            coreController?.startLoop(xrayConfig, 0)

            connectionStartMs = System.currentTimeMillis()
            Log.i(TAG, "Xray started successfully on SOCKS port $socksPort")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Xray", e)
            return false
        }
    }

    /**
     * Stop Xray. Records uptime bonus score for the working config.
     * Also stops any secondary instance that may be running.
     */
    fun stop() {
        try {
            // Stop secondary first if running
            stopSecondary()

            // Record uptime bonus before stopping
            if (connectionStartMs > 0 && scoreManager != null && workingConfig != null) {
                val uptimeMin = ((System.currentTimeMillis() - connectionStartMs) / 60_000).toInt()
                if (uptimeMin > 0) {
                    val id = configRepository?.getConfigId(workingConfig!!) ?: RemoteConfigFetcher.configId(workingConfig!!)
                    scoreManager.recordSuccess(id, uptimeMin)
                    Log.d(TAG, "Recorded uptime bonus: ${uptimeMin}min for ${workingConfig!!.name}")
                }
                connectionStartMs = 0L
            }

            if (coreController?.isRunning == true) {
                Log.i(TAG, "Stopping Xray")
                coreController?.stopLoop()
            }
            coreController = null
            currentPort = socksPort // Reset to default port
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray", e)
        }
    }

    // ========== Public Methods for Background Optimizer ==========

    /**
     * Set the working config externally (used by BackgroundOptimizer for seamless switching).
     */
    fun setWorkingConfig(config: VlessConfig) {
        workingConfig = config
        saveCachedConfig(config)
    }

    /**
     * Generate an Xray JSON config for testing purposes (uses a random high port).
     * Safe to call while another Xray instance is running on the main port.
     */
    fun generateXrayConfigForTest(config: VlessConfig): String {
        val testPort = (50000..60000).random()
        return generateXrayConfig(config, testPort)
    }

    /**
     * Get the current active SOCKS port.
     */
    fun getCurrentPort(): Int = currentPort

    // ========== Hot-Swap Methods ==========

    /**
     * Start a secondary Xray instance on a different port for hot-swapping.
     * The primary instance keeps running until we verify the secondary works.
     * Returns the port the secondary is running on, or -1 on failure.
     */
    fun startSecondaryOnPort(config: VlessConfig, port: Int): Int {
        if (secondaryCoreController?.isRunning == true) {
            Log.w(TAG, "Secondary already running, stopping it first")
            stopSecondary()
        }

        try {
            initXray()

            Log.i(TAG, "Starting secondary Xray on port $port with config: ${config.name}")

            val xrayConfig = generateXrayConfig(config, port)

            val callbackHandler = object : CoreCallbackHandler {
                override fun onEmitStatus(l: Long, s: String?): Long {
                    Log.d(TAG, "Secondary Xray status: $s")
                    return 0
                }
                override fun shutdown(): Long = 0
                override fun startup(): Long = 0
            }

            secondaryCoreController = Libv2ray.newCoreController(callbackHandler)
            secondaryCoreController?.startLoop(xrayConfig, 0)

            Log.i(TAG, "Secondary Xray started on port $port")
            return port

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start secondary Xray", e)
            return -1
        }
    }

    /**
     * Promote the secondary Xray instance to primary after successful switch.
     * Stops the old primary and updates state.
     */
    fun promoteSecondary(newConfig: VlessConfig, newPort: Int) {
        Log.i(TAG, "Promoting secondary to primary (port $newPort)")

        // Stop old primary (no uptime bonus - we're switching, not disconnecting)
        connectionStartMs = 0L
        coreController?.stopLoop()

        // Promote secondary to primary
        coreController = secondaryCoreController
        secondaryCoreController = null
        workingConfig = newConfig
        currentPort = newPort
        connectionStartMs = System.currentTimeMillis()

        saveCachedConfig(newConfig)
        Log.i(TAG, "Secondary promoted. Now running ${newConfig.name} on port $newPort")
    }

    /**
     * Stop the secondary instance (e.g., if switch failed).
     */
    fun stopSecondary() {
        try {
            if (secondaryCoreController?.isRunning == true) {
                Log.i(TAG, "Stopping secondary Xray")
                secondaryCoreController?.stopLoop()
            }
            secondaryCoreController = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping secondary Xray", e)
        }
    }

    // ========== Config Generation ==========

    /**
     * Generate Xray JSON config from VlessConfig
     * Supports both WebSocket and REALITY protocols
     */
    private fun generateXrayConfig(config: VlessConfig, port: Int = socksPort): String {
        val json = JSONObject()

        // Inbounds - SOCKS5 proxy
        val inbounds = JSONArray()
        val socksInbound = JSONObject().apply {
            put("tag", "socks")
            put("port", port)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply {
                    put("http")
                    put("tls")
                })
            })
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
        }
        inbounds.put(socksInbound)
        json.put("inbounds", inbounds)

        // Outbounds - VLESS (WebSocket or REALITY)
        val outbounds = JSONArray()
        val vlessOutbound = when (config) {
            is VlessConfig.WebSocket -> generateWebSocketOutbound(config)
            is VlessConfig.Reality -> generateRealityOutbound(config)
        }
        outbounds.put(vlessOutbound)

        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        }
        outbounds.put(directOutbound)

        json.put("outbounds", outbounds)

        // Routing
        val routing = JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray())
        }
        json.put("routing", routing)

        return json.toString(2)
    }

    /**
     * Generate VLESS + WebSocket + TLS outbound
     */
    private fun generateWebSocketOutbound(config: VlessConfig.WebSocket): JSONObject {
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", config.connectHost)
                        put("port", config.port)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", config.uuid)
                                put("encryption", "none")
                            })
                        })
                    })
                })
            })
            put("streamSettings", JSONObject().apply {
                put("network", "ws")
                put("security", "tls")
                put("tlsSettings", JSONObject().apply {
                    put("serverName", config.sni)
                    put("allowInsecure", false)
                })
                put("wsSettings", JSONObject().apply {
                    put("path", config.path)
                    put("headers", JSONObject().apply {
                        put("Host", config.host)
                    })
                })
            })
        }
    }

    /**
     * Generate VLESS + REALITY outbound
     */
    private fun generateRealityOutbound(config: VlessConfig.Reality): JSONObject {
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", config.connectHost)
                        put("port", config.port)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", config.uuid)
                                put("encryption", "none")
                                put("flow", config.flow)
                            })
                        })
                    })
                })
            })
            put("streamSettings", JSONObject().apply {
                put("network", "tcp")
                put("security", "reality")
                put("realitySettings", JSONObject().apply {
                    put("serverName", config.sni)
                    put("fingerprint", config.fingerprint)
                    put("publicKey", config.publicKey)
                    put("shortId", config.shortId)
                    put("spiderX", "")
                })
            })
        }
    }

    fun getWorkingConfig(): VlessConfig? = workingConfig
    fun isRunning(): Boolean = coreController?.isRunning ?: false
    fun getSocksPort(): Int = socksPort
}
