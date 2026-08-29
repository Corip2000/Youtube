package ru.corip.shortsoffline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import ru.corip.shortsoffline.ui.DownloadScreen
import ru.corip.shortsoffline.ui.MenuScreen
import ru.corip.shortsoffline.ui.Palette
import ru.corip.shortsoffline.ui.PlayerScreen
import ru.corip.shortsoffline.ui.ReceiptDialog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> vm.onAuthResult(result.data) }

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
            Screen.MENU -> MenuScreen(state, vm) {
                vm.authIntent()?.let(signInLauncher::launch)
            }
            Screen.DOWNLOAD -> DownloadScreen(state, vm)
            Screen.PLAYER -> PlayerScreen(state, vm)
        }

        if (state.showReceipt) {
            ReceiptDialog(state) { vm.dismissReceipt() }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
