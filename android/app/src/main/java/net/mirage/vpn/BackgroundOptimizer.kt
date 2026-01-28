package net.mirage.vpn

import android.util.Log
import kotlinx.coroutines.*
import libv2ray.Libv2ray

/**
 * Background config optimizer that periodically tests alternative configs
 * and seamlessly switches to faster ones while connected.
 *
 * - Starts 30s after connection to let things settle
 * - Every 5 minutes: measures current config delay, tests 15 alternatives in batches of 3
 * - Cycles through all configs over ~2.5 hours
 * - Only switches if new config is >=500ms faster (avoids unnecessary churn)
 * - Battery-friendly: 3 TLS handshakes per batch is negligible
 * - Every ~30 min: re-fetches configs from remote endpoint
 */
class BackgroundOptimizer(
    private val xrayManager: XrayManager,
    private val configRepository: ConfigRepository? = null,
    private val remoteConfigUrl: String? = null,
    private val onSwitchConfig: suspend (VlessConfig) -> Unit
) {
    companion object {
        private const val TAG = "BackgroundOptimizer"
        private const val INITIAL_DELAY_MS = 30_000L          // 30s after connection
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes
        private const val CONFIGS_PER_CYCLE = 15               // Test 15 configs per cycle
        private const val BATCH_SIZE = 3                       // Test 3 at a time
        private const val SWITCH_THRESHOLD_MS = 500L           // Must be 500ms faster to switch
        private const val TEST_TIMEOUT_MS = 10_000L            // 10s timeout per test
        private const val REMOTE_REFRESH_CYCLES = 6            // Refresh remote every 6 cycles (~30 min)
    }

    private var optimizerJob: Job? = null
    private var configOffset = 0  // Track where we are in the config list
    private var cycleCount = 0

    /**
     * Start the background optimizer in the given scope.
     */
    fun start(scope: CoroutineScope) {
        stop()
        optimizerJob = scope.launch {
            Log.i(TAG, "Background optimizer starting (initial delay: ${INITIAL_DELAY_MS / 1000}s)")
            delay(INITIAL_DELAY_MS)

            while (isActive) {
                try {
                    // Periodically refresh configs from remote
                    cycleCount++
                    if (cycleCount % REMOTE_REFRESH_CYCLES == 0 && configRepository != null && remoteConfigUrl != null) {
                        try {
                            val newCount = configRepository.refreshFromRemote(remoteConfigUrl)
                            Log.i(TAG, "Remote config refresh: $newCount new configs")
                        } catch (e: Exception) {
                            Log.w(TAG, "Remote refresh failed: ${e.message}")
                        }
                    }

                    runOptimizationCycle()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Optimization cycle error: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
            Log.i(TAG, "Background optimizer stopped")
        }
    }

    /**
     * Stop the background optimizer.
     */
    fun stop() {
        optimizerJob?.cancel()
        optimizerJob = null
        configOffset = 0
        cycleCount = 0
    }

    /**
     * Run one optimization cycle: measure current, test alternatives, switch if faster.
     */
    private suspend fun runOptimizationCycle() = withContext(Dispatchers.IO) {
        val currentConfig = xrayManager.getWorkingConfig() ?: return@withContext
        Log.d(TAG, "Optimization cycle: current config = ${currentConfig.name}")

        // Measure current config delay
        val currentDelay = measureConfigDelay(currentConfig)
        if (currentDelay < 0) {
            Log.w(TAG, "Current config measurement failed, skipping cycle")
            return@withContext
        }
        Log.d(TAG, "Current delay: ${currentDelay}ms (${currentConfig.name})")

        // Get alternative configs to test (use repository if available)
        val allConfigs = configRepository?.getAllConfigs() ?: VlessConfigs.getPrioritizedConfigs()
        val candidates = getNextCandidates(allConfigs, currentConfig)
        if (candidates.isEmpty()) {
            Log.d(TAG, "No candidates to test this cycle")
            return@withContext
        }

        Log.d(TAG, "Testing ${candidates.size} alternative configs")

        // Test candidates in batches
        var bestConfig: VlessConfig? = null
        var bestDelay = currentDelay

        for (batch in candidates.chunked(BATCH_SIZE)) {
            val results = batch.map { config ->
                async {
                    val delay = measureConfigDelay(config)
                    Pair(config, delay)
                }
            }.awaitAll()

            for ((config, delay) in results) {
                if (delay > 0 && delay < bestDelay - SWITCH_THRESHOLD_MS) {
                    bestDelay = delay
                    bestConfig = config
                    Log.d(TAG, "Found faster config: ${config.name} (${delay}ms vs current ${currentDelay}ms)")
                }
            }
        }

        // Switch if we found something significantly faster
        if (bestConfig != null) {
            Log.i(TAG, "Found faster config: ${bestConfig.name} (${bestDelay}ms vs ${currentDelay}ms, saved ${currentDelay - bestDelay}ms)")
            onSwitchConfig(bestConfig)
        } else {
            Log.d(TAG, "No faster config found this cycle")
        }
    }

    /**
     * Get the next batch of candidate configs to test, cycling through all configs.
     */
    private fun getNextCandidates(allConfigs: List<VlessConfig>, currentConfig: VlessConfig): List<VlessConfig> {
        // Filter out the current config
        val filtered = allConfigs.filter { it.name != currentConfig.name }
        if (filtered.isEmpty()) return emptyList()

        // Wrap around if needed
        if (configOffset >= filtered.size) {
            configOffset = 0
        }

        val end = (configOffset + CONFIGS_PER_CYCLE).coerceAtMost(filtered.size)
        val candidates = filtered.subList(configOffset, end)
        configOffset = end

        return candidates
    }

    /**
     * Measure outbound delay for a config using Xray's measureOutboundDelay.
     * Returns delay in ms, or -1 on failure.
     */
    private suspend fun measureConfigDelay(config: VlessConfig): Long = withContext(Dispatchers.IO) {
        try {
            val result = withTimeoutOrNull(TEST_TIMEOUT_MS) {
                val xrayConfig = xrayManager.generateXrayConfigForTest(config)
                val testUrl = "https://www.google.com/generate_204"
                Libv2ray.measureOutboundDelay(xrayConfig, testUrl)
            }

            if (result != null && result > 0 && result < TEST_TIMEOUT_MS) {
                result
            } else {
                -1L
            }
        } catch (e: Exception) {
            Log.d(TAG, "Measure error for ${config.name}: ${e.message}")
            -1L
        }
    }
}
