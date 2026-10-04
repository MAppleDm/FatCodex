package dev.dietapp.data.net

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** The gateway REST API (see /contracts/gateway.openapi.json). Paths are relative to the base URL. */
interface DietApi {
    @POST("v1/auth/request-code")
    suspend fun requestCode(@Body body: RequestCodeBody): Response<Unit>

    @POST("v1/auth/verify")
    suspend fun verify(@Body body: VerifyBody): TokenDto

    @GET("v1/me")
    suspend fun me(): MeDto

    @PUT("v1/me/goal")
    suspend fun setGoal(@Body body: GoalBody): MeDto

    @POST("v1/messages")
    suspend fun postMessage(@Body body: MessageBody): MessageResultDto

    @PATCH("v1/entries/{id}")
    suspend fun patchEntry(@Path("id") id: String, @Body body: EntryPatchBody): EntryDto

    @DELETE("v1/entries/{id}")
    suspend fun deleteEntry(@Path("id") id: String): Response<Unit>

    @PUT("v1/weights/{id}")
    suspend fun putWeight(@Path("id") id: String, @Body body: WeightBody): WeightDto

    @DELETE("v1/weights/{id}")
    suspend fun deleteWeight(@Path("id") id: String): Response<Unit>

    @GET("v1/sync")
    suspend fun sync(@Query("since") since: String?): SyncDto

    @Multipart
    @POST("v1/stt")
    suspend fun stt(@Part audio: MultipartBody.Part, @Part("language") language: RequestBody?): SttDto
}
