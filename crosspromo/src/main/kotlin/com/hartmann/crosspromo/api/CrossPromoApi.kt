package com.hartmann.crosspromo.api

import com.hartmann.crosspromo.model.PromoResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Result of a recommendation fetch. [value] is null on any failure —
 * cross-promotion is never mission-critical, so errors are data, not exceptions.
 */
sealed class PromoResult<out T> {
    data class Success<T>(val value: T) : PromoResult<T>()
    data class Failure(val reason: String) : PromoResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
}

/** Query parameters for the recommendations endpoint. */
data class RecommendationQuery(
    val sourcePackage: String,
    val placement: String,
    val limit: Int = 3,
    val sessionId: String? = null,
    val exclude: List<String> = emptyList(),
    val locale: String? = null,
    val sdkVersion: String? = null,
)

/** Thin OkHttp-backed client for the /api/v1 endpoints. */
class CrossPromoApi(
    private val baseUrl: String,
    client: OkHttpClient? = null,
) {
    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun recommendations(query: RecommendationQuery): PromoResult<PromoResponse> =
        withContext(Dispatchers.IO) {
            try {
                val url = buildString {
                    append(baseUrl.trimEnd('/'))
                    append("/api/v1/recommendations?sourcePackage=")
                    append(urlEncode(query.sourcePackage))
                    append("&placement=").append(urlEncode(query.placement))
                    append("&limit=").append(query.limit.coerceIn(1, 6))
                    query.sessionId?.let { append("&sessionId=").append(urlEncode(it)) }
                    query.locale?.let { append("&locale=").append(urlEncode(it)) }
                    query.sdkVersion?.let { append("&sdkVersion=").append(urlEncode(it)) }
                    if (query.exclude.isNotEmpty()) {
                        append("&exclude=").append(urlEncode(query.exclude.joinToString(",")))
                    }
                }
                val request = Request.Builder().url(url).get().build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext PromoResult.Failure("http_${response.code}")
                    }
                    val parsed = PromoResponse.fromJson(body)
                        ?: return@withContext PromoResult.Failure("malformed_json")
                    PromoResult.Success(parsed)
                }
            } catch (e: Exception) {
                PromoResult.Failure("network_error: ${e.message?.take(120)}")
            }
        }

    /** Fire-and-forget analytics event. Returns false when the backend rejected it. */
    suspend fun sendEvent(payload: Map<String, Any?>): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = payload.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":" + when (v) {
                    null -> "null"
                    is Number -> v.toString()
                    is Boolean -> v.toString()
                    else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                }
            }
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/api/v1/events")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
