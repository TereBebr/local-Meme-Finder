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

    /** null means "all folders". */
    fun buckets(ctx: Context): Set<String>? = prefs(ctx).getStringSet("buckets", null)

    fun setBuckets(ctx: Context, value: Set<String>?) {
        val e = prefs(ctx).edit()
        if (value == null) e.remove("buckets") else e.putStringSet("buckets", value)
        e.apply()
    }

    fun mode(ctx: Context): IndexMode =
        runCatching { IndexMode.valueOf(prefs(ctx).getString("mode", IndexMode.BOTH.name)!!) }.getOrDefault(IndexMode.BOTH)

    fun setMode(ctx: Context, m: IndexMode) {
        prefs(ctx).edit().putString("mode", m.name).apply()
    }
}
