package dev.memefinder.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A plain RGB image (ARGB ints). Pure Kotlin so the same code runs on Android and in JVM tests. */
class RgbImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size == width * height) { "pixel array size does not match ${width}x$height" }
    }

    enum class Filter { BILINEAR, BICUBIC }

    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): RgbImage {
        val w = x1 - x0
        val h = y1 - y0
        require(w > 0 && h > 0 && x0 >= 0 && y0 >= 0 && x1 <= width && y1 <= height) { "bad crop" }
        val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(argb, (y0 + y) * width + x0, out, y * w, w)
        return RgbImage(w, h, out)
    }

    /** Resampling that follows Pillow's convolution resize (support widens when shrinking, so it anti-aliases). */
    fun resize(newW: Int, newH: Int, filter: Filter = Filter.BILINEAR): RgbImage {
        if (newW == width && newH == height) return this
        val ch = coefficients(width, newW, filter)
        val cv = coefficients(height, newH, filter)

        val tmpR = FloatArray(height * newW)
        val tmpG = FloatArray(height * newW)
        val tmpB = FloatArray(height * newW)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until newW) {
                val s = ch.start[x]
                val n = ch.count[x]
                val wo = x * ch.stride
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in 0 until n) {
                    val p = argb[row + s + k]
                    val wt = ch.weights[wo + k]
                    r += ((p shr 16) and 255) * wt
                    g += ((p shr 8) and 255) * wt
                    b += (p and 255) * wt
                }
                val o = y * newW + x
                tmpR[o] = r
                tmpG[o] = g
                tmpB[o] = b
            }
        }

        val out = IntArray(newW * newH)
        for (y in 0 until newH) {
            val s = cv.start[y]
            val n = cv.count[y]
            val wo = y * cv.stride
            for (x in 0 until newW) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in 0 until n) {
                    val wt = cv.weights[wo + k]
                    val idx = (s + k) * newW + x
                    r += tmpR[idx] * wt
                    g += tmpG[idx] * wt
                    b += tmpB[idx] * wt
                }
                out[y * newW + x] = (255 shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
            }
        }
        return RgbImage(newW, newH, out)
    }

    private class Coefficients(val start: IntArray, val count: IntArray, val weights: FloatArray, val stride: Int)

    private fun to8(v: Float): Int = min(255, max(0, (v + 0.5f).toInt()))

    private fun kernel(x0: Double, filter: Filter): Double {
        val x = abs(x0)
        return when (filter) {
            Filter.BILINEAR -> if (x < 1.0) 1.0 - x else 0.0
            Filter.BICUBIC -> {
                val a = -0.5
                when {
                    x < 1.0 -> ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0
                    x < 2.0 -> (((x - 5.0) * x + 8.0) * x - 4.0) * a
                    else -> 0.0
                }
            }
        }
    }

    private fun coefficients(inSize: Int, outSize: Int, filter: Filter): Coefficients {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = (if (filter == Filter.BILINEAR) 1.0 else 2.0) * filterScale
        val stride = ceil(support).toInt() * 2 + 1
        val start = IntArray(outSize)
        val count = IntArray(outSize)
        val weights = FloatArray(outSize * stride)
        for (i in 0 until outSize) {
            val center = (i + 0.5) * scale
            val xmin = max(0, (center - support + 0.5).toInt())
            val xmax = min(inSize, (center + support + 0.5).toInt())
            val n = max(0, xmax - xmin)
            var sum = 0.0
            val tmp = DoubleArray(n)
            for (x in 0 until n) {
                val v = kernel((x + xmin - center + 0.5) / filterScale, filter)
                tmp[x] = v
                sum += v
            }
            for (x in 0 until n) weights[i * stride + x] = (if (sum != 0.0) tmp[x] / sum else tmp[x]).toFloat()
            start[i] = xmin
            count[i] = n
        }
        return Coefficients(start, count, weights, stride)
    }
}
