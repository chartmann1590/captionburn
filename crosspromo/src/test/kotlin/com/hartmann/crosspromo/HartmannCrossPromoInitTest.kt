package com.hartmann.crosspromo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CI builds compile without `crosspromo.url`, so CaptionBurn never calls
 * [HartmannCrossPromo.initialize] there. The SDK must degrade to a no-op
 * (null repository, silent UI) instead of crashing on the uninitialized
 * sourcePackage, which is what hosts without a configured backend rely on.
 */
@RunWith(RobolectricTestRunner::class)
class HartmannCrossPromoInitTest {

    @After
    fun tearDown() {
        // The singleton persists across test methods; reset so test order is
        // irrelevant (both states must hold regardless of execution order).
        HartmannCrossPromo.resetForTests()
    }

    @Test
    fun `uninitialized sdk degrades to a no-op instead of crashing`() {
        assertThat(HartmannCrossPromo.isInitialized).isFalse()
        assertThat(HartmannCrossPromo.repository("settings")).isNull()
        // Analytics emission before initialize must not throw.
        HartmannCrossPromo.impression(
            placement = "settings",
            app = com.hartmann.crosspromo.model.PromoApp(
                packageName = "com.a.b",
                name = "A",
                storeUrl = "https://play.google.com/store/apps/details?id=com.a.b",
            ),
            selectionType = null,
            rankPosition = 1,
            requestId = null,
        )
    }

    @Test
    fun `initialize enables repositories and detects the host package`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        HartmannCrossPromo.initialize(context, apiBaseUrl = "https://example.invalid/")
        assertThat(HartmannCrossPromo.isInitialized).isTrue()
        assertThat(HartmannCrossPromo.sourcePackage).isEqualTo(context.packageName)
        assertThat(HartmannCrossPromo.repository("settings")).isNotNull()
    }
}
