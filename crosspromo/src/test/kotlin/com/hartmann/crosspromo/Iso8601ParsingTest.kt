package com.hartmann.crosspromo

import com.google.common.truth.Truth.assertThat
import com.hartmann.crosspromo.repository.parseIso8601UtcMillis
import org.junit.Test

/**
 * Runs on the plain JVM, where Build.VERSION.SDK_INT is 0, so this exercises the
 * pre-API-26 fallback path that has no java.time available.
 */
class Iso8601ParsingTest {

    @Test
    fun `parses whole-second UTC timestamps`() {
        assertThat(parseIso8601UtcMillis("2026-09-22T19:00:00Z")).isEqualTo(1_790_103_600_000L)
    }

    @Test
    fun `parses millisecond timestamps and truncates longer fractions`() {
        assertThat(parseIso8601UtcMillis("2026-09-22T19:00:00.250Z")).isEqualTo(1_790_103_600_250L)
        assertThat(parseIso8601UtcMillis("2026-09-22T19:00:00.250999Z")).isEqualTo(1_790_103_600_250L)
    }

    @Test
    fun `returns null for values it cannot read`() {
        assertThat(parseIso8601UtcMillis("")).isNull()
        assertThat(parseIso8601UtcMillis("soon")).isNull()
        assertThat(parseIso8601UtcMillis("2026-13-45T99:00:00Z")).isNull()
    }
}
