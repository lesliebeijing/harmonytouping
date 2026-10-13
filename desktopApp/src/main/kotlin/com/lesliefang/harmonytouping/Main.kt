package com.lesliefang.harmonytouping

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.lesliefang.harmonytouping.device.AppSettings
import com.lesliefang.harmonytouping.mirror.MirrorSession
import com.lesliefang.harmonytouping.ui.App
import java.awt.Dimension
import java.awt.Frame
import java.awt.Toolkit
import kotlin.math.min
import kotlin.math.roundToInt

fun main() = application {
    AppSettings.load()

    Window(
        onCloseRequest = ::exitApplication,
        title = "鸿蒙设备投屏",
    ) {
        val scope = rememberCoroutineScope()
        val session = remember { MirrorSession(scope) }

        DisposableEffect(Unit) {
            onDispose { session.stop() }
        }

        App(session)

        // 画面分辨率首次出现或变化（如旋转）时，把窗口调整到合适大小并居中
        val frameW = session.frameWidth
        val frameH = session.frameHeight
        var lastResized by remember { mutableStateOf(0 to 0) }
        LaunchedEffect(frameW, frameH) {
            if (frameW == 0 || frameH == 0) return@LaunchedEffect
            if (lastResized == (frameW to frameH)) return@LaunchedEffect
            lastResized = frameW to frameH

            val awtWindow = window as? Frame ?: return@LaunchedEffect
            if (awtWindow.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) return@LaunchedEffect

            val screen = Toolkit.getDefaultToolkit().screenSize
            val chromeH = 130   // 设备栏 + 控制栏 + 边框
            val chromeW = 60
            val scale = min(
                (screen.height * 0.8 - chromeH) / frameH,
                (screen.width * 0.6 - chromeW) / frameW,
            ).coerceAtLeast(0.1)
            awtWindow.minimumSize = Dimension(560, 420)
            awtWindow.setSize(
                ((frameW * scale).roundToInt() + chromeW).coerceAtLeast(560),
                ((frameH * scale).roundToInt() + chromeH).coerceAtLeast(420),
            )
            awtWindow.setLocationRelativeTo(null)
        }
    }
}
