package com.charlesh.captionburn.ui.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.charlesh.captionburn.BuildConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView

@Composable
fun BottomBannerAd(modifier: Modifier = Modifier) {
    if (BuildConfig.ADMOB_BANNER_AD_UNIT_ID.isBlank()) return
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val adSize = remember(widthDp) {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
    }

    // Re-key on widthDp so rotation/resize recreates the AdView. AdView.setAdSize()
    // throws IllegalStateException if called more than once on the same instance.
    key(widthDp) {
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .height(adSize.height.dp),
            factory = { viewContext ->
                AdView(viewContext).apply {
                    adUnitId = BuildConfig.ADMOB_BANNER_AD_UNIT_ID
                    setAdSize(adSize)
                    loadAd(AdRequest.Builder().build())
                }
            },
        )
    }
}

@Composable
fun NativeAdvancedAd(modifier: Modifier = Modifier) {
    if (BuildConfig.ADMOB_NATIVE_ADVANCED_AD_UNIT_ID.isBlank()) return
    val context = LocalContext.current
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            nativeAd?.destroy()
            nativeAd = null
        }
    }

    LaunchedEffect(Unit) {
        val loader = AdLoader.Builder(context, BuildConfig.ADMOB_NATIVE_ADVANCED_AD_UNIT_ID)
            .forNativeAd { loadedAd ->
                nativeAd?.destroy()
                nativeAd = loadedAd
            }
            .withAdListener(object : AdListener() {})
            .build()
        loader.loadAd(AdRequest.Builder().build())
    }

    val ad = nativeAd ?: return
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { viewContext -> createNativeAdView(viewContext, ad) },
        update = { view -> bindNativeAd(view, ad) },
    )
}

fun showInterstitialThen(
    activity: Activity?,
    currentAd: InterstitialAd?,
    onNavigate: () -> Unit,
    onReloadRequested: () -> Unit,
) {
    val ad = currentAd
    if (activity == null || ad == null) {
        onNavigate()
        onReloadRequested()
        return
    }
    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
        override fun onAdDismissedFullScreenContent() {
            onNavigate()
            onReloadRequested()
        }

        override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
            onNavigate()
            onReloadRequested()
        }
    }
    ad.show(activity)
}

fun loadInterstitial(
    context: Context,
    onLoaded: (InterstitialAd?) -> Unit,
) {
    val unitId = BuildConfig.ADMOB_INTERSTITIAL_AD_UNIT_ID
    if (unitId.isBlank()) {
        onLoaded(null)
        return
    }
    InterstitialAd.load(
        context,
        unitId,
        AdRequest.Builder().build(),
        object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(interstitialAd: InterstitialAd) {
                onLoaded(interstitialAd)
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                onLoaded(null)
            }
        },
    )
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun createNativeAdView(context: Context, nativeAd: NativeAd): NativeAdView {
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val horizontal = (12 * resources.displayMetrics.density).toInt()
        val vertical = (10 * resources.displayMetrics.density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
    val headline = TextView(context).apply {
        textSize = 16f
    }
    val body = TextView(context).apply {
        textSize = 14f
    }
    val cta = Button(context)
    container.addView(headline)
    container.addView(body)
    container.addView(cta)
    return NativeAdView(context).apply {
        addView(container)
        headlineView = headline
        bodyView = body
        callToActionView = cta
        setNativeAd(nativeAd)
    }
}

private fun bindNativeAd(view: NativeAdView, ad: NativeAd) {
    (view.headlineView as? TextView)?.text = ad.headline.orEmpty()
    (view.bodyView as? TextView)?.apply {
        text = ad.body.orEmpty()
        visibility = if (ad.body.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
    }
    (view.callToActionView as? Button)?.apply {
        text = ad.callToAction.orEmpty()
        visibility = if (ad.callToAction.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
    }
    view.setNativeAd(ad)
}
