package dev.dietapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.dietapp.data.local.LegacyNotes
import javax.inject.Inject

@HiltAndroidApp
class DietApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** Only needs to exist: it clears the outdated "read without the model" lines at start. */
    @Inject lateinit var legacyNotes: LegacyNotes

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
