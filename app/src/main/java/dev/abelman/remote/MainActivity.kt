package dev.abelman.remote

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val repository get() = (application as RemoteApplication).remote
    private lateinit var remoteView: RemoteView
    private var picker: Dialog? = null
    private var pickerDevices: LinearLayout? = null
    private var pickerStatus: TextView? = null
    private var pickerProgress: ProgressBar? = null
    private var refreshButton: TextView? = null
    private var textButton: TextView? = null
    private var pinDialog: AlertDialog? = null
    private var previousState = ConnectionState.IDLE
    private var lastPrompt = 0L
    private val observer: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        remoteView = RemoteView(this, ::control, ::showDevices, ::showKeyboard)
        if (Build.VERSION.SDK_INT >= 30) remoteView.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(remoteView)
        hideSystemBars()
        render()
        repository.reconnect()
        if (intent.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") showDevices()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_remote", false)) picker?.dismiss()
        repository.reconnect()
        if (intent.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") showDevices()
    }

    override fun onResume() {
        super.onResume()
        if (::remoteView.isInitialized) repository.reconnect()
    }

    override fun onStart() {
        super.onStart()
        repository.observe(observer)
        render()
    }

    override fun onStop() {
        repository.removeObserver(observer)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::remoteView.isInitialized) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onDestroy() {
        repository.removeObserver(observer)
        picker?.dismiss()
        pinDialog?.dismiss()
        super.onDestroy()
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        remoteView.update(repository)
        updatePicker()
        if (repository.state == ConnectionState.AWAITING_PIN && pinDialog == null) showPin()
        if (repository.state == ConnectionState.CONNECTED && previousState != ConnectionState.CONNECTED) {
            pinDialog?.dismiss()
            picker?.dismiss()
            toast("Подключено: ${repository.selected?.name}")
        }
        if (repository.state == ConnectionState.ERROR && previousState in setOf(ConnectionState.CONNECTING, ConnectionState.STARTING_PAIRING, ConnectionState.VERIFYING_PIN)) {
            pinDialog?.dismiss()
            showDevices(startScan = false)
        }
        previousState = repository.state
    }

    private fun control(command: String, action: String) {
        if (repository.state !in setOf(ConnectionState.CONNECTED, ConnectionState.PREVIEW)) {
            if (!repository.isBusy && repository.state != ConnectionState.AWAITING_PIN) showDevices()
            return
        }
        if (command == "power") {
            AlertDialog.Builder(this)
                .setTitle("Питание Apple TV")
                .setItems(arrayOf("Включить", "Режим сна")) { _, position -> send(if (position == 0) "wake" else "sleep") }
                .setNegativeButton("Отмена", null).show()
            return
        }
        send(command, action)
    }

    private fun send(command: String, action: String = "tap", text: String = "") {
        repository.command(command, action, text) { error ->
            if (error != null && !isFinishing) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastPrompt > 1800) { toast(error); lastPrompt = now }
            }
        }
    }

    private fun showDevices() = showDevices(startScan = true)

    private fun showDevices(startScan: Boolean) {
        if (isFinishing || isDestroyed || picker?.isShowing == true) return
        val dialog = Dialog(this)
        picker = dialog
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(24))
            background = rounded(0xff171719.toInt(), 30f)
        }
        sheet.addView(View(this).apply { background = rounded(0xff55555a.toInt(), 3f) }, LinearLayout.LayoutParams(dp(34), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(24) })
        sheet.addView(label("Apple TV", 28f, Color.WHITE, true))
        sheet.addView(label("Выберите устройство", 15f, 0xff98989f.toInt()).apply { setPadding(0, dp(6), 0, dp(20)) })

        val scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false }
        val contents = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pickerDevices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contents.addView(pickerDevices)
        val scanningRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(12)) }
        pickerProgress = ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(0xff94949c.toInt()) }
        scanningRow.addView(pickerProgress, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(10) })
        pickerStatus = label("", 14f, 0xff9b9ba2.toInt())
        scanningRow.addView(pickerStatus, LinearLayout.LayoutParams(0, -2, 1f))
        contents.addView(scanningRow)
        contents.addView(label("Телефон и Apple TV должны быть в одной локальной сети Wi-Fi.", 14f, 0xff7f7f88.toInt()).apply { setPadding(0, 0, 0, dp(16)) })
        refreshButton = sheetAction("Повторить поиск") { repository.scan() }
        contents.addView(refreshButton)
        contents.addView(sheetAction("Подключить по IP-адресу", secondary = true) { showManualAddress() })
        textButton = sheetAction("Ввести текст", secondary = true) { showKeyboard() }
        contents.addView(textButton)
        contents.addView(sheetAction("Добавить в быстрые настройки", secondary = true) { addTile() })
        contents.addView(sheetAction("Как пользоваться", secondary = true) { showHelp() })
        contents.addView(sheetAction("Посмотреть интерфейс", secondary = true) { repository.preview(); dialog.dismiss() })
        if (repository.state == ConnectionState.PREVIEW) contents.addView(label("Режим просмотра: команды не отправляются на телевизор.", 13f, 0xff96969e.toInt()).apply { setPadding(0, dp(10), 0, 0) })
        scroll.addView(contents)
        sheet.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.6f)
            setGravity(Gravity.BOTTOM)
            setLayout(minOf(resources.displayMetrics.widthPixels, dp(500)), -2)
        }
        dialog.setOnDismissListener {
            picker = null
            pickerDevices = null
            pickerStatus = null
            pickerProgress = null
            refreshButton = null
            textButton = null
        }
        dialog.show()
        dialog.window?.setLayout(minOf(resources.displayMetrics.widthPixels, dp(500)), minOf(sheet.measuredHeight.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels, (resources.displayMetrics.heightPixels * .88f).toInt()))
        updatePicker()
        if (startScan && !repository.scanning && !repository.isBusy) repository.scan()
    }

    private fun updatePicker() {
        val container = pickerDevices ?: return
        container.removeAllViews()
        for (device in repository.devices) {
            val connected = repository.selected?.id == device.id && repository.state == ConnectionState.CONNECTED
            val busy = repository.selected?.id == device.id && repository.isBusy
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = rounded(if (connected) 0xff303034.toInt() else 0xff242427.toInt(), 18f)
                isClickable = true
                isFocusable = true
                contentDescription = "${device.name}, ${if (connected) "подключено" else device.address}"
                setOnClickListener { if (!repository.isBusy) repository.choose(device) }
                if (device.credentials.isNotEmpty()) setOnLongClickListener { confirmForget(device); true }
                alpha = if (repository.isBusy && !busy) .5f else 1f
            }
            row.addView(label(device.name + if (connected) "  ✓" else "", 18f, Color.WHITE, true))
            row.addView(label(when {
                busy -> if (repository.state == ConnectionState.STARTING_PAIRING) "Ожидание кода на телевизоре…" else "Подключение…"
                connected -> "Подключено"
                device.credentials.isNotEmpty() -> "Сопряжено · ${device.address}"
                else -> device.address
            }, 13f, 0xffa2a2aa.toInt()).apply { setPadding(0, dp(5), 0, 0) })
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        pickerProgress?.visibility = if (repository.scanning || repository.isBusy) View.VISIBLE else View.GONE
        pickerStatus?.apply {
            text = repository.error ?: when {
                repository.state == ConnectionState.STARTING_PAIRING -> "Посмотрите на экран Apple TV…"
                repository.state == ConnectionState.CONNECTING -> "Подключение к Apple TV…"
                repository.scanning -> "Поиск Apple TV…"
                repository.devices.isEmpty() -> "Apple TV пока не найден"
                else -> "Удерживайте устройство, чтобы удалить сопряжение."
            }
            setTextColor(if (repository.error != null) 0xffffa39d.toInt() else 0xff9b9ba2.toInt())
        }
        refreshButton?.apply { isEnabled = !repository.scanning && !repository.isBusy; alpha = if (isEnabled) 1f else .4f }
        textButton?.visibility = if (repository.state == ConnectionState.CONNECTED) View.VISIBLE else View.GONE
    }

    private fun showPin() {
        val input = editText("0000").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            textSize = 30f
            gravity = Gravity.CENTER
            letterSpacing = .35f
            contentDescription = "Код сопряжения с экрана Apple TV"
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val wrapper = inputWrapper(input)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Код с экрана Apple TV")
            .setMessage("Введите 4 цифры, которые появились на телевизоре «${repository.selected?.name}».")
            .setView(wrapper)
            .setPositiveButton("Подключить", null)
            .setNegativeButton("Отмена") { _, _ -> repository.cancelPairing() }
            .create()
        pinDialog = dialog
        dialog.setOnCancelListener { repository.cancelPairing() }
        dialog.setOnDismissListener { pinDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (pin.length != 4 || pin.any { it !in '0'..'9' }) input.error = "Нужен код из 4 цифр"
                else {
                    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                    dialog.setCancelable(false)
                    repository.pair(pin)
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun showManualAddress() {
        if (repository.isBusy) return
        val input = editText("192.168.1.100").apply { inputType = InputType.TYPE_CLASS_PHONE; contentDescription = "IP-адрес Apple TV" }
        val dialog = AlertDialog.Builder(this)
            .setTitle("IP-адрес Apple TV")
            .setMessage("На Apple TV: Настройки → Сеть. Укажите IPv4-адрес устройства.")
            .setView(inputWrapper(input))
            .setPositiveButton("Найти", null)
            .setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = input.text.toString().trim()
                val parts = address.split('.')
                if (parts.size != 4 || parts.any { part -> part.toIntOrNull()?.let { it in 0..255 } != true }) {
                    input.error = "Введите IPv4-адрес, например 192.168.1.100"
                } else if (repository.scanning) {
                    input.error = "Дождитесь завершения поиска устройств"
                } else {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    repository.discoverHost(address) { error ->
                        if (error == null) dialog.dismiss()
                        else { input.error = error; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showKeyboard() {
        if (repository.state != ConnectionState.CONNECTED) { toast("Сначала подключитесь к Apple TV."); return }
        val input = editText("Текст для Apple TV").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; contentDescription = "Текст для Apple TV" }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Клавиатура Apple TV")
            .setMessage("Откройте поле поиска или ввода на телевизоре. Введённый текст заменит содержимое этого поля.")
            .setView(inputWrapper(input))
            .setPositiveButton("Отправить", null)
            .setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                repository.command("text", text = input.text.toString()) { error ->
                    if (error == null) dialog.dismiss()
                    else { input.error = error; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun confirmForget(device: TvDevice) {
        AlertDialog.Builder(this).setTitle("Забыть «${device.name}»?")
            .setMessage("Для следующего подключения понадобится новый код с экрана Apple TV.")
            .setPositiveButton("Забыть") { _, _ -> repository.forget(device) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, RemoteTileService::class.java), getString(R.string.tile_name),
                Icon.createWithResource(this, R.drawable.ic_remote), mainExecutor,
            ) { result ->
                toast(when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Пульт добавлен в быстрые настройки"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Пульт уже есть в быстрых настройках"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Плитку можно добавить позже"
                    else -> "Откройте быстрые настройки и добавьте плитку «Пульт Apple TV» через редактирование."
                })
            }
        } else AlertDialog.Builder(this).setTitle("Пульт в быстрых настройках")
            .setMessage("Дважды потяните шторку вниз, нажмите карандаш или «Изменить» и перетащите плитку «Пульт Apple TV» в активную область.")
            .setPositiveButton("Понятно", null).show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("Как пользоваться пультом")
            .setMessage("Смахивайте по большой панели для навигации. Касание выбирает пункт, удержание вызывает дополнительное действие.\n\n«Назад» возвращает на предыдущий экран. Кнопка с телевизором открывает главный экран; двойное нажатие — переключение приложений, удержание — Пункт управления Apple TV.\n\nКлавиши громкости телефона меняют громкость Apple TV. Возможность регулировки и отключения звука зависит от аудиовыхода.\n\nДля быстрого запуска добавьте плитку «Пульт Apple TV» в шторку Android.\n\nSiri и ИК-управление телевизором не поддерживаются. Это независимое приложение для Apple TV HD и Apple TV 4K.")
            .setPositiveButton("Понятно", null).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val command = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "volume_up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "volume_down"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "select"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "play_pause"
            else -> null
        }
        if (command != null && repository.state == ConnectionState.CONNECTED && picker?.isShowing != true && pinDialog?.isShowing != true) {
            send(command)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(colour: Int, radius: Float) = GradientDrawable().apply { setColor(colour); cornerRadius = radius * resources.displayMetrics.density }
    private fun label(value: String, size: Float, colour: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(colour)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun sheetAction(value: String, secondary: Boolean = false, action: () -> Unit) = label(value, 15f, if (secondary) 0xffd1d1d7.toInt() else Color.BLACK, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(15), dp(12), dp(15))
        background = rounded(if (secondary) 0xff252528.toInt() else 0xffededf0.toInt(), 15f)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
    }
    private fun editText(placeholder: String) = EditText(this).apply {
        hint = placeholder
        setTextColor(Color.WHITE)
        setHintTextColor(0xff777780.toInt())
        isSingleLine = true
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private fun inputWrapper(input: EditText) = LinearLayout(this).apply {
        setPadding(dp(20), dp(4), dp(20), dp(4))
        addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}
