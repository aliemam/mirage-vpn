package net.mirage.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

class MirageVpnService : VpnService() {

    companion object {
        const val TAG = "MirageVPN"
        const val ACTION_CONNECT = "net.mirage.vpn.CONNECT"
        const val ACTION_DISCONNECT = "net.mirage.vpn.DISCONNECT"
        const val ACTION_CANCEL = "net.mirage.vpn.CANCEL"
        const val ACTION_START_HTTP_PROXY = "net.mirage.vpn.START_HTTP_PROXY"
        const val ACTION_STOP_HTTP_PROXY = "net.mirage.vpn.STOP_HTTP_PROXY"
        const val ACTION_STATUS_UPDATE = "net.mirage.vpn.STATUS_UPDATE"
        const val ACTION_PROBE_PROGRESS = "net.mirage.vpn.PROBE_PROGRESS"
        const val ACTION_HTTP_PROXY_STATUS = "net.mirage.vpn.HTTP_PROXY_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_IS_CONNECTING = "is_connecting"
        const val EXTRA_PROBE_CURRENT = "probe_current"
        const val EXTRA_PROBE_TOTAL = "probe_total"
        const val EXTRA_PROBE_SNI = "probe_sni"
        const val EXTRA_HTTP_PROXY_RUNNING = "http_proxy_running"
        const val EXTRA_HTTP_PROXY_ADDRESS = "http_proxy_address"

        private const val NOTIFICATION_CHANNEL_ID = "mirage_vpn_channel"
        private const val NOTIFICATION_ID = 1

        // Timeout constants
        private const val CONNECT_TIMEOUT_MS = 90_000L
        private const val DNSTT_CONNECT_TIMEOUT_MS = 60_000L
        private const val DNS_CONNECT_TIMEOUT_MS = 60_000L

        // Health monitor constants
        private const val HEALTH_CHECK_INTERVAL_MS = 15_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val BACKOFF_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000)
        private const val NETWORK_STABILIZE_DELAY_MS = 2_000L

        @Volatile
        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelProcess: Process? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var config: ServerConfig
    private var dnsConfig: DnsConfig? = null
    private var decoyJob: Job? = null
    private var dohProxy: DohProxy? = null
    private var xrayManager: XrayManager? = null
    private var activeTunnelMode: TunnelMode = TunnelMode.DNS
    private var activeSocksPort: Int = ServerConfig.DNS_LISTEN_PORT

    // Health monitor & reconnection state
    private var healthMonitorJob: Job? = null
    private var reconnectAttempts = 0
    private var isReconnecting = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Connection job (for cancellation support)
    private var connectJob: Job? = null

    // Background optimizer
    private var backgroundOptimizer: BackgroundOptimizer? = null

    // Dynamic config scoring and repository
    private var scoreManager: ConfigScoreManager? = null
    private var configRepository: ConfigRepository? = null

    // Protocol preferences
    private var protocolPreferences: ProtocolPreferences? = null

    // HTTP proxy for internet sharing
    private var httpProxyServer: HttpProxyServer? = null

    // dnstt state
    private var dnsttProcess: Process? = null
    private var activeDnsttConfig: DnsttConfig? = null

