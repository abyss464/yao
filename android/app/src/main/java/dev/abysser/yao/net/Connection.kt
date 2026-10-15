package dev.abysser.yao.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * One TLS connection to yaod. The server's certificate is pinned by the SHA-256 of
 * its DER encoding; with [pin] null (pairing) any certificate is accepted and its
 * fingerprint recorded in [fingerprint].
 *
 * [onFrame] and [onClosed] run on the connection's reader thread.
 */
class Connection(
    private val host: String,
    private val port: Int,
    private val pin: String?,
    private val onFrame: (Int, ByteArray) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
) {
    @Volatile var fingerprint: String? = null
        private set

    private val out = LinkedBlockingQueue<ByteArray>()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false

    /** Connects and starts the reader and writer threads. Blocking; throws on failure. */
    fun open() {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
                throw CertificateException("no client certs")

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                val fp = sha256(chain.first().encoded)
                if (pin != null && fp != pin) throw CertificateException("certificate changed")
                fingerprint = fp
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(trust), null) }
        // Straight to the computer: a system proxy has no business on the LAN.
        val raw = Socket(java.net.Proxy.NO_PROXY).apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, this@Connection.port), 4000)
        }
        val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.enabledProtocols = arrayOf("TLSv1.3")
        ssl.soTimeout = 0
        ssl.startHandshake()
        socket = ssl

        Thread({ writeLoop(ssl) }, "yao-writer").start()
        Thread({ readLoop(ssl) }, "yao-reader").start()
    }

    fun send(frame: ByteArray) {
        if (!closed) out.offer(frame)
    }

    fun close() {
        if (closed) return
        closed = true
        out.offer(ByteArray(0))
        runCatching { socket?.close() }
    }

    private fun writeLoop(ssl: SSLSocket) {
        try {
            val os = BufferedOutputStream(ssl.outputStream, 8192)
            while (!closed) {
                val f = out.poll(1, TimeUnit.SECONDS) ?: continue
                if (f.isEmpty()) break
                os.write(f)
                // Coalesce whatever is already queued into one TLS record.
                while (true) {
                    val more = out.poll() ?: break
                    if (more.isEmpty()) { closed = true; break }
                    os.write(more)
                }
                os.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { ssl.close() }
        }
    }

    private fun readLoop(ssl: SSLSocket) {
        var error: Throwable? = null
        try {
            val input = DataInputStream(BufferedInputStream(ssl.inputStream, 16384))
            while (!closed) {
                val len = input.readUnsignedShort()
                if (len == 0) throw java.io.IOException("empty frame")
                val type = input.readUnsignedByte()
                val payload = ByteArray(len - 1)
                input.readFully(payload)
                onFrame(type, payload)
            }
        } catch (e: Throwable) {
            error = e
        } finally {
            val wasClosed = closed
            close()
            onClosed(if (wasClosed) null else error)
        }
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
