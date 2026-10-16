package com.moise.laniakea.data.tags

import android.content.Context
import android.net.Uri
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib

/**
 * Thin wrapper around TagLib (via com.kyant.taglib) so the rest of the app never touches
 * the native API directly. Multi-valued fields (e.g. two artists) are real multi-value
 * properties, which is what "dual-artist" support means at the file level.
 *
 * NOTE: verify method names against the TagLib wrapper version pinned in libs.versions.toml.
 */
object TagIO {

    data class Tags(
        val title: String = "",
        val artists: List<String> = emptyList(),
        val albumArtists: List<String> = emptyList(),
        val album: String = "",
        val genres: List<String> = emptyList(),
        val year: Int = 0,
        val track: Int = 0,
        val disc: Int = 0,
        val composer: String = "",
        val comment: String = "",
        val lyrics: String = "",
    )

    private const val R = "r"
    private const val RW = "rw"

    fun read(context: Context, uri: Uri): Tags? = runCatching {
        context.contentResolver.openFileDescriptor(uri, R)?.use { pfd ->
            val fd = pfd.dup().detachFd()
            val md = TagLib.getMetadata(fd, readPictures = false) ?: return@use null
            val p = md.propertyMap
            fun one(k: String) = p[k]?.firstOrNull().orEmpty()
            fun many(k: String) = p[k]?.toList().orEmpty()
            Tags(
                title = one("TITLE"),
                artists = many("ARTIST"),
                albumArtists = many("ALBUMARTIST"),
                album = one("ALBUM"),
                genres = many("GENRE"),
                year = one("DATE").take(4).toIntOrNull() ?: 0,
                track = one("TRACKNUMBER").substringBefore('/').toIntOrNull() ?: 0,
                disc = one("DISCNUMBER").substringBefore('/').toIntOrNull() ?: 0,
                composer = one("COMPOSER"),
                comment = one("COMMENT"),
                lyrics = one("LYRICS"),
            )
        }
    }.getOrNull()

    /** Merge-writes: only keys present in [tags] with non-null selection are changed. */
    fun write(context: Context, uri: Uri, tags: Tags, fields: Set<Field> = Field.values().toSet()): Boolean {
        val pfd = context.contentResolver.openFileDescriptor(uri, RW) ?: return false
        return pfd.use {
            val fd = it.dup().detachFd()
            val current = TagLib.getMetadata(fd, readPictures = false)?.propertyMap ?: HashMap()
            val map = HashMap(current)
            fun set(k: String, values: List<String>) {
                if (values.isEmpty()) map.remove(k) else map[k] = values.toTypedArray()
            }
            if (Field.TITLE in fields) set("TITLE", listOf(tags.title).filter { s -> s.isNotBlank() })
            if (Field.ARTIST in fields) set("ARTIST", tags.artists)
            if (Field.ALBUM_ARTIST in fields) set("ALBUMARTIST", tags.albumArtists)
            if (Field.ALBUM in fields) set("ALBUM", listOf(tags.album).filter { s -> s.isNotBlank() })
            if (Field.GENRE in fields) set("GENRE", tags.genres)
            if (Field.YEAR in fields) set("DATE", if (tags.year > 0) listOf(tags.year.toString()) else emptyList())
            if (Field.TRACK in fields) set("TRACKNUMBER", if (tags.track > 0) listOf(tags.track.toString()) else emptyList())
            if (Field.DISC in fields) set("DISCNUMBER", if (tags.disc > 0) listOf(tags.disc.toString()) else emptyList())
            if (Field.COMPOSER in fields) set("COMPOSER", listOf(tags.composer).filter { s -> s.isNotBlank() })
            if (Field.COMMENT in fields) set("COMMENT", listOf(tags.comment).filter { s -> s.isNotBlank() })
            if (Field.LYRICS in fields) set("LYRICS", listOf(tags.lyrics).filter { s -> s.isNotBlank() })
            TagLib.savePropertyMap(fd, map)
        }
    }

    enum class Field { TITLE, ARTIST, ALBUM_ARTIST, ALBUM, GENRE, YEAR, TRACK, DISC, COMPOSER, COMMENT, LYRICS }

    // ---- Album art ---------------------------------------------------------------------------

    fun readCover(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openFileDescriptor(uri, R)?.use { pfd ->
            TagLib.getFrontCover(pfd.dup().detachFd())?.data
        }
    }.getOrNull()

    fun writeCover(context: Context, uri: Uri, jpegOrPng: ByteArray, mime: String): Boolean {
        val pfd = context.contentResolver.openFileDescriptor(uri, RW) ?: return false
        return pfd.use {
            TagLib.savePictures(
                it.dup().detachFd(),
                arrayOf(Picture(data = jpegOrPng, description = "", pictureType = "Front Cover", mimeType = mime)),
            )
        }
    }

    fun removeCover(context: Context, uri: Uri): Boolean {
        val pfd = context.contentResolver.openFileDescriptor(uri, RW) ?: return false
        return pfd.use { TagLib.savePictures(it.dup().detachFd(), emptyArray()) }
    }
}
