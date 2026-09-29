package com.hartmann.crosspromo

import com.google.common.truth.Truth.assertThat
import com.hartmann.crosspromo.install.HartmannInstallAttribution
import org.junit.Test

class InstallAttributionTest {

    @Test
    fun `parses a crosspromo referrer with all utm parameters`() {
        // The Play library returns the referrer once-decoded: literal & and =.
        val referrer = "utm_source=com.charlesh.captionburn&utm_medium=crosspromo" +
            "&utm_campaign=hartmann_crosspromo&utm_content=com.charles.qrcode"
        val attribution = HartmannInstallAttribution.parseReferrer(referrer, "com.charles.qrcode")
        assertThat(attribution).isNotNull()
        assertThat(attribution!!.sourcePackage).isEqualTo("com.charlesh.captionburn")
        assertThat(attribution.targetPackage).isEqualTo("com.charles.qrcode")
        assertThat(attribution.campaign).isEqualTo("hartmann_crosspromo")
    }

    @Test
    fun `tolerates double-encoded referrers`() {
        val referrer = "utm_source%3Dcom.charlesh.captionburn%26utm_medium%3Dcrosspromo"
        val attribution = HartmannInstallAttribution.parseReferrer(referrer, "com.target")
        assertThat(attribution).isNotNull()
        assertThat(attribution!!.sourcePackage).isEqualTo("com.charlesh.captionburn")
    }

    @Test
    fun `rejects non-crosspromo media`() {
        val referrer = "utm_source=com.some.other&utm_medium=ads"
        assertThat(HartmannInstallAttribution.parseReferrer(referrer, "com.target")).isNull()
    }

    @Test
    fun `rejects empty and garbage referrers`() {
        assertThat(HartmannInstallAttribution.parseReferrer("", "com.target")).isNull()
        assertThat(HartmannInstallAttribution.parseReferrer("garbage", "com.target")).isNull()
    }

    @Test
    fun `rejects referrer without utm_source`() {
        val referrer = "utm_medium%3Dcrosspromo"
        assertThat(HartmannInstallAttribution.parseReferrer(referrer, "com.target")).isNull()
    }
}
