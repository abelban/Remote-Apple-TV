package dev.abelman.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.min

enum class RemoteIcon { MUTE, POWER, PLAY_PAUSE, BACK, HOME, KEYBOARD }

/** Native views and vector strokes preserve the reference proportions at every screen density. */
@android.annotation.SuppressLint("ViewConstructor")
class RemoteView(context: Context, private val action: (String, String) -> Unit, choose: () -> Unit, keyboard: () -> Unit) : ViewGroup(context) {
    private val mute = RemoteButton(context, RemoteIcon.MUTE, "Выключить звук") { action("mute", it) }
    private val selector = DeviceSelector(context).apply { setOnClickListener { choose() } }
    private val power = RemoteButton(context, RemoteIcon.POWER, "Питание Apple TV") { action("power", it) }
    private val touchpad = TouchpadView(context, action)
    private val play = RemoteButton(context, RemoteIcon.PLAY_PAUSE, "Воспроизведение или пауза") { action("play_pause", it) }
    private val back = RemoteButton(context, RemoteIcon.BACK, "Назад") { action("menu", it) }
    private val home = RemoteButton(context, RemoteIcon.HOME, "Домой. Удерживайте для Пункта управления") { action("home", it) }
    private val textInput = RemoteButton(context, RemoteIcon.KEYBOARD, "Ввести текст на Apple TV") { keyboard() }
    private val positions = listOf(mute, selector, power, touchpad, play, back, home, textInput).associateWith { RectF() }

    init {
        setBackgroundColor(Color.BLACK)
        listOf(mute, selector, power, touchpad, play, back, home, textInput).forEach(::addView)
        textInput.visibility = GONE
    }

    fun update(repository: RemoteRepository) {
        selector.label = when (repository.state) {
            ConnectionState.CONNECTING -> "Подключение…"
            ConnectionState.STARTING_PAIRING, ConnectionState.VERIFYING_PIN -> "Сопряжение…"
            else -> repository.selected?.name ?: "Выбрать TV"
        }
        selector.contentDescription = "Выбрать Apple TV. ${selector.label}" +
            if (repository.state == ConnectionState.PREVIEW) ". Режим просмотра" else ""
        mute.highlighted = repository.muted
        mute.contentDescription = if (repository.muted) "Включить звук" else "Выключить звук"
        textInput.visibility = if (repository.keyboardFocused && repository.state == ConnectionState.CONNECTED) VISIBLE else GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val usableHeight = height - paddingBottom - paddingTop
        val density = resources.displayMetrics.density
        val designWidth = min(width.toFloat(), min(480f * density, usableHeight * 414f / 720f))
        val sx = designWidth / 414f
        val sy = usableHeight / 896f
        val left = (width - designWidth) / 2f
        fun place(view: View, x: Float, y: Float, w: Float, h: Float) {
            positions.getValue(view).set(left + x * sx, paddingTop + y * sy, left + (x + w) * sx, paddingTop + y * sy + h * sx)
        }
        place(mute, 16f, 48f, 56f, 56f)
        place(selector, 88f, 48f, 238f, 56f)
        place(power, 342f, 48f, 56f, 56f)
        val padTop = paddingTop + maxOf(136f * sy, 48f * sy + 80f * sx)
        val padBottom = paddingTop + 691f * sy
        positions.getValue(touchpad).set(left + 16f * sx, padTop, left + 398f * sx, maxOf(padTop + 48f * sx, padBottom))
        place(play, 22f, 749f, 84f, 84f)
        place(back, 152f, 736f, 110f, 110f)
        place(home, 308f, 749f, 84f, 84f)
        place(textInput, 185f, 624f, 44f, 44f)
        for ((view, rect) in positions) {
            view.measure(MeasureSpec.makeMeasureSpec(rect.width().toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height().toInt(), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        positions.forEach { (view, rect) -> view.layout(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()) }
    }
}

@android.annotation.SuppressLint("ViewConstructor")
class RemoteButton(context: Context, private val icon: RemoteIcon, description: String, private val trigger: (String) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPath = Path()
    private var edgeShader: Shader? = null
    var highlighted = false
        set(value) { field = value; invalidate() }
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (icon == RemoteIcon.HOME || icon == RemoteIcon.BACK) fire("double") else performClick()
            return true
        }
        override fun onLongPress(event: MotionEvent) {
            if (icon in setOf(RemoteIcon.HOME, RemoteIcon.BACK)) fire("hold")
        }
    })

