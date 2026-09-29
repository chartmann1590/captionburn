package com.hartmann.crosspromo

import com.hartmann.crosspromo.model.PromoResponse
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PromoResponseParsingTest {

    @Test
    fun `parses a full backend response`() {
        val raw = """
        {
          "version": 1,
          "requestId": "req-1",
          "generatedAt": "2026-09-22T13:00:00Z",
          "expiresAt": "2026-09-22T19:00:00Z",
          "apps": [
            {
              "packageName": "com.charlesh.captionburn",
              "name": "CaptionBurn: Video Captions",
              "iconUrl": "https://play-lh.googleusercontent.com/abc=s256-rw",
              "shortDescription": "On-device captions",
              "rating": 4.7,
              "ratingCount": 2400,
              "installText": "100K+",
              "storeUrl": "https://play.google.com/store/apps/details?id=com.charlesh.captionburn",
              "selectionType": "popular"
            }
          ]
        }
        """.trimIndent()

        val parsed = PromoResponse.fromJson(raw)!!
        assertThat(parsed.version).isEqualTo(1)
        assertThat(parsed.requestId).isEqualTo("req-1")
        assertThat(parsed.apps).hasSize(1)
        val app = parsed.apps[0]
        assertThat(app.packageName).isEqualTo("com.charlesh.captionburn")
        assertThat(app.rating).isEqualTo(4.7)
        assertThat(app.ratingCount).isEqualTo(2400)
        assertThat(app.selectionType).isEqualTo("popular")
        assertThat(app.ratingLabel).isEqualTo("★ 4.7")
    }

    @Test
    fun `ignores unknown JSON fields for forward compatibility`() {
        val raw = """
        {
          "version": 1,
          "brandNewField": {"nested": [1,2,3]},
          "apps": [
            {
              "packageName": "com.a.b",
              "name": "A",
              "storeUrl": "https://play.google.com/store/apps/details?id=com.a.b",
              "futureField": "whatever",
              "anotherFuture": 42
            }
          ]
        }
        """.trimIndent()

        val parsed = PromoResponse.fromJson(raw)!!
        assertThat(parsed.apps).hasSize(1)
        assertThat(parsed.apps[0].name).isEqualTo("A")
    }

    @Test
    fun `handles missing optional fields and empty apps`() {
        val raw = """
        {"version": 1, "apps": []}
        """.trimIndent()
        val parsed = PromoResponse.fromJson(raw)!!
        assertThat(parsed.apps).isEmpty()
        assertThat(parsed.requestId).isNull()
    }

    @Test
    fun `returns null instead of crashing on malformed json`() {
        assertThat(PromoResponse.fromJson("not json at all {{{")).isNull()
        assertThat(PromoResponse.fromJson("<html>error page</html>")).isNull()
        assertThat(PromoResponse.fromJson("")).isNull()
    }

    @Test
    fun `apps with null rating and description are tolerated`() {
        val raw = """
        {
          "apps": [
            {"packageName": "com.a.b", "name": "A", "storeUrl": "https://x", "rating": null, "shortDescription": null}
          ]
        }
        """.trimIndent()
        val parsed = PromoResponse.fromJson(raw)!!
        assertThat(parsed.apps[0].rating).isNull()
        assertThat(parsed.apps[0].ratingLabel).isNull()
        assertThat(parsed.apps[0].shortDescription).isNull()
    }
}
