package com.jaac.avoqado_tpv.core.presentation.systemui

import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import timber.log.Timber

fun applyTpvImmersiveMode(window: Window) {
    runCatching {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        window.decorView.post {
            runCatching {
                WindowInsetsControllerCompat(window, window.decorView)
                    .hide(WindowInsetsCompat.Type.systemBars())
            }.onFailure { error ->
                Timber.w(error, "Could not reapply TPV immersive mode")
            }
        }
    }.onFailure { error ->
        Timber.w(error, "Could not apply TPV immersive mode")
    }
}

@Composable
fun ImmersiveSystemUiEffect() {
    val view = LocalView.current

    DisposableEffect(view) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window

        if (dialogWindow == null) {
            onDispose {}
        } else {
            val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) {
                    applyTpvImmersiveMode(dialogWindow)
                }
            }

            applyTpvImmersiveMode(dialogWindow)
            dialogWindow.decorView.viewTreeObserver
                .addOnWindowFocusChangeListener(focusListener)

            onDispose {
                val observer = dialogWindow.decorView.viewTreeObserver
                if (observer.isAlive) {
                    observer.removeOnWindowFocusChangeListener(focusListener)
                }
            }
        }
    }
}
