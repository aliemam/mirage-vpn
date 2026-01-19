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
import java.net.InetSocketAddress
import java.net.Socket

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

            // Extract and prepare the tunnel binary
            val binaryPath = extractBinary()
            if (binaryPath == null) {
                sendStatus(getString(R.string.status_error_binary), false)
                stopSelf()
                return
            }

            // Start the DNS tunnel client
            startTunnelClient(binaryPath)

            // Wait for tunnel to be ready
            delay(2000)

            // Check if tunnel is working
            if (!isTunnelAlive()) {
                sendStatus(getString(R.string.status_error_tunnel), false)
                stopSelf()
                return
            }

            // Establish VPN interface
            establishVpn()

            isRunning = true
            sendStatus(getString(R.string.status_connected), true)
            updateNotification(getString(R.string.status_connected))

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            sendStatus("${getString(R.string.status_error)}: ${e.message}", false)
            disconnect()
        }
    }

    private fun extractBinary(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        val binaryName = when {
            abi.contains("arm64") -> "slipstream-client-arm64"
            abi.contains("arm") -> "slipstream-client-arm"
            abi.contains("x86_64") -> "slipstream-client-x86_64"
            else -> return null
        }

        val binaryFile = File(filesDir, "slipstream-client")
        try {
            assets.open("bin/$binaryName").use { input ->
                FileOutputStream(binaryFile).use { output ->
                    input.copyTo(output)
                }
            }
            binaryFile.setExecutable(true)
            return binaryFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract binary", e)
            return null
        }
    }

    private fun startTunnelClient(binaryPath: String) {
        // Build command for slipstream client
        // slipstream-client --resolver <ip> --domain <domain> --tcp-listen-port <port>
        val cmd = mutableListOf(
            binaryPath,
            "--domain", config.domain,
            "--tcp-listen-port", config.listenPort.toString()
        )

        // Add all resolvers
        for (resolver in config.resolvers) {
            cmd.add("--resolver")
            cmd.add(resolver)
        }

        Log.d(TAG, "Starting tunnel: ${cmd.joinToString(" ")}")

        val processBuilder = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .directory(filesDir)

        tunnelProcess = processBuilder.start()

        // Log output in background
        serviceScope.launch {
            tunnelProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                Log.d(TAG, "Tunnel: $line")
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
        // For simplicity, we'll use a pure Kotlin SOCKS proxy forwarder
        // In production, you'd use a proper tun2socks binary
        serviceScope.launch {
            vpnInterface?.let { vpn ->
                val fd = vpn.fd
                Log.d(TAG, "VPN interface established with fd: $fd")
                // Packet forwarding would happen here
                // This is simplified - real implementation needs tun2socks
            }
        }
    }

    private suspend fun disconnect() {
        isRunning = false
        sendStatus(getString(R.string.status_disconnecting), false)

        withContext(Dispatchers.IO) {
            tunnelProcess?.destroy()
            tunnelProcess = null

            vpnInterface?.close()
            vpnInterface = null
        }

        sendStatus(getString(R.string.status_disconnected), false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendStatus(status: String, connected: Boolean) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
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
        serviceScope.cancel()
        tunnelProcess?.destroy()
        vpnInterface?.close()
        isRunning = false
        super.onDestroy()
    }
}
