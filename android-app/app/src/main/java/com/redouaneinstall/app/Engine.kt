package com.redouaneinstall.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** غلاف بسيط حول محرك yt-dlp (نفس المحرك ديال أشهر برامج التحميل مفتوحة المصدر) */
object Engine {

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _status = MutableStateFlow("جاري تجهيز محرك التحميل (أول مرة كيجبد شوية)...")
    val status: StateFlow<String> = _status

    @Volatile
    var ffmpegOk: Boolean = false
        private set

    data class VideoMeta(
        val title: String,
        val thumb: String?,
        val durationSec: Long,
        val site: String,
        val url: String
    )

    suspend fun ensureInit(context: Context) = withContext(Dispatchers.IO) {
        if (_ready.value) return@withContext
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
            runCatching {
                FFmpeg.getInstance().init(context.applicationContext)
                ffmpegOk = true
            }
            _ready.value = true
            _status.value = "المحرك جاهز — ألصق الرابط"
        } catch (t: Throwable) {
            _status.value = "مشكل في تجهيز المحرك: ${t.message ?: "غير معروف"}"
        }
    }

    /** تحديث yt-dlp لآخر نسخة (مهم بزاف ليوتيوب) — في الخلفية */
    fun updateAsync(context: Context) {
        Thread {
            runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(
                    context.applicationContext,
                    YoutubeDL.UpdateChannel.NIGHTLY
                )
                _status.value = "المحرك محدّث وجاهز"
            }
        }.apply { isDaemon = true }.start()
    }

    /** جلب معلومات الفيديو (العنوان، الصورة، المدة، الموقع) */
    fun extractMeta(url: String): VideoMeta {
        val req = YoutubeDLRequest(url)
        req.addOption("-j")
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        val out = YoutubeDL.getInstance().execute(req).out
        val line = out.lineSequence().firstOrNull { it.trimStart().startsWith("{") }
            ?: throw IllegalStateException("ما قدرناش نجيبو معلومات الفيديو")
        val json = JSONObject(line)
        val title = json.optString("title").ifBlank { "فيديو بلا عنوان" }
        var thumb: String? = json.optString("thumbnail").ifBlank { null }
        val thumbs = json.optJSONArray("thumbnails")
        if (thumb == null && thumbs != null && thumbs.length() > 0) {
            thumb = thumbs.getJSONObject(thumbs.length() - 1).optString("url").ifBlank { null }
        }
        val dur = json.optDouble("duration", 0.0).toLong()
        val site = json.optString("extractor_key")
            .ifBlank { json.optString("extractor", "") }
            .ifBlank { hostOf(url) }
        return VideoMeta(title, thumb, dur, site, url)
    }

    fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host.orEmpty().removePrefix("www.").removePrefix("m.")
    }.getOrDefault("")
}
