package dev.dietapp.ui.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.dietapp.R
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The short sound of a food that has been recorded: a dull knock. */
interface AddedSound {
    fun play()

    object None : AddedSound {
        override fun play() = Unit
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SoundModule {
    @Binds abstract fun addedSound(impl: SoundPoolPlayer): AddedSound
}

/** Screens that are tested without the real sound get none. */
val LocalAddedSound = staticCompositionLocalOf<AddedSound> { AddedSound.None }

/** Whether the app makes the sound at all: on until the person turns it off. */
@Singleton
class SoundStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("sound", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(ENABLED, on) }
        _enabled.value = on
    }

    private companion object {
        const val ENABLED = "enabled"
    }
}

/**
 * Plays `res/raw/added.wav` (one short, dull knock, synthesized by tools/make_added_sound.py) the way a messenger plays
 * its sound for a message: as an instant-message notification, so it
 * follows the phone's notification volume and goes quiet in silent mode and in Do Not Disturb. The sound is loaded at the start of
 * the app, so that the first one comes without a wait.
 */
@Singleton
class SoundPoolPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SoundStore,
) : AddedSound {
    private var pool: SoundPool? = null
    private var soundId = 0
    private var loaded = false
    private var waiting = false

    /** Loads the sound; call once the app starts. */
    @Synchronized
    fun warmUp() {
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build()
        p.setOnLoadCompleteListener { sp, id, status ->
            synchronized(this) {
                loaded = status == 0
                if (loaded && waiting) sp.play(id, 1f, 1f, 1, 0, 1f)
                waiting = false
            }
        }
        soundId = p.load(context, R.raw.added, 1)
        pool = p
    }

    @Synchronized
    override fun play() {
        if (!store.enabled.value) return
        warmUp()
        if (loaded) pool?.play(soundId, 1f, 1f, 1, 0, 1f) else waiting = true
    }
}
