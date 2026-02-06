package net.mirage.vpn

/**
 * Native interface for hev-socks5-tunnel
 * Note: Methods must be non-static for JNI RegisterNatives to work
 */
class TunnelNative {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        // Singleton instance
        private val instance = TunnelNative()

        fun startService(configPath: String, fd: Int) {
            instance.TProxyStartService(configPath, fd)
        }

        fun stopService() {
            instance.TProxyStopService()
        }

        fun getStats(): LongArray {
            return instance.TProxyGetStats()
        }
    }

    /**
     * Start the tunnel service with given config file and VPN file descriptor
     */
    external fun TProxyStartService(configPath: String, fd: Int)

    /**
     * Stop the tunnel service
     */
    external fun TProxyStopService()

    /**
     * Get tunnel statistics
     * @return array of [tx_packets, tx_bytes, rx_packets, rx_bytes]
     */
    external fun TProxyGetStats(): LongArray
}
