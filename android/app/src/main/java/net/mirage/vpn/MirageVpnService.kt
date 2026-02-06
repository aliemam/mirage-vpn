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
        const val ACTION_STATUS_UPDATE = "net.mirage.vpn.STATUS_UPDATE"
        const val ACTION_PROBE_PROGRESS = "net.mirage.vpn.PROBE_PROGRESS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_PROBE_CURRENT = "probe_current"
        const val EXTRA_PROBE_TOTAL = "probe_total"
        const val EXTRA_PROBE_SNI = "probe_sni"

        private const val NOTIFICATION_CHANNEL_ID = "mirage_vpn_channel"
        private const val NOTIFICATION_ID = 1

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

    // Background optimizer
    private var backgroundOptimizer: BackgroundOptimizer? = null

    // Dynamic config scoring and repository
    private var scoreManager: ConfigScoreManager? = null
    private var configRepository: ConfigRepository? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.status_connecting)))
                serviceScope.launch { connect() }
            }
            ACTION_DISCONNECT -> {
                serviceScope.launch { disconnect() }
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
            sendStatus(getString(R.string.status_connecting), false)

            // Auto-detect available modes based on loaded configs
            val hasXray = configRepository?.hasXrayConfigs() ?: false
            val hasDns = dnsConfig?.isAvailable ?: false

            val connected = when {
                hasXray && hasDns -> {
                    // Both available: try Xray first, fall back to DNS
                    val xrayOk = connectXray()
                    if (!xrayOk) {
                        Log.i(TAG, "Xray failed, falling back to DNS tunnel...")
                        connectDns()
                    } else true
                }
                hasXray -> connectXray()
                hasDns -> connectDns()
                else -> {
                    Log.e(TAG, "No configs available (no Xray configs, no DNS domains)")
                    false
                }
            }

            if (!connected) {
                sendStatus(getString(R.string.status_error_tunnel), false)
                stopSelf()
                return
            }

            // Establish VPN interface
            establishVpn()

            isRunning = true
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
        sendStatus(getString(R.string.status_probing), false)
        val probeSuccess = xrayManager?.quickProbe() ?: false

        if (!probeSuccess) {
            sendStatus(getString(R.string.status_probe_failed), false)
            xrayManager = null
            return false
        }

        // Start Xray
        sendStatus(getString(R.string.status_connecting), false)
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

        activeTunnelMode = TunnelMode.XRAY
        activeSocksPort = ServerConfig.XRAY_SOCKS_PORT

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
            sendStatus(getString(R.string.status_probing), false)
            val probeSuccess = dohProxy?.probe() ?: false

            if (!probeSuccess) {
                sendStatus(getString(R.string.status_probe_failed), false)
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

        // Try connecting with domain failover
        var connected = false
        var attempts = 0
        val maxAttempts = activeDns.domains.size

        while (!connected && attempts < maxAttempts) {
            val currentDomain = dnsConfig!!.domain
            Log.d(TAG, "Trying domain ${attempts + 1}/$maxAttempts: $currentDomain")
            // Don't show technical details on screen - just "Connecting..."
            sendStatus(getString(R.string.status_connecting), false)

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

        if (connected) {
            activeTunnelMode = TunnelMode.DNS
            activeSocksPort = dnsConfig!!.listenPort
        } else {
            dohProxy?.stop()
            dohProxy = null
        }

        return connected
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
            TunnelMode.AUTO -> isSocksAlive(activeSocksPort)
        }
    }

    /**
     * Handle connection drop with exponential backoff reconnection.
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

                // Attempt 1: Try restarting xray with same config (fastest)
                if (activeTunnelMode == TunnelMode.XRAY && xrayManager != null) {
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

                    // Attempt 2: Quick probe for new config
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
            }

            // All attempts exhausted
            if (isRunning) {
                Log.e(TAG, "All reconnect attempts failed")
                sendStatus(getString(R.string.status_connection_lost), true)
                updateNotification(getString(R.string.status_connection_lost))
            }
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

    // ========== Disconnect ==========

    private suspend fun disconnect() {
        isRunning = false

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

    private fun sendStatus(status: String, connected: Boolean) {
        Log.d(TAG, "Sending status: $status, connected: $connected")
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_CONNECTED, connected)
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
