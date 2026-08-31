package ru.corip.shortsoffline

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import ru.corip.shortsoffline.ui.DownloadScreen
import ru.corip.shortsoffline.ui.MenuScreen
import ru.corip.shortsoffline.ui.Palette
import ru.corip.shortsoffline.ui.LinkScreen
import ru.corip.shortsoffline.ui.LoginScreen
import ru.corip.shortsoffline.ui.SoloPlayerScreen
import ru.corip.shortsoffline.ui.PlayerScreen
import ru.corip.shortsoffline.ui.ShortsFeedScreen
import ru.corip.shortsoffline.ui.ReceiptDialog

class MainActivity : ComponentActivity() {

    /** Ссылка, пришедшая из другого приложения, до создания экрана. */
    private val shared = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readShared(intent)
        setContent { App(shared) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShared(intent)
    }

    private fun readShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared.value = it }
    }
}

@Composable
private fun App(
    shared: MutableStateFlow<String?> = MutableStateFlow(null),
    vm: AppViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // Пришла ссылка извне — открываем экран загрузки и сразу её разбираем.
    val incoming by shared.collectAsState()
    LaunchedEffect(incoming) {
        incoming?.let {
            vm.handleSharedLink(it)
            shared.value = null
        }
    }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            vm.dismissToast()
        }
    }

    BackHandler(enabled = state.screen != Screen.MENU || state.commentsOpen) {
        when {
            state.commentsOpen -> vm.closeComments()
            state.screen == Screen.PLAYER -> vm.exitPlayer()
            else -> vm.go(Screen.MENU)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Void)
    ) {
        when (state.screen) {
            Screen.MENU -> MenuScreen(state, vm)
            Screen.LOGIN -> LoginScreen(
                platform = state.platform,
                desktop = state.desktopView,
                onDesktopChange = { vm.setDesktopView(it) },
                onDone = { vm.go(Screen.MENU) },
            )
            Screen.FEED -> ShortsFeedScreen(
                platform = state.platform,
                desktop = state.desktopView,
                collected = state.collected,
                target = state.count,
                onCollect = { vm.collectShort(it) },
                onDone = { vm.useCollected() },
                onCancel = { vm.go(Screen.MENU) },
            )
            Screen.DOWNLOAD -> DownloadScreen(state, vm)
            Screen.LINK -> LinkScreen(state, vm)
            Screen.SOLO -> state.localUri?.let {
                SoloPlayerScreen(it, onClose = { vm.closeLocal() })
            }
            Screen.PLAYER -> PlayerScreen(state, vm)
        }

        if (state.showReceipt) {
            ReceiptDialog(state) { vm.dismissReceipt() }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
