package com.alick.livertmp.utils

import android.graphics.Bitmap

object YUVConverter {
    // 全局复用缓冲区（线程安全需自行控制）
    private var pixelBuffer: IntArray? = null

    /**
     * Bitmap → NV12（零内存分配）
     * @param bitmap 输入Bitmap（ARGB_8888）
     * @param dest 目标NV12缓冲区（需大小 >= width*height*1.5）
     * @param width 输出宽度（需偶数）
     * @param height 输出高度（需偶数）
     */
    fun bitmapToNV12(
        bitmap: Bitmap,
        dest: ByteArray,
        width: Int,
        height: Int
    ) {
        // 1. 检查或初始化缓冲区
        val requiredPixels = width * height
        if (pixelBuffer == null || pixelBuffer!!.size != requiredPixels) {
            pixelBuffer = IntArray(requiredPixels)
        }

        bitmap.getPixels(pixelBuffer, 0, width, 0, 0, width, height)

        var yIndex = 0
        var uvIndex = width * height

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixelBuffer!![y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // 标准 BT.601 YUV 转换公式
                val Y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                dest[yIndex++] = Y.coerceIn(0, 255).toByte()

                // 仅偶数行偶数列采样 UV
                if (y % 2 == 0 && x % 2 == 0) {
                    val U = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val V = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    dest[uvIndex++] = U.coerceIn(0, 255).toByte()  // NV12: U first
                    dest[uvIndex++] = V.coerceIn(0, 255).toByte()
                }
            }
        }
    }
}