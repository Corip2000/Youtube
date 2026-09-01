package ru.corip.shortsoffline

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Невидимый приёмник ссылок.
 *
 * Во время автосбора служба жмёт «Поделиться» → ShortsOffline, и ссылка
 * приходит сюда. Показывать при этом экран нельзя: он выкинет тебя из ленты
 * и собьёт весь прогон. Поэтому окна нет — ссылка забирается и управление
 * тут же возвращается обратно.
 *
 * Когда сбор не идёт, ссылка передаётся в основное окно как обычно.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val link = Regex("""https?://\S+""").find(text)?.value

        if (link != null && ClickerService.running) {
            ClickerService.links.add(link)
        } else if (link != null) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }
        finish()
    }
}
