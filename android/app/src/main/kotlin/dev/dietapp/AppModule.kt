package dev.dietapp

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Set at build time: ./gradlew assembleRelease -PapiBaseUrl=https://your.server */
    @Provides @Named("apiBaseUrl")
    fun apiBaseUrl(): String = BuildConfig.API_BASE_URL

    /** False in a release build made without -PapiBaseUrl: there is no server to sign in to. */
    @Provides @Named("serverEnabled")
    fun serverEnabled(): Boolean = BuildConfig.SERVER_ENABLED

    /** DeepSeek, unless the build points the model at a stand-in for testing (-PmodelBaseUrl). */
    @Provides @Named("modelBaseUrl")
    fun modelBaseUrl(): String = BuildConfig.MODEL_BASE_URL
}
