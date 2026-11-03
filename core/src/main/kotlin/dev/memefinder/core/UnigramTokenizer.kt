package dev.memefinder.core

import java.text.Normalizer

/**
 * SentencePiece-style Unigram tokenizer over the trimmed XLM-R vocabulary (Cyrillic + common Latin).
 * Vocabulary lines are "piece<TAB>score"; the line number is the token id (0=<s>, 1=<pad>, 2=</s>, 3=<unk>).
 * Mirrors scripts/tok_ref.py.
 */
class UnigramTokenizer(vocabLines: List<String>) {
    private val pieces = ArrayList<String>()
    private val scores = ArrayList<Double>()
    private val index = HashMap<String, Int>()
    private val maxLen: Int
    private val unkScore: Double

    init {
        for (line in vocabLines) {
            if (line.isEmpty()) continue
            val tab = line.lastIndexOf('\t')
            pieces.add(line.substring(0, tab))
            scores.add(line.substring(tab + 1).toDouble())
        }
        var ml = 1
        var minScore = Double.MAX_VALUE
        for (i in 4 until pieces.size) {
            index[pieces[i]] = i
            if (pieces[i].length > ml) ml = pieces[i].length
            if (scores[i] < minScore) minScore = scores[i]
        }
        maxLen = ml
        unkScore = minScore - 10.0
    }

    class Encoded(val ids: IntArray, val mask: IntArray)

    fun encode(text: String): Encoded {
        val normalized = collapseWhitespace(Normalizer.normalize(text, Normalizer.Form.NFKC))
        val ids = ArrayList<Int>()
        ids.add(BOS)
        if (normalized.isNotEmpty()) {
            val body = ArrayList<Int>()
            for (word in normalized.split(" ")) body.addAll(viterbi("▁$word"))
            val room = MAX_LEN - 2
            ids.addAll(if (body.size > room) body.subList(0, room) else body)
        }
        ids.add(EOS)
        val outIds = IntArray(MAX_LEN) { PAD }
        val outMask = IntArray(MAX_LEN)
        for (i in ids.indices) {
            outIds[i] = ids[i]
            outMask[i] = 1
        }
        return Encoded(outIds, outMask)
    }

    /** Runs of whitespace become one space, ends trimmed. (No regex flags: Android's regex engine rejects some of them.) */
    private fun collapseWhitespace(s: String): String {
        val sb = StringBuilder(s.length)
        var prevSpace = true
        for (ch in s) {
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                if (!prevSpace) {
                    sb.append(' ')
                    prevSpace = true
                }
            } else {
                sb.append(ch)
                prevSpace = false
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    private fun viterbi(s: String): List<Int> {
        val n = s.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val backStart = IntArray(n + 1)
        val backId = IntArray(n + 1)
        best[0] = 0.0
        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            var foundSingle = false
            val limit = minOf(n, i + maxLen)
            for (j in i + 1..limit) {
                val pid = index[s.substring(i, j)] ?: continue
                val sc = best[i] + scores[pid]
                if (sc > best[j]) {
                    best[j] = sc
                    backStart[j] = i
                    backId[j] = pid
                }
                if (j == i + 1) foundSingle = true
            }
            if (!foundSingle) {
                val sc = best[i] + unkScore
                if (sc > best[i + 1]) {
                    best[i + 1] = sc
                    backStart[i + 1] = i
                    backId[i + 1] = UNK
                }
            }
        }
        val out = ArrayList<Int>()
        var j = n
        while (j > 0) {
            out.add(backId[j])
            j = backStart[j]
        }
        out.reverse()
        return out
    }

    companion object {
        const val MAX_LEN = 77
        const val BOS = 0
        const val PAD = 1
        const val EOS = 2
        const val UNK = 3
    }
}
