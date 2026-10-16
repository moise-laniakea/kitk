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
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
