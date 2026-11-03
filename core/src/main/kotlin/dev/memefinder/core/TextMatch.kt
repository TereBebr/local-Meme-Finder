package dev.memefinder.core

/**
 * Fuzzy matching of a query against OCR text. OCR output is messy: words glue together ("хорошимилюдьми"),
 * Latin letters replace Cyrillic ones ("MHe"), endings vary. So both sides are canonicalized, spaces dropped,
 * and matching falls back from exact substring to word stem to trigram coverage.
 */
object TextMatch {
    private val CONFUSABLE: Map<Char, Char> = mapOf(
        'a' to 'а', 'b' to 'в', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'k' to 'к', 'm' to 'м',
        'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х', 'y' to 'у',
        'ё' to 'е', 'й' to 'и', 'ъ' to 'ь',
    )

    /** Lower-case, unify look-alike letters, keep only letters and digits (no spaces). */
    fun canon(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            if (!ch.isLetterOrDigit()) continue
            sb.append(CONFUSABLE[ch] ?: ch)
        }
        return sb.toString()
    }

    /** Query split into canonical terms. */
    fun terms(query: String): List<String> =
        query.split(Regex("[^\\p{L}\\p{N}]+")).map { canon(it) }.filter { it.isNotEmpty() }

    fun trigrams(canonText: String): LongArray {
        if (canonText.length < 3) return LongArray(0)
        val set = HashSet<Long>()
        for (i in 0..canonText.length - 3) set.add(trigramKey(canonText, i))
        return set.toLongArray().also { it.sort() }
    }

    private fun trigramKey(s: String, i: Int): Long =
        (s[i].code.toLong() shl 32) or (s[i + 1].code.toLong() shl 16) or s[i + 2].code.toLong()

    /** Score of one canonical term against a canonical document, in 0..1. */
    fun termScore(term: String, doc: String, docTrigrams: LongArray): Double {
        if (term.length < 2) return 0.0
        if (doc.contains(term)) return 1.0
        val stemLen = when {
            term.length >= 7 -> term.length - 2
            term.length >= 4 -> term.length - 1
            else -> term.length
        }
        if (stemLen >= 3 && stemLen < term.length && doc.contains(term.substring(0, stemLen))) return 0.9
        if (term.length >= 4) {
            var hit = 0
            val total = term.length - 2
            for (i in 0 until total) {
                if (java.util.Arrays.binarySearch(docTrigrams, trigramKey(term, i)) >= 0) hit++
            }
            val cov = hit.toDouble() / total
            if (cov >= 0.6) return 0.7 * cov
        }
        return 0.0
    }

    /** Mean term score (a missing term counts as zero), so multi-word queries need most of their words. */
    fun queryScore(terms: List<String>, doc: String, docTrigrams: LongArray): Double {
        if (terms.isEmpty() || doc.isEmpty()) return 0.0
        var sum = 0.0
        for (t in terms) sum += termScore(t, doc, docTrigrams)
        return sum / terms.size
    }
}
