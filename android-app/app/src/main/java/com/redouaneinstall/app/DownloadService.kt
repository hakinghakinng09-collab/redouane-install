package com.redouaneinstall.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/** خدمة التحميل في الخلفية (كتكمل حتى إذا خرج المستخدم من التطبيق) */
class DownloadService : Service() {

    data class Progress(val title: String, val percent: Float, val etaSec: Long)

    sealed class Event {
        data class Done(val name: String) : Event()
        data class Failed(val msg: String) : Event()
        data class Info(val msg: String) : Event()
    }

    data class Spec(
        val url: String,
        val title: String,
        val quality: String,   // best | p1080 | p720 | p480 | mp3
        val processId: String
    ) {
        val isAudio: Boolean get() = quality == "mp3"
    }

    companion object {
        private const val ACTION_START = "action.START"
        private const val ACTION_CANCEL = "action.CANCEL"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 41

        val live = MutableStateFlow<Progress?>(null)
        val events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
        private val queue = Channel<Spec>(Channel.UNLIMITED)

        fun enqueue(ctx: Context, spec: Spec) {
            val i = Intent(ctx, DownloadService::class.java).setAction(ACTION_START)
                .putExtra("url", spec.url)
                .putExtra("title", spec.title)
                .putExtra("quality", spec.quality)
                .putExtra("pid", spec.processId)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processor: Job? = null
    private var currentPid: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notifManager: NotificationManager
    private var lastNotifAt = 0L

    override fun onCreate() {
        super.onCreate()
        notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "التحميلات", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val spec = Spec(
                    url = intent.getStringExtra("url") ?: return START_NOT_STICKY,
                    title = intent.getStringExtra("title") ?: "فيديو",
                    quality = intent.getStringExtra("quality") ?: "best",
                    processId = intent.getStringExtra("pid") ?: UUID.randomUUID().toString()
                )
                val busy = live.value != null || processor?.isActive == true
                queue.trySend(spec)
                if (busy) events.tryEmit(Event.Info("تزاد للائحة التحميلات: ${spec.title.take(40)}"))
                updateNotification("تجهيز التحميل…", -1)
                startProcessor()
            }

            ACTION_CANCEL -> {
                currentPid?.let { YoutubeDL.getInstance().destroyProcessById(it) }
            }
        }
        return START_STICKY
    }

    private fun startProcessor() {
        if (processor?.isActive == true) return
        processor = scope.launch {
            acquireWakeLock()
            try {
                while (true) {
                    val spec = withTimeoutOrNull(400) { queue.receive() } ?: break
                    runJob(spec)
                }
            } finally {
                live.value = null
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun runJob(spec: Spec) {
        currentPid = spec.processId
        live.value = Progress(spec.title, 0f, -1)
        val jobDir = File(
            applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir,
            "tmp/${spec.processId}"
        ).apply { mkdirs() }
        val template = File(jobDir, "%(title).48B.%(ext)s").absolutePath
        try {
            // أول محاولة بالجودة المختارة، وإلا ما خدمتش (بلا ffmpeg مثلاً) نجربو ملف واحد مبسط
            val f = runCatching { executeDl(spec, template, fallback = false) }
                .recoverCatching { executeDl(spec, template, fallback = true) }
                .getOrThrow()
            if (f != null && f.exists()) {
                val saved = MediaStoreHelper.saveToDownloads(applicationContext, f, spec.isAudio)
                if (saved) {
                    events.tryEmit(Event.Done(f.name))
                } else {
                    events.tryEmit(Event.Failed("التحميل كمل ولكن ما قدرناش نسجلو الملف. تأكد من المساحة والصلاحيات."))
                }
            } else {
                events.tryEmit(Event.Failed("ما نقدر يهز الفيديو. جرب جودة أخرى."))
            }
        } catch (c: YoutubeDL.CanceledException) {
            events.tryEmit(Event.Info("تم إلغاء التحميل"))
        } catch (e: Throwable) {
            events.tryEmit(Event.Failed(friendly(e)))
        } finally {
            jobDir.deleteRecursively()
            currentPid = null
            live.value = null
        }
    }

    private fun executeDl(spec: Spec, template: String, fallback: Boolean): File? {
        val req = YoutubeDLRequest(spec.url)
        req.addOption("-o", template)
        req.addOption("--newline")
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--retries", "3")
        when {
            spec.isAudio -> {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "0")
            }

            fallback -> req.addOption("-f", singleStream(spec.quality))
            spec.quality == "best" -> {
                req.addOption("-f", "bv*+ba/b")
                req.addOption("--merge-output-format", "mp4")
            }

            else -> {
                val h = spec.quality.removePrefix("p")
                req.addOption("-f", "bv*[height<=$h]+ba/b[height<=$h]/b[height<=$h]")
                req.addOption("--merge-output-format", "mp4")
            }
        }
        YoutubeDL.getInstance().execute(req, spec.processId) { p, eta, _ ->
            if (p >= 0f) {
                live.value = Progress(spec.title, p.coerceIn(0f, 100f), eta)
                updateNotification("تحميل: ${spec.title.take(45)}", p.toInt())
            }
        }
        val dir = File(template).parentFile ?: return null
        return dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun singleStream(quality: String): String = when (quality) {
        "p1080" -> "b[height<=1080]"
        "p720" -> "b[height<=720]"
        "p480" -> "b[height<=480]"
        else -> "b"
    }

    private fun friendly(e: Throwable): String {
        val m = (e.message ?: "").lowercase()
        return when {
            "sign in to confirm" in m || "not a bot" in m ->
                "يوتيوب بلوكا الطلب مؤقتاً (تحقق من الروبوت). المحرك كيتحدث تلقائياً — جرب مرة أخرى من بعد شوية."

            "unsupported url" in m -> "هاد الرابط ماشي مدعوم."
            "unable to" in m || "network" in m || "connection" in m ->
                "مشكل في الاتصال بالإنترنت. تثبت من الشبكة وجرب مرة أخرى."

            "no space" in m || "enospc" in m -> "ما كايناش مساحة كافية في الهاتف."
            "private" in m || "login" in m -> "هاد المحتوى خاص وخاصو تسجيل الدخول."
            else -> "وقع خطأ في التحميل: ${e.message?.take(120) ?: "غير معروف"}"
        }
    }

    // ---------- الإشعار والخلفية ----------

    private fun updateNotification(text: String, percent: Int) {
        val now = SystemClock.elapsedRealtime()
        if (percent >= 0 && percent != 100 && now - lastNotifAt < 500) return
        lastNotifAt = now
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(percent in 0..99)
            .setProgress(100, if (percent < 0) 0 else percent, percent < 0)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (_: Throwable) {
            // بعض هواتف Android كتكون صارمة مع أيقونة الإشعار؛ نستعمل أيقونة النظام كحل احتياطي.
            val safe = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(percent in 0..99)
                .setProgress(100, if (percent < 0) 0 else percent, percent < 0)
                .build()
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, safe, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIF_ID, safe)
                }
            }.onFailure {
                events.tryEmit(Event.Failed("ما قدرش يبدا إشعار التحميل. فعل الإشعارات وجرب مرة أخرى."))
                stopSelf()
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "redouane:downloads").apply {
            setReferenceCounted(false)
            acquire(120 * 60 * 1000L) // ساعتين كحد أقصى
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    override fun onDestroy() {
        processor?.cancel()
        scope.coroutineContext[Job]?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
