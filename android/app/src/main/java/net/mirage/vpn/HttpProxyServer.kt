package net.mirage.vpn

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP CONNECT proxy server that chains through an upstream SOCKS5 proxy.
 *
 * Other devices on the same WiFi can set their HTTP proxy to this phone's IP:port
 * to share the VPN connection.
 *
 * Flow: Client → HTTP CONNECT → this server → SOCKS5 → Xray/dnstt → internet
 */
class HttpProxyServer(
    private var upstreamSocksPort: Int,
    private val preferredPort: Int = 8080
) {
    companion object {
        private const val TAG = "HttpProxy"
        private const val BUFFER_SIZE = 8192
        private const val SOCKS5_VERSION: Byte = 0x05
        private const val SOCKS5_CMD_CONNECT: Byte = 0x01
        private const val SOCKS5_ATYP_DOMAIN: Byte = 0x03
        private const val SOCKS5_AUTH_NONE: Byte = 0x00
    }

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private var listenPort: Int = preferredPort

    val isRunning: Boolean get() = running.get()
    val port: Int get() = listenPort

    /**
     * Start the HTTP proxy server. Tries preferred port, then fallback ports.
     * Returns the actual port or -1 on failure.
     */
    fun start(): Int {
        if (running.get()) return listenPort

        val ports = listOf(preferredPort, preferredPort + 1, preferredPort + 2)
        for (port in ports) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss
                listenPort = port
                break
            } catch (e: IOException) {
                Log.w(TAG, "Port $port in use, trying next...")
            }
        }

        if (serverSocket == null) {
            Log.e(TAG, "Failed to bind to any port")
            return -1
        }

        executor = Executors.newFixedThreadPool(32)
        running.set(true)

        Thread({
            Log.i(TAG, "HTTP proxy started on 0.0.0.0:$listenPort → SOCKS5 127.0.0.1:$upstreamSocksPort")
            while (running.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    executor?.submit { handleClient(client) }
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "Accept error: ${e.message}")
                }
            }
        }, "HttpProxy-Accept").start()

        return listenPort
    }

    /**
     * Stop the HTTP proxy server.
     */
    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        Log.i(TAG, "HTTP proxy stopped")
    }

    /**
     * Update the upstream SOCKS5 port (used during hot-swap).
     */
    fun updateUpstreamPort(newPort: Int) {
        upstreamSocksPort = newPort
        Log.d(TAG, "Updated upstream SOCKS5 port to $newPort")
    }

    /**
     * Handle a single client connection.
     */
    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // Read the first line of the HTTP request
            val requestLine = readLine(input)
            if (requestLine.isNullOrBlank()) {
                client.close()
                return
            }

            val parts = requestLine.split(" ")
            if (parts.size < 3) {
                sendError(output, 400, "Bad Request")
                client.close()
                return
            }

            val method = parts[0].uppercase()
            val target = parts[1]

            // Read and discard remaining headers
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }

            if (method == "CONNECT") {
                handleConnect(client, input, output, target)
            } else {
                handlePlainHttp(client, output, method, target, parts[2])
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Handle HTTP CONNECT (HTTPS tunneling).
     * Client sends: CONNECT host:port HTTP/1.1
     * We connect to host:port through SOCKS5, then relay bidirectionally.
     */
    private fun handleConnect(client: Socket, clientIn: InputStream, clientOut: OutputStream, target: String) {
        val (host, port) = parseHostPort(target, 443) ?: run {
            sendError(clientOut, 400, "Bad target")
            return
        }

        // Connect to upstream SOCKS5
        val upstream = connectViaSocks5(host, port)
        if (upstream == null) {
            sendError(clientOut, 502, "Bad Gateway")
            return
        }

        // Tell client the tunnel is established
        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        clientOut.flush()

        // Bidirectional relay
        relay(client, upstream)
    }

    /**
     * Handle plain HTTP requests (non-CONNECT).
     * For simplicity, we reject these — most modern traffic uses HTTPS/CONNECT.
     */
    private fun handlePlainHttp(client: Socket, output: OutputStream, method: String, url: String, version: String) {
        // Parse host from URL
        val hostPort = try {
            val uri = java.net.URI(url)
            val h = uri.host ?: throw Exception("No host")
            val p = if (uri.port > 0) uri.port else 80
            h to p
        } catch (e: Exception) {
            sendError(output, 400, "Bad URL")
            return
        }

        val (host, port) = hostPort
        val upstream = connectViaSocks5(host, port)
        if (upstream == null) {
            sendError(output, 502, "Bad Gateway")
            return
        }

        // Forward the request to upstream
        val path = try {
            val uri = java.net.URI(url)
            val p = uri.rawPath ?: "/"
            if (uri.rawQuery != null) "$p?${uri.rawQuery}" else p
        } catch (_: Exception) { "/" }

        val reqLine = "$method $path $version\r\nHost: $host\r\nConnection: close\r\n\r\n"
        upstream.getOutputStream().write(reqLine.toByteArray())
        upstream.getOutputStream().flush()

        // Relay response back
        relay(client, upstream)
    }

    /**
     * Connect to a remote host:port through the upstream SOCKS5 proxy.
     */
    private fun connectViaSocks5(host: String, port: Int): Socket? {
        return try {
            val sock = Socket()
            sock.connect(InetSocketAddress("127.0.0.1", upstreamSocksPort), 10_000)
            sock.soTimeout = 30_000

            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            // SOCKS5 greeting: version=5, 1 auth method, no auth
            out.write(byteArrayOf(SOCKS5_VERSION, 0x01, SOCKS5_AUTH_NONE))
            out.flush()

            // Read server choice
            val authResp = ByteArray(2)
            readFully(inp, authResp)
            if (authResp[0] != SOCKS5_VERSION || authResp[1] != SOCKS5_AUTH_NONE) {
                sock.close()
                return null
            }

            // SOCKS5 CONNECT request
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            val connectReq = ByteArray(4 + 1 + hostBytes.size + 2)
            connectReq[0] = SOCKS5_VERSION
            connectReq[1] = SOCKS5_CMD_CONNECT
            connectReq[2] = 0x00 // reserved
            connectReq[3] = SOCKS5_ATYP_DOMAIN
            connectReq[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, connectReq, 5, hostBytes.size)
            connectReq[5 + hostBytes.size] = (port shr 8).toByte()
            connectReq[6 + hostBytes.size] = (port and 0xFF).toByte()
            out.write(connectReq)
            out.flush()

            // Read SOCKS5 response (at least 10 bytes for IPv4)
            val respHeader = ByteArray(4)
            readFully(inp, respHeader)
            if (respHeader[1] != 0x00.toByte()) {
                // Connection failed
                sock.close()
                return null
            }

            // Skip bound address based on address type
            when (respHeader[3]) {
                0x01.toByte() -> readFully(inp, ByteArray(4 + 2)) // IPv4 + port
                0x03.toByte() -> {
                    val len = inp.read()
                    readFully(inp, ByteArray(len + 2)) // domain + port
                }
                0x04.toByte() -> readFully(inp, ByteArray(16 + 2)) // IPv6 + port
            }

            sock
        } catch (e: Exception) {
            Log.d(TAG, "SOCKS5 connect to $host:$port failed: ${e.message}")
            null
        }
    }

    /**
     * Bidirectional relay between two sockets.
     */
    private fun relay(a: Socket, b: Socket) {
        val aIn = a.getInputStream()
        val aOut = a.getOutputStream()
        val bIn = b.getInputStream()
        val bOut = b.getOutputStream()

        val t1 = Thread({
            try { copyStream(aIn, bOut) } catch (_: Exception) {}
            try { b.shutdownOutput() } catch (_: Exception) {}
        }, "relay-a2b")

        val t2 = Thread({
            try { copyStream(bIn, aOut) } catch (_: Exception) {}
            try { a.shutdownOutput() } catch (_: Exception) {}
        }, "relay-b2a")

        t1.start()
        t2.start()
        t1.join()
        t2.join()

        try { a.close() } catch (_: Exception) {}
        try { b.close() } catch (_: Exception) {}
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buf = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString() // safety limit
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) throw IOException("Unexpected EOF")
            offset += n
        }
    }

    private fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int>? {
        return try {
            val colonIdx = target.lastIndexOf(':')
            if (colonIdx > 0) {
                val host = target.substring(0, colonIdx)
                val port = target.substring(colonIdx + 1).toInt()
                host to port
            } else {
                target to defaultPort
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sendError(output: OutputStream, code: Int, msg: String) {
        val body = "<h1>$code $msg</h1>"
        val resp = "HTTP/1.1 $code $msg\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        try {
            output.write(resp.toByteArray())
            output.flush()
        } catch (_: Exception) {}
    }
}
