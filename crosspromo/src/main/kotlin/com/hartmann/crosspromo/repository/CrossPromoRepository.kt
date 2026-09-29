package com.hartmann.crosspromo.repository

import com.hartmann.crosspromo.api.CrossPromoApi
import com.hartmann.crosspromo.api.PromoResult
import com.hartmann.crosspromo.api.RecommendationQuery
import com.hartmann.crosspromo.cache.PromoCache
import com.hartmann.crosspromo.model.CachedRecommendations
import com.hartmann.crosspromo.model.PromoResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Data layer behind the UI: serves cached recommendations instantly, refreshes in
 * the background, and keeps recent targets so the same group is not repeated.
 */
class CrossPromoRepository(
    private val api: CrossPromoApi,
    private val cache: PromoCache,
    private val sourcePackage: String,
    private val sessionId: String,
    private val locale: String? = null,
    private val sdkVersion: String? = null,
) {
    /** Recently shown target packages (bounded); rotated out of future requests. */
    private val recentTargets = ArrayDeque<String>()
    private val _recentTargets = MutableStateFlow<List<String>>(emptyList())
    val recentTargetsFlow: StateFlow<List<String>> get() = _recentTargets

    data class PromoState(
        val apps: List<com.hartmann.crosspromo.model.PromoApp>,
        val requestId: String? = null,
        val isFromCache: Boolean = false,
    )

    private val _state = MutableStateFlow(PromoState(emptyList()))
    val state: StateFlow<PromoState> get() = _state

    /**
     * Emit cached data immediately (if any), then refresh in the background.
     * Returns the state that should currently be rendered.
     */
    suspend fun loadForPlacement(placement: String, limit: Int = 3): PromoState {
        val cached = cache.get(sourcePackage, placement)
        if (cached != null && cached.hasContent) {
            _state.value = PromoState(cached.response.apps, cached.response.requestId, isFromCache = true)
        }

        val exclude = buildList {
            addAll(recentTargets)
        }
        val query = RecommendationQuery(
            sourcePackage = sourcePackage,
            placement = placement,
            limit = limit,
            sessionId = sessionId,
            exclude = exclude,
            locale = locale,
            sdkVersion = sdkVersion,
        )
        when (val result = api.recommendations(query)) {
            is PromoResult.Success -> {
                val response = result.value
                if (response.apps.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    cache.put(
                        CachedRecommendations(
                            response = response,
                            generatedAtMs = now,
                            expiresAtMs = response.expiresAt?.let { expiresAt ->
                                runCatching {
                                    java.time.Instant.parse(expiresAt).toEpochMilli()
                                }.getOrElse { now + DEFAULT_TTL_MS }
                            } ?: (now + DEFAULT_TTL_MS),
                            sourcePackage = sourcePackage,
                            placement = placement,
                        ),
                    )
                    rememberTargets(response)
                    _state.value = PromoState(response.apps, response.requestId, isFromCache = false)
                } else if (_state.value.apps.isEmpty()) {
                    // Empty catalog / disabled: keep whatever was cached; otherwise hide.
                    _state.value = PromoState(emptyList())
                }
            }
            is PromoResult.Failure -> {
                // Network failure: cached content (if emitted above) stays visible.
            }
        }
        return _state.value
    }

    private fun rememberTargets(response: PromoResponse) {
        for (app in response.apps) {
            recentTargets.addLast(app.packageName)
        }
        while (recentTargets.size > MAX_RECENT) {
            recentTargets.removeFirst()
        }
        _recentTargets.value = recentTargets.toList()
    }

    companion object {
        private const val MAX_RECENT = 6
        private const val DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
