package com.lesliefang.harmonytouping.mirror

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import com.huawei.hosscrcpy.api.HosRemoteConfig
import com.huawei.hosscrcpy.api.HosRemoteDevice
import com.huawei.hosscrcpy.api.ScreenCapCallback
import com.lesliefang.harmonytouping.device.HdcCli
import com.lesliefang.harmonytouping.device.HosDevice
import com.lesliefang.harmonytouping.key.KeyCodeMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.Toolkit
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** 投屏会话状态 */
sealed interface MirrorState {
    data object Idle : MirrorState
    data class Connecting(val device: HosDevice) : MirrorState
    data class Streaming(val device: HosDevice) : MirrorState
    data class Failed(val device: HosDevice, val message: String) : MirrorState
}

/**
 * 一次投屏会话：封装官方 SDK 的 HosRemoteDevice，
 * 负责 H.265 解码 -> Compose ImageBitmap、以及触控/鼠标/键盘/物理按键的注入。
 *
 * 行为对齐官方 Swing 版 MainForm：
 * - onData 回调（SDK 流线程）里同步解码渲染
 * - 触控/鼠标走 SDK 的 socket 通道，可直接在 UI 线程调用
 * - 键盘文本/电源等按键走 hdc shell（慢），异步执行
 */
class MirrorSession(private val scope: CoroutineScope) {

    var state by mutableStateOf<MirrorState>(MirrorState.Idle)
        private set

    /** 最新一帧画面，null 表示暂无画面 */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    /** 当前画面分辨率 */
    var frameWidth by mutableStateOf(0)
        private set
    var frameHeight by mutableStateOf(0)
        private set

    /** 连接过程中的提示文案 */
    var statusText by mutableStateOf("")
        private set

    /** 鼠标事件模式（2in1 设备/需要完整鼠标时开启） */
    var mouseMode by mutableStateOf(false)

    /** 设备 uitest 版本 >= 5.1.1.3 时支持完整鼠标事件（拖拽/滚轮） */
    var canUseCompleteMouse by mutableStateOf(false)
        private set

    private val ioDispatcher = Dispatchers.IO.limitedParallelism(4)
    private var device: HosDevice? = null
    private var remote: HosRemoteDevice? = null
    private var decoder: VideoStreamDecoder? = null
    private var startJob: Job? = null
    private val stopped = AtomicBoolean(false)

    private val callback = object : ScreenCapCallback {
        override fun onReady() {
            val dev = device ?: return
            // SDK 断流后会在内部自动换 server so 重连并再次回调 onReady；
            // 不同 so 输出的编码可能不同（H.264/H.265），重置解码器重新识别
            decoder?.close()
            decoder = null
            state = MirrorState.Streaming(dev)
            statusText = ""
            // 官方在 onReady 后发一条 trace 触发画面刷新
            shellAsync("uinput -M -m 100 100 200 200 --trace", 2)
            scope.launch { afterReady() }
        }

        override fun onData(byteBuffer: ByteBuffer) {
            try {
                val data = byteBuffer.array()
                val offset = byteBuffer.position()
                val length = byteBuffer.remaining()
                var dec = decoder
                if (dec == null) {
                    // 不同系统版本的 scrcpy server 输出编码不同（6.x 为 H.265，7.0 为 H.264），
                    // 首个包含参数集的分片到达后再确定编码并创建解码器
                    val codecId = VideoStreamDecoder.detectCodecId(data, offset, length)
                    if (codecId == -1) return
                    dec = VideoStreamDecoder(codecId, ::onDecodedFrame)
                    decoder = dec
                }
                dec.decode(data, offset, length)
            } catch (_: Exception) {
            }
        }

        override fun onException(throwable: Throwable) {
            val dev = device ?: return
            val message = if (remote?.isOnline != true) {
                "${dev}设备连接已断开"
            } else {
                "投屏失败:\n${throwable.message ?: throwable}"
            }
            cleanup()
            state = MirrorState.Failed(dev, message)
        }
    }

    /** 开始投屏 */
    fun start(device: HosDevice) {
        stop()
        this.device = device
        stopped.set(false)
        state = MirrorState.Connecting(device)
        statusText = "画面初始化中, 请等待..."
        mouseMode = false
        canUseCompleteMouse = false
        frame = null

        startJob = scope.launch(ioDispatcher) {
            try {
                val config = HosRemoteConfig(device.sn).apply {
                    ip = device.ip
                    hdcPath = HdcCli.hdcPath
                }
                val remoteDevice = HosRemoteDevice(config)
                // 官方在启动前先调一次 stop 清理旧 session
                remoteDevice.stopCaptureScreen()
                remote = remoteDevice
                remoteDevice.startCaptureScreen(callback)
            } catch (e: Exception) {
                cleanup()
                state = MirrorState.Failed(device, "投屏启动失败:\n${e.message ?: e}")
            }
        }
    }

    /** 停止投屏并复位 */
    fun stop() {
        startJob?.cancel()
        startJob = null
        val hadSession = remote != null || state != MirrorState.Idle
        cleanup()
        if (hadSession) state = MirrorState.Idle
    }

    fun dismissError() {
        if (state is MirrorState.Failed) {
            state = MirrorState.Idle
        }
    }

    private fun cleanup() {
        stopped.set(true)
        runCatching { remote?.stopCaptureScreen() }
        runCatching { decoder?.close() }
        remote = null
        decoder = null
        frame = null
        frameWidth = 0
        frameHeight = 0
        mouseMode = false
        canUseCompleteMouse = false
        statusText = ""
    }

