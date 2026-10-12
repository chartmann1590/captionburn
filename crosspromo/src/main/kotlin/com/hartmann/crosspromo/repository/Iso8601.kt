package com.hartmann.crosspromo.repository

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Parses an ISO-8601 UTC timestamp such as `2026-09-22T19:00:00Z` or
 * `2026-09-22T19:00:00.123Z` into epoch milliseconds.
 *
 * `java.time` is only available from API 26 and this library supports API 24,
 * so older devices fall back to [SimpleDateFormat]. Returns null when the value
 * cannot be parsed; callers substitute their own default TTL.
 */
internal fun parseIso8601UtcMillis(value: String): Long? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return runCatching { java.time.Instant.parse(trimmed).toEpochMilli() }.getOrNull()
    }
    // SimpleDateFormat reads every fractional digit as milliseconds, so keep at most three.
    val normalized = FRACTION_OVERFLOW.replace(trimmed, "$1")
    val utc = TimeZone.getTimeZone("UTC")
    for (pattern in FALLBACK_PATTERNS) {
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = utc
                isLenient = false
            }.parse(normalized)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return null
}

private val FRACTION_OVERFLOW = Regex("""(\.\d{3})\d+""")

private val FALLBACK_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
)
