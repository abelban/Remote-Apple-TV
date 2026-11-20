package dev.abelman.remote

import android.app.Application
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.util.concurrent.Executors

class RemoteApplication : Application() {
    val remote by lazy { RemoteRepository(this) }
}

enum class ConnectionState { IDLE, CONNECTING, STARTING_PAIRING, AWAITING_PIN, VERIFYING_PIN, CONNECTED, ERROR, PREVIEW }

class RemoteRepository(private val context: Application) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val store = DeviceStore(context)
    private val saved = linkedMapOf<String, TvDevice>()
    private val nearby = linkedMapOf<String, TvDevice>()
    private val listeners = linkedSetOf<() -> Unit>()
    private val discovery = DeviceDiscovery(context, ::discovered) { message ->
        scanning = false
        if (message != null) error = message
        notifyChanged()
    }
    private val python by lazy {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        Python.getInstance().getModule("remote_client")
    }
    private var operation = 0
    private var pendingCommands = 0
    private var statusRunning = false
    private val poll = object : Runnable {
        override fun run() {
            if (state != ConnectionState.CONNECTED || listeners.isEmpty() || statusRunning) return
            statusRunning = true
            val token = operation
            background("status", emptyArray()) { result ->
                statusRunning = false
                if (token == operation && state == ConnectionState.CONNECTED && result.optBoolean("ok")) {
                    updateStatus(result)
                    notifyChanged()
                }
                schedulePoll()
            }
        }
    }

    var state = ConnectionState.IDLE; private set
    var selected: TvDevice? = null; private set
    var scanning = false; private set
    var error: String? = null; private set
    var muted = false; private set
    var keyboardFocused = false; private set
    var canMute = false; private set

    val devices: List<TvDevice>
        get() = (saved.values + nearby.values.filter { it.id !in saved }).toList()
    val isBusy: Boolean
        get() = state in setOf(ConnectionState.CONNECTING, ConnectionState.STARTING_PAIRING, ConnectionState.VERIFYING_PIN)

    init {
        runCatching { store.load().forEach { saved[it.id] = it } }
            .onFailure { error = "Не удалось прочитать сохранённые сопряжения. Подключите Apple TV заново." }
        selected = saved[store.activeId]
    }

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
        schedulePoll()
    }

    fun removeObserver(listener: () -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            discovery.stop()
            scanning = false
            main.removeCallbacks(poll)
        }
    }

    private fun notifyChanged() { listeners.toList().forEach { it() } }

    private fun schedulePoll() {
        main.removeCallbacks(poll)
        if (listeners.isNotEmpty() && state == ConnectionState.CONNECTED) main.postDelayed(poll, 1500)
    }

    private fun background(method: String, args: Array<out Any>, callback: (JSONObject) -> Unit) {
        worker.execute {
            val result = try {
                JSONObject(python.callAttr(method, *args).toString())
            } catch (_: Exception) {
                JSONObject().put("ok", false).put("error", "engine")
            }
            main.post { callback(result) }
        }
    }

    fun scan() {
        if (isBusy) return
        error = null
        nearby.clear()
        scanning = true
        notifyChanged()
        discovery.start()
    }

    private fun discovered(device: TvDevice) {
        // Keep the saved identity when the same TV's DHCP address has changed.
        val known = saved.values.find { it.id == device.id ||
            (it.name == device.name && it.properties["rpmd"] == device.properties["rpmd"]) }
        if (known != null) {
            val updated = known.copy(address = device.address, port = device.port, properties = device.properties)
            saved[known.id] = updated
            if (selected?.id == known.id) selected = updated
        } else nearby[device.id] = device
        notifyChanged()
    }

    fun discoverHost(address: String, done: (String?) -> Unit) {
        if (isBusy || scanning) return
        scanning = true
        notifyChanged()
        background("discover_host", arrayOf(address)) { result ->
            scanning = false
            if (result.optBoolean("ok")) {
                val values = result.getJSONArray("devices")
                for (i in 0 until values.length()) discovered(TvDevice.from(values.getJSONObject(i)))
                done(if (values.length() == 0) "Apple TV не ответил. Проверьте IP-адрес и доступ к локальной сети." else null)
            } else done(message(result))
            notifyChanged()
        }
    }

    fun reconnect() {
        selected?.takeIf { it.credentials.isNotEmpty() && state in setOf(ConnectionState.IDLE, ConnectionState.ERROR) }
            ?.let(::choose)
    }

    fun choose(device: TvDevice) {
        if (isBusy) return
        discovery.stop()
        scanning = false
        main.removeCallbacks(poll)
        val token = ++operation
        error = null
        selected = device
        muted = false
        keyboardFocused = false
        val paired = device.credentials.isNotEmpty()
        state = if (paired) ConnectionState.CONNECTING else ConnectionState.STARTING_PAIRING
        notifyChanged()
        background(if (paired) "connect" else "begin_pairing", arrayOf(device.json().toString())) { result ->
            if (token != operation) return@background
            if (result.optBoolean("ok")) {
                if (paired) connected(result) else state = ConnectionState.AWAITING_PIN
            } else {
                state = ConnectionState.ERROR
                error = message(result)
            }
            notifyChanged()
        }
    }

    fun pair(pin: String) {
        if (state != ConnectionState.AWAITING_PIN || pin.length != 4 || pin.any { it !in '0'..'9' }) return
        val token = operation
        state = ConnectionState.VERIFYING_PIN
        error = null
        notifyChanged()
        background("finish_pairing", arrayOf(pin)) { result ->
            if (token != operation) return@background
            if (!result.optBoolean("ok")) {
                state = ConnectionState.ERROR
                error = if (result.optString("error") == "authentication")
                    "Код не подошёл или истёк. Выберите Apple TV ещё раз, чтобы получить новый код." else message(result)
                notifyChanged()
                return@background
            }
            val device = selected!!.copy(credentials = result.getString("credentials"))
            saved[device.id] = device
            if (runCatching { store.save(saved.values) }.isFailure) {
                saved.remove(device.id)
                state = ConnectionState.ERROR
                error = "Не удалось безопасно сохранить сопряжение. Попробуйте подключиться ещё раз."
                notifyChanged()
                return@background
            }
            state = ConnectionState.IDLE
            choose(device)
        }
    }

    private fun connected(result: JSONObject) {
        state = ConnectionState.CONNECTED
        store.activeId = selected?.id
        updateStatus(result)
        schedulePoll()
        TileService.requestListeningState(context, ComponentName(context, RemoteTileService::class.java))
    }

    private fun updateStatus(result: JSONObject) {
        muted = result.optBoolean("muted")
        keyboardFocused = result.optBoolean("keyboard")
        canMute = result.optBoolean("can_mute")
    }

    fun cancelPairing() {
        if (state !in setOf(ConnectionState.STARTING_PAIRING, ConnectionState.AWAITING_PIN, ConnectionState.VERIFYING_PIN)) return
        ++operation
        state = ConnectionState.IDLE
        background("disconnect", emptyArray()) { }
        notifyChanged()
    }

    fun command(name: String, action: String = "tap", text: String = "", done: (String?) -> Unit = {}) {
        if (state == ConnectionState.PREVIEW) { done("Режим просмотра. Подключите Apple TV для управления."); return }
        if (state != ConnectionState.CONNECTED) { done("Сначала подключитесь к Apple TV."); return }
        // Bound gesture backlog so a slow or disconnected TV cannot replay stale swipes.
        if (pendingCommands >= 4) return
        pendingCommands++
        val token = operation
        background("command", arrayOf(name, action, text)) { result ->
            pendingCommands--
            if (token != operation) return@background
            if (result.optBoolean("ok")) {
                updateStatus(result)
                notifyChanged()
                done(null)
            } else {
                val code = result.optString("error")
                if (code !in setOf("unsupported", "invalid_input")) {
                    ++operation
                    state = ConnectionState.ERROR
                    error = message(result)
                    main.removeCallbacks(poll)
                    notifyChanged()
                }
                done(if (code == "unsupported") when (name) {
                    "text" -> "Сначала откройте поле ввода на Apple TV."
                    "mute" -> "Для этого аудиовыхода Apple TV не предоставляет управление звуком."
                    else -> "Эта команда недоступна на вашем Apple TV."
                } else message(result))
            }
        }
    }

    fun preview() {
        ++operation
        discovery.stop()
        scanning = false
        background("disconnect", emptyArray()) { }
        selected = TvDevice("preview", "Гостиная", "", 0)
        state = ConnectionState.PREVIEW
        error = null
        keyboardFocused = false
        muted = false
        notifyChanged()
    }

    fun forget(device: TvDevice) {
        val remaining = saved.filterKeys { it != device.id }
        if (runCatching { store.save(remaining.values) }.isFailure) {
            error = "Не удалось удалить сохранённое сопряжение."
            notifyChanged()
            return
        }
        saved.remove(device.id)
        if (selected?.id == device.id) {
            ++operation
            background("disconnect", emptyArray()) { }
            state = ConnectionState.IDLE
            selected = null
            store.activeId = null
        }
        notifyChanged()
    }

    private fun message(result: JSONObject): String = when (result.optString("error")) {
        "authentication" -> "Apple TV отклонил подключение. Удалите сопряжение и подключитесь по новому коду."
        "timeout" -> "Apple TV не отвечает. Проверьте, что оба устройства в одной сети Wi-Fi."
        "connection" -> "Соединение прервано. Проверьте Wi-Fi и повторите подключение."
        "invalid_input" -> "Проверьте введённые данные."
        "engine" -> "Не удалось запустить модуль подключения. Перезапустите приложение."
        else -> "Не удалось выполнить команду. Повторите подключение к Apple TV."
    }
}
