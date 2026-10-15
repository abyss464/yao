package dev.abysser.yao.net

import java.nio.ByteBuffer
import org.json.JSONObject

/** Frame types and builders (see PROTOCOL.md). */
object Proto {
    const val HELLO = 0x01
    const val PAIR_REQUEST = 0x02
    const val PAIR_CODE = 0x03
    const val TOUCH_CONFIG = 0x10
    const val TOUCH = 0x11
    const val BUTTON = 0x12
    const val KEY = 0x13
    const val MOTION = 0x14
    const val CHORD = 0x15
    const val TEXT = 0x20
    const val RUN = 0x21
    const val SHORTCUT_PUT = 0x22
    const val SHORTCUT_DELETE = 0x23
    const val FAVORITE = 0x24
    const val PREEDIT = 0x25
    const val PING = 0x30

    const val WELCOME = 0x81
    const val PAIR_PENDING = 0x82
    const val PAIR_OK = 0x83
    const val ERROR = 0x84
    const val PONG = 0x90
    const val STATE = 0x91
    const val LIBRARY = 0x92

    const val PORT = 20780
    const val SERVICE = "_yao._tcp."

    fun frame(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = payload.size + 1
        require(len <= 0xFFFF)
        return ByteBuffer.allocate(len + 2).putShort(len.toShort()).put(type.toByte()).put(payload).array()
    }

    fun json(type: Int, obj: JSONObject) = frame(type, obj.toString().toByteArray())

    fun touchConfig(w: Int, h: Int, rx: Int, ry: Int) = frame(
        TOUCH_CONFIG,
        ByteBuffer.allocate(8).putShort(w.toShort()).putShort(h.toShort())
            .putShort(rx.toShort()).putShort(ry.toShort()).array(),
    )

    /** [contacts]: pointer id, x, y. */
    fun touch(timeMs: Long, contacts: List<Triple<Int, Int, Int>>): ByteArray {
        val b = ByteBuffer.allocate(5 + contacts.size * 5)
        b.putInt(timeMs.toInt()).put(contacts.size.toByte())
        for ((id, x, y) in contacts) b.put(id.toByte()).putShort(x.toShort()).putShort(y.toShort())
        return frame(TOUCH, b.array())
    }

    fun button(button: Int, down: Boolean) = frame(BUTTON, byteArrayOf(button.toByte(), if (down) 1 else 0))

    fun key(code: Int, down: Boolean) =
        frame(KEY, ByteBuffer.allocate(3).putShort(code.toShort()).put(if (down) 1 else 0).array())

    fun motion(dx: Int, dy: Int) =
        frame(MOTION, ByteBuffer.allocate(4).putShort(dx.toShort()).putShort(dy.toShort()).array())

    fun chord(codes: List<Int>): ByteArray {
        val b = ByteBuffer.allocate(1 + codes.size * 2).put(codes.size.toByte())
        codes.forEach { b.putShort(it.toShort()) }
        return frame(CHORD, b.array())
    }

    fun text(s: String) = frame(TEXT, s.toByteArray())

    fun ping(t: Long) = frame(PING, ByteBuffer.allocate(8).putLong(t).array())
}
