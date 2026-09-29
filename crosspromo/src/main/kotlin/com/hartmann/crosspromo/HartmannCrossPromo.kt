package com.hartmann.crosspromo

import android.content.Context
import com.hartmann.crosspromo.analytics.BackendAnalytics
import com.hartmann.crosspromo.analytics.NoOpAnalytics
import com.hartmann.crosspromo.analytics.PromoAnalytics
import com.hartmann.crosspromo.api.CrossPromoApi
import com.hartmann.crosspromo.cache.PromoCache
import com.hartmann.crosspromo.repository.CrossPromoRepository
import com.hartmann.crosspromo.repository.CrossPromoRepository.PromoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hartmann Cross-Promotion SDK entry point.
 *
 * Initialize once (Application.onCreate), then place Compose components:
 *
 * ```
 * HartmannCrossPromo.initialize(application, apiBaseUrl = "https://crosspromo.yourdomain.workers.dev")
 * HartmannCrossPromoRow(placement = "settings")
 * ```
 *
 * The source package is always auto-detected from the host app — never configured.
 */
object HartmannCrossPromo {

    const val SDK_VERSION = "1.0.0"

    /** Fired when a promo card becomes meaningfully visible (deduplicated per card). */
    fun interface ImpressionListener {
        fun onImpression(app: com.hartmann.crosspromo.model.PromoApp)
    }

    var analytics: PromoAnalytics = NoOpAnalytics()
        private set

    /** True after [initialize]. Composables must hide promo UI when false. */
    var isInitialized: Boolean = false
        private set

    lateinit var sourcePackage: String
        private set

    var apiBaseUrl: String = ""
        private set

    private val api: CrossPromoApi by lazy { CrossPromoApi(apiBaseUrl) }
    private val cache: PromoCache by lazy { PromoCache(appContext) }
    private val repositories = ConcurrentHashMap<String, CrossPromoRepository>()

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Session id: random UUID per app process, short-lived by design (no profiles). */
    val sessionId: String by lazy { UUID.randomUUID().toString().take(32) }

    fun initialize(context: Context, apiBaseUrl: String, analytics: PromoAnalytics = NoOpAnalytics()) {
        this.appContext = context.applicationContext
        // Automatic current-app detection — hosts never configure their package.
        this.sourcePackage = context.packageName
        this.apiBaseUrl = apiBaseUrl
        this.analytics = analytics
        this.isInitialized = true
    }

    /** Test-only: restores the pre-initialize state. */
    internal fun resetForTests() {
        repositories.clear()
        isInitialized = false
        apiBaseUrl = ""
        analytics = NoOpAnalytics()
    }

    /**
     * Repository per placement so each placement caches and rotates independently.
     * Returns null before [initialize] (e.g. hosts without a configured backend
     * URL) so callers can degrade to no UI instead of crashing.
     */
    fun repository(placement: String): CrossPromoRepository? {
        if (!isInitialized) return null
        return repositories.getOrPut(placement) {
            CrossPromoRepository(
                api = api,
                cache = cache,
                sourcePackage = sourcePackage,
                sessionId = sessionId,
                sdkVersion = SDK_VERSION,
            )
        }
    }

    /** No-op before [initialize]: host apps without a backend never see promos. */
    fun loadPlacement(
        placement: String,
        limit: Int = 3,
        onState: (PromoState) -> Unit = {},
    ) {
        val repo = repository(placement) ?: return
        scope.launch {
            val state = repo.loadForPlacement(placement, limit)
            onState(state)
        }
    }

    fun impression(
        placement: String,
        app: com.hartmann.crosspromo.model.PromoApp,
        selectionType: String?,
        rankPosition: Int,
        requestId: String?,
    ) {
        if (!isInitialized) return
        analytics.impression(
            com.hartmann.crosspromo.analytics.PromoEventParams(
                sourcePackage = sourcePackage,
                targetPackage = app.packageName,
                placement = placement,
                selectionType = selectionType,
                rankPosition = rankPosition,
                recommendationRequestId = requestId,
                sessionId = sessionId,
                sdkVersion = SDK_VERSION,
            ),
        )
    }

    fun click(
        placement: String,
        app: com.hartmann.crosspromo.model.PromoApp,
        selectionType: String?,
        rankPosition: Int,
        requestId: String?,
    ) {
        if (!isInitialized) return
        analytics.click(
            com.hartmann.crosspromo.analytics.PromoEventParams(
                sourcePackage = sourcePackage,
                targetPackage = app.packageName,
                placement = placement,
                selectionType = selectionType,
                rankPosition = rankPosition,
                recommendationRequestId = requestId,
                sessionId = sessionId,
                sdkVersion = SDK_VERSION,
            ),
        )
    }
}
