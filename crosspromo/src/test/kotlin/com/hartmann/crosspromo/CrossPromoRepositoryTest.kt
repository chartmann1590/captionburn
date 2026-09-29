package com.hartmann.crosspromo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.hartmann.crosspromo.api.CrossPromoApi
import com.hartmann.crosspromo.cache.PromoCache
import com.hartmann.crosspromo.repository.CrossPromoRepository
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Repository semantics on a Robolectric device (real DataStore + MockWebServer):
 * cache-then-network behavior and the kill-switch guarantee that an
 * authoritative empty response clears previously cached cards.
 */
@RunWith(RobolectricTestRunner::class)
class CrossPromoRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: CrossPromoRepository
    private lateinit var cache: PromoCache
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val twoAppsBody =
        """{"version":1,"requestId":"r1","apps":[""" +
            """{"packageName":"com.a.one","name":"One","storeUrl":"https://play.google.com/store/apps/details?id=com.a.one"},""" +
            """{"packageName":"com.a.two","name":"Two","storeUrl":"https://play.google.com/store/apps/details?id=com.a.two"}]}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = CrossPromoApi(server.url("/").toString())
        cache = PromoCache(context)
        repository = CrossPromoRepository(
            api = api,
            cache = cache,
            sourcePackage = "com.test.host",
            sessionId = "session-1",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `serves cache instantly then refreshes from the network`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(twoAppsBody))
        val first = repository.loadForPlacement("settings")
        assertThat(first.apps).hasSize(2)
        assertThat(first.isFromCache).isFalse()

        // Second load: cache emit + fresh network response.
        server.enqueue(MockResponse().setResponseCode(200).setBody(twoAppsBody))
        val second = repository.loadForPlacement("settings")
        assertThat(second.apps).hasSize(2)
        // Final returned state reflects the network refresh.
        assertThat(second.isFromCache).isFalse()
        // Cache entry exists for the next cold start.
        assertThat(cache.get("com.test.host", "settings")).isNotNull()
    }

    @Test
    fun `authoritative empty response clears cached cards`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(twoAppsBody))
        assertThat(repository.loadForPlacement("settings").apps).hasSize(2)
        assertThat(cache.get("com.test.host", "settings")).isNotNull()

        // Kill switch flips on the backend: 200 with zero apps.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":1,"requestId":"r2","apps":[]}"""))
        val cleared = repository.loadForPlacement("settings")
        assertThat(cleared.apps).isEmpty()
        // The cached payload must be gone, or the kill switch could never stop
        // already-cached promotions.
        assertThat(cache.get("com.test.host", "settings")).isNull()
    }

    @Test
    fun `network failure keeps cached content visible`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(twoAppsBody))
        assertThat(repository.loadForPlacement("settings").apps).hasSize(2)

        server.enqueue(MockResponse().setResponseCode(500))
        val stale = repository.loadForPlacement("settings")
        assertThat(stale.apps).hasSize(2)
        assertThat(cache.get("com.test.host", "settings")).isNotNull()
    }
}
