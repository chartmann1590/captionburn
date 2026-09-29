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
 */
object PlayStoreLauncher {

    fun openPlayStore(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false

        // 1. market:// resolved explicitly (avoids ActivityNotFoundException paths
        //    on devices without Play).
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        if (tryLaunch(context, marketIntent)) return true

        // 2. https Play link handled by the Play Store app itself.
        val playIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName"),
        ).setPackage("com.android.vending")
        if (tryLaunch(context, playIntent)) return true

        // 3. Browser fallback.
        val browserIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName"),
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
