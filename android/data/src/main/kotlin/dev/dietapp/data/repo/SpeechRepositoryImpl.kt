package dev.dietapp.data.repo

import dev.dietapp.data.domain.Lang.t
import dev.dietapp.data.local.ModeStore
import dev.dietapp.data.net.AppError
import dev.dietapp.data.net.DietApi
import dev.dietapp.data.net.apiCall
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

val LOCAL_SPEECH_MESSAGE get() = t("Без сервера речь распознаётся только на самом телефоне. Включи офлайн-распознавание русского языка в настройках телефона.",
    "Without a server, speech is recognised on the phone only. Turn on offline speech recognition in the phone's settings, or type.")

@Singleton
class SpeechRepositoryImpl @Inject constructor(
    private val api: DietApi,
    private val json: Json,
    private val mode: ModeStore,
) : SpeechRepository {
    override suspend fun transcribe(audio: ByteArray, filename: String, mime: String, language: String?): Result<String> = guarded {
        if (mode.isLocal) throw AppError(LOCAL_SPEECH_MESSAGE, "local_mode")
        val part = MultipartBody.Part.createFormData("audio", filename, audio.toRequestBody(mime.toMediaType()))
        val lang = language?.takeIf { it.matches(Regex("[a-z]{2,3}")) }?.toRequestBody("text/plain".toMediaType())
        apiCall(json) { api.stt(part, lang) }.text.trim()
    }
}
