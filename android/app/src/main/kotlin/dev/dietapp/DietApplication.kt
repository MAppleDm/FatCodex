package dev.dietapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.dietapp.data.local.LocalPreloader
import javax.inject.Inject

@HiltAndroidApp
class DietApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** Only needs to exist: it loads the food catalog in the background when the app runs without a server. */
    @Inject lateinit var preloader: LocalPreloader

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
