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

class ResampleParityTest {
    private fun load(name: String): RgbImage {
        val bytes = javaClass.getResourceAsStream("/$name")!!.readBytes()
        val bb = java.nio.ByteBuffer.wrap(bytes)
        val w = bb.int
        val h = bb.int
        val px = IntArray(w * h)
        for (i in px.indices) {
            val r = bb.get().toInt() and 255
            val g = bb.get().toInt() and 255
            val b = bb.get().toInt() and 255
            px[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return RgbImage(w, h, px)
    }

    private fun check(name: String, filter: RgbImage.Filter) {
        val src = load("resample_input.bin")
        val expected = load("resample_$name.bin")
        val actual = src.resize(expected.width, expected.height, filter)
        var sum = 0L
        var max = 0
        for (i in expected.argb.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                val d = Math.abs(((expected.argb[i] shr shift) and 255) - ((actual.argb[i] shr shift) and 255))
                sum += d
                if (d > max) max = d
            }
        }
        val mean = sum.toDouble() / (expected.argb.size * 3)
        println("resample $name: mean abs diff %.3f, max %d".format(mean, max))
        assertTrue(mean < 0.6 && max <= 40, "resample $name differs from Pillow: mean=$mean max=$max")
    }

    @Test fun bilinearDownMatchesPillow() = check("bilinear_down", RgbImage.Filter.BILINEAR)
    @Test fun bilinearUpMatchesPillow() = check("bilinear_up", RgbImage.Filter.BILINEAR)
    @Test fun bicubicDownMatchesPillow() = check("bicubic_down", RgbImage.Filter.BICUBIC)
    @Test fun bicubicUpMatchesPillow() = check("bicubic_up", RgbImage.Filter.BICUBIC)
}

class ZeroShotTest {
    private fun unit(index: Int): FloatArray = FloatArray(EMBEDDING_DIM).also { it[index] = 1f }

    @Test
    fun picksTheClosestLabelsAndAlwaysReturnsThree() {
        val labels = listOf("кот", "собака", "хомяк", "машина")
        val matrix = FloatArray(labels.size * EMBEDDING_DIM)
        for (i in labels.indices) unit(i).copyInto(matrix, i * EMBEDDING_DIM)
        val image = FloatArray(EMBEDDING_DIM).also { it[0] = 0.95f; it[2] = 0.31f }
        val res = ZeroShot.top(image, matrix, labels)
        assertEquals("кот", res[0].first)
        assertTrue(res.size >= 3, "top three are always present")
        assertTrue(res.zipWithNext().all { (a, b) -> a.second >= b.second }, "sorted by probability")
        assertEquals(1.0, res.sumOf { it.second }, 0.5)
    }
}

class TokenizerWhitespaceTest {
    @Test
    fun oddWhitespaceAndEmptyInput() {
        val tok = UnigramTokenizer(File("../app/src/main/assets/vocab.tsv").readLines(Charsets.UTF_8))
        val plain = tok.encode("кот на диване").ids.toList()
        assertEquals(plain, tok.encode("  кот на\t\tдиване \n").ids.toList(), "NBSP, tabs and newlines act as single spaces")
        val empty = tok.encode("   ")
        assertEquals(listOf(0, 2), empty.ids.take(2))
        assertEquals(2, empty.mask.sum())
    }
}
