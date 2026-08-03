package com.redouaneinstall.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** تسجيل الملفات المحملة في مجلد التحميلات وقراءتها */
object MediaStoreHelper {

    data class Item(
        val name: String,
        val sizeText: String,
        val uri: Uri?,      // Android 10+
        val path: String?,  // Android 9 وأقدم
        val isAudio: Boolean
    )

    fun mimeFor(ext: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.US))
            ?: when (ext.lowercase(Locale.US)) {
                "mp3", "m4a", "ogg", "opus" -> "audio/mpeg"
                "mp4", "mkv", "webm" -> "video/mp4"
                else -> "application/octet-stream"
            }

    fun isAudioFile(ext: String) = mimeFor(ext).startsWith("audio")

    /** ينسخ الملف لمجلد Download/RedouaneInstall اللي كيبان في مدير الملفات */
    fun saveToDownloads(ctx: Context, src: File, _isAudio: Boolean): Boolean {
        val mime = mimeFor(src.extension)
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/RedouaneInstall"
                    )
                    // ما نخليوش المشغل يشوف الملف قبل ما يكمل النسخ.
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                try {
                    val output = resolver.openOutputStream(uri)
                        ?: throw IllegalStateException("ما قدرناش نفتحو ملف التحميل")
                    output.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                    val ready = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, ready, null, null)
                    true
                } catch (_: Throwable) {
                    resolver.delete(uri, null, null)
                    false
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "RedouaneInstall"
                ).apply { mkdirs() }
                val dst = File(dir, src.name)
                src.inputStream().use { inp -> dst.outputStream().use { inp.copyTo(it) } }
                MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), arrayOf(mime), null)
                true
            }
        }.getOrDefault(false)
    }

    suspend fun listDownloads(ctx: Context): List<Item> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) {
            val list = mutableListOf<Item>()
            val proj = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.IS_PENDING
            )
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                proj,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING}=0",
                arrayOf(Environment.DIRECTORY_DOWNLOADS + "/RedouaneInstall/%"),
                "${MediaStore.Downloads.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                while (c.moveToNext() && list.size < 50) {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        c.getLong(idCol)
                    )
                    val mime = c.getString(mimeCol) ?: ""
                    list += Item(
                        name = c.getString(nameCol) ?: "file",
                        sizeText = humanSize(c.getLong(sizeCol)),
                        uri = uri,
                        path = null,
                        isAudio = mime.startsWith("audio")
                    )
                }
            }
            list
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "RedouaneInstall"
            )
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(50)?.map { f ->
                Item(f.name, humanSize(f.length()), null, f.absolutePath, isAudioFile(f.extension))
            } ?: emptyList()
        }
    }

    fun openUriFor(ctx: Context, item: Item): Pair<Uri, String>? {
        val ext = item.name.substringAfterLast('.', "")
        val uri = item.uri ?: item.path?.let { p ->
            FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", File(p))
        } ?: return null
        val storedMime = item.uri?.let { ctx.contentResolver.getType(it) }
        return uri to (storedMime ?: mimeFor(ext))
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toFloat())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toFloat())
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    }
}
