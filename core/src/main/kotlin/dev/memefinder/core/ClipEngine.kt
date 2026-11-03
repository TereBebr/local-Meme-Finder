package dev.memefinder.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlin.math.max
import kotlin.math.sqrt

const val EMBEDDING_DIM = 512

private fun l2Normalize(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += x.toDouble() * x
    val n = sqrt(s).toFloat()
    if (n > 0f) for (i in v.indices) v[i] = v[i] / n
    return v
}

/** CLIP ViT-B/32 image tower (int8). Mirrors the preprocessing in clip_test.py. */
class ClipImageEncoder(private val env: OrtEnvironment, modelPath: String, threads: Int = 4) : AutoCloseable {
    private val session: OrtSession =
        env.createSession(modelPath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) })

    fun encode(img: RgbImage): FloatArray {
        val s = 224.0 / minOf(img.width, img.height)
        val nw = max(224, Math.rint(img.width * s).toInt())
        val nh = max(224, Math.rint(img.height * s).toInt())
        val resized = img.resize(nw, nh, RgbImage.Filter.BICUBIC)
        val left = (nw - 224) / 2
        val top = (nh - 224) / 2
        val plane = 224 * 224
        val input = FloatArray(3 * plane)
        for (y in 0 until 224) {
            for (x in 0 until 224) {
                val p = resized.argb[(top + y) * nw + left + x]
                val o = y * 224 + x
                input[o] = (((p shr 16) and 255) / 255f - MEAN[0]) / STD[0]
                input[plane + o] = (((p shr 8) and 255) / 255f - MEAN[1]) / STD[1]
                input[2 * plane + o] = ((p and 255) / 255f - MEAN[2]) / STD[2]
            }
        }
        val out = OrtUtil.floatTensor(env, input, longArrayOf(1, 3, 224, 224)).use { tensor ->
            session.run(mapOf("image" to tensor)).use { res -> OrtUtil.readFloats(res[0] as OnnxTensor) }
        }
        return l2Normalize(out)
    }

    override fun close() = session.close()

    companion object {
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }
}

/** Multilingual text tower of the CLIP model (int8, trimmed vocabulary). */
class ClipTextEncoder(
    private val env: OrtEnvironment,
    modelPath: String,
    private val tokenizer: UnigramTokenizer,
    threads: Int = 4,
) : AutoCloseable {
    private val session: OrtSession =
        env.createSession(modelPath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) })

    fun encode(text: String): FloatArray {
        val enc = tokenizer.encode(text)
        val shape = longArrayOf(1, UnigramTokenizer.MAX_LEN.toLong())
        val out = OrtUtil.intTensor(env, enc.ids, shape).use { ids ->
            OrtUtil.intTensor(env, enc.mask, shape).use { mask ->
                session.run(mapOf("input_ids" to ids, "attention_mask" to mask)).use { res ->
                    OrtUtil.readFloats(res[0] as OnnxTensor)
                }
            }
        }
        return l2Normalize(out)
    }

    override fun close() = session.close()
}
