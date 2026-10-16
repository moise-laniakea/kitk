package com.moise.laniakea.data.files

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.moise.laniakea.data.db.SongEntity
import java.io.File

/**
 * Direct file management: move, copy, rename and delete files/folders.
 *
 * On Android 11+ (scoped storage) deleting or rewriting media you don't own needs a user-approved
 * request. Operations that need it return [Result.NeedsApproval] with an IntentSender the UI launches,
 * then re-calls the operation once the user approves.
 */
object FileOps {

    sealed class Result {
        data class Done(val message: String = "") : Result()
        data class NeedsApproval(val sender: IntentSender) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun requestDelete(context: Context, songs: List<SongEntity>): Result {
        if (songs.isEmpty()) return Result.Done()
        val uris = songs.map { Uri.parse(it.uri) }
        return if (Build.VERSION.SDK_INT >= 30) {
            Result.NeedsApproval(MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender)
        } else {
            try {
                uris.forEach { context.contentResolver.delete(it, null, null) }
                Result.Done("Deleted ${uris.size}")
            } catch (e: RecoverableSecurityException) {
                Result.NeedsApproval(e.userAction.actionIntent.intentSender)
            } catch (e: SecurityException) {
                Result.Failed("Permission denied")
            }
        }
    }

    fun requestWrite(context: Context, songs: List<SongEntity>): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30)
            MediaStore.createWriteRequest(context.contentResolver, songs.map { Uri.parse(it.uri) }).intentSender
        else null

    /** Rename a single audio file (keeps extension unless [newName] includes one). */
    fun rename(context: Context, song: SongEntity, newName: String): Result {
        val name = if ('.' in newName) newName else "$newName.${song.extension}"
        return runCatching {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
            val rows = context.contentResolver.update(Uri.parse(song.uri), values, null, null)
            if (rows > 0) Result.Done(name) else Result.Failed("Rename not applied")
        }.getOrElse {
            if (it is RecoverableSecurityException) Result.NeedsApproval(it.userAction.actionIntent.intentSender)
            else Result.Failed(it.message ?: "Rename failed")
        }
    }

    /** Move/copy a file into another folder under shared storage (e.g. "Music/Favourites"). */
    fun moveOrCopy(context: Context, song: SongEntity, targetRelativeDir: String, copy: Boolean): Result {
        return runCatching {
            val relative = targetRelativeDir.trim('/').let { "$it/" }
            if (copy) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, File(song.path).name)
                    put(MediaStore.MediaColumns.MIME_TYPE, song.mimeType.ifBlank { "audio/*" })
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                }
                val dest = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return Result.Failed("Could not create destination")
                context.contentResolver.openInputStream(Uri.parse(song.uri))!!.use { input ->
                    context.contentResolver.openOutputStream(dest)!!.use { out -> input.copyTo(out) }
                }
                Result.Done("Copied to $relative")
            } else {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relative) }
                val rows = context.contentResolver.update(Uri.parse(song.uri), values, null, null)
                if (rows > 0) Result.Done("Moved to $relative") else Result.Failed("Move not applied")
            }
        }.getOrElse {
            if (it is RecoverableSecurityException) Result.NeedsApproval(it.userAction.actionIntent.intentSender)
            else Result.Failed(it.message ?: "Operation failed")
        }
    }

    /**
     * Folder operations act on every indexed song inside it, since folders themselves are not
     * addressable through MediaStore. Returns the songs a folder action would touch.
     */
    fun songsInFolder(all: List<SongEntity>, folderPath: String, recursive: Boolean = true): List<SongEntity> =
        all.filter { if (recursive) it.folder == folderPath || it.folder.startsWith("$folderPath/") else it.folder == folderPath }
}
