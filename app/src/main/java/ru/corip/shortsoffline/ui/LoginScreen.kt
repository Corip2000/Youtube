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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Обычный вход на youtube.com в WebView. Куки забираются, когда пользователь
 * нажимает «Готово» — отдельного OAuth-клиента и ключей не требуется.
 *
 * Google иногда отказывает WebView со словами «этот браузер небезопасен».
 * Поэтому подменяем User-Agent на десктопный Chrome; если всё равно не пускает,
 * остаётся импорт cookies.txt из браузера на компьютере.
 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0.0.0 Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit) {
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
        Spacer(Modifier.height(2.dp))

        AndroidView(
            factory = { context ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(context).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = DESKTOP_UA
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                    loadUrl("https://accounts.google.com/ServiceLogin?service=youtube")
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
