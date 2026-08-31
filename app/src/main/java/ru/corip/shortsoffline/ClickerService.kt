package ru.corip.shortsoffline

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.content.Context.MODE_PRIVATE
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Автокликер: сам жмёт «Поделиться» → «Копировать ссылку», забирает адрес
 * ролика и листает дальше. Нужен, потому что заглянуть в чужое приложение
 * нельзя, а вот повторить за тебя те же касания — можно.
 *
 * Кнопки ищутся по подписи, а не по координатам: разметка у приложений
 * меняется, а слово «Поделиться» остаётся. Подписи задаются в настройках,
 * так что подойдёт и мод, и другой язык.
 */
class ClickerService : AccessibilityService() {

    companion object {
        @Volatile var instance: ClickerService? = null
            private set

        /** Собранные ссылки — их потом заберёт приложение. */
        val links = linkedSetOf<String>()

        @Volatile var running = false
            private set

        @Volatile var status: String = "Не запущен"
            private set

        /** Сколько раз подряд буфер не отдал новую ссылку. */
        @Volatile var copyFailures = 0
            private set

        @Volatile var target: Int = 20

        /** Подписи кнопок: правятся в приложении под свой язык и мод. */
        @Volatile var shareLabels: List<String> = listOf("Поделиться", "Share", "Отправить")
        @Volatile var copyLabels: List<String> =
            listOf("Ссылка", "Link", "Копировать ссылку", "Copy link")

        /**
         * Куда ткнуть, если «Поделиться» — иконка без подписи и найти её
         * по тексту нельзя. Доли от ширины и высоты экрана: стрелка обычно
         * у правого края, чуть ниже середины.
         */
        @Volatile var shareX: Float = 0.93f
        @Volatile var shareY: Float = 0.62f

        fun start(count: Int) {
            target = count
            links.clear()
            instance?.beginRun()
        }

        fun stop() {
            instance?.endRun("Остановлено вручную")
        }

        /** Показать перекрестие поверх других приложений. */
        fun showPicker() = instance?.addPicker()

        fun hidePicker() = instance?.removePicker()
    }

