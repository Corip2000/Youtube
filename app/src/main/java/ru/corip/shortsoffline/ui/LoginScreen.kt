package ru.corip.shortsoffline.ui

import android.annotation.SuppressLint
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ru.corip.shortsoffline.YtDlp

/**
 * Вход в аккаунт площадки.
 *
 * Две вещи, без которых страница входа зависает белым экраном или застывшим
 * логотипом:
 *
 * 1. Всплывающие окна. Сайты открывают форму входа отдельным окном, а
 *    встроенный браузер по умолчанию их запрещает — окно не создаётся, и
 *    страница просто стоит. Поэтому окна разрешены и показываются поверх.
 * 2. Вид сайта. Настольная версия на телефоне часто не дорисовывается.
 *    По умолчанию берём мобильную, но вид можно переключить — какая-то
 *    из двух обычно открывается.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    desktop: Boolean,
    onDesktopChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    var root by remember { mutableStateOf<FrameLayout?>(null) }
    var main by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var hasSession by remember { mutableStateOf(false) }

    // Без сброса на диск куки живут только в памяти окна. Окно уничтожается —
    // сессия пропадает, и площадка снова просит войти. Отсюда был круг.
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            root?.removeAllViews()
            main?.destroy()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Void)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.padding(end = 12.dp)) {
                Text("Вход в YouTube", style = Type.Data)
                Text(
                    when {
                        hasSession -> "Сессия поймана — можно жать «Готово»"
                        progress in 1..99 -> "Загрузка $progress%"
                        else -> "Войди в аккаунт, потом жми «Готово»"
                    },
                    style = Type.Label.copy(
                        color = if (hasSession) Palette.Jade else Palette.Muted
                    ),
                )
            }
            Text(
                "Готово \u2713",
                style = Type.Data.copy(color = Palette.Signal),
                modifier = Modifier.clickable {
                    CookieManager.getInstance().flush()
                    onDone()
                },
            )
        }

        // Спасательные кнопки: если страница застряла, обычно помогает
        // перезагрузка или другой вид сайта.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppButton("Перезагрузить", Modifier.weight(1f)) {
                main?.loadUrl(YtDlp.Yt.LOGIN_URL)
            }
            AppButton(
                if (desktop) "Вид: компьютер" else "Вид: телефон",
                Modifier.weight(1f),
            ) {
                val next = !desktop
                onDesktopChange(next)
                main?.settings?.userAgentString = YtDlp.Yt.userAgent(next)
                main?.loadUrl(YtDlp.Yt.LOGIN_URL)
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Palette.Edge)
        )

        AndroidView(
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                val container = FrameLayout(ctx)

                fun newWeb(): WebView = WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.userAgentString = YtDlp.Yt.userAgent(desktop)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                }

                val web = newWeb()
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?,
                    ): Boolean = false

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Каждый шаг входа может выдать новую куку — сохраняем сразу,
                        // чтобы ничего не потерялось при закрытии окна.
                        CookieManager.getInstance().flush()
                        hasSession = hasSessionCookie()
                        super.onPageFinished(view, url)
                    }
                }
                web.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progress = newProgress
                    }

                    /** Всплывающее окно входа: создаём и кладём поверх. */
                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message,
                    ): Boolean {
                        val popup = newWeb()
                        popup.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                CookieManager.getInstance().flush()
                                super.onPageFinished(view, url)
                            }
                        }
                        popup.webChromeClient = object : WebChromeClient() {
                            override fun onCloseWindow(window: WebView?) {
                                container.removeView(window)
                                window?.destroy()
                            }
                        }
                        container.addView(popup)
                        val transport = resultMsg.obj as WebView.WebViewTransport
                        transport.webView = popup
                        resultMsg.sendToTarget()
                        return true
                    }
                }
                web.loadUrl(YtDlp.Yt.LOGIN_URL)

                container.addView(web)
                root = container
                main = web
                container
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Есть ли в браузере кука входа Google. */
private fun hasSessionCookie(): Boolean {
    val jar = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
    val keys = listOf("SID", "__Secure-1PSID", "LOGIN_INFO")
    return keys.any { key -> Regex("(^|[;\\s])$key=[^;\\s]+").containsMatchIn(jar) }
}
