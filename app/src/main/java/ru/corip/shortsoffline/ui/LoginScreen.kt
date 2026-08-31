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
    platform: YtDlp.Platform,
    desktop: Boolean,
    onDesktopChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    var root by remember { mutableStateOf<FrameLayout?>(null) }
    var main by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) { onDispose { root?.removeAllViews(); main?.destroy() } }

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
                Text("Вход в ${platform.title}", style = Type.Data)
                Text(
                    if (progress in 1..99) "Загрузка $progress%"
                    else "Войди в аккаунт, потом жми «Готово»",
                    style = Type.Label,
                )
            }
            Text(
                "Готово \u2713",
                style = Type.Data.copy(color = Palette.Signal),
                modifier = Modifier.clickable { onDone() },
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
                main?.loadUrl(platform.loginUrl)
            }
            AppButton(
                if (desktop) "Вид: компьютер" else "Вид: телефон",
                Modifier.weight(1f),
            ) {
                val next = !desktop
                onDesktopChange(next)
                main?.settings?.userAgentString = platform.userAgent(next)
                main?.loadUrl(platform.loginUrl)
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
                    settings.userAgentString = platform.userAgent(desktop)
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
                        popup.webViewClient = WebViewClient()
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
                web.loadUrl(platform.loginUrl)

                container.addView(web)
                root = container
                main = web
                container
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
