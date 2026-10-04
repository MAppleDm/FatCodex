package dev.dietapp.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dietapp.data.di.AppScope
import dev.dietapp.data.local.food.FoodCatalog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The USDA catalog bundled in the APK. Parsed once, on first use (about a tenth of a second).
 *
 * The file is `.gzip`, not `.gz`, on purpose: the Android Gradle Plugin silently decompresses assets that end in
 * `.gz` and drops the extension, after which opening them by their original name fails on the device.
 * `BundledAssetsTest` opens every asset through a real AssetManager so this cannot come back unnoticed.
 */
@Singleton
class AssetCatalogSource @Inject constructor(@ApplicationContext private val context: Context) : CatalogSource {
    private val catalog: FoodCatalog by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        context.assets.open("foods_sr_legacy.tsv.gzip").use { FoodCatalog.fromGzippedTsv(it) }
    }

    override fun get(): FoodCatalog = catalog
}

/**
 * Loads the catalog in the background as soon as the app is in local mode, so the first message is not the one
 * that pays for it.
 */
@Singleton
class LocalPreloader @Inject constructor(mode: ModeStore, catalog: AssetCatalogSource, @AppScope scope: CoroutineScope) {
    init {
        scope.launch(Dispatchers.Default) {
            mode.mode.collect { if (it == AppMode.Local) catalog.get() }
        }
    }
}
