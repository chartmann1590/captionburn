package com.hartmann.crosspromo

import com.hartmann.crosspromo.api.CrossPromoApi
import com.hartmann.crosspromo.api.PromoResult
import com.hartmann.crosspromo.api.RecommendationQuery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class RecommendationsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: CrossPromoApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = CrossPromoApi(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetches recommendations and builds the expected request`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"version":1,"requestId":"r1","apps":[{"packageName":"com.a.b","name":"A","storeUrl":"https://play.google.com/store/apps/details?id=com.a.b"}]}""",
            ),
        )
        val result = api.recommendations(
            RecommendationQuery(
                sourcePackage = "com.charlesh.captionburn",
                placement = "settings",
                limit = 3,
                sessionId = "sess-1",
                sdkVersion = "1.0.0",
            ),
        )
        assertThat(result.isSuccess).isTrue()
        val response = (result as PromoResult.Success).value
        assertThat(response.apps).hasSize(1)

        val recorded = server.takeRequest()
        val url = recorded.requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/api/v1/recommendations")
        assertThat(url.queryParameter("sourcePackage")).isEqualTo("com.charlesh.captionburn")
        assertThat(url.queryParameter("placement")).isEqualTo("settings")
        assertThat(url.queryParameter("limit")).isEqualTo("3")
        assertThat(url.queryParameter("sessionId")).isEqualTo("sess-1")
        assertThat(url.queryParameter("sdkVersion")).isEqualTo("1.0.0")
    }

    @Test
    fun `failure result on http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = api.recommendations(
            RecommendationQuery(sourcePackage = "com.a.b", placement = "settings"),
        )
        assertThat(result.isSuccess).isFalse()
        result as PromoResult.Failure
        assertThat(result.reason).isEqualTo("http_500")
    }

    @Test
    fun `failure result on malformed json`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>broken</html>"))
        val result = api.recommendations(
            RecommendationQuery(sourcePackage = "com.a.b", placement = "settings"),
        )
        result as PromoResult.Failure
        assertThat(result.reason).isEqualTo("malformed_json")
    }

    @Test
    fun `empty apps response parses as success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":1,"apps":[]}"""))
        val result = api.recommendations(
            RecommendationQuery(sourcePackage = "com.a.b", placement = "settings"),
        )
        val response = (result as PromoResult.Success).value
        assertThat(response.apps).isEmpty()
    }

    @Test
    fun `events are posted to the events endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val ok = api.sendEvent(
            mapOf(
                "event" to "promo_click",
                "sourcePackage" to "com.a.b",
                "targetPackage" to "com.c.d",
                "placement" to "settings",
                "rankPosition" to 1,
            ),
        )
        assertThat(ok).isTrue()
        val recorded = server.takeRequest()
        assertThat(recorded.requestUrl!!.encodedPath).isEqualTo("/api/v1/events")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"event\":\"promo_click\"")
        assertThat(body).contains("\"rankPosition\":1")
    }
}
