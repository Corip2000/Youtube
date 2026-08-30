package ru.corip.shortsoffline.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Вход в YouTube. Делается один раз: у браузера внутри приложения своя
 * корзина с куками, она переживает перезапуск. Тот же браузер потом
 * открывает твою ленту рекомендаций.
 *
 * Для скачивания вход не нужен вовсе — ролики публичные. Он нужен только
 * чтобы лента была твоей, а не обезличенной.
 */
private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }

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
                Text("Войди в аккаунт, потом жми «Готово»", style = Type.Label)
            }
            Text(
                "Готово \u2713",
                style = Type.Data.copy(color = Palette.Signal),
                modifier = Modifier.clickable { onDone() },
            )
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
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = UA
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                    loadUrl(LOGIN_URL)
                    web = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
