package net.mirage.vpn

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences wrapper for protocol enable/disable toggles.
 * Controls which protocols the app will try during connection.
 */
class ProtocolPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "protocol_preferences"
        private const val KEY_VLESS_ENABLED = "vless_enabled"
        private const val KEY_VMESS_ENABLED = "vmess_enabled"
        private const val KEY_DNSTT_ENABLED = "dnstt_enabled"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var vlessEnabled: Boolean
        get() = prefs.getBoolean(KEY_VLESS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VLESS_ENABLED, value).apply()

    var vmessEnabled: Boolean
        get() = prefs.getBoolean(KEY_VMESS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VMESS_ENABLED, value).apply()

    var dnsttEnabled: Boolean
        get() = prefs.getBoolean(KEY_DNSTT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DNSTT_ENABLED, value).apply()

    /**
     * Returns the set of enabled Xray protocol names.
     * Maps UI checkboxes to protocol folder names:
     * - VLESS checkbox → "vless" (covers both WS+TLS and REALITY)
     * - VMess checkbox → "vmess", "trojan", "shadowsocks"
     */
    fun getEnabledXrayProtocols(): Set<String> {
        val protocols = mutableSetOf<String>()
        if (vlessEnabled) protocols.add("vless")
        if (vmessEnabled) {
            protocols.add("vmess")
            protocols.add("trojan")
            protocols.add("shadowsocks")
        }
        return protocols
    }
}
