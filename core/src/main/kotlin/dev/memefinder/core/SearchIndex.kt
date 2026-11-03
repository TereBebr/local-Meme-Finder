package dev.memefinder.core

import kotlin.math.sqrt

enum class SearchMode { ALL, TEXT, SEMANTIC }

data class Hit(val id: Long, val score: Double, val textScore: Double, val clipScore: Double)

/** In-memory index over all indexed pictures: canonical OCR text plus one CLIP embedding each. */
class SearchIndex {
    private val ids = ArrayList<Long>()
    private val texts = ArrayList<String>()
    private val trigrams = ArrayList<LongArray>()
    private val embeddings = ArrayList<FloatArray?>()
    private val positions = HashMap<Long, Int>()
    private val hidden = HashSet<Long>()

    val size: Int get() = ids.size

    /** Pictures the user hid from search results; they stay indexed but are never returned. */
    fun setHidden(ids: Collection<Long>) {
        hidden.clear()
        hidden.addAll(ids)
    }

    fun embeddedCount(): Int = embeddings.count { it != null }

    fun embeddingOf(id: Long): FloatArray? = positions[id]?.let { embeddings[it] }

    fun clear() {
        ids.clear(); texts.clear(); trigrams.clear(); embeddings.clear(); positions.clear()
    }

    /** Adds or replaces a picture. `ocrText` is the raw recognized text (null if not recognized yet). */
    fun put(id: Long, ocrText: String?, embedding: FloatArray?) {
        val canon = TextMatch.canon(ocrText ?: "")
        val tri = TextMatch.trigrams(canon)
        val pos = positions[id]
        if (pos == null) {
            positions[id] = ids.size
            ids.add(id); texts.add(canon); trigrams.add(tri); embeddings.add(embedding)
        } else {
            texts[pos] = canon; trigrams[pos] = tri; embeddings[pos] = embedding
        }
    }

    fun search(query: String, queryEmbedding: FloatArray?, mode: SearchMode, limit: Int = 300): List<Hit> {
        val terms = if (mode == SearchMode.SEMANTIC) emptyList() else TextMatch.terms(query)
        val useClip = mode != SearchMode.TEXT && queryEmbedding != null

        // CLIP similarity for everyone, turned into a z-score so scores are comparable across queries.
        var clipZ: DoubleArray? = null
        if (useClip) {
            val sims = DoubleArray(ids.size) { Double.NaN }
            var sum = 0.0
            var cnt = 0
            for (i in ids.indices) {
                val e = embeddings[i] ?: continue
                var d = 0.0
                for (k in e.indices) d += e[k].toDouble() * queryEmbedding!![k]
                sims[i] = d
                sum += d
                cnt++
            }
            if (cnt > 1) {
                val mean = sum / cnt
                var v = 0.0
                for (s in sims) if (!s.isNaN()) v += (s - mean) * (s - mean)
                val std = sqrt(v / cnt).coerceAtLeast(1e-6)
                clipZ = DoubleArray(ids.size) { if (sims[it].isNaN()) Double.NaN else (sims[it] - mean) / std }
            }
        }

        val hits = ArrayList<Hit>()
        for (i in ids.indices) {
            if (hidden.contains(ids[i])) continue
            val ts = if (terms.isEmpty()) 0.0 else TextMatch.queryScore(terms, texts[i], trigrams[i])
            val z = clipZ?.get(i) ?: Double.NaN
            val cs = if (z.isNaN()) 0.0 else ((z - 1.5) / 3.0).coerceIn(0.0, 1.0)
            val score = when (mode) {
                SearchMode.TEXT -> ts
                SearchMode.SEMANTIC -> cs
                SearchMode.ALL -> if (ts >= 0.6) 1.0 + ts + 0.3 * cs else cs + 0.5 * ts
            }
            if (score > 0.0) hits.add(Hit(ids[i], score, ts, cs))
        }
        hits.sortByDescending { it.score }
        return if (hits.size > limit) hits.subList(0, limit) else hits
    }
}
