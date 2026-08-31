package ru.corip.shortsoffline

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
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

        @Volatile var target: Int = 20

        /** Подписи кнопок: правятся в приложении под свой язык и мод. */
        @Volatile var shareLabels: List<String> = listOf("Поделиться", "Share", "Отправить")
        @Volatile var copyLabels: List<String> = listOf("Копировать ссылку", "Copy link", "Скопировать ссылку")

        fun start(count: Int) {
            target = count
            links.clear()
            instance?.beginRun()
        }

        fun stop() {
            instance?.endRun("Остановлено вручную")
        }
    }

    private var worker: Thread? = null
    private var savedVolume = -1

    override fun onServiceConnected() {
        instance = this
        status = "Готов"
    }

    override fun onDestroy() {
        instance = null
        running = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ------------------------------------------------------------ прогон

    fun beginRun() {
        if (running) return
        running = true
        mute(true)
        worker = Thread { loop() }.also { it.start() }
    }

    private fun endRun(reason: String) {
        running = false
        mute(false)
        status = "$reason · собрано ${links.size}"
    }

    private fun loop() {
        // Даём время переключиться в приложение с лентой.
        status = "Открой ленту — начну через 5 секунд"
        sleep(5000)

        var idle = 0
        while (running && links.size < target && idle < 6) {
            val before = links.size
            status = "Собрано ${links.size} из $target"

            if (!tapByLabels(shareLabels)) {
                idle++
                sleep(1200)
                continue
            }
            sleep(1200)

            if (!tapByLabels(copyLabels)) {
                // Панель открылась, но нужной кнопки нет — закрываем и листаем.
                performGlobalAction(GLOBAL_ACTION_BACK)
                idle++
                sleep(800)
                swipeUp()
                sleep(1500)
                continue
            }
            sleep(1200)

            readClipboard()?.let { links.add(it) }
            if (links.size == before) idle++ else idle = 0

            swipeUp()
            sleep(1800)
        }

        endRun(
            when {
                links.size >= target -> "Готово"
                idle >= 6 -> "Кнопки не найдены — проверь подписи"
                else -> "Завершено"
            }
        )
    }

    // ------------------------------------------------------------ действия

    /** Ищет кнопку по подписи и нажимает — включая случай, когда нажимается родитель. */
    private fun tapByLabels(labels: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        for (label in labels) {
            val found = root.findAccessibilityNodeInfosByText(label) ?: continue
            for (node in found) {
                if (clickNodeOrParent(node)) return true
            }
        }
        return false
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
