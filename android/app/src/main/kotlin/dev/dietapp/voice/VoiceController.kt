package dev.dietapp.voice

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.dietapp.Texts
import dev.dietapp.data.repo.LOCAL_SPEECH_MESSAGE
import dev.dietapp.ui.main.MainActions
import dev.dietapp.ui.main.VoicePhase
import java.io.File
import java.util.Locale

/**
 * Voice input, in two tiers:
 *  1. Android's SpeechRecognizer, asking for on-device recognition (EXTRA_PREFER_OFFLINE, and the
 *     dedicated on-device recogniser where the OS has one). Partial results fill the input live.
 *  2. If that is not available or fails in a way [VoiceFallbackPolicy] calls recoverable: record a short
 *     clip and send it to our own `stt` service through the gateway. Without a server ([serverSpeech] false)
 *     there is no second tier: the person is told how to get the on-device recogniser instead.
 *
 * Must be used from the main thread. The mic button toggles: start / stop.
 */
class VoiceController(
    private val context: Context,
    private val actions: MainActions,
    private val serverSpeech: () -> Boolean,
    private val requestPermission: () -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var recorder: MediaRecorder? = null
    private var clip: File? = null

    /** The mic button was pressed while in [phase]. */
    fun toggle(phase: VoicePhase) {
        when (phase) {
            VoicePhase.Idle -> start()
            VoicePhase.Listening -> recognizer?.stopListening()
            VoicePhase.Recording -> stopRecording()
            VoicePhase.Transcribing -> Unit
        }
    }

    fun start() {
        when {
            SpeechRecognizer.isRecognitionAvailable(context) -> startRecognizer()
            serverSpeech() -> startRecording()
            else -> actions.onVoiceProblem(LOCAL_SPEECH_MESSAGE)
        }
    }

    fun destroy() {
        releaseRecognizer()
        releaseRecorder()
    }

    // ---------- tier 1: the OS recogniser ----------

    private fun startRecognizer() {
        releaseRecognizer()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        r.setRecognitionListener(listener)
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            },
        )
        actions.onVoiceListening()
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { actions.onVoiceText(it, final = false) }
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            releaseRecognizer()
            if (text.isNullOrBlank()) actions.onVoiceProblem(Texts.VOICE_NO_MATCH) else actions.onVoiceText(text, final = true)
        }

        override fun onError(error: Int) {
            releaseRecognizer()
            when (val decision = VoiceFallbackPolicy.decide(error, serverSpeech())) {
                VoiceDecision.UseServer -> startRecording()
                is VoiceDecision.Tell -> actions.onVoiceProblem(decision.message)
                VoiceDecision.AskPermission -> {
                    actions.onVoiceStopped()
                    requestPermission()
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun releaseRecognizer() {
        recognizer?.apply {
            setRecognitionListener(null)
            destroy()
        }
        recognizer = null
    }

    // ---------- tier 2: record, let the server transcribe ----------

    @Suppress("DEPRECATION")
    private fun startRecording() {
        releaseRecorder()
        val file = File(context.cacheDir, CLIP_NAME).also { clip = it }
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(MAX_CLIP_MS)
            r.setOutputFile(file.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording()
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            actions.onVoiceProblem(Texts.VOICE_AUDIO)
            return
        }
        recorder = r
        actions.onVoiceRecording()
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        val stopped = try {
            r.stop()
            true
        } catch (e: RuntimeException) { // stopped too quickly: nothing was recorded
            false
        } finally {
            r.release()
        }
        val file = clip
        val bytes = if (stopped) file?.takeIf { it.exists() }?.readBytes() else null
        file?.delete()
        if (bytes == null || bytes.isEmpty()) actions.onVoiceProblem(Texts.VOICE_NO_MATCH) else actions.onVoiceRecorded(bytes, "audio/mp4", CLIP_NAME)
    }

    private fun releaseRecorder() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        clip?.delete()
    }

    private companion object {
        const val CLIP_NAME = "voice.m4a"
        const val MAX_CLIP_MS = 30_000
    }
}