    init {
        contentDescription = description
        isClickable = true
        isFocusable = true
    }

    private fun fire(action: String) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        trigger(action)
    }

    override fun performClick(): Boolean { super.performClick(); fire("tap"); return true }

    // GestureDetector routes confirmed taps through performClick for accessibility.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { isPressed = true; animate().scaleX(.95f).scaleY(.95f).setDuration(100).start(); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { isPressed = false; animate().scaleX(1f).scaleY(1f).setDuration(160).start(); invalidate() }
        }
        detector.onTouchEvent(event)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        if (icon in setOf(RemoteIcon.HOME, RemoteIcon.BACK)) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) { fire("hold"); return true }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        edgeShader = LinearGradient(0f, 0f, 0f, min(w, h).toFloat(), intArrayOf(0xff414143.toInt(), 0xff101011.toInt(), 0xff343436.toInt()), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        val radius = diameter / 2f
        val topButton = icon == RemoteIcon.MUTE || icon == RemoteIcon.POWER
        val colour = when { isPressed -> 0xff303032.toInt(); highlighted -> 0xffdddddf.toInt(); topButton -> 0xff202021.toInt(); else -> 0xff101011.toInt() }
        paint.style = Paint.Style.FILL
        paint.color = colour
        paint.shader = null
        canvas.drawCircle(width / 2f, height / 2f, radius - .5f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density * .7f
        paint.shader = edgeShader
        canvas.drawCircle(width / 2f, height / 2f, radius - 1f, paint)
        paint.shader = null
        val iconSize = diameter * when (icon) { RemoteIcon.BACK -> .36f; RemoteIcon.HOME, RemoteIcon.PLAY_PAUSE -> .38f; else -> .42f }
        canvas.save()
        canvas.translate((width - iconSize) / 2f, (height - iconSize) / 2f)
        canvas.scale(iconSize / 32f, iconSize / 32f)
        paint.color = if (highlighted) 0xff323234.toInt() else 0xff85858b.toInt()
        paint.strokeWidth = if (icon == RemoteIcon.BACK) 1.8f else 1.65f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        fun path(vararg points: Float) {
            iconPath.reset()
            iconPath.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) iconPath.lineTo(points[i], points[i + 1])
            canvas.drawPath(iconPath, paint)
        }
        when (icon) {
            RemoteIcon.MUTE -> {
                path(8f, 13f, 5f, 13f, 5f, 20f, 10f, 20f, 17f, 26f, 17f, 20f)
                path(12f, 10f, 17f, 6f, 17f, 15f)
                path(4f, 5f, 27f, 28f)
            }
            RemoteIcon.POWER -> { canvas.drawArc(5f, 5f, 27f, 27f, -50f, 280f, false, paint); canvas.drawLine(16f, 2f, 16f, 14f, paint) }
            RemoteIcon.PLAY_PAUSE -> { path(3f, 8f, 3f, 24f, 16f, 16f, 3f, 8f); canvas.drawLine(22f, 8f, 22f, 24f, paint); canvas.drawLine(29f, 8f, 29f, 24f, paint) }
            RemoteIcon.BACK -> path(22f, 5f, 10f, 16f, 22f, 27f)
            RemoteIcon.HOME -> { canvas.drawRoundRect(3f, 7f, 29f, 24f, 1.7f, 1.7f, paint); canvas.drawLine(10.5f, 28f, 21.5f, 28f, paint) }
            RemoteIcon.KEYBOARD -> { canvas.drawRoundRect(3f, 7f, 29f, 25f, 2f, 2f, paint); for (row in 0..1) for (col in 0..4) canvas.drawPoint(7f + col * 4.5f, 12f + row * 4.5f, paint); canvas.drawLine(10f, 21f, 22f, 21f, paint) }
        }
        canvas.restore()
    }
}

