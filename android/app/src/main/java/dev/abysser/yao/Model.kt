package dev.abysser.yao

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.abysser.yao.net.Connection
import dev.abysser.yao.net.Discovery
import dev.abysser.yao.net.Host
import dev.abysser.yao.net.Proto
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

enum class Tab { Touchpad, Remote, Keyboard, Shortcuts }

data class WindowInfo(val cls: String, val title: String)

data class MediaInfo(
    val player: String,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val positionMs: Long,
    val lengthMs: Long,
    /** elapsedRealtime when the position was reported. */
    val at: Long,
)

data class Leds(val caps: Boolean = false, val num: Boolean = false)

sealed interface Step {
    data class Chord(val codes: List<Int>) : Step
    data class Text(val text: String) : Step
    data class Wait(val ms: Int) : Step
}

data class Shortcut(
    val id: String,
    val user: Boolean,
    val app: String,
    val group: String,
    val name: String,
    val keys: String,
    val steps: List<Step>,
    val favorite: Boolean,
)

/** The computer this phone is paired with. */
data class Pairing(val name: String, val address: String, val port: Int, val pin: String, val token: String)

/** Where pairing stands, for the pairing screen. */
enum class PairPhase { Searching, Connecting, EnterCode, Failed }

class Model(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("yao", Context.MODE_PRIVATE)
    private val discovery = Discovery(app)
    val hosts: StateFlow<List<Host>> = discovery.hosts

    private val deviceId: String = prefs.getString("device_id", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
    private val deviceName: String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() && it != "unknown" } ?: android.os.Build.MODEL

    private val _pairing = MutableStateFlow(loadPairing())
    val pairing: StateFlow<Pairing?> = _pairing

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _rtt = MutableStateFlow(0)
    val rtt: StateFlow<Int> = _rtt

    private val _pairPhase = MutableStateFlow(PairPhase.Searching)
    val pairPhase: StateFlow<PairPhase> = _pairPhase
    private val _pairHost = MutableStateFlow<Host?>(null)
    val pairHost: StateFlow<Host?> = _pairHost
    private val _pairMessage = MutableStateFlow<String?>(null)
    val pairMessage: StateFlow<String?> = _pairMessage

    private val _window = MutableStateFlow<WindowInfo?>(null)
    val window: StateFlow<WindowInfo?> = _window
    private val _media = MutableStateFlow<MediaInfo?>(null)
    val media: StateFlow<MediaInfo?> = _media
    private val _leds = MutableStateFlow(Leds())
    val leds: StateFlow<Leds> = _leds
    private val _library = MutableStateFlow<List<Shortcut>>(emptyList())
    val library: StateFlow<List<Shortcut>> = _library

    var tab by mutableStateOf(Tab.Touchpad)
    var lastPortraitTab = Tab.Touchpad

    @Volatile private var conn: Connection? = null
    @Volatile private var authed = false
    @Volatile private var lastPong = 0L
    private var touchConfig: ByteArray? = null
    private var touchSize = intArrayOf(0, 0, 0, 0)
    private var session: Job? = null

    // ---- lifecycle -------------------------------------------------------

    /** The app came to the foreground. */
    fun start() {
        if (session?.isActive == true) return
        session = viewModelScope.launch {
            discovery.start()
            if (_pairing.value == null) runPairing() else runConnected()
        }
    }

    /** The app went to the background: drop the connection so nothing stays pressed. */
    fun stop() {
        session?.cancel()
        session = null
        closeConnection()
        discovery.stop()
    }

    private fun closeConnection() {
        conn?.close()
        conn = null
        authed = false
        _connected.value = false
    }

    // ---- connected ---------------------------------------------------------

    private suspend fun runConnected() {
        var backoff = 300L
        while (viewModelScope.isActive) {
            val p = _pairing.value ?: return runPairing()
            // The computer's address may have changed: prefer what discovery sees now.
            val seen = hosts.value.firstOrNull { it.name == p.name }
            if (seen != null && (seen.address != p.address || seen.port != p.port)) {
                savePairing(p.copy(address = seen.address, port = seen.port))
            }
            val cur = _pairing.value ?: return runPairing()
            val closed = MutableStateFlow(false)
            val c = Connection(cur.address, cur.port, cur.pin, ::onFrame) { closed.value = true }
            val err = withContext(Dispatchers.IO) { runCatching { c.open() }.exceptionOrNull() }
            if (err != null) android.util.Log.w("yao", "connect ${cur.address}:${cur.port}", err)
            if (err == null) {
                conn = c
                c.send(Proto.json(Proto.HELLO, JSONObject().put("id", deviceId).put("token", cur.token).put("name", deviceName)))
                backoff = 300L
                pingUntilClosed(c, closed)
                closeConnection()
                if (_pairing.value == null) return runPairing()
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(3000L)
        }
    }

    private suspend fun pingUntilClosed(c: Connection, closed: MutableStateFlow<Boolean>) {
        lastPong = SystemClock.elapsedRealtime()
        while (!closed.value && viewModelScope.isActive) {
            c.send(Proto.ping(SystemClock.elapsedRealtime()))
            delay(250)
            if (SystemClock.elapsedRealtime() - lastPong > 2000) break
        }
    }

    // ---- pairing -----------------------------------------------------------

    private suspend fun runPairing() {
        _pairPhase.value = PairPhase.Searching
        _pairMessage.value = null
        // Pick the computer automatically when exactly one is around.
        val host = _pairHost.value ?: hosts.first { it.size == 1 }.first()
        connectForPairing(host)
    }

    /** The user picked a computer (or typed an address). */
    fun choose(host: Host) {
        session?.cancel()
        closeConnection()
        _pairHost.value = host
        session = viewModelScope.launch { connectForPairing(host) }
    }

    private suspend fun connectForPairing(host: Host) {
        _pairHost.value = host
        _pairPhase.value = PairPhase.Connecting
        _pairMessage.value = null
        val closed = MutableStateFlow(false)
        val c = Connection(host.address, host.port, null, ::onFrame) { closed.value = true }
        val err = withContext(Dispatchers.IO) { runCatching { c.open() }.exceptionOrNull() }
        if (err != null) {
            android.util.Log.w("yao", "connect ${host.address}:${host.port}", err)
            _pairPhase.value = PairPhase.Failed
            _pairMessage.value = "连不上 ${host.address}"
            return
        }
        conn = c
        requestCode()
        pingUntilClosed(c, closed)
        val paired = _pairing.value != null && authed
        closeConnection()
        if (paired) runConnected()
        else if (_pairPhase.value != PairPhase.Failed) {
            _pairPhase.value = PairPhase.Failed
            _pairMessage.value = "连接断开了"
        }
    }

    private fun requestCode() {
        conn?.send(Proto.json(Proto.PAIR_REQUEST, JSONObject().put("id", deviceId).put("name", deviceName)))
    }

    fun submitCode(code: String) {
        _pairMessage.value = null
        conn?.send(Proto.json(Proto.PAIR_CODE, JSONObject().put("code", code)))
    }

    fun retryPairing() {
        val h = _pairHost.value
        if (h != null) choose(h) else {
            session?.cancel()
            session = viewModelScope.launch { runPairing() }
        }
    }

    fun forget() {
        stop()
        prefs.edit().remove("pairing").apply()
        _pairing.value = null
        _pairHost.value = null
        _library.value = emptyList()
        _window.value = null
        _media.value = null
        start()
    }

    // ---- frames from the computer -----------------------------------------

    private fun onFrame(type: Int, p: ByteArray) {
        when (type) {
            Proto.PONG -> {
                lastPong = SystemClock.elapsedRealtime()
                if (p.size == 8) _rtt.value = (lastPong - ByteBuffer.wrap(p).long).toInt()
            }
            Proto.WELCOME -> onAuthed()
            Proto.PAIR_PENDING -> {
                _pairPhase.value = PairPhase.EnterCode
            }
            Proto.PAIR_OK -> {
                val o = JSONObject(String(p))
                val h = _pairHost.value ?: return
                val pin = conn?.fingerprint ?: return
                savePairing(Pairing(o.optString("host", h.name), h.address, h.port, pin, o.getString("token")))
                onAuthed()
            }
            Proto.ERROR -> onError(JSONObject(String(p)))
            Proto.STATE -> onState(JSONObject(String(p)))
            Proto.LIBRARY -> onLibrary(JSONObject(String(p)))
        }
    }

    private fun onAuthed() {
        authed = true
        _connected.value = true
        touchConfig?.let { conn?.send(it) }
        if (_pairing.value != null) _pairPhase.value = PairPhase.Searching
    }

    private fun onError(o: JSONObject) {
        when (o.optString("code")) {
            "unpaired" -> {
                prefs.edit().remove("pairing").apply()
                _pairing.value = null
            }
            "bad_code" -> _pairMessage.value = "配对码不对，再试一次"
            "expired" -> {
                _pairMessage.value = "配对码已失效，已重新发送"
                requestCode()
            }
        }
    }

    private fun onState(o: JSONObject) {
        _window.value = o.optJSONObject("window")?.let { WindowInfo(it.optString("class"), it.optString("title")) }
        _media.value = o.optJSONObject("media")?.let {
            MediaInfo(
                it.optString("player"), it.optString("title"), it.optString("artist"),
                it.optBoolean("playing"), it.optLong("position_ms"), it.optLong("length_ms"),
                SystemClock.elapsedRealtime(),
            )
        }
        o.optJSONObject("leds")?.let { _leds.value = Leds(it.optBoolean("caps"), it.optBoolean("num")) }
    }

    private fun onLibrary(o: JSONObject) {
        val arr = o.optJSONArray("shortcuts") ?: JSONArray()
        _library.value = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            Shortcut(
                id = s.optString("id"),
                user = s.optString("source") == "user",
                app = s.optString("app"),
                group = s.optString("group"),
                name = s.optString("name"),
                keys = s.optString("keys"),
                steps = parseSteps(s.optJSONArray("steps")),
                favorite = s.optBoolean("favorite"),
            )
        }
    }

    private fun parseSteps(a: JSONArray?): List<Step> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val s = a.getJSONObject(i)
            when {
                s.has("chord") -> s.getJSONArray("chord").let { c -> Step.Chord((0 until c.length()).map { c.getInt(it) }) }
                s.has("text") -> Step.Text(s.getString("text"))
                s.has("wait_ms") -> Step.Wait(s.getInt("wait_ms"))
                else -> null
            }
        }
    }

    // ---- input to the computer ---------------------------------------------

    private fun send(frame: ByteArray) {
        if (authed) conn?.send(frame)
    }

    /**
     * The touch surface's size. A surface that only shrinks (the input bar opening)
     * keeps the bigger configuration, so the computer's touchpad is not recreated.
     */
    fun touchConfig(w: Int, h: Int, pxPerMmX: Int, pxPerMmY: Int) {
        val s = touchSize
        if (w <= s[0] && h <= s[1] && pxPerMmX == s[2] && pxPerMmY == s[3]) return
        touchSize = intArrayOf(maxOf(w, s[0]), maxOf(h, s[1]), pxPerMmX, pxPerMmY)
        val f = Proto.touchConfig(touchSize[0], touchSize[1], pxPerMmX, pxPerMmY)
        touchConfig = f
        send(f)
    }

    fun touch(timeMs: Long, contacts: List<Triple<Int, Int, Int>>) = send(Proto.touch(timeMs, contacts))
    fun button(button: Int, down: Boolean) = send(Proto.button(button, down))
    fun key(code: Int, down: Boolean) = send(Proto.key(code, down))
    fun tap(code: Int) { key(code, true); key(code, false) }
    fun motion(dx: Int, dy: Int) = send(Proto.motion(dx, dy))
    fun chord(codes: List<Int>) = send(Proto.chord(codes))
    fun text(s: String) = send(Proto.text(s))
    fun run(id: String) = send(Proto.json(Proto.RUN, JSONObject().put("id", id)))

    fun favorite(id: String, on: Boolean) =
        send(Proto.json(Proto.FAVORITE, JSONObject().put("id", id).put("favorite", on)))

    fun deleteShortcut(id: String) = send(Proto.json(Proto.SHORTCUT_DELETE, JSONObject().put("id", id)))

    fun putShortcut(id: String?, app: String, name: String, steps: List<Step>) {
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(
                when (s) {
                    is Step.Chord -> JSONObject().put("chord", JSONArray(s.codes))
                    is Step.Text -> JSONObject().put("text", s.text)
                    is Step.Wait -> JSONObject().put("wait_ms", s.ms)
                },
            )
        }
        val o = JSONObject().put("app", app).put("name", name).put("steps", arr)
        if (id != null) o.put("id", id)
        send(Proto.json(Proto.SHORTCUT_PUT, o))
    }

    // ---- storage -----------------------------------------------------------

    private fun loadPairing(): Pairing? {
        val s = prefs.getString("pairing", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            Pairing(o.getString("name"), o.getString("address"), o.getInt("port"), o.getString("pin"), o.getString("token"))
        }.getOrNull()
    }

    private fun savePairing(p: Pairing) {
        val o = JSONObject().put("name", p.name).put("address", p.address).put("port", p.port)
            .put("pin", p.pin).put("token", p.token)
        prefs.edit().putString("pairing", o.toString()).apply()
        _pairing.value = p
    }
}
