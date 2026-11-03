package dev.memefinder.app

import android.content.ContentUris
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import dev.memefinder.core.RgbImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** One picture known to MediaStore. */
data class MediaItem(val id: Long, val name: String, val mtime: Long, val bucketId: Long, val bucketName: String)

data class Bucket(val id: Long, val name: String, val count: Int)

data class IndexStatus(val mtime: Long, val hasOcr: Boolean, val hasEmb: Boolean)

object Media {
    private val BASE: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun uriOf(id: Long): Uri = ContentUris.withAppendedId(BASE, id)

    fun listImages(ctx: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val selection = "${MediaStore.Images.Media.MIME_TYPE} IN ('image/jpeg','image/png','image/webp','image/gif','image/heic')"
        ctx.contentResolver.query(BASE, projection, selection, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iTime = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val iBid = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val iBname = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                out.add(MediaItem(c.getLong(iId), c.getString(iName) ?: "", c.getLong(iTime), c.getLong(iBid), c.getString(iBname) ?: "?"))
            }
        }
        return out
    }

    fun buckets(items: List<MediaItem>): List<Bucket> =
        items.groupBy { it.bucketId }
            .map { (id, list) -> Bucket(id, list.first().bucketName, list.size) }
            .sortedByDescending { it.count }

    /** Decodes a picture down to roughly `maxSide` pixels and returns it as a plain RGB image. */
    fun loadRgb(ctx: Context, id: Long, maxSide: Int = 1280): RgbImage? {
        val uri = uriOf(id)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        // transparent pixels are composited over white, as a viewer would show them
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            if (a < 255) {
                val r = (((p shr 16) and 255) * a + 255 * (255 - a)) / 255
                val g = (((p shr 8) and 255) * a + 255 * (255 - a)) / 255
                val b = ((p and 255) * a + 255 * (255 - a)) / 255
                px[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return RgbImage(w, h, px)
    }

    fun loadBitmap(ctx: Context, id: Long, maxSide: Int): Bitmap? {
        val uri = uriOf(id)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}

/** Persistent index: recognized text and CLIP embedding per picture. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "index.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE images(id INTEGER PRIMARY KEY, mtime INTEGER NOT NULL, name TEXT, " +
                "ocr TEXT, has_ocr INTEGER NOT NULL DEFAULT 0, emb BLOB)",
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS hidden(id INTEGER PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("CREATE TABLE IF NOT EXISTS hidden(id INTEGER PRIMARY KEY)")
    }

    fun hiddenIds(): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery("SELECT id FROM hidden", null).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    fun hide(ids: Collection<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.execSQL("INSERT OR IGNORE INTO hidden(id) VALUES(?)", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun unhide(ids: Collection<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.delete("hidden", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun unhideAll() {
        writableDatabase.delete("hidden", null, null)
    }

    fun upsert(id: Long, mtime: Long, name: String, ocr: String?, hasOcr: Boolean, emb: FloatArray?) {
        val st = writableDatabase.compileStatement(
            "INSERT OR REPLACE INTO images(id, mtime, name, ocr, has_ocr, emb) VALUES(?,?,?,?,?,?)",
        )
        st.bindLong(1, id)
        st.bindLong(2, mtime)
        st.bindString(3, name)
        if (ocr == null) st.bindNull(4) else st.bindString(4, ocr)
        st.bindLong(5, if (hasOcr) 1 else 0)
        if (emb == null) st.bindNull(6) else st.bindBlob(6, floatsToBytes(emb))
        st.executeInsert()
    }

    fun statuses(): Map<Long, IndexStatus> {
        val out = HashMap<Long, IndexStatus>()
        readableDatabase.rawQuery("SELECT id, mtime, has_ocr, emb IS NOT NULL FROM images", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = IndexStatus(c.getLong(1), c.getInt(2) == 1, c.getInt(3) == 1)
        }
        return out
    }

    /** Counts for the status panel: total rows, rows with an embedding, rows with recognized text. */
    fun stats(): Triple<Int, Int, Int> {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), SUM(emb IS NOT NULL), SUM(ocr IS NOT NULL AND length(ocr) > 0) FROM images", null,
        ).use { c ->
            c.moveToFirst()
            return Triple(c.getInt(0), c.getInt(1), c.getInt(2))
        }
    }

    class Row(val ocr: String?, val hasOcr: Boolean, val emb: FloatArray?)

    fun getRow(id: Long): Row? {
        readableDatabase.rawQuery("SELECT ocr, has_ocr, emb FROM images WHERE id=?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return Row(
                if (c.isNull(0)) null else c.getString(0),
                c.getInt(1) == 1,
                if (c.isNull(2)) null else bytesToFloats(c.getBlob(2)),
            )
        }
    }

    fun forEachRow(cb: (id: Long, ocr: String?, emb: FloatArray?) -> Unit) {
        readableDatabase.rawQuery("SELECT id, ocr, emb FROM images", null).use { c ->
            while (c.moveToNext()) {
                cb(c.getLong(0), if (c.isNull(1)) null else c.getString(1), if (c.isNull(2)) null else bytesToFloats(c.getBlob(2)))
            }
        }
    }

    fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.delete("images", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        fun floatsToBytes(f: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(f)
            return bb.array()
        }

        fun bytesToFloats(b: ByteArray): FloatArray {
            val out = FloatArray(b.size / 4)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }
    }
}
