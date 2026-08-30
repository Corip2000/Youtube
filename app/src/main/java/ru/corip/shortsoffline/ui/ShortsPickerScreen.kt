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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import ru.corip.shortsoffline.Cookies

/**
 * Личная лента шортсов, собираемая автоматически.
 *
 * Списка этой ленты наружу не существует — YouTube отдаёт её только своему
 * приложению. Но страница ленты держит номера роликов прямо в своих данных,
 * так что открываем её с твоими куками, вычитываем номера и листаем дальше,
 * пока не наберётся нужное количество. Смотреть при этом ничего не надо.
 */

/** Номера роликов лежат в данных страницы как "videoId":"XXXXXXXXXXX". */
private const val JS_EXTRACT = """
(function(){
  var found = [];
  var seen = {};
  var re = /"videoId":"([A-Za-z0-9_-]{11})"/g;
  var html = document.documentElement.innerHTML;
  var m;
  while ((m = re.exec(html)) !== null) {
    if (!seen[m[1]]) { seen[m[1]] = 1; found.push(m[1]); }
  }
  return found.join(",");
})()
"""

/** Листаем вперёд и прокруткой, и стрелкой — что-нибудь да сработает. */
private const val JS_ADVANCE = """
(function(){
  window.scrollBy(0, window.innerHeight);
  var e = new KeyboardEvent('keydown', {key:'ArrowDown', keyCode:40, which:40, bubbles:true});
  document.dispatchEvent(e);
  document.body.dispatchEvent(e);
  return 'ok';
})()
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ShortsPickerScreen(
    collected: List<String>,
    target: Int,
    onCollect: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("Открываю твою ленту…") }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { Cookies.injectIntoWebView(context) }

    LaunchedEffect(web) {
        val view = web ?: return@LaunchedEffect
        delay(2500)
        repeat(40) {
            if (finished) return@LaunchedEffect
            status = "Читаю ленту… собрано ${collected.size} из $target"

            view.evaluateJavascript(JS_EXTRACT) { raw ->
                raw.trim('"')
                    .replace("\\\"", "")
                    .split(",")
                    .filter { id -> id.length == 11 }
                    .forEach(onCollect)
            }
            delay(500)

            if (collected.size >= target) {
                finished = true
                status = "Готово, собрано ${collected.size}"
                onDone()
                return@LaunchedEffect
            }

            view.evaluateJavascript(JS_ADVANCE, null)
            delay(900)
        }
        finished = true
        status = if (collected.isEmpty())
            "Ничего не нашлось. Похоже, лента открылась без входа — проверь cookies.txt."
        else "Собрано ${collected.size}, дальше лента не отдаёт"
        if (collected.isNotEmpty()) onDone()
    }

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
                Text("Твоя лента шортсов", style = Type.Data)
                Text(status, style = Type.Label)
            }
            Text(
                "Отмена",
                style = Type.Small.copy(color = Palette.Muted),
                modifier = Modifier.clickable { onCancel() },
            )
        }

        Box(Modifier.padding(horizontal = 18.dp)) {
            Bar(
                if (target > 0) collected.size.toFloat() / target else 0f,
                color = Palette.Jade,
            )
        }
        Spacer(Modifier.height(12.dp))

        if (collected.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 12.dp)
            ) {
                AppButton("Хватит, качаем", tail = "${collected.size} шт.", primary = true) {
                    finished = true
                    onDone()
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Palette.Edge)
        )

        // Лента остаётся видимой: если YouTube спросит согласие или вход,
        // ты это заметишь, а не будешь смотреть на зависший счётчик.
        AndroidView(
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                    loadUrl("https://m.youtube.com/shorts")
                    web = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
