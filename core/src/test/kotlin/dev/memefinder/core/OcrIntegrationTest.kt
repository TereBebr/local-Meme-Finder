package dev.memefinder.core

import ai.onnxruntime.OrtEnvironment
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs the real OCR models on a generated picture. Skipped unless MODELS_DIR points at the model files. */
class OcrIntegrationTest {
    private fun render(lines: List<String>): RgbImage {
        val w = 1100
        val h = 130 * lines.size + 60
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = bi.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.color = Color.BLACK
        g.font = Font("SansSerif", Font.BOLD, 90)
        for ((i, line) in lines.withIndex()) g.drawString(line, 40, 120 + 130 * i)
        g.dispose()
        val px = IntArray(w * h)
        bi.getRGB(0, 0, w, h, px, 0, w)
        return RgbImage(w, h, px)
    }

    @Test
    fun readsRussianText() {
        val dir = System.getenv("MODELS_DIR")?.takeIf { it.isNotBlank() }
        if (dir == null) {
            println("SKIPPED: MODELS_DIR is not set")
            return
        }
        val det = File(dir, "ch_PP-OCRv5_det_mobile.onnx")
        val rec = File(dir, "eslav_PP-OCRv5_rec_mobile.onnx")
        if (!det.exists() || !rec.exists()) {
            println("SKIPPED: OCR models not found in $dir")
            return
        }
        OcrEngine(OrtEnvironment.getEnvironment(), det.path, rec.path, threads = 2).use { ocr ->
            val img = render(listOf("ПРИВЕТ МИР", "КОТИК И ЖИРАФ"))
            val t0 = System.nanoTime()
            val lines = ocr.run(img)
            println("OCR (${(System.nanoTime() - t0) / 1_000_000} ms): $lines")
            val doc = TextMatch.canon(lines.joinToString(" "))
            val tri = TextMatch.trigrams(doc)
            assertTrue(TextMatch.queryScore(TextMatch.terms("привет мир"), doc, tri) >= 0.6, "expected 'привет мир' in $lines")
            assertTrue(TextMatch.queryScore(TextMatch.terms("котик"), doc, tri) >= 0.6, "expected 'котик' in $lines")
        }
    }
}
