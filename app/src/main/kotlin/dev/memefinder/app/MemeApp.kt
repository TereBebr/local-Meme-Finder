package dev.memefinder.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class MemeApp : Application() {
    val db: Db by lazy { Db(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val channel = NotificationChannel(CHANNEL_ID, "Индексация галереи", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "indexing"
        lateinit var instance: MemeApp
            private set
    }
}

enum class IndexMode(val title: String, val hint: String) {
    BOTH("Смысл + текст", "Находит и по объектам, и по надписям. Дольше всего."),
    CLIP_ONLY("Только смысл", "Котики, хомяки, еда… Намного быстрее."),
    OCR_ONLY("Только текст", "Только надписи на картинках."),
}

/** User choices that survive restarts: which gallery folders to index and what to extract. */
object Settings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Selected gallery folder ids; null means "all folders". Stored as a plain comma-separated string. */
    fun buckets(ctx: Context): Set<String>? {
        val raw = prefs(ctx).getString("buckets_csv", null) ?: return null
        return raw.split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setBuckets(ctx: Context, value: Set<String>?) {
        val e = prefs(ctx).edit()
        if (value == null) e.remove("buckets_csv") else e.putString("buckets_csv", value.joinToString(","))
        e.commit()
    }

    fun mode(ctx: Context): IndexMode =
        runCatching { IndexMode.valueOf(prefs(ctx).getString("mode", IndexMode.BOTH.name)!!) }.getOrDefault(IndexMode.BOTH)

    fun setMode(ctx: Context, m: IndexMode) {
        prefs(ctx).edit().putString("mode", m.name).apply()
    }
}
