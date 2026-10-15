package dev.abysser.yao.net

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import javax.net.ssl.SSLSocket
import org.json.JSONObject

/**
 * The picture of the window being typed into: a second connection to yaod (so video
 * never queues up in front of touches and keys) carrying H.264 access units, decoded
 * straight onto [surface]. Reconnects until closed.
 */
class VideoStream(
    private val host: String,
    private val port: Int,
    private val pin: String,
    private val deviceId: String,
    private val token: String,
    private val maxW: Int,
    private val maxH: Int,
    private val surface: Surface,
    private val onFormat: (Int, Int) -> Unit,
) {
    @Volatile private var closed = false
    @Volatile private var socket: SSLSocket? = null
    private var codec: MediaCodec? = null
    private var pts = 0L
    private val info = MediaCodec.BufferInfo()

    fun start() = Thread({ run() }, "yao-video").start()

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    private fun run() {
        while (!closed) {
            try {
                val s = Connection.tls(host, port, pin)
                socket = s
                val open = JSONObject().put("id", deviceId).put("token", token).put("width", maxW).put("height", maxH)
                s.outputStream.apply { write(Proto.json(Proto.VIDEO_OPEN, open)); flush() }
                val input = DataInputStream(BufferedInputStream(s.inputStream, 1 shl 16))
                while (!closed) {
                    val len = input.readInt()
                    val type = input.readUnsignedByte()
                    val payload = ByteArray(len - 1)
                    input.readFully(payload)
                    when (type) {
                        1 -> format(JSONObject(String(payload)))
                        2 -> decode(payload)
                    }
                }
            } catch (_: Exception) {
                if (!closed) Thread.sleep(500)
            } finally {
                release()
                runCatching { socket?.close() }
            }
        }
    }

    private fun format(o: JSONObject) {
        release()
        val w = o.getInt("width")
        val h = o.getInt("height")
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(fmt, surface, null, 0)
            start()
        }
        onFormat(w, h)
    }

    private fun decode(unit: ByteArray) {
        val c = codec ?: return
        val i = c.dequeueInputBuffer(20_000)
        if (i >= 0) {
            c.getInputBuffer(i)?.apply { clear(); put(unit) }
            pts += 33_333
            c.queueInputBuffer(i, 0, unit.size, pts, 0)
        }
        // Show whatever is decoded at once: the newest picture is the only one that matters.
        while (true) {
            val o = c.dequeueOutputBuffer(info, 2_000)
            if (o < 0) break
            c.releaseOutputBuffer(o, true)
        }
    }

    private fun release() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
    }
}
