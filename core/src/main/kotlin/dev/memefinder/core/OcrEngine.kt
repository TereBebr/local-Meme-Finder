package dev.memefinder.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * PP-OCRv5 text detection + recognition (East-Slavic recognizer) on top of ONNX Runtime.
 * Mirrors scripts/ocr_ref.py step by step.
 */
class OcrEngine(
    private val env: OrtEnvironment,
    detPath: String,
    recPath: String,
    threads: Int = 4,
) : AutoCloseable {

    data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    private val det: OrtSession
    private val rec: OrtSession
    private val charset: List<String>

    init {
        val detOpts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
        val recOpts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
        det = env.createSession(detPath, detOpts)
        rec = env.createSession(recPath, recOpts)
        val raw = rec.metadata.customMetadata["character"] ?: error("recognition model has no 'character' metadata")
        var chars = raw.split("\n")
        if (chars.isNotEmpty() && chars.last().isEmpty()) chars = chars.dropLast(1)
        charset = listOf("blank") + chars + listOf(" ")
    }

    // ---------------------------------------------------------------- detection

    fun detect(img: RgbImage): List<Box> {
        val w0 = img.width
        val h0 = img.height
        var s = if (min(w0, h0) < DET_MIN_SIDE) DET_MIN_SIDE.toDouble() / min(w0, h0) else 1.0
        if (max(w0, h0) * s > DET_MAX_SIDE) s = DET_MAX_SIDE.toDouble() / max(w0, h0)
        val nh = max(32, Math.rint(h0 * s / 32.0).toInt() * 32)
        val nw = max(32, Math.rint(w0 * s / 32.0).toInt() * 32)
        val small = img.resize(nw, nh, RgbImage.Filter.BILINEAR)

        val plane = nh * nw
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = small.argb[i]
            input[i] = (((p shr 16) and 255) / 255f - 0.5f) / 0.5f
            input[plane + i] = (((p shr 8) and 255) / 255f - 0.5f) / 0.5f
            input[2 * plane + i] = ((p and 255) / 255f - 0.5f) / 0.5f
        }
        val prob = OrtUtil.floatTensor(env, input, longArrayOf(1, 3, nh.toLong(), nw.toLong())).use { tensor ->
            det.run(mapOf("x" to tensor)).use { res ->
                OrtUtil.readFloats(res[0] as OnnxTensor)
            }
        }
        return boxesFromProbabilities(prob, nw, nh, w0, h0)
    }

    internal fun boxesFromProbabilities(prob: FloatArray, w: Int, h: Int, origW: Int, origH: Int): List<Box> {
        // binarize, then dilate with a 2x2 kernel
        val dil = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var v = prob[i] > DET_THRESH
                if (!v && x > 0 && prob[i - 1] > DET_THRESH) v = true
                if (!v && y > 0 && prob[i - w] > DET_THRESH) v = true
                if (!v && x > 0 && y > 0 && prob[i - w - 1] > DET_THRESH) v = true
                dil[i] = v
            }
        }
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val sx = origW.toDouble() / w
        val sy = origH.toDouble() / h
        val boxes = ArrayList<Box>()

        for (start in 0 until w * h) {
            if (!dil[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var x0 = w
            var x1 = -1
            var y0 = h
            var y1 = -1
            while (head < tail) {
                val cur = queue[head++]
                val cy = cur / w
                val cx = cur - cy * w
                if (cx < x0) x0 = cx
                if (cx > x1) x1 = cx
                if (cy < y0) y0 = cy
                if (cy > y1) y1 = cy
                for (dy in -1..1) {
                    val ny = cy + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = cx + dx
                        if (nx < 0 || nx >= w) continue
                        val ni = ny * w + nx
                        if (dil[ni] && !visited[ni]) {
                            visited[ni] = true
                            queue[tail++] = ni
                        }
                    }
                }
            }
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (min(bw, bh) < MIN_SIZE) continue
            var sum = 0.0
            for (yy in y0..y1) for (xx in x0..x1) sum += prob[yy * w + xx]
            val score = sum / (bw.toDouble() * bh)
            if (score < BOX_THRESH) continue
            val dist = bw.toDouble() * bh * UNCLIP / (2.0 * (bw + bh))
            val ex0 = x0 - dist
            val ey0 = y0 - dist
            val ex1 = x1 + 1 + dist
            val ey1 = y1 + 1 + dist
            if (min(ex1 - ex0, ey1 - ey0) < MIN_SIZE + 2) continue
            boxes.add(
                Box(
                    max(0, Math.floor(ex0 * sx).toInt()),
                    max(0, Math.floor(ey0 * sy).toInt()),
                    min(origW, ceil(ex1 * sx).toInt()),
                    min(origH, ceil(ey1 * sy).toInt()),
                ),
            )
        }
        return readingOrder(boxes)
    }

    private fun readingOrder(boxes: List<Box>): List<Box> {
        val sorted = boxes.sortedWith(compareBy<Box>({ it.y0 }, { it.x0 }))
        val rows = ArrayList<MutableList<Box>>()
        for (b in sorted) {
            val row = rows.firstOrNull { kotlin.math.abs(it[0].y0 - b.y0) < 10 }
            if (row != null) row.add(b) else rows.add(mutableListOf(b))
        }
        return rows.flatMap { r -> r.sortedBy { it.x0 } }
    }

    // ---------------------------------------------------------------- recognition

    data class Recognized(val text: String, val score: Float)

    fun recognize(crop: RgbImage): Recognized {
        val ratio = crop.width.toDouble() / crop.height
        val maxWh = max(REC_MIN_W.toDouble() / REC_H, ratio)
        val padW = (REC_H * maxWh).toInt()
        val rw = min(padW, ceil(REC_H * ratio).toInt())
        val small = crop.resize(max(1, rw), REC_H, RgbImage.Filter.BILINEAR)

        val plane = REC_H * padW
        val input = FloatArray(3 * plane) // zero padding on the right, as in the reference
        for (y in 0 until REC_H) {
            for (x in 0 until small.width) {
                val p = small.argb[y * small.width + x]
                val o = y * padW + x
                input[o] = (((p shr 16) and 255) / 255f - 0.5f) / 0.5f
                input[plane + o] = (((p shr 8) and 255) / 255f - 0.5f) / 0.5f
                input[2 * plane + o] = ((p and 255) / 255f - 0.5f) / 0.5f
            }
        }
        val out = OrtUtil.floatTensor(env, input, longArrayOf(1, 3, REC_H.toLong(), padW.toLong())).use { tensor ->
            rec.run(mapOf("x" to tensor)).use { res ->
                val t = res[0] as OnnxTensor
                Pair(OrtUtil.readFloats(t), t.info.shape.last().toInt())
            }
        }
        return ctcDecode(out.first, out.second)
    }

    internal fun ctcDecode(logits: FloatArray, classes: Int): Recognized {
        val steps = logits.size / classes
        val sb = StringBuilder()
        var scoreSum = 0.0
        var n = 0
        var prev = -1
        for (t in 0 until steps) {
            var best = 0
            var bestV = logits[t * classes]
            for (c in 1 until classes) {
                val v = logits[t * classes + c]
                if (v > bestV) {
                    bestV = v
                    best = c
                }
            }
            if (best != 0 && best != prev && best < charset.size) {
                sb.append(charset[best])
                scoreSum += bestV
                n++
            }
            prev = best
        }
        return Recognized(sb.toString(), if (n > 0) (scoreSum / n).toFloat() else 0f)
    }

    /** Full pipeline: returns the recognized lines in reading order. */
    fun run(img: RgbImage): List<String> {
        val lines = ArrayList<String>()
        for (b in detect(img)) {
            if (b.x1 - b.x0 < 2 || b.y1 - b.y0 < 2) continue
            val r = recognize(img.crop(b.x0, b.y0, b.x1, b.y1))
            if (r.text.isNotEmpty() && r.score >= TEXT_SCORE && r.text.any { it.isLetterOrDigit() }) lines.add(r.text)
        }
        return lines
    }

    override fun close() {
        det.close()
        rec.close()
    }

    companion object {
        const val DET_MIN_SIDE = 736
        const val DET_MAX_SIDE = 1280
        const val DET_THRESH = 0.3f
        const val BOX_THRESH = 0.5
        const val UNCLIP = 1.6
        const val MIN_SIZE = 3
        const val REC_H = 48
        const val REC_MIN_W = 320
        const val TEXT_SCORE = 0.5f
    }
}
