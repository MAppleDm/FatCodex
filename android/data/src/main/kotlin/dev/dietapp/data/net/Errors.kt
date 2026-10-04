package dev.dietapp.data.net

import dev.dietapp.data.domain.Lang.t
import java.io.IOException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.Response

/** The server answered with an error. [message] is the Russian text from the gateway, safe to show. */
class ApiException(val status: Int, val code: String?, message: String) : Exception(message) {
    val isUnauthorized get() = status == 401

    /** Worth trying again later (server busy or down) as opposed to "your request is wrong". */
    val isRetryable get() = status >= 500 || status == 429 || status == 408
}

/** No answer at all: offline, DNS, timeout, refused connection. */
class NetworkUnavailableException(cause: Throwable) : IOException("network unavailable", cause)

/** What the UI shows and what the repositories hand to view models. */
class AppError(message: String, val code: String? = null, val retryable: Boolean = false) : Exception(message)

val OFFLINE_MESSAGE get() = t("Нет связи с сервером.", "No connection to the server.")

fun Throwable.toAppError(): AppError = when (this) {
    is AppError -> this
    is ApiException -> AppError(message ?: t("Ошибка сервера.", "Server error."), code, isRetryable)
    is NetworkUnavailableException -> AppError(OFFLINE_MESSAGE, "offline", retryable = true)
    else -> AppError(t("Что-то пошло не так.", "Something went wrong."), "unknown")
}

/**
 * Runs a Retrofit call and turns every failure into [ApiException] or [NetworkUnavailableException].
 * Coroutine cancellation passes through untouched.
 */
suspend fun <T> apiCall(json: Json, block: suspend () -> T): T = try {
    block()
} catch (e: HttpException) {
    throw e.toApiException(json)
} catch (e: IOException) {
    throw NetworkUnavailableException(e)
}

/** For endpoints declared as `Response<Unit>` (204 No Content). 404 is passed to [okStatuses] when it is fine. */
suspend fun apiCallUnit(json: Json, okStatuses: Set<Int> = emptySet(), block: suspend () -> Response<Unit>) {
    val response = try {
        block()
    } catch (e: IOException) {
        throw NetworkUnavailableException(e)
    }
    if (!response.isSuccessful && response.code() !in okStatuses) throw parseError(json, response.code(), response.errorBody()?.string())
}

private fun HttpException.toApiException(json: Json): ApiException =
    parseError(json, code(), response()?.errorBody()?.string())

internal fun parseError(json: Json, status: Int, body: String?): ApiException {
    val envelope = body?.let { runCatching { json.decodeFromString<ErrorEnvelope>(it) }.getOrNull() }
    return ApiException(status, envelope?.error?.code, envelope?.error?.message ?: fallbackMessage(status))
}

private fun fallbackMessage(status: Int) = when {
    status == 401 -> t("Нужно войти заново.", "Please sign in again.")
    status >= 500 -> t("Сервер сейчас недоступен. Попробуй позже.", "The server is unavailable. Try again later.")
    else -> t("Запрос не удался.", "The request failed.")
}
