package com.charlesh.captionburn.crosspromo

import android.content.Context
import android.os.Bundle
import com.charlesh.captionburn.BuildConfig
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import com.hartmann.crosspromo.HartmannCrossPromo
import com.hartmann.crosspromo.analytics.PromoAnalytics
import com.hartmann.crosspromo.analytics.PromoEventParams

/**
 * Single wiring point for the cross-promotion SDK in CaptionBurn.
 *
 * The worker URL comes from local.properties (`crosspromo.url`, not committed) and
 * is baked into BuildConfig.CROSS_PROMO_URL. No secrets are involved — the worker
 * is a public API; only admin routes require a token that never ships.
 */
object CrossPromoInstaller {

    /** Firebase forwarding: mirrors backend events into the app's own Analytics. */
    private class FirebaseCrossPromoAnalytics(private val firebase: FirebaseAnalytics) : PromoAnalytics {
        override fun impression(params: PromoEventParams) {
            val bundle = Bundle().apply {
                putString("source_package", params.sourcePackage)
                putString("target_package", params.targetPackage)
                putString("placement", params.placement)
                putString("selection_type", params.selectionType ?: "unknown")
                params.rankPosition?.let { putInt("rank_position", it) }
            }
            firebase.logEvent("crosspromo_impression", bundle)
        }

        override fun click(params: PromoEventParams) {
            val bundle = Bundle().apply {
                putString("source_package", params.sourcePackage)
                putString("target_package", params.targetPackage)
                putString("placement", params.placement)
                putString("selection_type", params.selectionType ?: "unknown")
                params.rankPosition?.let { putInt("rank_position", it) }
            }
            firebase.logEvent("crosspromo_click", bundle)
        }

        override fun install(params: PromoEventParams) {
            val bundle = Bundle().apply {
                putString("source_package", params.sourcePackage)
                putString("target_package", params.targetPackage)
                putString("placement", params.placement)
            }
            firebase.logEvent("crosspromo_install", bundle)
        }
    }

    fun initialize(context: Context) {
        val url = BuildConfig.CROSS_PROMO_URL
        if (url.isBlank()) return // cross promo silently disabled without config

        HartmannCrossPromo.initialize(
            context = context,
            apiBaseUrl = url,
            analytics = Composite(
                backend = com.hartmann.crosspromo.analytics.BackendAnalytics(
                    com.hartmann.crosspromo.api.CrossPromoApi(url),
                ),
                firebase = FirebaseCrossPromoAnalytics(Firebase.analytics),
            ),
        )
    }

    private class Composite(
        private val backend: PromoAnalytics,
        private val firebase: PromoAnalytics,
    ) : PromoAnalytics {
        override fun impression(params: PromoEventParams) {
            backend.impression(params)
            firebase.impression(params)
        }

        override fun click(params: PromoEventParams) {
            backend.click(params)
            firebase.click(params)
        }

        override fun install(params: PromoEventParams) {
            backend.install(params)
            firebase.install(params)
        }
    }
}
