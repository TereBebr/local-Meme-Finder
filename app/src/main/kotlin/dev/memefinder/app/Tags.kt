package dev.memefinder.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import dev.memefinder.core.EMBEDDING_DIM
import dev.memefinder.core.SearchMode
import dev.memefinder.core.ZeroShot
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** "What is in this picture": a fixed list of Russian words scored against the image embedding. */
object Tags {
    val state = MutableStateFlow("")

    @Volatile private var labels: List<String> = emptyList()
    @Volatile private var matrix: FloatArray? = null
    private val lock = Any()

    fun reset(ctx: Context) {
        synchronized(lock) {
            matrix = null
            labels = emptyList()
            File(ctx.filesDir, CACHE).delete()
        }
    }

    fun top(emb: FloatArray, k: Int = 6): List<Pair<String, Double>> {
        val m = matrix ?: return emptyList()
        return ZeroShot.top(emb, m, labels, k)
    }

    /** Loads the tag embeddings from the cache or computes them with the text model. Returns null on success, else an error. */
    fun ensure(ctx: Context): String? {
        if (matrix != null) return null
        return synchronized(lock) { loadOrCompute(ctx) }
    }

    private fun loadOrCompute(ctx: Context): String? {
        if (matrix != null) return null
        return try {
                val list = ctx.assets.open("tags.txt").bufferedReader(Charsets.UTF_8).readLines().map { it.trim() }.filter { it.isNotEmpty() }
                val cache = File(ctx.filesDir, CACHE)
                if (cache.exists() && cache.length() == 4L + list.size * EMBEDDING_DIM * 4L) {
                    val bb = ByteBuffer.wrap(cache.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                    if (bb.int == list.size) {
                        val m = FloatArray(list.size * EMBEDDING_DIM)
                        bb.asFloatBuffer().get(m)
                        labels = list
                        matrix = m
                        return null
                    }
                }
                val enc = Engines.text(ctx)
                val m = FloatArray(list.size * EMBEDDING_DIM)
                for ((i, tag) in list.withIndex()) {
                    state.value = "Готовлю описания картинок: ${i + 1} из ${list.size}"
                    enc.encode(tag).copyInto(m, i * EMBEDDING_DIM)
                }
                val bb = ByteBuffer.allocate(4 + m.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bb.putInt(list.size)
                bb.asFloatBuffer().put(m)
                cache.writeBytes(bb.array())
                labels = list
                matrix = m
                state.value = ""
                null
            } catch (e: Throwable) {
                state.value = ""
                IndexState.note("теги", e)
                "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private const val CACHE = "tags_v1.bin"
}

/** Step-by-step self test of the models, shown on the indexing screen. */
object Diagnostics {
    private val EXPECTED_SIZES = linkedMapOf(
        ModelFiles.DET to 4819576L,
        ModelFiles.REC to 7911802L,
        ModelFiles.VISUAL to 96195402L,
        ModelFiles.TEXT to 363337880L,
    )

    private inline fun <T> step(say: (String) -> Unit, title: String, block: () -> T): T? {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val r = block()
            say("✓ $title: ${SystemClock.elapsedRealtime() - t0} мс")
            r
        } catch (e: Throwable) {
            say("✗ $title: ${e.javaClass.simpleName}: ${e.message}".take(500))
            IndexState.note("диагностика: $title", e)
            null
        }
    }

    /** Blocking; call from a background thread. */
    fun run(ctx: Context, say: (String) -> Unit) {
        val rt = Runtime.getRuntime()
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        say("Android ${Build.VERSION.RELEASE}, ${Build.MODEL}, ядер: ${rt.availableProcessors()}")
        say("Память: свободно ${mi.availMem / 1048576} МБ из ${mi.totalMem / 1048576} МБ")

        for ((name, size) in EXPECTED_SIZES) {
            val f = File(ModelFiles.dir(ctx), name)
            say(
                when {
                    !f.exists() -> "✗ $name: файла нет"
                    f.length() != size -> "✗ $name: размер ${f.length()} вместо $size (файл неполный или другая версия)"
                    else -> "✓ $name: ${f.length() / 1048576} МБ"
                },
            )
        }

        step(say, "модели распознавания текста загружаются") { Engines.ocr(ctx) }

        val image = step(say, "модель картинок загружается") { Engines.image(ctx) }
        if (image != null) {
            val item = Media.listImages(ctx).firstOrNull()
            if (item == null) {
                say("• в галерее нет картинок для проверки")
            } else {
                val rgb = Media.loadRgb(ctx, item.id)
                if (rgb == null) say("✗ не удалось прочитать ${item.name}") else {
                    val v = step(say, "вектор смысла для ${item.name}") { image.encode(rgb) }
                    if (v != null) say(if (v.all { it.isFinite() }) "  вектор в порядке (${v.size} чисел)" else "✗ в векторе есть NaN или бесконечности")
                }
            }
        }

        val text = step(say, "текстовая модель загружается") { Engines.text(ctx) }
        var query: FloatArray? = null
        if (text != null) {
            query = step(say, "вектор для запроса «кошка»") { text.encode("кошка") }
            if (query != null) say(if (query.all { it.isFinite() }) "  вектор в порядке (${query.size} чисел)" else "✗ в векторе есть NaN или бесконечности")
        }

        val (total, withEmb, withText) = MemeApp.instance.db.stats()
        say("В базе: картинок $total, с вектором смысла $withEmb, с текстом $withText")
        if (withEmb >= 2) {
            val sample = ArrayList<FloatArray>()
            MemeApp.instance.db.forEachRow { _, _, emb -> if (emb != null && sample.size < 30) sample.add(emb) }
            var sum = 0.0
            var n = 0
            for (i in sample.indices) for (j in i + 1 until sample.size) {
                var d = 0.0
                for (k in sample[i].indices) d += sample[i][k].toDouble() * sample[j][k]
                sum += d
                n++
            }
            val mean = if (n > 0) sum / n else 0.0
            say(
                if (mean > 0.97) "✗ вектора разных картинок почти одинаковые (средняя близость %.3f), модель картинок работает неверно".format(mean)
                else "✓ вектора разных картинок различаются (средняя близость %.3f)".format(mean),
            )
        } else if (total > 0) {
            say("✗ у проиндексированных картинок нет вектора смысла: индексация шла в режиме «Только текст» или модель картинок падала")
        }
        if (query != null && withEmb > 0) {
            val hits = Library.search("кошка", query, SearchMode.SEMANTIC)
            say("Поиск «кошка» по смыслу: ${hits.size} результатов")
        }
        say("Ошибок при индексации за последний запуск: ${IndexState.errors.value}")
        if (IndexState.lastError.value.isNotEmpty()) say("Последняя ошибка: ${IndexState.lastError.value}")
        say("— конец проверки —")
    }
}
