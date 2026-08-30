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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Твоя лента шортсов.
 *
 * Браузер внутри приложения открывает настоящую ленту YouTube, приложение
 * вычитывает из её данных номера роликов и передаёт их скачивалке. Списка
 * этой ленты наружу не существует — YouTube отдаёт её только своему
 * приложению, поэтому другого пути нет.
 *
 * Вход делается один раз прямо здесь: у браузера своя корзина с куками,
 * она переживает перезапуск. Для скачивания вход не нужен вовсе —
 * ролики публичные.
 */

/**
 * Берём только настоящие шортсы. Подстрока "videoId" встречается на странице
 * повсюду — в рекламе, служебных блоках, боковых подборках, — поэтому ищем
 * ссылку вида /shorts/ID и переходы reelWatchEndpoint, которыми помечены
 * именно ролики ленты.
 */
private const val JS_EXTRACT = """
(function(){
  var found = [], seen = {};
  var html = document.documentElement.innerHTML;
  var patterns = [
    /\/shorts\/([A-Za-z0-9_-]{11})/g,
    /"reelWatchEndpoint":\{"videoId":"([A-Za-z0-9_-]{11})"/g
  ];
  for (var p = 0; p < patterns.length; p++) {
    var m;
    while ((m = patterns[p].exec(html)) !== null) {
      if (!seen[m[1]]) { seen[m[1]] = 1; found.push(m[1]); }
    }
  }
  return found.join(",");
})()
"""

private const val JS_LOGGED_IN = """
(function(){
  var h = document.documentElement.innerHTML;
  if (h.indexOf('"loggedIn":true') >= 0) return 'yes';
  if (h.indexOf('"loggedIn":false') >= 0) return 'no';
  return 'unknown';
})()
"""

private const val JS_ADVANCE = """
(function(){
  window.scrollBy(0, window.innerHeight);
  var e = new KeyboardEvent('keydown', {key:'ArrowDown', keyCode:40, which:40, bubbles:true});
  document.dispatchEvent(e); document.body.dispatchEvent(e);
  return 'ok';
})()
"""

private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube"

private const val FEED_URL = "https://m.youtube.com/shorts"

// Обычный мобильный Chrome: встроенный браузер по умолчанию помечает себя
// как «wv», и по этой метке Google отказывает во входе.
private const val UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ShortsFeedScreen(
    collected: List<String>,
    target: Int,
    onCollect: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var loggedIn by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf("Открываю ленту…") }
    var done by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(0) }

    DisposableEffect(Unit) { onDispose { web?.destroy() } }

    LaunchedEffect(web) {
        val view = web ?: return@LaunchedEffect

        // Ждём загрузку и выясняем, узнал ли нас YouTube.
        while (loggedIn == null) {
            delay(1500)
            view.evaluateJavascript(JS_LOGGED_IN) { r ->
                when (r.trim('"')) {
                    "yes" -> loggedIn = true
                    "no" -> loggedIn = false
                }
            }
        }

        if (loggedIn != true) {
            status = "Войди в аккаунт — это нужно один раз"
            return@LaunchedEffect
        }

        // Считаем сами: параметр collected внутри этого блока «заморожен»
        // на значении из момента запуска и обновляться не будет.
        val mine = linkedSetOf<String>()

        repeat(30) { step ->
            if (done) return@LaunchedEffect
            status = "Собираю ленту: ${mine.size} из $target"

            view.evaluateJavascript(JS_EXTRACT) { raw ->
                raw.trim('"').replace("\\\"", "").split(",")
                    .filter { id -> id.length == 11 }
                    .forEach { id ->
                        if (mine.add(id)) onCollect(id)
                    }
            }
            delay(600)
            found = mine.size

            if (mine.size >= target) {
                done = true
                onDone()
                return@LaunchedEffect
            }

            // Каждый третий шаг берём свежую порцию: имитация свайпа в плеере
            // шортсов срабатывает не всегда, а перезагрузка ленты — всегда.
            if (step % 3 == 2) {
                view.loadUrl(FEED_URL)
                delay(2200)
            } else {
                view.evaluateJavascript(JS_ADVANCE, null)
                delay(1000)
            }
        }
        done = true
        if (mine.isNotEmpty()) onDone() else status = "Лента ничего не отдала"
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
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.padding(end = 12.dp)) {
                Text("Моя лента шортсов", style = Type.Data)
                Text(
                    status,
                    style = Type.Label.copy(
                        color = if (loggedIn == false) Palette.Signal else Palette.Muted
                    ),
                )
            }
            Text(
                "Закрыть",
                style = Type.Small.copy(color = Palette.Muted),
                modifier = Modifier.clickable { onCancel() },
            )
        }

        Box(Modifier.padding(horizontal = 18.dp)) {
            Bar(
                if (target > 0) found.toFloat() / target else 0f,
                color = Palette.Jade,
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppButton("Войти в Google", Modifier.weight(1f)) { web?.loadUrl(LOGIN_URL) }
            AppButton("К ленте", Modifier.weight(1f)) { web?.loadUrl(FEED_URL) }
        }

        if (found > 0 && !done) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 12.dp)
            ) {
                AppButton("Хватит, качаем", tail = "$found", primary = true) {
                    done = true
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
                    loadUrl(FEED_URL)
                    web = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
