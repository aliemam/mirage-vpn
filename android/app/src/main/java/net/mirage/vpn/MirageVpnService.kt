package net.mirage.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
        const val EXTRA_STATUS = "status"
        const val EXTRA_CONNECTED = "connected"

        private const val NOTIFICATION_CHANNEL_ID = "mirage_vpn_channel"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelProcess: Process? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var config: ServerConfig
    private var decoyJob: Job? = null
    private var dohProxy: DohProxy? = null

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

    private suspend fun connect() {
        try {
            sendStatus(getString(R.string.status_connecting), false)

            // Start DoH proxy if enabled (makes DNS traffic look like HTTPS)
            if (config.useDoH) {
                Log.d(TAG, "Starting DoH proxy on port ${config.dohPort}")
                dohProxy = DohProxy(config.dohPort, config.dohEndpoints)
                dohProxy?.start()
                delay(500) // Give proxy time to start
            }

            // Extract and prepare the tunnel binary
            val binaryPath = extractBinary()
            if (binaryPath == null) {
                sendStatus(getString(R.string.status_error_binary), false)
                dohProxy?.stop()
                stopSelf()
                return
            }

            // Try connecting with domain failover
            var connected = false
            var attempts = 0
            val maxAttempts = config.domains.size

            while (!connected && attempts < maxAttempts) {
                val currentDomain = config.domain
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
                    if (config.hasMoreDomains()) {
                        config = config.nextDomain()
                        ServerConfig.save(this@MirageVpnService, config)
                    }
                }
            }

            if (!connected) {
                sendStatus(getString(R.string.status_error_tunnel), false)
                dohProxy?.stop()
                stopSelf()
                return
            }

            // Establish VPN interface
            establishVpn()

            isRunning = true
            sendStatus(getString(R.string.status_connected), true)
            updateNotification(getString(R.string.status_connected))

            // Start decoy DNS queries to make traffic look normal
            startDecoyDns()

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            sendStatus("${getString(R.string.status_error)}: ${e.message}", false)
            disconnect()
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
        // Build command for slipstream client
        // slipstream-client --resolver <ip> --domain <domain> --tcp-listen-port <port>
        val cmd = mutableListOf(
            binaryPath,
            "--domain", config.domain,
            "--tcp-listen-port", config.listenPort.toString()
        )

        // When DoH is enabled, route through local DoH proxy
        // Otherwise use direct resolvers
        if (config.useDoH) {
            // Use local DoH proxy - traffic will look like HTTPS
            cmd.add("--resolver")
            cmd.add("127.0.0.1:${config.dohPort}")
            Log.d(TAG, "Using DoH proxy at 127.0.0.1:${config.dohPort}")
        } else {
            // Add all direct resolvers (standard DNS on port 53)
            for (resolver in config.resolvers) {
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
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", config.listenPort), 1000)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun establishVpn() {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
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

    private fun startTun2Socks() {
        vpnInterface?.let { vpn ->
            val fd = vpn.fd
            Log.d(TAG, "VPN interface established with fd: $fd")

            // Create configuration file for hev-socks5-tunnel
            val configFile = File(filesDir, "tun2socks.yml")
            val configContent = """
                misc:
                  task-stack-size: 81920

                tunnel:
                  mtu: 8500

                socks5:
                  port: ${config.listenPort}
                  address: '127.0.0.1'
                  udp: 'udp'
            """.trimIndent()

            try {
                FileWriter(configFile).use { it.write(configContent) }
                Log.d(TAG, "Created tun2socks config at: ${configFile.absolutePath}")
                Log.d(TAG, "Starting tun2socks with SOCKS5 proxy at 127.0.0.1:${config.listenPort}")

                // Start the native tunnel
                TunnelNative.startService(configFile.absolutePath, fd)
                Log.d(TAG, "tun2socks started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tun2socks", e)
            }
        }
    }

    private suspend fun disconnect() {
        isRunning = false
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
            .setContentTitle(getString(R.string.app_name))
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
        decoyJob?.cancel()
        serviceScope.cancel()
        try {
            TunnelNative.stopService()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tun2socks in onDestroy", e)
        }
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
                    val resolver = config.resolvers.random()

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
