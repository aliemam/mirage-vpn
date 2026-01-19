package net.mirage.vpn

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * DNS over HTTPS (DoH) Proxy
 *
 * Receives standard DNS queries on a local UDP port and forwards them
 * via HTTPS to DoH providers (Cloudflare, Google). This makes DNS traffic
 * look like normal HTTPS, evading DPI detection.
 *
 * Flow:
 * slipstream-client → UDP:5353 → DoHProxy → HTTPS:443 → cloudflare-dns.com
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
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", listenPort))
                    soTimeout = 1000 // 1 second timeout for checking running flag
                }

                Log.i(TAG, "DoH proxy listening on 127.0.0.1:$listenPort")
                Log.i(TAG, "Using DoH endpoints: ${dohEndpoints.joinToString(", ")}")

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
     * Handle a single DNS query by forwarding to DoH endpoint
     */
    private suspend fun handleQuery(queryData: ByteArray, clientAddress: InetSocketAddress) {
        var lastError: Exception? = null

        // Try each endpoint until one succeeds
        for (i in dohEndpoints.indices) {
            val endpointIndex = (currentEndpointIndex + i) % dohEndpoints.size
            val endpoint = dohEndpoints[endpointIndex]

            try {
                val response = forwardToDoH(queryData, endpoint)
                if (response != null) {
                    // Send response back to client
                    val responsePacket = DatagramPacket(
                        response, response.size,
                        clientAddress.address, clientAddress.port
                    )
                    socket?.send(responsePacket)

                    // Remember successful endpoint for next query
                    currentEndpointIndex = endpointIndex
                    return
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "DoH endpoint $endpoint failed: ${e.message}")
            }
        }

        Log.e(TAG, "All DoH endpoints failed", lastError)
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
                setRequestProperty("User-Agent", "Mozilla/5.0")
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
                setRequestProperty("User-Agent", "Mozilla/5.0")
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
