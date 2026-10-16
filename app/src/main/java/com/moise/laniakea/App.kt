package com.moise.laniakea

import android.app.Application
import com.moise.laniakea.data.SettingsRepository
import com.moise.laniakea.data.db.AppDatabase
import com.moise.laniakea.data.library.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Tiny manual DI container; keeps the APK small (no Hilt/Dagger). */
class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.build(this) }
    val settings by lazy { SettingsRepository(this, appScope) }
    val library by lazy { LibraryRepository(this, database, settings, appScope) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashScreen()
    }

    /** Instead of closing silently, show the error on screen so it can be reported. */
    private fun installCrashScreen() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = buildString {
                    append("Thread: ").append(thread.name).append('\n')
                    append("Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT)
                        .append("), ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append("\n\n")
                    append(android.util.Log.getStackTraceString(error))
                }.take(12_000)
                startActivity(
                    android.content.Intent(this, CrashActivity::class.java)
                        .putExtra(CrashActivity.EXTRA_TRACE, trace)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                Thread.sleep(400)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
