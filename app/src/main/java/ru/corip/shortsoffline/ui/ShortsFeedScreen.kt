package ru.corip.shortsoffline.ui

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import ru.corip.shortsoffline.YtDlp

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
/** Собираем полные ссылки на ролики: у площадок они разного вида. */
private const val JS_EXTRACT_YT = """
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
      if (!seen[m[1]]) {
        seen[m[1]] = 1;
        found.push("https://www.youtube.com/shorts/" + m[1]);
      }
    }
  }
  return found.join(" ");
})()
"""

private const val JS_EXTRACT_TT = """
(function(){
  var found = [], seen = {};
  var html = document.documentElement.innerHTML;
  // Основной вид ссылки — с именем автора. Если разметка другая, ловим
  // просто номер ролика: у TikTok он работает и с любым автором в адресе.
  var withAuthor = /\/@([A-Za-z0-9_.\-]{1,30})\/video\/(\d{15,21})/g;
  var m;
  while ((m = withAuthor.exec(html)) !== null) {
    if (!seen[m[2]]) {
      seen[m[2]] = 1;
      found.push("https://www.tiktok.com/@" + m[1] + "/video/" + m[2]);
    }
  }
  var bare = /"(?:id|itemId|awemeId)":"(\d{18,21})"/g;
  while ((m = bare.exec(html)) !== null) {
    if (!seen[m[1]]) {
      seen[m[1]] = 1;
      found.push("https://www.tiktok.com/@i/video/" + m[1]);
    }
  }
  return found.join(" ");
})()
"""

/** На настольной версии TikTok лента листается стрелкой вниз. */
private const val JS_ARROW_DOWN = """
(function(){
  var e = new KeyboardEvent('keydown', {key:'ArrowDown', keyCode:40, which:40, bubbles:true});
  document.dispatchEvent(e);
  document.body.dispatchEvent(e);
  return 'ok';
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

/** У TikTok признак входа называется иначе. */
private const val JS_LOGGED_IN_TT = """
(function(){
  var h = document.documentElement.innerHTML;
  if (h.indexOf('"isLogin":true') >= 0) return 'yes';
  if (h.indexOf('"isLogin":false') >= 0) return 'no';
  return 'unknown';
})()
"""

/**
 * Настоящий свайп пальцем по ленте. Прокрутка через JavaScript в плеере
 * шортсов не работает — он слушает касания, а не скролл. Поэтому шлём
 * браузеру полноценное событие движения, как если бы ты вёл пальцем.
 */
private fun WebView.swipeUp() {
    val start = SystemClock.uptimeMillis()
    val x = width / 2f
    val from = height * 0.80f
    val to = height * 0.20f
    fun send(time: Long, action: Int, y: Float) {
        MotionEvent.obtain(start, time, action, x, y, 0).also {
            dispatchTouchEvent(it)
            it.recycle()
        }
    }
    send(start, MotionEvent.ACTION_DOWN, from)
    var t = start
    for (i in 1..12) {
        t += 14
        send(t, MotionEvent.ACTION_MOVE, from + (to - from) * i / 12f)
    }
    send(t + 14, MotionEvent.ACTION_UP, to)
}




@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ShortsFeedScreen(
    platform: YtDlp.Platform,
    desktop: Boolean,
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
    var loadError by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf(0) }
    // Лента работает скрыто: смотреть ролики заранее не нужно.
    val showBrowser = false

    DisposableEffect(Unit) { onDispose { web?.destroy() } }

    LaunchedEffect(web) {
        val view = web ?: return@LaunchedEffect

        delay(3000)
        // Вход показываем справочно и сбор им не перекрываем: определитель
        // может ошибиться, а лента при этом работать.
        val loginCheck =
            if (platform == YtDlp.Platform.TIKTOK) JS_LOGGED_IN_TT else JS_LOGGED_IN
        view.evaluateJavascript(loginCheck) { r ->
            when (r.trim('"')) {
                "yes" -> loggedIn = true
                "no" -> loggedIn = false
            }
        }

        // Считаем сами: параметр collected внутри этого блока «заморожен»
        // на значении из момента запуска и обновляться не будет.
        val mine = linkedSetOf<String>()

        // Запас по шагам: за один заход лента отдаёт по нескольку роликов,
        // но и повторы попадаются, поэтому берём с большим запасом.
        repeat(if (target > 0) target * 4 + 30 else 600) {
            if (done) return@LaunchedEffect
            status = if (target > 0) "Собираю ленту: ${mine.size} из $target"
                else "Собираю ленту: ${mine.size}, жми «Хватит» когда достаточно"

            val extractor =
                if (platform == YtDlp.Platform.TIKTOK) JS_EXTRACT_TT else JS_EXTRACT_YT
            view.evaluateJavascript(extractor) { raw ->
                raw.trim('"').replace("\\\"", "").split(" ")
                    .map { it.trim() }
                    .filter { it.startsWith("http") }
                    .forEach { link -> if (mine.add(link)) onCollect(link) }
            }
            delay(600)
            found = mine.size

            if (target > 0 && mine.size >= target) {
                done = true
                onDone()
                return@LaunchedEffect
            }

            // Листаем ту самую ленту, что на экране. Никаких перезагрузок:
            // они подсовывали другую подборку вместо твоей.
            // Пробуем оба способа: свайп для мобильной вёрстки, стрелка для настольной.
            view.swipeUp()
            // На настольной вёрстке лента слушается стрелки, на мобильной — свайпа.
            if (desktop) view.evaluateJavascript(JS_ARROW_DOWN, null)
            delay(1400)
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
                Text("Моя лента · ${platform.title}", style = Type.Data)
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
                if (target > 0) found.toFloat() / target else 1f,
                color = Palette.Jade,
            )
        }
        Spacer(Modifier.height(12.dp))


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

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        // Нам нужен только код страницы со ссылками. Картинки и
                        // ролики не грузим: именно они съедали память и роняли
                        // приложение на длинной ленте.
                        settings.blockNetworkImage = true
                        settings.loadsImagesAutomatically = false
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.userAgentString = platform.userAgent(desktop)
                        webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            // Только для главной страницы: мелкие сбои картинок не важны.
                            if (request?.isForMainFrame == true) {
                                loadError = "Страница не открылась. Проверь VPN и сеть."
                            }
                            super.onReceivedError(view, request, error)
                        }
                    }
                        webChromeClient = WebChromeClient()
                        loadUrl(platform.feedUrl)
                        web = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Заслонка. Браузер под ней живой и нужного размера — иначе свайпы
            // некуда слать, — но ролики ты не видишь.
            if (!showBrowser) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Palette.Void)
                        .padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "$found",
                        style = Type.Display.copy(fontSize = 64.sp, color = Palette.Jade),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (target > 0) "собрано из $target" else "собрано, предела нет",
                        style = Type.Small,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        loadError ?: if (loggedIn == false)
                            "${platform.title} не узнаёт тебя. Вернись и войди в аккаунт."
                        else "Листаю твою ленту и запоминаю ролики. Смотреть их сейчас не нужно.",
                        style = Type.Small.copy(
                            color = if (loggedIn == false || loadError != null) Palette.Signal
                            else Palette.Muted
                        ),
                    )
                }
            }
        }
    }
}
