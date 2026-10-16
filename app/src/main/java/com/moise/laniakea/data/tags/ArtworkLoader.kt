package com.moise.laniakea.data.tags

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.moise.laniakea.data.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Album art pipeline: embedded picture first, then (optionally) cover.jpg / folder.png etc. found in the
 * song's folder via storage access. Decoded bitmaps are kept in a small LRU cache.
 */
object ArtworkLoader {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtMost(48 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val folderNames = listOf("cover", "folder", "front", "album", "albumart", "artwork", "thumb")
    private val folderExts = listOf("jpg", "jpeg", "png", "webp")

    fun invalidate(song: SongEntity) {
        cache.snapshot().keys.filter { it.startsWith("${song.id}:") }.forEach { cache.remove(it) }
    }

    suspend fun load(context: Context, song: SongEntity, maxSize: Int = 512, useFolderArt: Boolean = true): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "${song.id}:$maxSize:$useFolderArt"
            cache.get(key)?.let { return@withContext it }
            val bytes = embedded(context, song) ?: if (useFolderArt) folderArt(song) else null
            val bmp = bytes?.let { decodeScaled(it, maxSize) }
            if (bmp != null) cache.put(key, bmp)
            bmp
        }

    /** Full-resolution bytes (for the zoomable viewer and the art editor). */
    suspend fun loadRaw(context: Context, song: SongEntity, useFolderArt: Boolean = true): ByteArray? =
        withContext(Dispatchers.IO) { embedded(context, song) ?: if (useFolderArt) folderArt(song) else null }

    private fun embedded(context: Context, song: SongEntity): ByteArray? {
        // MediaMetadataRetriever is only AutoCloseable from API 29 and minSdk is 26, so release() manually.
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(song.uri))
            r.embeddedPicture?.let { return it }
        } catch (_: Exception) {
        } finally {
            runCatching { r.release() }
        }
        return TagIO.readCover(context, Uri.parse(song.uri))
    }

    private fun folderArt(song: SongEntity): ByteArray? {
        val dir = File(song.folder)
        if (!dir.isDirectory) return null
        val files = dir.listFiles() ?: return null
        for (name in folderNames) for (ext in folderExts) {
            files.firstOrNull { it.isFile && it.name.equals("$name.$ext", true) }?.let { return runCatching { it.readBytes() }.getOrNull() }
        }
        return null
    }

    private fun decodeScaled(bytes: ByteArray, maxSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxSize && opts.outHeight / (sample * 2) >= maxSize) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Re-encode user-chosen image (from picker) to a sane embedded size. */
    fun prepareForEmbedding(source: Bitmap, maxEdge: Int = 1000): Pair<ByteArray, String> {
        val scale = maxEdge.toFloat() / maxOf(source.width, source.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true) else source
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        return out.toByteArray() to "image/jpeg"
    }
}
