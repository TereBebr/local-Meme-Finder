package dev.memefinder.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of the running indexing job, observed by the UI. */
object IndexState {
    val running = MutableStateFlow(false)
    val done = MutableStateFlow(0)
    val total = MutableStateFlow(0)
    val etaSeconds = MutableStateFlow(-1)
    val message = MutableStateFlow("")
    val errors = MutableStateFlow(0)
    val lastError = MutableStateFlow("")

    fun report(where: String, e: Throwable) {
        errors.value++
        lastError.value = "$where: ${e.javaClass.simpleName}: ${e.message}".take(300)
        Log.w("MemeFinder", where, e)
    }
}

object Indexer {
    private const val TAG = "Indexer"

    suspend fun run(ctx: Context, onProgress: (done: Int, total: Int) -> Unit) = withContext(Dispatchers.Default) {
        val db = MemeApp.instance.db
        val mode = Settings.mode(ctx)
        val doOcr = mode != IndexMode.CLIP_ONLY
        val doClip = mode != IndexMode.OCR_ONLY
        // run at foreground priority; ONNX Runtime's worker threads inherit it when the sessions are created below
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
        IndexState.running.value = true
        IndexState.errors.value = 0
        IndexState.lastError.value = ""
        IndexState.done.value = 0
        IndexState.total.value = 0
        IndexState.etaSeconds.value = -1
        IndexState.message.value = "Читаю список картинок…"
        try {
            val all = Media.listImages(ctx)
            val present = all.map { it.id }.toHashSet()
            val status = db.statuses()
            db.delete(status.keys.filter { it !in present })

            val selected = Settings.buckets(ctx)
            if (selected != null && selected.isEmpty()) {
                IndexState.message.value = "Не выбрано ни одной папки"
                return@withContext
            }
            val wanted = if (selected == null) all else all.filter { it.bucketId.toString() in selected }
            val todo = wanted.filter {
                val st = status[it.id]
                st == null || st.mtime != it.mtime || (doClip && !st.hasEmb) || (doOcr && !st.hasOcr)
            }
            IndexState.total.value = todo.size
            if (todo.isEmpty()) {
                IndexState.message.value = "Нечего индексировать: выбранные папки уже обработаны"
                Library.load(db)
                return@withContext
            }

            IndexState.message.value = "Загружаю модели…"
            val ocr = if (doOcr) Engines.ocr(ctx) else null
            val clip = if (doClip) Engines.image(ctx) else null
            IndexState.message.value = "Индексирую…"

            val recent = ArrayDeque<Long>()
            for ((n, item) in todo.withIndex()) {
                ensureActive()
                val t0 = SystemClock.elapsedRealtime()
                try {
                    val img = Media.loadRgb(ctx, item.id)
                    if (img != null) {
                        val st = status[item.id]
                        val prev = if (st != null && st.mtime == item.mtime) db.getRow(item.id) else null
                        var text = prev?.ocr
                        var hasOcr = prev?.hasOcr ?: false
                        var emb = prev?.emb
                        if (ocr != null && !hasOcr) {
                            text = ocr.run(img).joinToString("\n")
                            hasOcr = true
                        }
                        if (clip != null && emb == null) emb = clip.encode(img)
                        db.upsert(item.id, item.mtime, item.name, text, hasOcr, emb)
                        Library.put(item.id, text, emb)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    IndexState.report("индексация ${item.name}", e)
                }
                recent.addLast(SystemClock.elapsedRealtime() - t0)
                if (recent.size > 20) recent.removeFirst()
                val done = n + 1
                IndexState.done.value = done
                IndexState.etaSeconds.value = (recent.average() * (todo.size - done) / 1000.0).toInt()
                if (done % 25 == 0) Library.version.value++
                onProgress(done, todo.size)
            }
            Library.version.value++
            IndexState.message.value = "Готово"
        } finally {
            IndexState.running.value = false
        }
    }
}

class IndexService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val running = job?.isActive == true
            job?.cancel()
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification(0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // keep the CPU awake while the screen is off, otherwise Android pauses the work
        val wl = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "memefinder:indexing")
        wl.acquire(12 * 60 * 60 * 1000L)
        wakeLock = wl
        job = scope.launch {
            try {
                Indexer.run(applicationContext) { done, total ->
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(done, total))
                }
            } catch (e: CancellationException) {
                IndexState.message.value = "Остановлено"
            } catch (e: Throwable) {
                IndexState.report("индексация", e)
                IndexState.message.value = "Ошибка: ${e.message}"
            } finally {
                Engines.releaseIndexing()
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(done: Int, total: Int): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, IndexService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, MemeApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Индексация галереи")
            .setContentText(if (total > 0) "$done из $total" else "Подготовка…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "dev.memefinder.app.STOP"
        private const val NOTIFICATION_ID = 1

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, IndexService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, IndexService::class.java).setAction(ACTION_STOP))
        }
    }
}
