package dev.memefinder.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenizerTest {
    @Test
    fun matchesPythonReference() {
        val vocab = File("../app/src/main/assets/vocab.tsv").readLines(Charsets.UTF_8)
        val tok = UnigramTokenizer(vocab)
        val golden = javaClass.getResourceAsStream("/tokenizer_golden.tsv")!!.bufferedReader(Charsets.UTF_8).readLines()
        var checked = 0
        for (line in golden) {
            if (line.isEmpty()) continue
            val tab = line.lastIndexOf('\t')
            val text = line.substring(0, tab)
            val expected = line.substring(tab + 1).split(",").map { it.toInt() }
            val actual = tok.encode(text).ids.toList()
            assertEquals(expected, actual, "token ids differ for \"$text\"")
            checked++
        }
        assertTrue(checked >= 30, "expected at least 30 golden phrases, got $checked")
    }
}

class TextMatchTest {
    @Test
    fun latinLookalikesAreUnified() {
        assertEquals(TextMatch.canon("мне"), TextMatch.canon("MHe"))
        assertEquals(TextMatch.canon("всё"), TextMatch.canon("BCË"))
        assertEquals(TextMatch.canon("вот"), TextMatch.canon("BOT"))
    }

    @Test
    fun gluedWordsAndEndingsStillMatch() {
        val doc = TextMatch.canon("Вас посетил ХОМЯЧОК хорошего сна. ТЫШАРИШЬ хорошимилюдьми")
        val tri = TextMatch.trigrams(doc)
        assertTrue(TextMatch.queryScore(TextMatch.terms("хомяк"), doc, tri) >= 0.6, "stem match for хомяк")
        assertTrue(TextMatch.queryScore(TextMatch.terms("хорошими людьми"), doc, tri) >= 0.6, "glued words")
        assertTrue(TextMatch.queryScore(TextMatch.terms("шаришь"), doc, tri) >= 0.6, "substring inside glued text")
        assertEquals(0.0, TextMatch.queryScore(TextMatch.terms("пингвин"), doc, tri))
    }
}

class SearchIndexTest {
    private fun unit(vararg v: Float): FloatArray {
        val full = FloatArray(EMBEDDING_DIM)
        for (i in v.indices) full[i] = v[i]
        val n = Math.sqrt(full.sumOf { it.toDouble() * it }).toFloat()
        for (i in full.indices) full[i] /= n
        return full
    }

    @Test
    fun textMatchesRankFirstAndSemanticFindsTheRest() {
        val index = SearchIndex()
        val rnd = java.util.Random(1)
        // 200 random distractors
        for (i in 0 until 200) index.put(1000L + i, "", FloatArray(EMBEDDING_DIM) { rnd.nextGaussian().toFloat() * 0.05f }.also { v ->
            val n = Math.sqrt(v.sumOf { it.toDouble() * it }).toFloat(); for (k in v.indices) v[k] /= n
        })
        index.put(1, "ХОМЯЧКИ ВОЗЛЕ КОСТЕРКА", unit(0f, 1f))
        index.put(2, "", unit(1f, 0f, 0.1f)) // visually "hamster-like" but without text
        val q = unit(1f, 0f, 0.1f)

        val all = index.search("хомяк", q, SearchMode.ALL)
        assertEquals(1L, all.first().id, "text hit comes first")
        assertTrue(all.any { it.id == 2L }, "semantic hit is present")

        val text = index.search("хомяк", q, SearchMode.TEXT)
        assertEquals(listOf(1L), text.map { it.id })

        val sem = index.search("хомяк", q, SearchMode.SEMANTIC)
        assertEquals(2L, sem.first().id)
    }
}

class ResampleTest {
    @Test
    fun constantImageStaysConstantAndSizesAreRight() {
        val px = IntArray(40 * 30) { (255 shl 24) or (200 shl 16) or (100 shl 8) or 50 }
        val img = RgbImage(40, 30, px)
        for (f in RgbImage.Filter.values()) {
            val r = img.resize(13, 7, f)
            assertEquals(13, r.width); assertEquals(7, r.height)
            assertTrue(r.argb.all { it == px[0] }, "colour preserved with $f")
        }
    }

    @Test
    fun downscaleAveragesCheckerboard() {
        val px = IntArray(64 * 64) { val v = if (((it % 64) + (it / 64)) % 2 == 0) 255 else 0; (255 shl 24) or (v shl 16) or (v shl 8) or v }
        val r = RgbImage(64, 64, px).resize(8, 8, RgbImage.Filter.BILINEAR)
        for (p in r.argb) assertTrue(((p shr 8) and 255) in 100..155, "anti-aliased grey expected, got ${(p shr 8) and 255}")
    }
}
