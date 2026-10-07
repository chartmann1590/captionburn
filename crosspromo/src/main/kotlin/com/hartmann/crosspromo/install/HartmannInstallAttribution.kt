package com.hartmann.crosspromo.install

import android.content.Context
import android.os.RemoteException
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import com.hartmann.crosspromo.HartmannCrossPromo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Optional install attribution via Google's supported Install Referrer API.
 *
 * Call once from the promoted (target) app's first launch:
 *
 * ```
 * HartmannInstallAttribution.trackCrossPromoInstall(this)
 * ```
 *
 * Reads `referrer=utm_source%3D<pkg>%26utm_medium%3Dcrosspromo%26...` — built by
 * the backend with Play-supported utm parameters — and reports a
 * `crosspromo_install` event when the referrer identifies a cross-promotion.
 */
object HartmannInstallAttribution {

    data class Attribution(
        val sourcePackage: String?,
        val targetPackage: String,
        val campaign: String?,
    )

    suspend fun trackCrossPromoInstall(context: Context) {
        val attribution = readAttribution(context) ?: return
        HartmannCrossPromo.analytics.install(
            com.hartmann.crosspromo.analytics.PromoEventParams(
                sourcePackage = attribution.sourcePackage ?: return,
                targetPackage = attribution.targetPackage,
                placement = "install_referrer",
                selectionType = null,
                rankPosition = null,
                recommendationRequestId = null,
                sessionId = null,
                sdkVersion = HartmannCrossPromo.SDK_VERSION,
            ),
        )
    }

    /** Reads and parses the referrer; null when absent or not a cross-promo install. */
    suspend fun readAttribution(context: Context): Attribution? {
        val referrer = readReferrer(context) ?: return null
        return parseReferrer(referrer, context.packageName)
    }

    /** Visible for tests: parses a URL-encoded referrer string. */
    fun parseReferrer(rawReferrer: String, targetPackage: String): Attribution? {
        // The Play library returns the referrer once-decoded (literal & and =).
        // Tolerate double-encoded forms where '=' arrived as %3D.
        val referrer = rawReferrer
        val normalized = if (!referrer.contains('=')) {
            runCatching { java.net.URLDecoder.decode(referrer, "UTF-8") }.getOrElse { referrer }
        } else {
            referrer
        }
        val params = normalized
            .split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val k = java.net.URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val v = java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                k to v
            }
            .toMap()

        val medium = params["utm_medium"] ?: return null
        if (medium != "crosspromo") return null
        val source = params["utm_source"] ?: return null
        val content = params["utm_content"]
        return Attribution(
            sourcePackage = source,
            targetPackage = content ?: targetPackage,
            campaign = params["utm_campaign"],
        )
    }

    private suspend fun readReferrer(context: Context): String? =
        suspendCancellableCoroutine { cont ->
            val client = InstallReferrerClient.newBuilder(context).build()
            try {
                client.startConnection(object : InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(responseCode: Int) {
                        if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                            try {
                                val details: ReferrerDetails = client.installReferrer
                                cont.resume(details.installReferrer)
                            } catch (_: RemoteException) {
                                cont.resume(null)
                            } finally {
                                runCatching { client.endConnection() }
                            }
                        } else {
                            runCatching { client.endConnection() }
                            cont.resume(null)
                        }
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        cont.resume(null)
                    }
                })
            } catch (_: Exception) {
                cont.resume(null)
            }
        }
}
