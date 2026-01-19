package net.mirage.vpn

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocketFactory

/**
 * DNS over HTTPS (DoH) Proxy with IP Donor Support
 *
 * Key Innovation: Uses "IP Donors" - popular Cloudflare sites that are unlikely
 * to be blocked (like chatgpt.com) to get working Cloudflare edge IPs.
 *
 * Since Cloudflare's edge servers serve multiple services, we can:
 * 1. Resolve chatgpt.com → 172.64.155.209 (not blocked in Iran)
 * 2. Connect to that IP for cloudflare-dns.com DoH service
 * 3. Cloudflare serves our DoH request ✓
 *
 * Flow:
 * slipstream → DoHProxy → chatgpt.com's IP → cloudflare-dns.com service → VPS
 */
class DohProxy(
    private val listenPort: Int = 5353,
    private val dohEndpoints: List<String> = DEFAULT_DOH_ENDPOINTS
) {
    companion object {
        private const val TAG = "DoHProxy"
        private const val BUFFER_SIZE = 4096
        private const val TIMEOUT_MS = 5000

        // DoH endpoints - these look like normal HTTPS traffic
        val DEFAULT_DOH_ENDPOINTS = listOf(
            "https://cloudflare-dns.com/dns-query",      // Cloudflare
            "https://dns.google/dns-query",              // Google
            "https://dns.quad9.net/dns-query",           // Quad9
            "https://doh.opendns.com/dns-query",         // OpenDNS
        )

        // IP Donor domains - popular Cloudflare sites unlikely to be blocked
        // We resolve these to get working Cloudflare edge IPs
        private val IP_DONOR_DOMAINS = listOf(
            "chatgpt.com",          // OpenAI - very popular, unlikely to be blocked
            "openai.com",           // OpenAI
            "cdn.oaistatic.com",    // OpenAI CDN
            "discord.com",          // Discord - popular gaming platform
            "medium.com",           // Medium - blog platform
            "notion.so",            // Notion - productivity app
            "canva.com",            // Canva - design platform
        )

        // Cache of working IPs discovered from donors
        private val donorIpCache = mutableListOf<String>()
        private var lastIpRefresh = 0L
        private const val IP_CACHE_TTL_MS = 300_000L // 5 minutes
    }

    private var socket: DatagramSocket? = null
    private var running = false
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentEndpointIndex = 0

    /**
     * Start the DoH proxy server
     */
    fun start() {
        if (running) {
            Log.w(TAG, "DoH proxy already running")
            return
        }

        running = true
        job = scope.launch {
            try {
                // Refresh donor IPs on startup
                refreshDonorIps()

                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", listenPort))
                    soTimeout = 1000 // 1 second timeout for checking running flag
                }

                Log.i(TAG, "DoH proxy listening on 127.0.0.1:$listenPort")
                Log.i(TAG, "Using DoH endpoints: ${dohEndpoints.joinToString(", ")}")
                Log.i(TAG, "Donor IPs available: ${donorIpCache.size}")

                val buffer = ByteArray(BUFFER_SIZE)

                while (running && isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)

                        // Handle query in separate coroutine for concurrency
                        launch {
                            handleQuery(
                                packet.data.copyOf(packet.length),
                                packet.socketAddress as InetSocketAddress
                            )
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Expected, just continue to check running flag
                    } catch (e: Exception) {
                        if (running) {
                            Log.e(TAG, "Error receiving packet: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "DoH proxy error: ${e.message}", e)
            } finally {
                socket?.close()
                socket = null
                Log.i(TAG, "DoH proxy stopped")
            }
        }
    }

    /**
     * Stop the DoH proxy server
     */
    fun stop() {
        running = false
        job?.cancel()
        socket?.close()
        socket = null
        Log.i(TAG, "DoH proxy stopping...")
    }

    /**
     * Refresh donor IPs by resolving popular Cloudflare domains
     */
    private fun refreshDonorIps() {
        val now = System.currentTimeMillis()
        if (now - lastIpRefresh < IP_CACHE_TTL_MS && donorIpCache.isNotEmpty()) {
            return // Cache still valid
        }

        Log.d(TAG, "Refreshing donor IPs from ${IP_DONOR_DOMAINS.size} domains...")
        val newIps = mutableSetOf<String>()

        for (domain in IP_DONOR_DOMAINS) {
            try {
                val addresses = InetAddress.getAllByName(domain)
                for (addr in addresses) {
                    val ip = addr.hostAddress
                    if (ip != null && !ip.contains(":")) { // IPv4 only for now
                        newIps.add(ip)
                        Log.d(TAG, "Donor IP from $domain: $ip")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve $domain: ${e.message}")
            }
        }

        if (newIps.isNotEmpty()) {
            synchronized(donorIpCache) {
                donorIpCache.clear()
                donorIpCache.addAll(newIps)
            }
            lastIpRefresh = now
            Log.i(TAG, "Refreshed ${donorIpCache.size} donor IPs")
        }
    }

    /**
     * Handle a single DNS query by forwarding to DoH endpoint
     */
    private suspend fun handleQuery(queryData: ByteArray, clientAddress: InetSocketAddress) {
        var lastError: Exception? = null

        // First, try with donor IPs (IP fronting technique)
        if (donorIpCache.isNotEmpty()) {
            val shuffledIps = donorIpCache.shuffled().take(3) // Try up to 3 random donor IPs
            for (donorIp in shuffledIps) {
                try {
                    val response = forwardToDoHWithDonorIp(queryData, "cloudflare-dns.com", donorIp)
                    if (response != null) {
                        sendResponse(response, clientAddress)
                        return
                    }
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "DoH via donor IP $donorIp failed: ${e.message}")
                }
            }
        }

        // Fallback: try direct endpoints
        for (i in dohEndpoints.indices) {
            val endpointIndex = (currentEndpointIndex + i) % dohEndpoints.size
            val endpoint = dohEndpoints[endpointIndex]

            try {
                val response = forwardToDoH(queryData, endpoint)
                if (response != null) {
                    sendResponse(response, clientAddress)
                    currentEndpointIndex = endpointIndex
                    return
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "DoH endpoint $endpoint failed: ${e.message}")
            }
        }

        Log.e(TAG, "All DoH methods failed", lastError)
    }

    private fun sendResponse(response: ByteArray, clientAddress: InetSocketAddress) {
        val responsePacket = DatagramPacket(
            response, response.size,
            clientAddress.address, clientAddress.port
        )
        socket?.send(responsePacket)
    }

    /**
     * Forward DNS query using a donor IP (IP fronting)
     * Connects to donor IP but requests cloudflare-dns.com service
     */
    private fun forwardToDoHWithDonorIp(queryData: ByteArray, dohHost: String, donorIp: String): ByteArray? {
        var connection: HttpsURLConnection? = null

        try {
            // Create URL with the actual DoH host
            val url = URL("https://$dohHost/dns-query")

            // Open connection but we'll redirect to donor IP
            connection = url.openConnection() as HttpsURLConnection

            // Force connection to donor IP while keeping Host header as dohHost
            // This is done by setting a custom socket factory or using setRequestProperty
            connection.apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS

                // RFC 8484 headers
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
                setRequestProperty("Host", dohHost)

                // Make it look more like a browser
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            }

            // Use system property to force IP (this is a workaround)
            // Better approach would be custom DNS resolver, but this works for HttpsURLConnection
            val originalUrl = connection.url
            val donorUrl = URL("https://$donorIp/dns-query")

            // We need to create a new connection to the donor IP
            connection.disconnect()

            connection = donorUrl.openConnection() as HttpsURLConnection
            connection.apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS

                // Set hostname for SSL verification
                setRequestProperty("Host", dohHost)
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")

                // Disable hostname verification for IP connection
                // The Host header tells the server which service we want
                hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }

            // Send DNS query in body
            connection.outputStream.use { out ->
                out.write(queryData)
                out.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "DoH via $donorIp returned HTTP $responseCode")
                return null
            }

            Log.d(TAG, "DoH success via donor IP $donorIp")

            // Read response
            return connection.inputStream.use { input ->
                input.readBytes()
            }

        } catch (e: Exception) {
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Forward DNS query to a DoH endpoint via HTTPS POST
     * Uses wire format (RFC 8484)
     */
    private fun forwardToDoH(queryData: ByteArray, endpoint: String): ByteArray? {
        var connection: HttpsURLConnection? = null

        try {
            val url = URL(endpoint)
            connection = url.openConnection() as HttpsURLConnection

            connection.apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS

                // RFC 8484 headers
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")

                // Make it look more like a browser
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            }

            // Send DNS query in body
            connection.outputStream.use { out ->
                out.write(queryData)
                out.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "DoH returned HTTP $responseCode from $endpoint")
                return null
            }

            // Read response
            return connection.inputStream.use { input ->
                input.readBytes()
            }

        } catch (e: Exception) {
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Forward DNS query using GET with base64url (alternative method)
     * Some endpoints prefer this format
     */
    private fun forwardToDoHGet(queryData: ByteArray, endpoint: String): ByteArray? {
        var connection: HttpsURLConnection? = null

        try {
            // Base64url encode the query (no padding)
            val encoded = Base64.encodeToString(
                queryData,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )

            val url = URL("$endpoint?dns=$encoded")
            connection = url.openConnection() as HttpsURLConnection

            connection.apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/dns-message")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                return null
            }

            return connection.inputStream.use { input ->
                input.readBytes()
            }

        } finally {
            connection?.disconnect()
        }
    }
}
