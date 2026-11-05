package com.nuvio.tv.core.subtitles

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * Client for the Nan Add-ons timed-cue translation endpoint.
 *
 * The Gemini credential must never be stored in the app. Only the user's
 * Nan Add-ons access token is supplied at call time.
 */
class EmbeddedSubtitleTranslationClient(
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val gson: Gson = Gson(),
) {
    data class Cue(
        @SerializedName("startTimeMs") val startTimeMs: Long,
        @SerializedName("endTimeMs") val endTimeMs: Long,
        val text: String,
    )

    data class Result(
        val cues: List<Cue>,
        val cached: Boolean,
        val translatedCount: Int,
    )

    suspend fun translate(
        endpoint: String,
        token: String,
        cues: List<Cue>,
        targetLanguage: String = "Português do Brasil",
    ): Result {
        val normalizedEndpoint = endpoint.trim().trimEnd('/')
        require(normalizedEndpoint.startsWith("https://", ignoreCase = true)) {
            "O endereço do serviço precisa usar HTTPS."
        }
        require(token.isNotBlank()) { "Configure o token do Nan Add-ons." }
        require(cues.isNotEmpty()) { "Não há falas para traduzir." }
        require(cues.size <= MAX_CUES_PER_REQUEST) {
            "O lote excede o limite de $MAX_CUES_PER_REQUEST falas."
        }
        require(cues.all { it.startTimeMs >= 0L && it.endTimeMs > it.startTimeMs && it.text.isNotBlank() }) {
            "A legenda contém falas ou tempos inválidos."
        }

        val payload = RequestPayload(cues, targetLanguage)
        val body = gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$normalizedEndpoint/api/subtitles/translate-cues")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(body)
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        val result = response.use {
                            val responseText = it.body?.string().orEmpty()
                            if (!it.isSuccessful) {
                                val reason = when (it.code) {
                                    401, 403 -> "Token do Nan Add-ons inválido ou sem permissão."
                                    429 -> "Limite de traduções atingido. Tente novamente em instantes."
                                    in 500..599 -> "O serviço de tradução está temporariamente indisponível."
                                    else -> "Falha ao traduzir legendas (HTTP ${it.code})."
                                }
                                throw IOException(reason)
                            }
                            val parsed = gson.fromJson(responseText, ResponsePayload::class.java)
                                ?: throw IOException("Resposta vazia do serviço de tradução.")
                            if (parsed.cues.size != cues.size || parsed.translatedCount != cues.size) {
                                throw IOException("O serviço retornou uma quantidade diferente de falas.")
                            }
                            parsed.cues.forEachIndexed { index, translated ->
                                val original = cues[index]
                                if (
                                    translated.startTimeMs != original.startTimeMs ||
                                    translated.endTimeMs != original.endTimeMs ||
                                    translated.text.isBlank()
                                ) {
                                    throw IOException("A resposta alterou a sincronização ou contém uma fala vazia.")
                                }
                            }
                            Result(parsed.cues, parsed.cached, parsed.translatedCount)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }

    private data class RequestPayload(
        val cues: List<Cue>,
        val targetLanguage: String,
    )

    private data class ResponsePayload(
        val cues: List<Cue> = emptyList(),
        val cached: Boolean = false,
        val translatedCount: Int = 0,
    )

    companion object {
        private const val MAX_CUES_PER_REQUEST = 1_500
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val defaultHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
