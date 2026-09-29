package com.hartmann.crosspromo.analytics

import com.hartmann.crosspromo.api.CrossPromoApi
import com.hartmann.crosspromo.model.PromoEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Parameters for a single analytics event. */
data class PromoEventParams(
    val sourcePackage: String,
    val targetPackage: String,
    val placement: String,
    val selectionType: String?,
    val rankPosition: Int?,
    val recommendationRequestId: String?,
    val sessionId: String?,
    val sdkVersion: String?,
)

/**
 * Analytics abstraction. Hosts may plug in their own implementation (e.g. a
 * Firebase adapter) — the SDK never requires any particular analytics stack.
 */
interface PromoAnalytics {
    fun impression(params: PromoEventParams)
    fun click(params: PromoEventParams)
    fun install(params: PromoEventParams)
}

/** Does nothing — default when no analytics are configured. */
class NoOpAnalytics : PromoAnalytics {
    override fun impression(params: PromoEventParams) {}
    override fun click(params: PromoEventParams) {}
    override fun install(params: PromoEventParams) {}
}

/** Forwards events to the cross-promotion backend. */
class BackendAnalytics(
    private val api: CrossPromoApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : PromoAnalytics {

    override fun impression(params: PromoEventParams) = send(PromoEventType.IMPRESSION, params)
    override fun click(params: PromoEventParams) = send(PromoEventType.CLICK, params)
    override fun install(params: PromoEventParams) = send(PromoEventType.INSTALL, params)

    private fun send(type: PromoEventType, p: PromoEventParams) {
        scope.launch {
            api.sendEvent(
                mapOf(
                    "event" to type.wireName,
                    "sourcePackage" to p.sourcePackage,
                    "targetPackage" to p.targetPackage,
                    "placement" to p.placement,
                    "selectionType" to p.selectionType,
                    "rankPosition" to p.rankPosition,
                    "recommendationRequestId" to p.recommendationRequestId,
                    "sessionId" to p.sessionId,
                    "sdkVersion" to p.sdkVersion,
                ),
            )
        }
    }
}

/**
 * Fan-out adapter: logs to any number of delegates. Use it to combine backend
 * analytics with a host-provided Firebase adapter:
 *
 * `CompositeAnalytics(BackendAnalytics(api), FirebaseCrossPromoAnalytics(...))`
 */
class CompositeAnalytics(private vararg val delegates: PromoAnalytics) : PromoAnalytics {
    override fun impression(params: PromoEventParams) = delegates.forEach { it.impression(params) }
    override fun click(params: PromoEventParams) = delegates.forEach { it.click(params) }
    override fun install(params: PromoEventParams) = delegates.forEach { it.install(params) }
}
