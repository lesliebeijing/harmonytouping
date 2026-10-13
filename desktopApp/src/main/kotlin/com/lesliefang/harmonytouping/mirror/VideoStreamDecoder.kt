package com.lesliefang.harmonytouping.mirror

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avcodec.AVCodecParserContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.io.Closeable

/**
 * 视频流解码器（H.264 / H.265）。
 *
 * 移植自官方 Swing 版 MainForm.decodeH264/initH265Decoder：
 * onData 回调给出任意切块的裸流字节，这里用 ffmpeg parser 重组帧、
 * 软解后经 swscale 转 RGBA 交上层渲染。
 *
 * 注意：不同系统版本的 scrcpy server 输出的编码不同
 * （6.x 系统 server 为 H.265，HarmonyOS 7.0 的 unix_6.6 server 为 H.264），
 * 由 [companion.detectCodecId] 根据流内 NAL 类型自动识别。
 *
 * 约定：decode 只会被单个线程（SDK 的流回调线程）调用。
 */
class VideoStreamDecoder(
    codecId: Int,
    private val onFrame: (width: Int, height: Int, rgba: ByteArray) -> Unit,
) : Closeable {

    private val codecCtx: AVCodecContext
    private val parser: AVCodecParserContext
    private val frame: AVFrame = avutil.av_frame_alloc()
    private val packet: AVPacket = avcodec.av_packet_alloc()

    /** 解码输出帧（RGBA） */
    private var rgbFrame: AVFrame? = null
    private var swsCtx: SwsContext? = null
    private var rgbaBuf: ByteArray = ByteArray(0)

    init {
        val codec = avcodec.avcodec_find_decoder(codecId)
        check(codec != null) { "decoder not found for codecId=$codecId" }
        codecCtx = avcodec.avcodec_alloc_context3(codec)
        parser = avcodec.av_parser_init(codecId)
        avcodec.avcodec_open2(codecCtx, codec, null as PointerPointer<*>?)
    }

    /** 送入一段流数据，内部完成组帧/解码/转色，成功解出帧时回调 [onFrame] */
    fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        var consumed = 0
        val pktSize = IntPointer(1)
        val outBuf = BytePointer(1L)
        try {
            while (length - consumed > 0) {
                val remaining = length - consumed
                val chunk = BytePointer(remaining.toLong())
                chunk.position(0).put(data, offset + consumed, remaining)
                pktSize.put(0, packet.size())
                val parsed = avcodec.av_parser_parse2(
                    parser, codecCtx, outBuf, pktSize, chunk, remaining,
                    avutil.AV_NOPTS_VALUE, avutil.AV_NOPTS_VALUE, avutil.AV_NOPTS_VALUE,
                )
                consumed += parsed
                if (pktSize.get() != 0) {
                    packet.size(pktSize.get())
                    packet.data(outBuf)
                    avcodec.avcodec_send_packet(codecCtx, packet)
                    if (avcodec.avcodec_receive_frame(codecCtx, frame) == 0) {
                        emitFrame()
                    }
                }
                // 注意：parser 的输出可能直接指向 chunk 内部（完整帧零拷贝），
                // 必须等 send_packet 完成数据拷贝后才能释放
                chunk.deallocate()
            }
        } finally {
            pktSize.deallocate()
            outBuf.deallocate()
        }
    }

    private fun emitFrame() {
        val w = codecCtx.width()
        val h = codecCtx.height()
        if (w <= 0 || h <= 0) return

        // 分辨率变化（旋转等）时重建输出帧
        if (rgbFrame == null || rgbFrame!!.width() != w || rgbFrame!!.height() != h) {
            releaseRgbFrame()
            val nf = avutil.av_frame_alloc()
            nf.format(avutil.AV_PIX_FMT_RGBA)
            nf.width(w)
            nf.height(h)
            avutil.av_image_alloc(nf.data(), nf.linesize(), w, h, avutil.AV_PIX_FMT_RGBA, 1)
            rgbFrame = nf
        }
        val rgb = rgbFrame ?: return

        swsCtx = swscale.sws_getCachedContext(
            swsCtx, w, h, codecCtx.pix_fmt(), w, h, avutil.AV_PIX_FMT_RGBA,
            2 /* SWS_BILINEAR */, null, null, null as DoublePointer?,
        )
        swscale.sws_scale(
            swsCtx, frame.data(), frame.linesize(), 0, frame.height(),
            rgb.data(), rgb.linesize(),
        )

        val rowBytes = w * 4
        val expected = rowBytes * h
        if (rgbaBuf.size != expected) rgbaBuf = ByteArray(expected)
        val data = rgb.data(0)
        val stride = rgb.linesize(0)
        if (stride == rowBytes) {
            data.position(0).get(rgbaBuf, 0, expected)
        } else {
            // linesize 有对齐填充时逐行拷贝
            for (row in 0 until h) {
                data.position((row * stride).toLong()).get(rgbaBuf, row * rowBytes, rowBytes)
            }
        }
        onFrame(w, h, rgbaBuf)
    }

    private fun releaseRgbFrame() {
        val rgb = rgbFrame ?: return
        runCatching {
            // av_image_alloc 的像素是单个连续块，挂在 data[0] 上；先释放再释放帧结构
            avutil.av_free(rgb.data(0))
            avutil.av_frame_free(rgb)
        }
        rgbFrame = null
    }

    override fun close() {
        runCatching {
            releaseRgbFrame()
            swsCtx?.let { swscale.sws_freeContext(it) }
            swsCtx = null
            avcodec.av_parser_close(parser)
            avcodec.avcodec_free_context(codecCtx)
            avutil.av_frame_free(frame)
            avcodec.av_packet_free(packet)
        }
    }

    companion object {
        /** 从流数据中识别编码：返回 AV_CODEC_ID_H264 / AV_CODEC_ID_H265，无法识别返回 -1 */
        @JvmStatic
        fun detectCodecId(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
            val end = offset + length
            var i = offset
            while (i + 4 < end) {
                val is3ByteStart = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
                val is4ByteStart = data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
                if (is3ByteStart || is4ByteStart) {
                    val nalPos = if (is3ByteStart) i + 3 else i + 4
                    if (nalPos < end) {
                        val b = data[nalPos].toInt() and 0xFF
                        val h264Type = b and 0x1F
                        val h265Type = (b shr 1) and 0x3F
                        return when {
                            // H.264: SPS(7)/PPS(8)/IDR(5)
                            h264Type == 7 || h264Type == 8 || h264Type == 5 -> avcodec.AV_CODEC_ID_H264
                            // H.265: VPS(32)/SPS(33)/PPS(34)
                            h265Type == 32 || h265Type == 33 || h265Type == 34 -> avcodec.AV_CODEC_ID_H265
                            else -> -1
                        }
                    }
                }
                i++
            }
            return -1
        }
    }
}
