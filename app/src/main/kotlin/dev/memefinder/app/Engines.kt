package dev.memefinder.app

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.memefinder.core.ClipImageEncoder
import dev.memefinder.core.ClipTextEncoder
import dev.memefinder.core.Hit
import dev.memefinder.core.OcrEngine
import dev.memefinder.core.SearchIndex
import dev.memefinder.core.SearchMode
import dev.memefinder.core.UnigramTokenizer
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** The four model files the user copies to the phone once. */
object ModelFiles {
    const val DET = "ch_PP-OCRv5_det_mobile.onnx"
    const val REC = "eslav_PP-OCRv5_rec_mobile.onnx"
    const val VISUAL = "visual_q8.onnx"
    const val TEXT = "text_q8.onnx"
    val ALL = listOf(DET, REC, VISUAL, TEXT)

    fun dir(ctx: Context): File = File(ctx.filesDir, "models").also { it.mkdirs() }

    fun path(ctx: Context, name: String): String = File(dir(ctx), name).path

    fun missing(ctx: Context): List<String> = ALL.filter {
        val f = File(dir(ctx), it)
        !(f.exists() && f.length() > 0)
    }

    /** Copies the model files out of a folder the user picked (also looks one level down). Returns the names copied. */
    fun importFrom(ctx: Context, tree: Uri, progress: (String) -> Unit): List<String> {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val folders = listOf(root) + root.listFiles().filter { it.isDirectory }
        val copied = ArrayList<String>()
        for (name in ALL) {
            val src = folders.firstNotNullOfOrNull { it.findFile(name) } ?: continue
            progress("Копирую $name…")
            val tmp = File(dir(ctx), "$name.part")
            ctx.contentResolver.openInputStream(src.uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            } ?: continue
            val dst = File(dir(ctx), name)
            if (dst.exists()) dst.delete()
            if (tmp.renameTo(dst)) copied.add(name)
            if (name == TEXT) Tags.reset(ctx)
        }
        return copied
    }
}

/** Lazily created ONNX sessions, shared by the indexer and the search box. */
object Engines {
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private const val THREADS = 4

    @Volatile private var ocr: OcrEngine? = null
    @Volatile private var image: ClipImageEncoder? = null
    @Volatile private var text: ClipTextEncoder? = null

    @Synchronized
    fun ocr(ctx: Context): OcrEngine = ocr ?: OcrEngine(
        env, ModelFiles.path(ctx, ModelFiles.DET), ModelFiles.path(ctx, ModelFiles.REC), THREADS,
    ).also { ocr = it }

    @Synchronized
    fun image(ctx: Context): ClipImageEncoder =
        image ?: ClipImageEncoder(env, ModelFiles.path(ctx, ModelFiles.VISUAL), THREADS).also { image = it }

    @Synchronized
    fun text(ctx: Context): ClipTextEncoder {
        text?.let { return it }
        val vocab = ctx.assets.open("vocab.tsv").bufferedReader(Charsets.UTF_8).use { it.readLines() }
        return ClipTextEncoder(env, ModelFiles.path(ctx, ModelFiles.TEXT), UnigramTokenizer(vocab), THREADS).also { text = it }
    }

    fun isTextLoaded(): Boolean = text != null

    @Synchronized
    fun releaseIndexing() {
        ocr?.close(); ocr = null
        image?.close(); image = null
    }

    @Synchronized
    fun releaseAll() {
        releaseIndexing()
        text?.close(); text = null
    }
}

/** In-memory copy of the index, kept in sync with the database. */
object Library {
    private val lock = Any()
    private val index = SearchIndex()
    private val ocrById = HashMap<Long, String>()

    /** Bumped whenever the index changes so the UI can re-run the current search. */
    val version = MutableStateFlow(0)

    fun size(): Int = synchronized(lock) { index.size }

    fun ocrText(id: Long): String = synchronized(lock) { ocrById[id] ?: "" }

    fun embeddedCount(): Int = synchronized(lock) { index.embeddedCount() }

    fun embedding(id: Long): FloatArray? = synchronized(lock) { index.embeddingOf(id) }

    fun load(db: Db) {
        synchronized(lock) {
            index.clear()
            ocrById.clear()
            db.forEachRow { id, ocr, emb ->
                index.put(id, ocr, emb)
                if (!ocr.isNullOrEmpty()) {
                    ocrById[id] = ocr
                }
            }
        }
        version.value++
    }

    fun put(id: Long, ocr: String?, emb: FloatArray?) {
        synchronized(lock) {
            index.put(id, ocr, emb)
            if (ocr.isNullOrEmpty()) {
                ocrById.remove(id)
            } else {
                ocrById[id] = ocr
            }
            Unit
        }
    }

    fun search(query: String, queryEmb: FloatArray?, mode: SearchMode): List<Hit> =
        synchronized(lock) { index.search(query, queryEmb, mode) }
}
