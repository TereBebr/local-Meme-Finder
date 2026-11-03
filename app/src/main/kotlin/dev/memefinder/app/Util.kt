package dev.memefinder.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Thumbs {
    private val cache = LruCache<Long, Bitmap>(400)

    suspend fun get(ctx: Context, id: Long): Bitmap? {
        cache.get(id)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(Media.uriOf(id), Size(300, 300), null) }
                .getOrNull()
                ?.also { cache.put(id, it) }
        }
    }
}

object Share {
    fun send(ctx: Context, ids: List<Long>) {
        if (ids.isEmpty()) return
        val uris = ids.map { Media.uriOf(it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            }
        }
        val clip = ClipData.newRawUri("", uris[0])
        for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun formatEta(seconds: Int): String {
    if (seconds < 0) return "…"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "~$h ч $m мин"
        m > 0 -> "~$m мин"
        else -> "меньше минуты"
    }
}
