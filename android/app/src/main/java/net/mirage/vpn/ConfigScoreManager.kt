package net.mirage.vpn

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Manages scoring of VLESS configs based on success/failure history.
 * Scores are persisted in SharedPreferences as a JSON map.
 *
 * Scoring rules:
 * - Success: +10 + min(uptimeMinutes, 10)
 * - Failure: -1 (with 3-hour cooldown per config to avoid over-penalizing)
 * - New configs start at score 5
 */
class ConfigScoreManager(context: Context) {

    companion object {
        private const val TAG = "ConfigScoreManager"
        private const val PREFS_NAME = "config_scores"
        private const val KEY_SCORES_JSON = "scores_json"
        private const val DEFAULT_SCORE = 5
        private const val FAILURE_COOLDOWN_MS = 3 * 60 * 60 * 1000L // 3 hours
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache: configId -> ScoreEntry
    private val scores = mutableMapOf<String, ScoreEntry>()

    data class ScoreEntry(
        var score: Int = DEFAULT_SCORE,
        var successes: Int = 0,
        var failures: Int = 0,
        var lastUsedMs: Long = 0L,
        var lastFailureScoreUpdateMs: Long = 0L
    )

    init {
        loadFromPrefs()
    }

    /**
     * Record a successful connection for the given config.
     * Bonus: +10 + min(uptimeMinutes, 10)
     */
    fun recordSuccess(configId: String, uptimeMinutes: Int) {
        val entry = ensureEntry(configId)
        val bonus = 10 + uptimeMinutes.coerceAtMost(10)
        entry.score += bonus
        entry.successes++
        entry.lastUsedMs = System.currentTimeMillis()
        Log.d(TAG, "Success for $configId: +$bonus (score=${entry.score}, successes=${entry.successes})")
        saveToPrefs()
    }

    /**
     * Record a failure for the given config.
     * Penalty: -1, but only if >3h since last failure score update for this config.
     */
    fun recordFailure(configId: String) {
        val entry = ensureEntry(configId)
        val now = System.currentTimeMillis()
        entry.failures++
        entry.lastUsedMs = now

        if (now - entry.lastFailureScoreUpdateMs > FAILURE_COOLDOWN_MS) {
            entry.score -= 1
            entry.lastFailureScoreUpdateMs = now
            Log.d(TAG, "Failure for $configId: -1 (score=${entry.score}, failures=${entry.failures})")
        } else {
            Log.d(TAG, "Failure for $configId: cooldown active, no score change (score=${entry.score}, failures=${entry.failures})")
        }
        saveToPrefs()
    }

    /**
     * Get the top N config IDs sorted by score descending.
     */
    fun getTopScoredIds(count: Int): List<String> {
        return scores.entries
            .sortedByDescending { it.value.score }
            .take(count)
            .map { it.key }
    }

    /**
     * Get the score for a specific config ID, or null if not tracked.
     */
    fun getScore(configId: String): Int? {
        return scores[configId]?.score
    }

    /**
     * Ensure an entry exists for the given config ID.
     * If new, starts at DEFAULT_SCORE.
     */
    fun ensureEntry(configId: String, startScore: Int = DEFAULT_SCORE): ScoreEntry {
        return scores.getOrPut(configId) {
            ScoreEntry(score = startScore).also {
                Log.d(TAG, "New config entry: $configId (startScore=$startScore)")
            }
        }
    }

    private fun loadFromPrefs() {
        try {
            val json = prefs.getString(KEY_SCORES_JSON, null) ?: return
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val obj = root.getJSONObject(id)
                scores[id] = ScoreEntry(
                    score = obj.optInt("score", DEFAULT_SCORE),
                    successes = obj.optInt("successes", 0),
                    failures = obj.optInt("failures", 0),
                    lastUsedMs = obj.optLong("lastUsedMs", 0L),
                    lastFailureScoreUpdateMs = obj.optLong("lastFailureScoreUpdateMs", 0L)
                )
            }
            Log.d(TAG, "Loaded ${scores.size} score entries")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load scores: ${e.message}")
        }
    }

    private fun saveToPrefs() {
        try {
            val root = JSONObject()
            for ((id, entry) in scores) {
                root.put(id, JSONObject().apply {
                    put("score", entry.score)
                    put("successes", entry.successes)
                    put("failures", entry.failures)
                    put("lastUsedMs", entry.lastUsedMs)
                    put("lastFailureScoreUpdateMs", entry.lastFailureScoreUpdateMs)
                })
            }
            prefs.edit().putString(KEY_SCORES_JSON, root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save scores: ${e.message}")
        }
    }
}