class DeviceSelector(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Path()
    private var edgeShader: Shader? = null
    private val televisionBadge = requireNotNull(context.getDrawable(R.drawable.ic_apple_tv))
    var label = "Выбрать TV"
        set(value) { field = value; invalidate() }

    init { isClickable = true; isFocusable = true; contentDescription = "Выбрать Apple TV" }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        edgeShader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xff49494b.toInt(), 0xff1c1c1e.toInt(), 0xff414143.toInt()), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val unit = height / 56f
        paint.style = Paint.Style.FILL
        paint.color = if (isPressed) 0xff303032.toInt() else 0xff202021.toInt()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = unit * .7f
        paint.shader = edgeShader
        canvas.drawRoundRect(.6f, .6f, width - .6f, height - .6f, height / 2f, height / 2f, paint)
        paint.shader = null
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 17.5f * unit
        val maxText = width - 79f * unit
        var text = label
        while (paint.measureText(text) > maxText && text.length > 2) text = text.dropLast(2) + "…"
        val textWidth = paint.measureText(text)
        val groupWidth = textWidth + 46f * unit
        val x = (width - groupWidth) / 2f
        televisionBadge.setBounds(x.toInt(), (19.5f * unit).toInt(), (x + 16f * unit).toInt(), (35.5f * unit).toInt())
        televisionBadge.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 17.5f * unit
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, x + 27f * unit, baseline, paint)
        paint.color = 0xff7e7e85.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * unit
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val arrowX = x + 38f * unit + textWidth
        arrow.reset()
        arrow.moveTo(arrowX, 23.5f * unit)
        arrow.lineTo(arrowX + 4.5f * unit, 28f * unit)
        arrow.lineTo(arrowX, 32.5f * unit)
        canvas.drawPath(arrow, paint)
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(info); info.className = "android.widget.Button" }
}

@android.annotation.SuppressLint("ViewConstructor")
class TouchpadView(context: Context, private val action: (String, String) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val clip = Path()
    private var accumulatedX = 0f
    private var accumulatedY = 0f
    private var lastCommand = 0L
    private var touchX = 0f
    private var touchY = 0f
    private val threshold get() = maxOf(18 * resources.displayMetrics.density, width * .065f)
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean { accumulatedX = 0f; accumulatedY = 0f; lastCommand = 0L; return true }
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(event: MotionEvent): Boolean { emit("select", "double"); return true }
        override fun onLongPress(event: MotionEvent) { emit("select", "hold") }
        override fun onScroll(first: MotionEvent?, event: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            accumulatedX -= distanceX
            accumulatedY -= distanceY
            if (maxOf(abs(accumulatedX), abs(accumulatedY)) >= threshold && SystemClock.uptimeMillis() - lastCommand >= 90) {
                val direction = if (abs(accumulatedX) >= abs(accumulatedY)) {
                    if (accumulatedX > 0) "right" else "left"
                } else if (accumulatedY > 0) "down" else "up"
                emit(direction)
                accumulatedX = 0f
                accumulatedY = 0f
                lastCommand = SystemClock.uptimeMillis()
            }
            return true
        }
    })

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Сенсорная панель. Свайп для навигации, касание для выбора"
    }

    private fun emit(command: String, input: String = "tap") {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        action(command, input)
    }

    override fun performClick(): Boolean { super.performClick(); emit("select"); return true }

    // GestureDetector routes confirmed taps through performClick for accessibility.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchX = event.x
        touchY = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); isPressed = true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isPressed = false
        }
        invalidate()
        detector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = 0xff1c1c1e.toInt()
        val radius = width * .12f
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, paint)
        if (isPressed) {
            canvas.save()
            clip.reset()
            clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            canvas.clipPath(clip)
            paint.color = 0x103e3e45
            canvas.drawCircle(touchX, touchY, width * .22f, paint)
            canvas.restore()
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(1001, "Вверх"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(1002, "Вниз"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(1003, "Влево"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(1004, "Вправо"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        when (action) {
            1001 -> emit("up")
            1002 -> emit("down")
            1003 -> emit("left")
            1004 -> emit("right")
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> emit("select", "hold")
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }
}
