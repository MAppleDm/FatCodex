package dev.dietapp.ui.sound

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** The "Звук" row of the about page. */
@HiltViewModel
class SoundViewModel @Inject constructor(private val store: SoundStore, private val sound: AddedSound) : ViewModel() {
    val enabled: StateFlow<Boolean> = store.enabled

    /** Turned on, it plays once, so that the person hears what they chose. */
    fun onToggle() {
        val on = !store.enabled.value
        store.setEnabled(on)
        if (on) sound.play()
    }
}
