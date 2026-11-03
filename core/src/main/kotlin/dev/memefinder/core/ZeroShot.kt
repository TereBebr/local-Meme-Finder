package dev.memefinder.core

import kotlin.math.exp

/** Zero-shot labelling: which of a fixed list of words best describes an image embedding. */
object ZeroShot {
    /**
     * @param emb       L2-normalized image embedding
     * @param labelEmbs L2-normalized label embeddings, row-major, labels.size x EMBEDDING_DIM
     * @return up to `k` labels with softmax probabilities; the top 3 are always returned, further ones need `minProb`
     */
    fun top(
        emb: FloatArray,
        labelEmbs: FloatArray,
        labels: List<String>,
        k: Int = 6,
        scale: Double = 100.0,
        minProb: Double = 0.04,
    ): List<Pair<String, Double>> {
        val n = labels.size
        if (n == 0 || labelEmbs.size < n * EMBEDDING_DIM) return emptyList()
        val logits = DoubleArray(n)
        var max = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            var d = 0.0
            val o = i * EMBEDDING_DIM
            for (j in 0 until EMBEDDING_DIM) d += emb[j].toDouble() * labelEmbs[o + j]
            logits[i] = d * scale
            if (logits[i] > max) max = logits[i]
        }
        var sum = 0.0
        for (i in 0 until n) {
            logits[i] = exp(logits[i] - max)
            sum += logits[i]
        }
        return (0 until n).sortedByDescending { logits[it] }
            .mapIndexedNotNull { rank, idx ->
                val p = logits[idx] / sum
                if (rank < 3 || p >= minProb) labels[idx] to p else null
            }
            .take(k)
    }
}
