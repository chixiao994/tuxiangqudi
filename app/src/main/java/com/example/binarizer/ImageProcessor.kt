package com.example.binarizer

import android.graphics.Bitmap

object ImageProcessor {

    /**
     * 对图像做去底二值化
     */
    fun binarize(src: Bitmap, params: Params): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 灰度化
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        val bin = IntArray(w * h)
        when (params.mode) {
            1 -> {
                val t = params.threshold.coerceIn(0, 255)
                for (i in gray.indices) bin[i] = if (gray[i] > t) 255 else 0
            }
            2 -> {
                adaptiveThreshold(gray, bin, w, h, 15, 10)
            }
            else -> {
                val t = otsu(gray)
                for (i in gray.indices) bin[i] = if (gray[i] > t) 255 else 0
            }
        }

        // 输出 ARGB
        val out = IntArray(w * h)
        val inv = params.invert
        for (i in bin.indices) {
            var v = bin[i]
            if (inv) v = 255 - v
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    /**
     * Otsu 大津法自动阈值
     */
    private fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        for (v in gray) hist[v.coerceIn(0, 255)]++

        val total = gray.size
        var sumAll = 0.0
        for (i in 0..255) sumAll += i.toDouble() * hist[i]

        var sumB = 0.0
        var wB = 0
        var maxBetween = -1.0
        var threshold = 0

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)

            if (between > maxBetween) {
                maxBetween = between
                threshold = t
            }
        }
        return threshold
    }

    /**
     * 自适应阈值（积分图均值法）
     */
    private fun adaptiveThreshold(
        gray: IntArray,
        out: IntArray,
        w: Int,
        h: Int,
        blockSize: Int,
        c: Int
    ) {
        val stride = w + 1
        val integral = LongArray(stride * (h + 1))

        for (y in 0 until h) {
            var rowSum = 0L
            val rowOffset = (y + 1) * stride
            val prevOffset = y * stride
            val srcOffset = y * w
            for (x in 0 until w) {
                rowSum += gray[srcOffset + x].toLong()
                integral[rowOffset + x + 1] = integral[prevOffset + x + 1] + rowSum
            }
        }

        val r = blockSize / 2
        for (y in 0 until h) {
            for (x in 0 until w) {
                val x1 = if (x - r < 0) 0 else x - r
                val y1 = if (y - r < 0) 0 else y - r
                val x2 = if (x + r >= w) w - 1 else x + r
                val y2 = if (y + r >= h) h - 1 else y + r

                val count = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sum = integral[(y2 + 1) * stride + (x2 + 1)] -
                        integral[y1 * stride + (x2 + 1)] -
                        integral[(y2 + 1) * stride + x1] +
                        integral[y1 * stride + x1]

                val mean = sum / count
                out[y * w + x] = if (gray[y * w + x] > mean - c) 255 else 0
            }
        }
    }
}
