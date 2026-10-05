package io.github.zsozso01.platen.transport.network

import io.github.zsozso01.platen.protocol.ipp.IppConnection
import io.github.zsozso01.platen.protocol.ipp.IppConnector
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Opens plain TCP connections for IPP over the LAN. A raw [Socket] is deliberately used instead of an
 * HTTP stack: it gives exact control of the connection and is not subject to Android's cleartext-traffic
 * policy, which only governs the platform HTTP clients.
 */
public class TcpConnector(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMillis: Int = 5_000,
    /** How long a read may block. Printers can stay silent while they warm up or process a page. */
    private val readTimeoutMillis: Int = 60_000,
) : IppConnector {
    @Throws(IOException::class)
    override fun connect(): IppConnection {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            socket.soTimeout = readTimeoutMillis
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        return SocketConnection(socket)
    }

    private class SocketConnection(private val socket: Socket) : IppConnection {
        override val input: InputStream = socket.getInputStream()
        override val output: OutputStream = socket.getOutputStream()

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
