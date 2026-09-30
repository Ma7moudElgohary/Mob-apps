package com.ma7moud.reality3d.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the screen from turning off while [active], for the minutes the computer takes to build a model: a phone
 * that goes to sleep stops asking how the build is going.
 */
@Composable
internal fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active, view) {
        val before = view.keepScreenOn
        if (active) view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}
