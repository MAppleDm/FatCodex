package dev.dietapp.voice

import dev.dietapp.Texts
import dev.dietapp.data.repo.LOCAL_SPEECH_MESSAGE

sealed interface VoiceDecision {
    /** On-device recognition cannot do it: record audio and let our server transcribe it. */
    data object UseServer : VoiceDecision

    /** Nothing to fall back to; tell the user and let them try again. */
    data class Tell(val message: String) : VoiceDecision

    data object AskPermission : VoiceDecision
}

/**
 * What to do when Android's SpeechRecognizer reports an error. The numbers are the
 * `SpeechRecognizer.ERROR_*` constants, written out so the policy can be unit tested on the JVM.
 */
object VoiceFallbackPolicy {
    private const val NETWORK_TIMEOUT = 1
    private const val NETWORK = 2
    private const val AUDIO = 3
    private const val SPEECH_TIMEOUT = 6
    private const val NO_MATCH = 7
    private const val INSUFFICIENT_PERMISSIONS = 9

    /** [serverAvailable] is false when the app runs without a server: then there is nothing to fall back to. */
    fun decide(error: Int, serverAvailable: Boolean = true): VoiceDecision = when (error) {
        SPEECH_TIMEOUT, NO_MATCH -> VoiceDecision.Tell(Texts.VOICE_NO_MATCH)
        INSUFFICIENT_PERMISSIONS -> VoiceDecision.AskPermission
        AUDIO -> VoiceDecision.Tell(Texts.VOICE_AUDIO)
        // the recogniser itself needed the network and could not get it; our server would need it too
        NETWORK, NETWORK_TIMEOUT -> VoiceDecision.Tell(if (serverAvailable) Texts.VOICE_NO_NETWORK else LOCAL_SPEECH_MESSAGE)
        // server / client / busy / too many requests / disconnected / language not supported or unavailable /
        // cannot check support, and anything new: the on-device path is not usable right now
        else -> if (serverAvailable) VoiceDecision.UseServer else VoiceDecision.Tell(LOCAL_SPEECH_MESSAGE)
    }
}
