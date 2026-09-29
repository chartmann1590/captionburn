package com.hartmann.crosspromo.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens the official Google Play listing for a package.
 *
 * Preference order: Play Store app via market:// (native experience) -> Play
 * Store app via https play link -> browser fallback. Never crashes when no
 * handler exists; never downloads anything.
 *
 * Every launch URL carries an install referrer built from Play-supported utm
 * parameters (utm_source=<source app>, utm_medium=crosspromo,
 * utm_content=<target package>) so that installs started from a promo card can
 * be attributed by [com.hartmann.crosspromo.install.HartmannInstallAttribution]
 * in the target app via the Install Referrer API. Without the referrer, clicks
 * were measurable but installs were not.
 */
object PlayStoreLauncher {

    /**
     * Value for the `referrer` query parameter: URL-encoded utm params. Play's
     * intent URLs expect the referrer value itself to be percent-encoded, hence
     * the double encoding of the inner `&`/`=` characters.
     */
    fun buildReferrer(sourcePackage: String, targetPackage: String): String {
        val raw = "utm_source=${urlEncode(sourcePackage)}" +
            "&utm_medium=crosspromo" +
            "&utm_content=${urlEncode(targetPackage)}"
        return urlEncode(raw)
    }

    fun openPlayStore(context: Context, packageName: String, referrer: String? = null): Boolean {
        if (packageName.isBlank()) return false

        val referrerParam = referrer?.takeIf { it.isNotBlank() }
            ?.let { "&referrer=${Uri.encode(it)}" }
            ?: ""

        // 1. market:// resolved explicitly (avoids ActivityNotFoundException paths
        //    on devices without Play).
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$packageName$referrerParam"),
        )
        if (tryLaunch(context, marketIntent)) return true

        // 2. https Play link handled by the Play Store app itself.
        val playIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName$referrerParam"),
        ).setPackage("com.android.vending")
        if (tryLaunch(context, playIntent)) return true

        // 3. Browser fallback.
        val browserIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName$referrerParam"),
        )
        return tryLaunch(context, browserIntent)
    }

    /** Resolves an intent safely; returns false instead of throwing. */
    fun canResolve(context: Context, intent: Intent): Boolean {
        return try {
            intent.resolveActivity(context.packageManager) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun urlEncode(value: String): String = Uri.encode(value)

    private fun tryLaunch(context: Context, intent: Intent): Boolean {
        return try {
            if (!canResolve(context, intent)) return false
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
