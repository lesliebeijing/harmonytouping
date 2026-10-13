package com.lesliefang.harmonytouping.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lesliefang.harmonytouping.device.AppSettings
import com.lesliefang.harmonytouping.device.HdcCli
import com.lesliefang.harmonytouping.device.HosDevice
import com.lesliefang.harmonytouping.geom.DevicePoint
import com.lesliefang.harmonytouping.geom.ViewportMath
import com.lesliefang.harmonytouping.mirror.MirrorSession
import com.lesliefang.harmonytouping.mirror.MirrorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 应用主界面：顶部设备栏 + 投屏画面 + 控制按钮 */
@Composable
fun App(session: MirrorSession) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<HosDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<HosDevice?>(null) }
    var loadingDevices by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    fun refreshDevices() {
        if (loadingDevices) return
        loadingDevices = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { HdcCli.listAllDevices() }
            devices = found
            if (found.none { it == selectedDevice }) {
                selectedDevice = found.firstOrNull()
            }
            loadingDevices = false
        }
    }

    // 启动时自动刷新设备列表（对齐官方行为）
    LaunchedEffect(Unit) { refreshDevices() }

    MaterialTheme(colorScheme = darkMirrorColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                DeviceBar(
                    devices = devices,
                    selected = selectedDevice,
                    loading = loadingDevices,
                    state = session.state,
                    onRefresh = ::refreshDevices,
                    onSelect = { selectedDevice = it },
                    onToggleCapture = {
                        val dev = selectedDevice
                        if (dev != null && session.state !is MirrorState.Connecting) {
                            if (session.state is MirrorState.Streaming) session.stop() else session.start(dev)
                        }
                    },
                    onSettings = { showSettings = true },
                )
                if (session.state is MirrorState.Streaming) {
                    ControlBar(session)
                }
                ScreenArea(
                    session = session,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (showSettings) {
            SettingsDialog(
                onDismiss = { showSettings = false },
                onSaved = { refreshDevices() },
            )
        }

        (session.state as? MirrorState.Failed)?.let { failed ->
            AlertDialog(
                onDismissRequest = { session.dismissError() },
                title = { Text("投屏已停止") },
                text = { Text(failed.message) },
                confirmButton = {
                    TextButton(onClick = { session.dismissError() }) { Text("确定") }
                },
            )
        }
    }
}

// ---------- 顶部设备栏 ----------

@Composable
private fun DeviceBar(
    devices: List<HosDevice>,
    selected: HosDevice?,
    loading: Boolean,
    state: MirrorState,
    onRefresh: () -> Unit,
    onSelect: (HosDevice) -> Unit,
    onToggleCapture: () -> Unit,
    onSettings: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val busy = state is MirrorState.Connecting
    val streaming = state is MirrorState.Streaming

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            OutlinedButton(onClick = { menuExpanded = true }, enabled = !busy && !streaming) {
                Text(selected?.toString() ?: if (devices.isEmpty()) "无设备（请刷新）" else "选择设备")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (devices.isEmpty()) {
                    DropdownMenuItem(text = { Text("无设备") }, onClick = { menuExpanded = false })
                }
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.toString()) },
                        onClick = {
                            onSelect(device)
                            menuExpanded = false
                        },
                    )
                }
            }
        }
        TextButton(onClick = onRefresh, enabled = !loading) {
            Text(if (loading) "刷新中..." else "刷新")
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onToggleCapture, enabled = selected != null && !busy) {
            Text(
                when {
                    busy -> "连接中..."
                    streaming -> "停止投屏"
                    else -> "进入投屏"
                }
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSettings) { Text("设置") }
    }
}

// ---------- 投屏时的控制按钮栏 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlBar(session: MirrorSession) {
    var confirmReboot by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        smallTonalButton("电源") { session.power() }
        smallTonalButton("返回") { session.back() }
        smallTonalButton("音量+") { session.volumeUp() }
        smallTonalButton("音量-") { session.volumeDown() }
        smallTonalButton("重启") { confirmReboot = true }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("鼠标模式", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(1.dp))
            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                Switch(
                    checked = session.mouseMode,
                    onCheckedChange = { session.mouseMode = it },
                    modifier = Modifier.scale(0.8f),
                )
            }
        }
    }

    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text("重启设备") },
            text = { Text("是否确认重启?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReboot = false
                    session.reboot()
                }) { Text("重启") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReboot = false }) { Text("取消") }
            },
        )
    }
}

// ---------- 小尺寸控制按钮 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun smallTonalButton(text: String, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------- 投屏画面区 ----------

