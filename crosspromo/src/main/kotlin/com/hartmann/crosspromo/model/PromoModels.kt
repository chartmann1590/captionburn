package com.hartmann.crosspromo.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One promoted app as returned by the cross-promotion backend. */
@Serializable
data class PromoApp(
    val packageName: String,
    val name: String,
    val iconUrl: String? = null,
    val shortDescription: String? = null,
    val rating: Double? = null,
    @SerialName("ratingCount") val ratingCount: Long? = null,
    @SerialName("installText") val installText: String? = null,
    val storeUrl: String,
    val selectionType: String? = null,
    /** Unknown JSON fields, preserved so re-serialization never loses data. */
    val unknown: Map<String, String> = emptyMap(),
) {
    /** Play-store style label, e.g. "★ 4.7". Null when unrated. */
    val ratingLabel: String?
        get() = rating?.let { "★ " + it.toString() }
}

/** Full recommendation response from the backend. */
@Serializable
data class PromoResponse(
    val version: Int = 1,
    val requestId: String? = null,
    val generatedAt: String? = null,
    val expiresAt: String? = null,
    val apps: List<PromoApp> = emptyList(),
) {
    companion object {
        /** Tolerant parser: unknown fields are ignored, missing fields default. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        fun fromJson(raw: String): PromoResponse? =
            try {
                json.decodeFromString(PromoResponse.serializer(), raw)
            } catch (_: Exception) {
                null
            }
    }
}

/** Cached recommendation payload (stale-while-revalidate). */
data class CachedRecommendations(
    val response: PromoResponse,
    val generatedAtMs: Long,
    val expiresAtMs: Long,
    val sourcePackage: String,
    val placement: String,
) {
    val isFresh: Boolean get() = System.currentTimeMillis() < expiresAtMs
    val hasContent: Boolean get() = response.apps.isNotEmpty()
}

/** Analytics event types emitted by the SDK. */
enum class PromoEventType(val wireName: String) {
    IMPRESSION("promo_impression"),
    CLICK("promo_click"),
    INSTALL("crosspromo_install"),
}