    // Popular domains for decoy DNS queries - makes traffic look normal
    // These are domains Iranians commonly visit (mostly Iranian sites)
    private val decoyDomains = listOf(
        // Google (commonly used in Iran)
        "google.com", "www.google.com", "gmail.com",
        // Popular Iranian websites
        "digikala.com", "www.digikala.com",      // E-commerce (like Amazon)
        "aparat.com", "www.aparat.com",          // Video sharing (like YouTube)
        "varzesh3.com", "www.varzesh3.com",      // Sports news
        "namnak.com", "www.namnak.com",          // Lifestyle
        "zoomit.ir", "www.zoomit.ir",            // Tech news
        "techrato.com", "www.techrato.com",      // Tech
        "tgju.org", "www.tgju.org",              // Currency/gold prices
        "irna.ir", "www.irna.ir",                // News agency
        "isna.ir", "www.isna.ir",                // Student news
        "tasnimnews.com", "www.tasnimnews.com",  // News
        "farsnews.ir", "www.farsnews.ir",        // News
        "khabaronline.ir", "www.khabaronline.ir",// News
        "tabnak.ir", "www.tabnak.ir",            // News
        "entekhab.ir", "www.entekhab.ir",        // News
        "rokna.net", "www.rokna.net",            // Entertainment
        "cinematicket.org",                      // Cinema tickets
        "snapp.ir", "www.snapp.ir",              // Taxi app
        "tapsi.ir", "www.tapsi.ir",              // Taxi app
        "divar.ir", "www.divar.ir",              // Classifieds
        "sheypoor.com", "www.sheypoor.com",      // Classifieds
        "torob.com", "www.torob.com",            // Price comparison
        "emalls.ir", "www.emalls.ir",            // Price comparison
        "softgozar.com", "www.softgozar.com",    // Software downloads
        "p30download.ir",                        // Software downloads
        "fa.wikipedia.org",                      // Persian Wikipedia
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        config = ServerConfig.load(this)
        dnsConfig = DnsConfig.load(this)
        scoreManager = ConfigScoreManager(this)
        configRepository = ConfigRepository(this)
        protocolPreferences = ProtocolPreferences(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.status_connecting)))
                connectJob = serviceScope.launch { connect() }
            }
            ACTION_DISCONNECT -> {
                serviceScope.launch { disconnect() }
            }
            ACTION_CANCEL -> {
                Log.i(TAG, "Connection cancelled by user")
                connectJob?.cancel()
                connectJob = null
            }
            ACTION_START_HTTP_PROXY -> {
                startHttpProxy()
            }
            ACTION_STOP_HTTP_PROXY -> {
                stopHttpProxy()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isRunning) {
            Log.i(TAG, "App swiped away while connected, restarting service")
            val restartIntent = Intent(this, MirageVpnService::class.java).apply {
                action = ACTION_CONNECT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
    }

    private suspend fun connect() {
        try {
            sendStatus(getString(R.string.status_connecting), false, isConnecting = true)

            // Apply protocol preferences to filter configs
            protocolPreferences?.let { prefs ->
                configRepository?.setEnabledProtocols(prefs.getEnabledXrayProtocols())
                configRepository?.setDnsttEnabled(prefs.dnsttEnabled)
            }

            // Overall connection timeout
            val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {

                // Auto-detect available modes based on loaded configs
                val hasXray = configRepository?.hasXrayConfigs() ?: false
                val hasDns = dnsConfig?.isAvailable ?: false
                val hasDnstt = configRepository?.hasDnsttConfigs() ?: false

                val modeCount = listOf(hasXray, hasDns, hasDnstt).count { it }

                when {
                    modeCount == 0 -> {
                        Log.e(TAG, "No configs available (no Xray, no DNS, no dnstt)")
                        false
                    }
                    modeCount == 1 -> {
                        // Single mode — just run it
                        when {
                            hasXray -> {
                                activeTunnelMode = TunnelMode.XRAY
                                activeSocksPort = ServerConfig.XRAY_SOCKS_PORT
                                connectXray()
                            }
                            hasDnstt -> {
                                activeTunnelMode = TunnelMode.DNSTT
                                activeSocksPort = ServerConfig.DNSTT_SOCKS_PORT
                                connectDnstt()
                            }
                            else -> {
                                activeTunnelMode = TunnelMode.DNS
                                activeSocksPort = dnsConfig?.listenPort ?: ServerConfig.DNS_LISTEN_PORT
                                connectDns()
                            }
                        }
                    }
                    else -> coroutineScope {
                        // Multiple modes available: race concurrently
                        Log.i(TAG, "Racing modes: xray=$hasXray dnstt=$hasDnstt dns=$hasDns")
                        val jobs = mutableListOf<Deferred<Pair<TunnelMode, Boolean>>>()

                        if (hasXray) jobs.add(async { TunnelMode.XRAY to connectXray() })
                        if (hasDnstt) jobs.add(async { TunnelMode.DNSTT to connectDnstt() })
                        if (hasDns) jobs.add(async { TunnelMode.DNS to connectDns() })

                        val results = jobs.map { it.await() }.toMap()

                        // Prefer XRAY > DNSTT > DNS among successes
                        val winner = listOf(TunnelMode.XRAY, TunnelMode.DNSTT, TunnelMode.DNS)
                            .firstOrNull { results[it] == true }

                        if (winner != null) {
                            Log.i(TAG, "Race winner: $winner")
                            activeTunnelMode = winner
                            activeSocksPort = when (winner) {
                                TunnelMode.XRAY -> ServerConfig.XRAY_SOCKS_PORT
                                TunnelMode.DNSTT -> ServerConfig.DNSTT_SOCKS_PORT
                                TunnelMode.DNS -> dnsConfig?.listenPort ?: ServerConfig.DNS_LISTEN_PORT
                            }
                            // Cleanup losers
                            if (winner != TunnelMode.XRAY && results.containsKey(TunnelMode.XRAY)) cleanupXray()
                            if (winner != TunnelMode.DNSTT && results.containsKey(TunnelMode.DNSTT)) cleanupDnstt()
                            if (winner != TunnelMode.DNS && results.containsKey(TunnelMode.DNS)) cleanupDns()
                            true
                        } else {
                            false
                        }
                    }
                }
            }

            if (connected != true) {
                val reason = if (connected == null) "Connection timed out" else getString(R.string.status_error_tunnel)
                Log.e(TAG, reason)
                sendStatus(reason, false)
                cleanupXray()
                cleanupDnstt()
                cleanupDns()
                stopSelf()
                return
            }

            // Establish VPN interface
            establishVpn()

            isRunning = true
            connectJob = null
            reconnectAttempts = 0
            isReconnecting = false
            sendStatus(getString(R.string.status_connected), true)
            updateNotification(getString(R.string.status_connected))

            // Start decoy DNS queries to make traffic look normal
            startDecoyDns()

            // Start health monitor
            startHealthMonitor()

            // Register network change callback
            registerNetworkCallback()

            // Start background optimizer for Xray connections (uses hot-swap for seamless switching)
            if (activeTunnelMode == TunnelMode.XRAY && xrayManager != null) {
                startBackgroundOptimizer()
            }

        } catch (e: CancellationException) {
            Log.i(TAG, "Connection cancelled")
            cleanupXray()
            cleanupDnstt()
            cleanupDns()
            sendStatus(getString(R.string.status_disconnected), false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            sendStatus("${getString(R.string.status_error)}: ${e.message}", false)
            disconnect()
        }
    }

    /**
     * Connect using Xray-core (VLESS, VMess, Trojan, Shadowsocks)
     */
    private suspend fun connectXray(): Boolean {
        Log.i(TAG, "Connecting via Xray (multi-protocol)...")

        // Refresh configs from remote before connecting
        try {
            configRepository?.refreshFromRemote(config.remoteConfigUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Remote config refresh failed (will use local/bundled): ${e.message}")
        }

        // Create XrayManager with scoring and repository
        xrayManager = XrayManager(this, ServerConfig.XRAY_SOCKS_PORT, scoreManager, configRepository)

        // Set up probe listener
        xrayManager?.setProbeListener(object : XrayManager.ProbeListener {
            override fun onProbeProgress(current: Int, total: Int, currentConfig: String) {
                sendProbeProgress(current, total, currentConfig)
            }

            override fun onProbeSuccess(config: ProxyConfig) {
                Log.i(TAG, "Xray probe found working config: ${config.name}")
            }

            override fun onProbeFailed() {
                Log.e(TAG, "Xray probe failed - no working configuration found")
            }
        })

        // Probe for working config
        sendStatus(getString(R.string.status_probing), false, isConnecting = true)
        val probeSuccess = xrayManager?.quickProbe() ?: false

        if (!probeSuccess) {
            sendStatus(getString(R.string.status_probe_failed), false, isConnecting = true)
            xrayManager = null
            return false
        }

        // Start Xray
        sendStatus(getString(R.string.status_connecting), false, isConnecting = true)
        val started = xrayManager?.start() ?: false

        if (!started) {
            sendStatus(getString(R.string.status_error_tunnel), false)
            xrayManager = null
            return false
        }

        // Wait a bit for SOCKS server to be ready
        delay(1000)

        // Verify SOCKS server is listening
        if (!isSocksAlive(ServerConfig.XRAY_SOCKS_PORT)) {
            Log.e(TAG, "Xray SOCKS server not responding")
            xrayManager?.stop()
            xrayManager = null
            return false
        }

        Log.i(TAG, "Xray connected successfully via ${xrayManager?.getWorkingConfig()?.name}")
        return true
    }

    /**
     * Connect using DNS tunneling (original method)
     */
    private suspend fun connectDns(): Boolean {
        val dns = dnsConfig ?: return false
        Log.i(TAG, "Connecting via DNS tunnel...")

        // Refresh DNS config from remote
        try {
            val updated = configRepository?.refreshDnsConfigFromRemote(this, config.remoteConfigUrl)
            if (updated != null) dnsConfig = updated
        } catch (e: Exception) {
            Log.w(TAG, "Remote DNS config refresh failed: ${e.message}")
        }

        val activeDns = dnsConfig ?: dns

        // Start DoH proxy if enabled (makes DNS traffic look like HTTPS)
        if (activeDns.useDoH) {
            Log.d(TAG, "Starting DoH proxy on port ${activeDns.dohPort}")
            dohProxy = DohProxy(activeDns.dohPort, activeDns.dohEndpoints)

            // Set up probe listener to report progress
            dohProxy?.setProbeListener(object : DohProxy.ProbeListener {
                override fun onProbeProgress(current: Int, total: Int, currentIp: String, currentSni: String, currentPort: Int) {
                    sendProbeProgress(current, total, currentSni)
                }

                override fun onProbeSuccess(config: DohProxy.WorkingConfig) {
                    Log.i(TAG, "DoH probe found working config: ${config.ip}:${config.port} SNI=${config.sni}")
                }

                override fun onProbeFailed() {
                    Log.e(TAG, "DoH probe failed - no working configuration found")
                }
            })

            // Probe for a working configuration first
            sendStatus(getString(R.string.status_probing), false, isConnecting = true)
            val probeSuccess = dohProxy?.probe() ?: false

            if (!probeSuccess) {
                sendStatus(getString(R.string.status_probe_failed), false, isConnecting = true)
                dohProxy?.stop()
                dohProxy = null
                return false
            }

            // Start the proxy with the working configuration
            dohProxy?.start()
            delay(500)
        }

        // Extract and prepare the tunnel binary
        val binaryPath = extractBinary()
        if (binaryPath == null) {
            sendStatus(getString(R.string.status_error_binary), false)
            dohProxy?.stop()
            return false
        }

        // Try connecting with domain failover, with overall timeout
        val result = withTimeoutOrNull(DNS_CONNECT_TIMEOUT_MS) {
            var connected = false
            var attempts = 0
            val maxAttempts = activeDns.domains.size

            while (!connected && attempts < maxAttempts) {
                val currentDomain = dnsConfig!!.domain
                Log.d(TAG, "Trying domain ${attempts + 1}/$maxAttempts: $currentDomain")
                sendStatus(getString(R.string.status_connecting), false, isConnecting = true)

                // Kill any existing tunnel process
                tunnelProcess?.destroy()
                tunnelProcess = null

                // Start the DNS tunnel client with current domain
                startTunnelClient(binaryPath)

                // Wait for tunnel to be ready
                delay(3000)

                // Check if tunnel is working
                if (isTunnelAlive()) {
                    connected = true
                    Log.d(TAG, "Successfully connected via $currentDomain")
                } else {
                    Log.w(TAG, "Failed to connect via $currentDomain, trying next...")
                    attempts++
                    if (dnsConfig!!.hasMoreDomains()) {
                        dnsConfig = dnsConfig!!.nextDomain()
                    }
                }
            }
            connected
        }

        if (result != true) {
            Log.e(TAG, if (result == null) "DNS connection timed out" else "All DNS domains failed")
            dohProxy?.stop()
            dohProxy = null
        }

        return result ?: false
    }

    private fun isSocksAlive(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun extractBinary(): String? {
        // First, check bundled native library (extracted from jniLibs)
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val bundledBinary = File(nativeLibDir, "libslipstream.so")
        if (bundledBinary.exists()) {
            Log.d(TAG, "Found bundled slipstream at: ${bundledBinary.absolutePath}")
            // Try to run directly from native lib dir first (it should be executable)
            return bundledBinary.absolutePath
        }

        // Fallback: Check Termux paths
        val termuxPaths = listOf(
            "/data/data/com.termux/files/usr/bin/slipstream-client",
            "/data/data/com.termux/files/home/slipstream-client"
        )

        for (path in termuxPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                Log.d(TAG, "Found slipstream-client in Termux at: $path")
                return path
            }
        }

        Log.e(TAG, "slipstream-client binary not found")
        return null
    }

    private fun startTunnelClient(binaryPath: String) {
        val dns = dnsConfig ?: return
        // Build command for slipstream client
        // slipstream-client --resolver <ip> --domain <domain> --tcp-listen-port <port>
        val cmd = mutableListOf(
            binaryPath,
            "--domain", dns.domain,
            "--tcp-listen-port", dns.listenPort.toString()
        )

        // When DoH is enabled, route through local DoH proxy
        // Otherwise use direct resolvers
        if (dns.useDoH) {
            // Use local DoH proxy - traffic will look like HTTPS
            cmd.add("--resolver")
            cmd.add("127.0.0.1:${dns.dohPort}")
            Log.d(TAG, "Using DoH proxy at 127.0.0.1:${dns.dohPort}")
        } else {
            // Add all direct resolvers (standard DNS on port 53)
            for (resolver in dns.resolvers) {
                cmd.add("--resolver")
                cmd.add(resolver)
            }
        }

        Log.d(TAG, "Starting tunnel: ${cmd.joinToString(" ")}")

        val processBuilder = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .directory(filesDir)

        tunnelProcess = processBuilder.start()

        // Log output in background
        serviceScope.launch {
            try {
                tunnelProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    Log.d(TAG, "Tunnel: $line")
                }
            } catch (e: Exception) {
                // Expected when process is destroyed during disconnect
                Log.d(TAG, "Tunnel logging stopped")
            }
        }
    }

    private fun isTunnelAlive(): Boolean {
        val port = dnsConfig?.listenPort ?: ServerConfig.DNS_LISTEN_PORT
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clean up Xray resources (used when DNS wins the race or during mode switch).
     */
    private fun cleanupXray() {
        xrayManager?.stop()
        xrayManager = null
    }

    /**
     * Clean up DNS resources (used when Xray wins the race or during mode switch).
     */
    private fun cleanupDns() {
        tunnelProcess?.destroy()
        tunnelProcess = null
        dohProxy?.stop()
        dohProxy = null
    }

    /**
     * Connect using dnstt DNS tunneling.
     * dnstt-client creates a TCP tunnel through DNS queries.
     * Chain: tun2socks → dnstt-client (local SOCKS) → DNS tunnel → dnstt-server → microsocks → internet
     */
    private suspend fun connectDnstt(): Boolean {
        Log.i(TAG, "Connecting via dnstt...")

        // Refresh dnstt configs from remote
        try {
            configRepository?.refreshDnsttFromRemote(config.remoteConfigUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Remote dnstt config refresh failed (will use local/bundled): ${e.message}")
        }

        val configs = configRepository?.getQuickProbeDnsttConfigs(scoreManager!!) ?: emptyList()
        if (configs.isEmpty()) {
            Log.e(TAG, "No dnstt configs available")
            return false
        }

        // Extract dnstt binary
        val binaryPath = extractDnsttBinary()
        if (binaryPath == null) {
            Log.e(TAG, "dnstt binary not found")
            return false
        }

        // Try configs in order (scored) with overall timeout
        sendStatus(getString(R.string.status_probing), false, isConnecting = true)
        val result = withTimeoutOrNull(DNSTT_CONNECT_TIMEOUT_MS) {
            for ((index, dnsttConfig) in configs.withIndex()) {
                sendProbeProgress(index + 1, configs.size, dnsttConfig.name)
                Log.d(TAG, "Trying dnstt config ${index + 1}/${configs.size}: ${dnsttConfig.name}")

                // Kill any existing dnstt process
                dnsttProcess?.destroy()
                dnsttProcess = null

                startDnsttClient(binaryPath, dnsttConfig)

                // Wait for tunnel to be ready
                delay(3000)

                if (isSocksAlive(ServerConfig.DNSTT_SOCKS_PORT)) {
                    activeDnsttConfig = dnsttConfig
                    scoreManager?.recordSuccess(DnsttConfig.configId(dnsttConfig), 0)
                    Log.i(TAG, "dnstt connected via ${dnsttConfig.name}")
                    return@withTimeoutOrNull true
                } else {
                    Log.w(TAG, "dnstt config ${dnsttConfig.name} failed, trying next...")
                    scoreManager?.recordFailure(DnsttConfig.configId(dnsttConfig))
                    dnsttProcess?.destroy()
                    dnsttProcess = null
                }
            }
            false
        }

        if (result != true) {
            Log.e(TAG, if (result == null) "dnstt connection timed out" else "All dnstt configs failed")
        }
        return result ?: false
    }

    /**
     * Extract dnstt binary path from native libraries.
     */
    private fun extractDnsttBinary(): String? {
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val binary = File(nativeLibDir, "libdnstt.so")
        if (binary.exists()) {
            Log.d(TAG, "Found dnstt binary at: ${binary.absolutePath}")
            return binary.absolutePath
        }
        Log.e(TAG, "dnstt binary not found in $nativeLibDir")
        return null
    }

    /**
     * Start dnstt-client process.
     *
     * dnstt-client usage:
     *   dnstt-client [-udp ADDR|-doh URL|-dot ADDR] -pubkey HEX DOMAIN LOCAL_ADDR
     */
    private fun startDnsttClient(binaryPath: String, config: DnsttConfig) {
        val cmd = mutableListOf(binaryPath)

        // Transport flag
        when (config.transport) {
            "doh" -> {
                cmd.add("-doh")
                cmd.add(config.resolver)
            }
            "dot" -> {
                cmd.add("-dot")
                cmd.add(config.resolver)
            }
            else -> {
                // Default: UDP
                cmd.add("-udp")
                val resolver = if (config.resolver.contains(":")) config.resolver
                               else "${config.resolver}:53"
                cmd.add(resolver)
            }
        }

        cmd.add("-pubkey")
        cmd.add(config.pubkey)
        cmd.add(config.nsDomain)
        cmd.add("127.0.0.1:${ServerConfig.DNSTT_SOCKS_PORT}")

        Log.d(TAG, "Starting dnstt-client: ${cmd.joinToString(" ")}")

        val processBuilder = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .directory(filesDir)

        dnsttProcess = processBuilder.start()

        // Log output in background
        serviceScope.launch {
            try {
                dnsttProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    Log.d(TAG, "dnstt: $line")
                }
            } catch (e: Exception) {
                Log.d(TAG, "dnstt logging stopped")
            }
        }
    }

    /**
     * Clean up dnstt resources.
     */
    private fun cleanupDnstt() {
        dnsttProcess?.destroy()
        dnsttProcess = null
        activeDnsttConfig = null
    }

    private fun establishVpn() {
        val builder = Builder()
            .setSession(config.displayName)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .setMtu(1500)
            .setBlocking(true)

        // Don't route tunnel traffic through VPN (prevent loop)
        builder.addDisallowedApplication(packageName)

        vpnInterface = builder.establish()
            ?: throw Exception("Failed to establish VPN interface")

        // Start tun2socks to route VPN traffic to SOCKS proxy
        startTun2Socks()
    }

    private fun startTun2Socks(port: Int = activeSocksPort) {
        vpnInterface?.let { vpn ->
            val fd = vpn.fd
            Log.d(TAG, "VPN interface established with fd: $fd")
            Log.d(TAG, "Using SOCKS5 port: $port (mode: $activeTunnelMode)")

            // Create configuration file for hev-socks5-tunnel
            val configFile = File(filesDir, "tun2socks.yml")
            val configContent = """
                misc:
                  task-stack-size: 81920

                tunnel:
                  mtu: 8500

                socks5:
                  port: $port
                  address: '127.0.0.1'
                  udp: 'udp'
            """.trimIndent()

            try {
                FileWriter(configFile).use { it.write(configContent) }
                Log.d(TAG, "Created tun2socks config at: ${configFile.absolutePath}")
                Log.d(TAG, "Starting tun2socks with SOCKS5 proxy at 127.0.0.1:$port")

                // Start the native tunnel
                TunnelNative.startService(configFile.absolutePath, fd)
                Log.d(TAG, "tun2socks started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tun2socks", e)
            }
        }
    }

    /**
     * Restart tun2socks with a new SOCKS port (used during hot-swap).
     */
    private fun restartTun2Socks(newPort: Int) {
        try {
            Log.d(TAG, "Restarting tun2socks with new port: $newPort")
            TunnelNative.stopService()
            startTun2Socks(newPort)
            activeSocksPort = newPort
            // Update HTTP proxy upstream if running
            httpProxyServer?.updateUpstreamPort(newPort)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart tun2socks", e)
        }
    }

    // ========== Health Monitor & Auto-Reconnection ==========

    /**
     * Start health monitor coroutine that checks connection every 15 seconds.
     */
    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = serviceScope.launch {
            Log.i(TAG, "Health monitor started")
            while (isActive && isRunning) {
                delay(HEALTH_CHECK_INTERVAL_MS)

                if (!isRunning || isReconnecting) continue

                val healthy = checkHealth()
                if (healthy) {
                    // Reset reconnect counter on successful health check
                    if (reconnectAttempts > 0) {
                        Log.i(TAG, "Connection healthy again, resetting reconnect counter")
                        reconnectAttempts = 0
                    }
                } else {
                    Log.w(TAG, "Health check failed, triggering reconnect")
                    handleConnectionDrop()
                }
            }
            Log.i(TAG, "Health monitor stopped")
        }
    }

    /**
     * Check connection health: SOCKS port alive + xray still running.
     */
    private fun checkHealth(): Boolean {
        return when (activeTunnelMode) {
            TunnelMode.XRAY -> {
                val socksAlive = isSocksAlive(activeSocksPort)
                val xrayRunning = xrayManager?.isRunning() ?: false
                if (!socksAlive) Log.w(TAG, "Health: SOCKS port $activeSocksPort not responding")
                if (!xrayRunning) Log.w(TAG, "Health: Xray not running")
                socksAlive && xrayRunning
            }
            TunnelMode.DNS -> isTunnelAlive()
            TunnelMode.DNSTT -> {
                val socksAlive = isSocksAlive(ServerConfig.DNSTT_SOCKS_PORT)
                val processAlive = dnsttProcess?.isAlive ?: false
                if (!socksAlive) Log.w(TAG, "Health: dnstt SOCKS port not responding")
                if (!processAlive) Log.w(TAG, "Health: dnstt process not alive")
                socksAlive && processAlive
            }
        }
    }

    /**
     * Handle connection drop with exponential backoff reconnection.
     * Tries to recover with the current mode first, then switches to the other mode.
     */
    private suspend fun handleConnectionDrop() {
        if (isReconnecting) return
        isReconnecting = true

        try {
            while (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && isRunning) {
                val backoffDelay = BACKOFF_DELAYS_MS[reconnectAttempts.coerceAtMost(BACKOFF_DELAYS_MS.size - 1)]
                reconnectAttempts++

                Log.i(TAG, "Reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS (backoff: ${backoffDelay}ms)")
                sendStatus(getString(R.string.status_reconnecting), true)
                updateNotification(getString(R.string.status_reconnecting))

                delay(backoffDelay)

                if (!isRunning) break

                if (activeTunnelMode == TunnelMode.XRAY && xrayManager != null) {
                    // Attempt 1: Restart Xray with same config (fastest)
                    Log.i(TAG, "Trying to restart Xray with same config...")
                    xrayManager?.stop()
                    delay(300)
                    val restarted = xrayManager?.start() ?: false
                    if (restarted) {
                        delay(1000)
                        if (isSocksAlive(activeSocksPort)) {
                            Log.i(TAG, "Reconnected by restarting Xray")
                            reconnectAttempts = 0
                            sendStatus(getString(R.string.status_connected), true)
                            updateNotification(getString(R.string.status_connected))
                            return
                        }
                    }

                    // Attempt 2: Quick probe for new Xray config
                    Log.i(TAG, "Restart failed, trying quick probe...")
                    val probeSuccess = xrayManager?.quickProbe() ?: false
                    if (probeSuccess) {
                        xrayManager?.stop()
                        delay(300)
                        val started = xrayManager?.start() ?: false
                        if (started) {
                            delay(1000)
                            if (isSocksAlive(activeSocksPort)) {
                                Log.i(TAG, "Reconnected with new config: ${xrayManager?.getWorkingConfig()?.name}")
                                reconnectAttempts = 0
                                sendStatus(getString(R.string.status_connected), true)
                                updateNotification(getString(R.string.status_connected))
                                return
                            }
                        }
                    }
                }

                if (activeTunnelMode == TunnelMode.DNSTT) {
                    // dnstt reconnect: restart process, try next config
                    Log.i(TAG, "Trying to restart dnstt tunnel...")
                    cleanupDnstt()
                    delay(300)
                    val dnsttOk = connectDnstt()
                    if (dnsttOk) {
                        restartTun2Socks(activeSocksPort)
                        Log.i(TAG, "Reconnected via dnstt tunnel")
                        reconnectAttempts = 0
                        sendStatus(getString(R.string.status_connected), true)
                        updateNotification(getString(R.string.status_connected))
                        return
                    }
                }

                if (activeTunnelMode == TunnelMode.DNS) {
                    // DNS reconnect: try restarting tunnel with current domain
                    Log.i(TAG, "Trying to restart DNS tunnel...")
                    cleanupDns()
                    delay(300)
                    val dnsOk = connectDns()
                    if (dnsOk) {
                        restartTun2Socks(activeSocksPort)
                        Log.i(TAG, "Reconnected via DNS tunnel")
                        reconnectAttempts = 0
                        sendStatus(getString(R.string.status_connected), true)
                        updateNotification(getString(R.string.status_connected))
                        return
                    }
                }
            }

            if (!isRunning) return

            // All same-mode attempts exhausted — try alternative modes
            Log.i(TAG, "Same-mode reconnection exhausted, trying alternative modes...")

            // Build list of alternative modes to try (priority: XRAY > DNSTT > DNS)
            val alternatives = mutableListOf<Pair<TunnelMode, suspend () -> Boolean>>()
            if (activeTunnelMode != TunnelMode.XRAY && configRepository?.hasXrayConfigs() == true) {
                alternatives.add(TunnelMode.XRAY to suspend { connectXray() })
            }
            if (activeTunnelMode != TunnelMode.DNSTT && configRepository?.hasDnsttConfigs() == true) {
                alternatives.add(TunnelMode.DNSTT to suspend { connectDnstt() })
            }
            if (activeTunnelMode != TunnelMode.DNS && dnsConfig?.isAvailable == true) {
                alternatives.add(TunnelMode.DNS to suspend { connectDns() })
            }

            for ((mode, connector) in alternatives) {
                Log.i(TAG, "Trying alternative mode: $mode")
                // Cleanup current mode
                cleanupXray()
                cleanupDnstt()
                cleanupDns()

                val ok = connector()
                if (ok) {
                    restartTun2Socks(activeSocksPort)
                    reconnectAttempts = 0
                    sendStatus(getString(R.string.status_connected), true)
                    updateNotification(getString(R.string.status_connected))
                    return
                }
            }

            // Everything failed
            Log.e(TAG, "All reconnect attempts failed (all modes)")
            sendStatus(getString(R.string.status_connection_lost), true)
            updateNotification(getString(R.string.status_connection_lost))
        } finally {
            isReconnecting = false
        }
    }

    // ========== Network Change Detection ==========

    /**
     * Register for network connectivity changes.
     */
    private fun registerNetworkCallback() {
        try {
            val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network available, checking health after stabilization")
                    serviceScope.launch {
                        delay(NETWORK_STABILIZE_DELAY_MS)
                        if (!isRunning) return@launch
                        reconnectAttempts = 0 // Reset backoff on network change
                        val healthy = checkHealth()
                        if (!healthy) {
                            Log.i(TAG, "Network changed but connection unhealthy, reconnecting")
                            handleConnectionDrop()
                        } else {
                            Log.i(TAG, "Network changed, connection still healthy")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Network lost")
                    if (isRunning) {
                        sendStatus(getString(R.string.status_waiting_network), true)
                        updateNotification(getString(R.string.status_waiting_network))
                    }
                }
            }

            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.i(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Unregister network callback.
     */
    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { callback ->
                val connectivityManager = getSystemService(ConnectivityManager::class.java)
                connectivityManager?.unregisterNetworkCallback(callback)
                Log.i(TAG, "Network callback unregistered")
            }
            networkCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback", e)
        }
    }

    // ========== Background Optimizer ==========

    /**
     * Start the background optimizer to periodically test and switch to faster configs.
     */
    private fun startBackgroundOptimizer() {
        val manager = xrayManager ?: return
        backgroundOptimizer = BackgroundOptimizer(
            xrayManager = manager,
            configRepository = configRepository,
            remoteConfigUrl = config.remoteConfigUrl,
            onSwitchConfig = { newConfig -> switchToConfig(newConfig) }
        )
        backgroundOptimizer?.start(serviceScope)
    }

    /**
     * Seamlessly switch to a faster config using hot-swap.
     * Starts new Xray instance on alternate port, then switches tun2socks.
     * This avoids any connection gap - traffic flows through old config until tun2socks switches.
     */
    private suspend fun switchToConfig(newConfig: ProxyConfig) {
        if (!isRunning || activeTunnelMode != TunnelMode.XRAY) return

        val manager = xrayManager ?: return
        Log.i(TAG, "Hot-swap: switching to ${newConfig.name}")

        try {
            // Pick alternate port (toggle between primary and primary+1)
            val currentPort = manager.getCurrentPort()
            val alternatePort = if (currentPort == ServerConfig.XRAY_SOCKS_PORT) {
                ServerConfig.XRAY_SOCKS_PORT + 1
            } else {
                ServerConfig.XRAY_SOCKS_PORT
            }

            // Step 1: Start secondary Xray on alternate port
            val startedPort = manager.startSecondaryOnPort(newConfig, alternatePort)
            if (startedPort < 0) {
                Log.w(TAG, "Hot-swap: failed to start secondary Xray")
                manager.stopSecondary()
                return
            }

            // Step 2: Wait for secondary to be ready
            delay(1000)

            // Step 3: Verify SOCKS works on alternate port
            if (!isSocksAlive(alternatePort)) {
                Log.w(TAG, "Hot-swap: secondary SOCKS not responding on port $alternatePort")
                manager.stopSecondary()
                return
            }

            Log.i(TAG, "Hot-swap: secondary ready on port $alternatePort, switching tun2socks")

            // Step 4: Restart tun2socks with new port (brief <100ms interruption)
            restartTun2Socks(alternatePort)

            // Step 5: Promote secondary to primary (stops old Xray)
            manager.promoteSecondary(newConfig, alternatePort)

            Log.i(TAG, "Hot-swap: successfully switched to ${newConfig.name} on port $alternatePort")

        } catch (e: Exception) {
            Log.e(TAG, "Hot-swap error", e)
            xrayManager?.stopSecondary()
        }
    }

    // ========== HTTP Proxy (Internet Sharing) ==========

    private fun startHttpProxy() {
        if (!isRunning) {
            sendHttpProxyStatus(false, null)
            return
        }

        val proxy = HttpProxyServer(activeSocksPort)
        val port = proxy.start()
        if (port < 0) {
            Log.e(TAG, "Failed to start HTTP proxy")
            sendHttpProxyStatus(false, null)
            return
        }

        httpProxyServer = proxy
        val wifiIp = getWifiIpAddress()
        val address = if (wifiIp != null) "$wifiIp:$port" else "127.0.0.1:$port"
        Log.i(TAG, "HTTP proxy started at $address")
        sendHttpProxyStatus(true, address)
    }

    private fun stopHttpProxy() {
        httpProxyServer?.stop()
        httpProxyServer = null
        sendHttpProxyStatus(false, null)
    }

    private fun sendHttpProxyStatus(running: Boolean, address: String?) {
        val intent = Intent(ACTION_HTTP_PROXY_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_HTTP_PROXY_RUNNING, running)
            if (address != null) putExtra(EXTRA_HTTP_PROXY_ADDRESS, address)
        }
        sendBroadcast(intent)
    }

    private fun getWifiIpAddress(): String? {
        return try {
            val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return null
            val network = connectivityManager.activeNetwork ?: return null
            val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
            linkProperties.linkAddresses
                .map { it.address }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get WiFi IP: ${e.message}")
            null
        }
    }

    // ========== Disconnect ==========

    private suspend fun disconnect() {
        isRunning = false

        // Cancel any in-progress connection
        connectJob?.cancel()
        connectJob = null

        // Stop HTTP proxy
        httpProxyServer?.stop()
        httpProxyServer = null

        // Stop health monitor and optimizer
        healthMonitorJob?.cancel()
        healthMonitorJob = null
        backgroundOptimizer?.stop()
        backgroundOptimizer = null
        unregisterNetworkCallback()

        decoyJob?.cancel()
        sendStatus(getString(R.string.status_disconnecting), false)

        withContext(Dispatchers.IO) {
            // Stop the native tun2socks
            try {
                TunnelNative.stopService()
                Log.d(TAG, "tun2socks stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping tun2socks", e)
            }

            // Stop Xray manager
            xrayManager?.stop()
            xrayManager = null

            // Stop dnstt
            dnsttProcess?.destroy()
            dnsttProcess = null
            activeDnsttConfig = null

            tunnelProcess?.destroy()
            tunnelProcess = null

            // Stop DoH proxy
            dohProxy?.stop()
            dohProxy = null

            vpnInterface?.close()
            vpnInterface = null
        }

        sendStatus(getString(R.string.status_disconnected), false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendStatus(status: String, connected: Boolean, isConnecting: Boolean = false) {
        Log.d(TAG, "Sending status: $status, connected: $connected, isConnecting: $isConnecting")
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_CONNECTED, connected)
            putExtra(EXTRA_IS_CONNECTING, isConnecting)
        }
        sendBroadcast(intent)
    }

    private fun sendProbeProgress(current: Int, total: Int, currentSni: String) {
        val intent = Intent(ACTION_PROBE_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_PROBE_CURRENT, current)
            putExtra(EXTRA_PROBE_TOTAL, total)
            putExtra(EXTRA_PROBE_SNI, currentSni)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(config.displayName)
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(status))
    }

    override fun onDestroy() {
        connectJob?.cancel()
        connectJob = null
        httpProxyServer?.stop()
        httpProxyServer = null
        healthMonitorJob?.cancel()
        backgroundOptimizer?.stop()
        backgroundOptimizer = null
        unregisterNetworkCallback()
        decoyJob?.cancel()
        serviceScope.cancel()
        try {
            TunnelNative.stopService()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tun2socks in onDestroy", e)
        }
        xrayManager?.stop()
        xrayManager = null
        dnsttProcess?.destroy()
        dnsttProcess = null
        activeDnsttConfig = null
        dohProxy?.stop()
        dohProxy = null
        tunnelProcess?.destroy()
        vpnInterface?.close()
        isRunning = false
        super.onDestroy()
    }

    /**
     * Start sending decoy DNS queries to make traffic look normal.
     * This helps evade DPI detection by mixing tunnel traffic with
     * normal-looking DNS queries to popular domains.
     */
    private fun startDecoyDns() {
        decoyJob = serviceScope.launch {
            Log.d(TAG, "Starting decoy DNS queries")
            while (isActive) {
                try {
                    // Random delay between 2-10 seconds
                    val delayMs = Random.nextLong(2000, 10000)
                    delay(delayMs)

                    // Pick a random domain and resolver
                    val domain = decoyDomains.random()
                    val resolvers = dnsConfig?.resolvers ?: DnsConfig.DEFAULT_RESOLVERS
                    val resolver = resolvers.random()

                    // Send decoy DNS query (runs in background, we don't care about result)
                    launch {
                        sendDecoyDnsQuery(domain, resolver)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Decoy DNS error: ${e.message}")
                }
            }
            Log.d(TAG, "Decoy DNS stopped")
        }
    }

    /**
     * Send a single DNS query to a resolver.
     * This creates normal-looking DNS traffic to mix with tunnel queries.
     */
    private fun sendDecoyDnsQuery(domain: String, resolver: String) {
        try {
            // Build a simple DNS query packet
            val query = buildDnsQuery(domain)

            val socket = DatagramSocket()
            socket.soTimeout = 2000

            val address = InetAddress.getByName(resolver)
            val packet = DatagramPacket(query, query.size, address, 53)

            // Protect socket from VPN routing (send directly, not through tunnel)
            protect(socket)

            socket.send(packet)

            // Try to receive response (we don't actually need it)
            val response = ByteArray(512)
            val responsePacket = DatagramPacket(response, response.size)
            try {
                socket.receive(responsePacket)
            } catch (e: Exception) {
                // Timeout is fine, we just want to send the query
            }

            socket.close()
            Log.v(TAG, "Decoy query: $domain via $resolver")
        } catch (e: Exception) {
            // Silently ignore errors - decoy queries are best-effort
        }
    }

    /**
     * Build a minimal DNS query packet for a domain.
     */
    private fun buildDnsQuery(domain: String): ByteArray {
        val query = mutableListOf<Byte>()

        // Transaction ID (random)
        val txId = Random.nextInt(0xFFFF)
        query.add((txId shr 8).toByte())
        query.add((txId and 0xFF).toByte())

        // Flags: standard query
        query.add(0x01.toByte())
        query.add(0x00.toByte())

        // Questions: 1
        query.add(0x00.toByte())
        query.add(0x01.toByte())

        // Answer RRs: 0
        query.add(0x00.toByte())
        query.add(0x00.toByte())

        // Authority RRs: 0
        query.add(0x00.toByte())
        query.add(0x00.toByte())

        // Additional RRs: 0
        query.add(0x00.toByte())
        query.add(0x00.toByte())

        // Query name
        for (label in domain.split(".")) {
            query.add(label.length.toByte())
            for (c in label) {
                query.add(c.code.toByte())
            }
        }
        query.add(0x00.toByte()) // End of name

        // Query type: A (1)
        query.add(0x00.toByte())
        query.add(0x01.toByte())

        // Query class: IN (1)
        query.add(0x00.toByte())
        query.add(0x01.toByte())

        return query.toByteArray()
    }
}
