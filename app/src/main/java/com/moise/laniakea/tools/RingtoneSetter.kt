package com.moise.laniakea.tools

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File

object RingtoneSetter {

    enum class Kind(val ringtoneType: Int, val folder: String) {
        RINGTONE(RingtoneManager.TYPE_RINGTONE, Environment.DIRECTORY_RINGTONES),
        NOTIFICATION(RingtoneManager.TYPE_NOTIFICATION, Environment.DIRECTORY_NOTIFICATIONS),
        ALARM(RingtoneManager.TYPE_ALARM, Environment.DIRECTORY_ALARMS),
    }

    fun canWriteSettings(context: Context) = Settings.System.canWrite(context)

    /** Opens the system screen where the user grants "Modify system settings". */
    fun requestWriteSettingsIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Publishes [file] to the system ringtone store. Returns its content Uri. */
    fun publish(context: Context, file: File, title: String, mime: String, kind: Kind): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.TITLE, title)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, kind.folder)
            put(MediaStore.Audio.Media.IS_RINGTONE, kind == Kind.RINGTONE)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, kind == Kind.NOTIFICATION)
            put(MediaStore.Audio.Media.IS_ALARM, kind == Kind.ALARM)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return null
        return uri
    }

    /** Publishes and sets as the default. Caller must hold WRITE_SETTINGS (see [canWriteSettings]). */
    fun setAsDefault(context: Context, file: File, title: String, mime: String, kind: Kind): Boolean {
        if (!canWriteSettings(context)) return false
        val uri = publish(context, file, title, mime, kind) ?: return false
        RingtoneManager.setActualDefaultRingtoneUri(context, kind.ringtoneType, uri)
        return true
    }
}