    private var worker: Thread? = null
    private var savedVolume = -1
    private var picker: View? = null
    private var counter: View? = null
    private var counterText: TextView? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        status = "Готов"
    }

    override fun onDestroy() {
        removePicker()
        removeCounter()
        instance = null
        running = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ------------------------------------------------------ выбор места кнопки

    /**
     * Перекрестие поверх чужих приложений. Разрешение «поверх других окон»
     * не нужно: служба специальных возможностей рисует своим типом окна.
     * Ты открываешь TikTok, тащишь метку на стрелку и жмёшь «Готово».
     */
    fun addPicker() {
        if (picker != null) return
        val windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        val dp = metrics.density
        val markSize = (56 * dp).toInt()

        val mark = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(120, 255, 61, 132))
                setStroke((3 * dp).toInt(), Color.rgb(255, 61, 132))
            }
        }
        val done = Button(this).apply {
            text = "Готово"
            setBackgroundColor(Color.rgb(255, 61, 132))
            setTextColor(Color.BLACK)
        }

        val container = FrameLayout(this).apply {
            addView(mark, FrameLayout.LayoutParams(markSize, markSize))
            addView(
                done,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = markSize + (8 * dp).toInt() },
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels * shareX).toInt() - markSize / 2
            y = (metrics.heightPixels * shareY).toInt() - markSize / 2
        }

        // Метку таскаем пальцем, остальной экран остаётся рабочим.
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        mark.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    originX = params.x; originY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = originX + (event.rawX - startX).toInt()
                    params.y = originY + (event.rawY - startY).toInt()
                    windows.updateViewLayout(container, params)
                }
            }
            true
        }

        done.setOnClickListener {
            val cx = (params.x + markSize / 2f) / metrics.widthPixels
            val cy = (params.y + markSize / 2f) / metrics.heightPixels
            shareX = cx.coerceIn(0f, 1f)
            shareY = cy.coerceIn(0f, 1f)
            getSharedPreferences("shorts", MODE_PRIVATE).edit()
                .putFloat("share_x", shareX)
                .putFloat("share_y", shareY)
                .apply()
            status = "Место кнопки сохранено"
            removePicker()
        }

        windows.addView(container, params)
        picker = container
    }

    fun removePicker() {
        val view = picker ?: return
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        }
        picker = null
    }

    // ------------------------------------------------------ счётчик на экране

    /**
     * Счётчик поверх других приложений: видно, сколько собрано и сколько
     * осталось, не переключаясь обратно. Тут же кнопка остановки.
     */
    private fun addCounter() {
        if (counter != null) return
        val windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density

        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(Color.argb(220, 22, 20, 27))
                setStroke((1 * dp).toInt(), Color.rgb(255, 61, 132))
            }
            text = "Собрано 0"
        }
        val stop = Button(this).apply {
            text = "Стоп"
            setBackgroundColor(Color.rgb(255, 61, 132))
            setTextColor(Color.BLACK)
            setOnClickListener { endRun("Остановлено") }
        }

        val row = FrameLayout(this).apply {
            addView(label)
            addView(
                stop,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (44 * dp).toInt() },
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * dp).toInt()
            y = (60 * dp).toInt()
        }

        windows.addView(row, params)
        counter = row
        counterText = label
    }

    private fun updateCounter(text: String) {
        ui.post { counterText?.text = text }
    }

    private fun removeCounter() {
        val view = counter ?: return
        ui.post {
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            }
        }
        counter = null
        counterText = null
    }

    // ------------------------------------------------------------ прогон

    fun beginRun() {
        if (running) return
        running = true
        copyFailures = 0
        mute(true)
        ui.post { addCounter() }
        worker = Thread { loop() }.also { it.start() }
    }

    private fun endRun(reason: String) {
        running = false
        mute(false)
        status = "$reason · собрано ${links.size}"
        // Итог держим на экране подольше: ты в это время в TikTok и должен
        // успеть увидеть, чем всё кончилось.
        updateCounter("$reason\nСобрано ${links.size} — вернись в приложение")
        ui.postDelayed({ removeCounter() }, 12000)
    }

    private fun loop() {
        // Даём время переключиться в приложение с лентой.
        status = "Открой ленту — начну через 5 секунд"
        updateCounter("Старт через 5 секунд")
        sleep(5000)

        // Терпимость к промахам: панель может открыться с задержкой, ролик —
        // подгружаться. Раньше шесть подряд обрывали сбор на середине.
        var idle = 0
        while (running && (target <= 0 || links.size < target) && idle < 15) {
            val before = links.size
            status = "Собрано ${links.size} из $target"
            updateCounter(
                (if (target > 0) "Собрано ${links.size} из $target"
                else "Собрано ${links.size}") +
                    if (copyFailures >= 3) "\nБуфер не читается" else ""
            )

            // Сначала пробуем найти кнопку осмысленно, и только потом — вслепую
            // по месту: у TikTok «Поделиться» это стрелка без подписи.
            if (!tapByLabels(shareLabels)) tapAt(shareX, shareY)
            sleep(1400)

            if (!tapByLabels(copyLabels)) {
                // Панель не открылась или кнопки нет — закрываем и листаем дальше.
                performGlobalAction(GLOBAL_ACTION_BACK)
                idle++
                sleep(800)
                swipeUp()
                sleep(1600)
                continue
            }
            sleep(1400)

            // Один и тот же адрес означает, что копирование не сработало,
            // но это не повод останавливаться.
            val link = readClipboard()
            if (link == null || !links.add(link)) copyFailures++ else copyFailures = 0
            // Панель могла остаться открытой — закрываем, иначе свайп уйдёт в неё.
            performGlobalAction(GLOBAL_ACTION_BACK)
            sleep(500)
            if (links.size == before) idle++ else idle = 0

            // Цель достигнута — дальше листать незачем.
            if (links.size >= target) break

            swipeUp()
            sleep(1800)
        }

        endRun(
            when {
                target > 0 && links.size >= target -> "Готово"
                copyFailures >= 8 -> "Буфер обмена недоступен — ссылки не читаются"
                idle >= 15 -> "Кнопки не находятся — проверь подписи и перекрестие"
                else -> "Завершено"
            }
        )
    }

    // ------------------------------------------------------------ действия

    /**
     * Ищет кнопку по подписи. Проверяем и текст, и описание для незрячих:
     * у иконок без надписи осмысленным бывает только описание.
     */
    private fun tapByLabels(labels: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        for (label in labels) {
            root.findAccessibilityNodeInfosByText(label)?.forEach { node ->
                if (clickNodeOrParent(node)) return true
            }
        }
        return tapByDescription(root, labels)
    }

    /** Обход дерева в поисках описания: иконки подписаны только им. */
    private fun tapByDescription(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen < 600) {
            val node = queue.removeFirst()
            seen++
            val text = (node.contentDescription?.toString() ?: "")
            if (text.isNotBlank() && labels.any { text.contains(it, ignoreCase = true) }) {
                if (clickNodeOrParent(node)) return true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return false
    }

    /** Тычок вслепую по месту на экране — когда кнопку не опознать никак. */
    private fun tapAt(fx: Float, fy: Float) {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(metrics.widthPixels * fx, metrics.heightPixels * fy)
            lineTo(metrics.widthPixels * fx, metrics.heightPixels * fy)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun swipeUp() {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * 0.78f)
            lineTo(x, metrics.heightPixels * 0.22f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 260)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun readClipboard(): String? {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val text = runCatching {
            manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull() ?: return null
        return Regex("""https?://\S+""").find(text)?.value
    }

    /** Звук на время прогона выключаем: слушать полсотни роликов незачем. */
    private fun mute(on: Boolean) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (on) {
                savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } else if (savedVolume >= 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
                savedVolume = -1
            }
        }
    }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }
}