@Composable
private fun ScreenArea(session: MirrorSession, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    val frame: ImageBitmap? = session.frame

    // 会话进入 Streaming 后抢占焦点，让键盘输入直达投屏画面
    LaunchedEffect(session.state) {
        if (session.state is MirrorState.Streaming) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(session.frameWidth, session.frameHeight, containerSize) {
        if (session.frameWidth > 0 && session.frameHeight > 0 &&
            containerSize.width > 0 && containerSize.height > 0
        ) {
            ViewportMath.fit(
                containerSize.width.toFloat(), containerSize.height.toFloat(),
                session.frameWidth.toFloat(), session.frameHeight.toFloat(),
            )
        } else {
            null
        }
    }
    // 坐标换算函数需跟随 fit/分辨率变化，rememberUpdatedState 保证指针协程里拿到最新实现
    val mapPointState = rememberUpdatedState<(Offset) -> DevicePoint?> { position ->
        fit?.let {
            ViewportMath.toDevicePoint(position.x, position.y, it, session.frameWidth, session.frameHeight)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { session.handleKeyEvent(it) }
            // 指针事件挂在整个画面区上：点击画面外的黑边时按官方逻辑钳制到画面边缘
            .pointerInput(session.mouseMode) {
                awaitPointerEventScope {
                    awaitPointerEventLoop(session, mapPointState)
                }
            },
    ) {
        if (frame != null && fit != null && !fit.isEmpty) {
            Image(
                bitmap = frame,
                contentDescription = "device screen",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .offset { IntOffset(fit.left.roundToInt(), fit.top.roundToInt()) }
                    .pixelSize(fit.width.roundToInt(), fit.height.roundToInt()),
            )
        }

        val overlayText = when (session.state) {
            is MirrorState.Connecting -> session.statusText.ifBlank { "连接中..." }
            is MirrorState.Streaming ->
                if (frame == null) "视频流准备完成, 如无画面请点击电源键" else ""
            is MirrorState.Idle -> "选择设备后点击「进入投屏」"
            is MirrorState.Failed -> ""
        }
        if (overlayText.isNotEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (session.state is MirrorState.Connecting) {
                    CircularProgressIndicator(Modifier.padding(bottom = 16.dp))
                }
                Text(overlayText, color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

/** 原始指针事件循环：把 Compose 指针事件翻译成 SDK 触控/鼠标注入 */
@OptIn(ExperimentalComposeUiApi::class)
private suspend fun AwaitPointerEventScope.awaitPointerEventLoop(
    session: MirrorSession,
    mapPointState: State<(Offset) -> DevicePoint?>,
) {
    var activeButton: String? = null
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: continue
        val point = mapPointState.value(change.position) ?: continue
        val mouseMode = session.mouseMode
        when (event.type) {
            PointerEventType.Press -> {
                if (mouseMode) {
                    val button = event.button.pointerName()
                    if (button != null) {
                        activeButton = button
                        session.mouseDown(button, point.x, point.y)
                    }
                } else {
                    session.touchDown(point.x, point.y)
                }
                change.consume()
            }
            PointerEventType.Move -> {
                if (change.pressed) {
                    if (mouseMode) {
                        session.mouseMove(activeButton, point.x, point.y)
                    } else {
                        session.touchMove(point.x, point.y)
                    }
                } else if (mouseMode) {
                    // 悬停移动
                    session.mouseMove(null, point.x, point.y)
                }
                change.consume()
            }
            PointerEventType.Release -> {
                if (mouseMode) {
                    val button = event.button.pointerName() ?: activeButton ?: "mouseLeft"
                    session.mouseUp(button, point.x, point.y)
                    activeButton = null
                } else {
                    session.touchUp(point.x, point.y)
                }
                change.consume()
            }
            PointerEventType.Scroll -> {
                val up = change.scrollDelta.y < 0
                session.wheel(up, point.x, point.y)
                change.consume()
            }
            else -> Unit
        }
    }
}

private fun PointerButton?.pointerName(): String? = when (this) {
    PointerButton.Primary -> "mouseLeft"
    PointerButton.Secondary -> "mouseRight"
    PointerButton.Tertiary -> "mouseMiddle"
    else -> null
}

/** 以像素为单位约束尺寸（fit 结果即像素） */
private fun Modifier.pixelSize(widthPx: Int, heightPx: Int): Modifier = this.then(
    Modifier.layout { measurable, _ ->
        val w = widthPx.coerceAtLeast(0)
        val h = heightPx.coerceAtLeast(0)
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(w, h) { placeable.place(0, 0) }
    }
)

// ---------- 设置对话框 ----------

@Composable
private fun SettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    var hdcPath by remember { mutableStateOf(HdcCli.hdcPath) }
    var hosts by remember { mutableStateOf(AppSettings.remoteHosts.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hdcPath,
                    onValueChange = { hdcPath = it },
                    label = { Text("hdc 路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "远程主机 IP 列表（每行一个，设备通过 hdc -s <ip>:8710 访问）",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = hosts,
                    onValueChange = { hosts = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                HdcCli.hdcPath = hdcPath.trim().ifEmpty { "hdc" }
                AppSettings.remoteHosts = hosts.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                AppSettings.save()
                onDismiss()
                onSaved()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun darkMirrorColorScheme() = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF0D1B2A),
    secondary = Color(0xFFB0BEC5),
    surface = Color(0xFF1C1C1E),
    background = Color(0xFF141416),
    onSurface = Color(0xFFE6E6E6),
)
