package com.lesliefang.harmonytouping.geom

import kotlin.math.max
import kotlin.math.min

/** 等比缩放后图像在容器中的位置与大小 */
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
}

data class DevicePoint(val x: Int, val y: Int)

/**
 * 投屏画面与容器的坐标换算。
 *
 * 对应官方 Swing 版 MainForm.getActualClickPoint：
 * - 画面在容器内等比居中
 * - 点击画面外时按官方逻辑钳制到画面边缘（横向越界贴边并回到中线，纵向同理）
 */
object ViewportMath {

    /** 计算图像 [imageW]x[imageH] 等比居中放入容器后的矩形 */
    fun fit(containerW: Float, containerH: Float, imageW: Float, imageH: Float): FitRect {
        if (containerW <= 0f || containerH <= 0f || imageW <= 0f || imageH <= 0f) {
            return FitRect(0f, 0f, 0f, 0f)
        }
        val scale = min(containerW / imageW, containerH / imageH)
        val w = imageW * scale
        val h = imageH * scale
        return FitRect((containerW - w) / 2f, (containerH - h) / 2f, w, h)
    }

    /**
     * 将容器内坐标 [px],[py] 换算为设备屏幕坐标。
     * 超出画面时按官方逻辑钳制（与 getActualClickPoint(point, allowOverStep=true) 一致）。
     */
    fun toDevicePoint(px: Float, py: Float, rect: FitRect, imageW: Int, imageH: Int): DevicePoint? {
        if (rect.isEmpty || imageW <= 0 || imageH <= 0) return null
        val right = rect.left + rect.width
        val bottom = rect.top + rect.height
        var x = px
        var y = py
        val inside = x >= rect.left && x <= right && y >= rect.top && y <= bottom
        if (!inside) {
            val centerX = rect.left + rect.width / 2f
            val centerY = rect.top + rect.height / 2f
            when {
                x < rect.left -> { x = rect.left + 2f; y = centerY }
                x > right -> { x = centerX; y = centerY }
                y < rect.top -> { x = centerX; y = rect.top + 2f }
                else -> { x = centerX; y = bottom - 2f }
            }
        }
        val sx = rect.width / imageW
        val sy = rect.height / imageH
        val dx = ((x - rect.left) / max(sx, 1e-6f)).toInt()
        val dy = ((y - rect.top) / max(sy, 1e-6f)).toInt()
        return DevicePoint(dx.coerceIn(0, imageW - 1), dy.coerceIn(0, imageH - 1))
    }
}