    private fun onDecodedFrame(width: Int, height: Int, rgba: ByteArray) {
        if (stopped.get()) return
        frameWidth = width
        frameHeight = height
        val bytes = if (rgba.size == width * height * 4) rgba else rgba.copyOf(width * height * 4)
        frame = Image.makeRaster(
            ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE),
            bytes,
            width * 4,
        ).toComposeImageBitmap()
    }

    /** onReady 后的初始化检查：2in1 设备自动开鼠标模式、探测 uitest 版本 */
    private suspend fun afterReady() {
        val dev = device ?: return
        val devType = withContext(ioDispatcher) { HdcCli.shell(dev, "param get const.product.devicetype", 5) }
        if (devType.contains("2in1")) {
            mouseMode = true
        }
        val uitestVersion = withContext(ioDispatcher) { HdcCli.shell(dev, "uitest --version", 7) }
        canUseCompleteMouse = versionAtLeast(uitestVersion, "5.1.1.3")
    }

    // ---------- 输入注入：触控（直接走 SDK socket 通道，可从 UI 线程调用） ----------

    fun touchDown(x: Int, y: Int) {
        remote?.onTouchDown(x, y)
    }

    fun touchMove(x: Int, y: Int) {
        remote?.onTouchMove(x, y)
    }

    fun touchUp(x: Int, y: Int) {
        remote?.onTouchUp(x, y)
    }

    // ---------- 输入注入：鼠标 ----------

    fun mouseDown(button: String, x: Int, y: Int) {
        if (canUseCompleteMouse) remote?.onMouseDown(button, x, y)
    }

    fun mouseMove(button: String?, x: Int, y: Int) {
        if (canUseCompleteMouse) remote?.onMouseMove(button, x, y)
    }

    fun mouseUp(button: String, x: Int, y: Int) {
        val dev = remote ?: return
        if (canUseCompleteMouse) {
            dev.onMouseUp(button, x, y)
        } else if (button == HosRemoteDevice.MOUSE_LEFT) {
            // 老系统只支持单击，走 uinput
            shellAsync("uinput -M -m $x $y -c 0", 5)
        } else if (button == HosRemoteDevice.MOUSE_RIGHT) {
            shellAsync("uinput -M -m $x $y -c 1", 5)
        }
    }

    fun wheel(up: Boolean, x: Int, y: Int) {
        val dev = remote ?: return
        if (canUseCompleteMouse) {
            if (up) {
                dev.onMouseWheelUp(x, y)
                dev.onMouseWheelStop(x, y)
            } else {
                dev.onMouseWheelDown(x, y)
                dev.onMouseWheelStop(x, y)
            }
        } else {
            val sign = if (up) "-500" else "500"
            shellAsync("uinput -M -m $x $y -s $sign", 5)
        }
    }

    // ---------- 输入注入：键盘（hdc shell，异步） ----------

    /** 处理 Compose 按键事件，返回是否已消费 */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (state !is MirrorState.Streaming) return false

        val awtKeyCode = event.key.keyCode.toInt()
        val keyChar = event.utf16CodePoint.toChar().takeIf { it.code != 0 }
        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed

        if (KeyCodeMapper.isPaste(ctrl, awtKeyCode)) {
            pasteFromClipboard()
            return true
        }
        if (ctrl) return false
        if (!KeyCodeMapper.isSupported(awtKeyCode, keyChar, shift)) return false
        val command = KeyCodeMapper.uinputCommand(awtKeyCode, keyChar, shift) ?: return false
        shellAsync(command, 5)
        return true
    }

    /** 粘贴系统剪贴板文本到设备（对应官方 Ctrl+V 行为） */
    fun pasteFromClipboard() {
        scope.launch(ioDispatcher) {
            val content = runCatching {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            }.getOrNull() ?: return@launch
            if (content.isBlank()) return@launch
            val escaped = content.replace("'", "'\\''")
            shell("uitest uiInput text '$escaped'", 5)
        }
    }

    // ---------- 物理按键 ----------

    fun power() = shellAsync("uinput -K -d 18 -u 18", 3)
    fun back() = shellAsync("uinput -K -d 2 -u 2", 3)
    fun volumeUp() = shellAsync("uinput -K -d 16 -u 16", 3)
    fun volumeDown() = shellAsync("uinput -K -d 17 -u 17", 3)
    fun reboot() = shellAsync("reboot", 3)

    // ---------- 内部 ----------

    private fun shellAsync(command: String, timeoutSec: Long) {
        val dev = device ?: return
        scope.launch(ioDispatcher) { HdcCli.shell(dev, command, timeoutSec) }
    }

    private suspend fun shell(command: String, timeoutSec: Long) {
        val dev = device ?: return
        withContext(ioDispatcher) { HdcCli.shell(dev, command, timeoutSec) }
    }

    private fun versionAtLeast(actual: String, baseline: String): Boolean {
        val regex = Regex("\\d+(\\.\\d+)+")
        val match = regex.find(actual) ?: return false
        val actualParts = match.value.split('.').map { it.toIntOrNull() ?: 0 }
        val baseParts = baseline.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(actualParts.size, baseParts.size)
        for (i in 0 until n) {
            val a = actualParts.getOrElse(i) { 0 }
            val b = baseParts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }
}
