package com.lesliefang.harmonytouping

import androidx.compose.ui.input.key.Key
import com.lesliefang.harmonytouping.geom.ViewportMath
import com.lesliefang.harmonytouping.key.KeyCodeMapper
import com.lesliefang.harmonytouping.mirror.VideoStreamDecoder
import org.bytedeco.ffmpeg.global.avcodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoStreamDecoderTest {

    /**
     * 用一段真实的 H.265 裸流（VideoToolbox 编码，已嵌入资源）验证解码链路：
     * parser 任意分包重组 -> 软解 -> sws 转 RGBA -> 字节拷贝。
     */
    @Test
    fun `decode embedded h265 stream`() {
        val stream = javaClass.classLoader.getResourceAsStream("test_stream.h265")!!
            .readBytes()
        assertTrue(stream.size > 1000)
        assertEquals(avcodec.AV_CODEC_ID_H265, VideoStreamDecoder.detectCodecId(stream))

        var frames = 0
        var lastW = 0
        var lastH = 0
        var nonBlackBytes = 0
        val decoder = VideoStreamDecoder(avcodec.AV_CODEC_ID_H265) { w, h, rgba ->
            frames++
            lastW = w
            lastH = h
            nonBlackBytes += rgba.count { it != 0.toByte() }
        }

        // 按 997 字节任意切块送入，模拟 SDK onData 的非对齐分包
        decodeChunked(decoder, stream, chunkSize = 997)
        decoder.close()

        assertTrue(frames >= 20, "decoded only $frames frames")
        assertEquals(320, lastW)
        assertEquals(240, lastH)
        assertTrue(nonBlackBytes > 10_000, "decoded frames look empty")
    }

    /**
     * 真机（HarmonyOS 7.0 + unix_6.6 server）抓取的 H.264 流回归测试。
     * 不同系统版本的 scrcpy server 输出编码不同：6.x 为 H.265，7.0 上部分 so 为 H.264。
     */
    @Test
    fun `decode real device h264 stream`() {
        val stream = javaClass.classLoader.getResourceAsStream("device_stream_h264.bin")!!
            .readBytes()
        assertEquals(avcodec.AV_CODEC_ID_H264, VideoStreamDecoder.detectCodecId(stream))

        var frames = 0
        var lastW = 0
        var lastH = 0
        val decoder = VideoStreamDecoder(avcodec.AV_CODEC_ID_H264) { w, h, _ ->
            frames++
            lastW = w
            lastH = h
        }
        // 按整包分块送入，贴近 SDK onData 的真实分包方式（每包一个完整 NAL 或分片）
        var offset = 0
        val packetSizes = intArrayOf(33, 145443, 38205, 30890, 30705)
        var i = 0
        while (offset < stream.size) {
            val len = minOf(packetSizes[i % packetSizes.size], stream.size - offset)
            decoder.decode(stream, offset, len)
            offset += len
            i++
        }
        decoder.close()

        assertTrue(frames >= 3, "decoded only $frames frames")
        assertEquals(1080, lastW)
        assertEquals(2504, lastH)
    }

    @Test
    fun `key code mapper basics`() {
        // 字母 A -> OH 2017
        assertEquals(2017, KeyCodeMapper.ohKeyCode(65, 'a', shiftPressed = false))
        // 数字 1 -> OH 2001
        assertEquals(2001, KeyCodeMapper.ohKeyCode(49, '1', shiftPressed = false))
        // 回车 -> 2119
        assertEquals(2119, KeyCodeMapper.ohKeyCode(10, null, shiftPressed = false))
        // 大写字母命令带 Shift 包裹
        assertEquals(
            "uinput -K -d 2047 -d 2017 -u 2017 -u 2047",
            KeyCodeMapper.uinputCommand(65, 'a', shiftPressed = true),
        )
        // '.' 走 uitest 文本输入
        assertEquals(
            "uitest uiInput text '.'",
            KeyCodeMapper.uinputCommand(46, '.', shiftPressed = false),
        )
        // Ctrl+V 识别为粘贴
        assertTrue(KeyCodeMapper.isPaste(ctrlPressed = true, awtKeyCode = Key.V.keyCode.toInt()))
    }

    @Test
    fun `viewport math maps and clamps`() {
        val rect = ViewportMath.fit(1000f, 800f, 500f, 1000f)
        // 等比缩放：以高度为限制
        assertEquals(400f, rect.width)
        assertEquals(800f, rect.height)
        assertEquals(300f, rect.left)

        // 画面内点击换算（容器中心 -> 设备画面中心）
        val p = ViewportMath.toDevicePoint(500f, 400f, rect, 500, 1000)
        assertEquals(250, p!!.x)
        assertEquals(500, p.y)

        // 越界点击被钳制（横向越界 -> 贴左边缘、纵向居中）
        val clamped = ViewportMath.toDevicePoint(0f, 400f, rect, 500, 1000)!!
        assertTrue(clamped.x < 10)
    }

    private fun decodeChunked(decoder: VideoStreamDecoder, stream: ByteArray, chunkSize: Int) {
        var offset = 0
        while (offset < stream.size) {
            val len = minOf(chunkSize, stream.size - offset)
            decoder.decode(stream, offset, len)
            offset += len
        }
    }
}
