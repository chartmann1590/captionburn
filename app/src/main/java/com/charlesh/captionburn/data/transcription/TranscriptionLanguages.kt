package com.charlesh.captionburn.data.transcription

/**
 * Languages Whisper can transcribe, exposed as ISO-639-1 codes (the format
 * whisper.cpp expects in `whisper_full_params.language`). Auto-detection is
 * represented by `null`/empty at the call sites — never by a value here.
 *
 * The list mirrors whisper.cpp's `g_lang` map (all 99 languages) so any hint we
 * pass is guaranteed to resolve to a valid `whisper_lang_id`.
 */
object TranscriptionLanguages {

    /** Stable, roughly-ordered-by-relevance list for UI pickers. */
    val all: List<Entry> = listOf(
        Entry("en", "English"),
        Entry("es", "Spanish"),
        Entry("fr", "French"),
        Entry("de", "German"),
        Entry("it", "Italian"),
        Entry("pt", "Portuguese"),
        Entry("nl", "Dutch"),
        Entry("ru", "Russian"),
        Entry("uk", "Ukrainian"),
        Entry("pl", "Polish"),
        Entry("tr", "Turkish"),
        Entry("ar", "Arabic"),
        Entry("he", "Hebrew"),
        Entry("fa", "Persian"),
        Entry("hi", "Hindi"),
        Entry("bn", "Bengali"),
        Entry("ur", "Urdu"),
        Entry("ta", "Tamil"),
        Entry("te", "Telugu"),
        Entry("ml", "Malayalam"),
        Entry("kn", "Kannada"),
        Entry("mr", "Marathi"),
        Entry("ne", "Nepali"),
        Entry("si", "Sinhala"),
        Entry("th", "Thai"),
        Entry("vi", "Vietnamese"),
        Entry("id", "Indonesian"),
        Entry("ms", "Malay"),
        Entry("tl", "Tagalog"),
        Entry("jw", "Javanese"),
        Entry("su", "Sundanese"),
        Entry("km", "Khmer"),
        Entry("lo", "Lao"),
        Entry("my", "Burmese"),
        Entry("zh", "Chinese"),
        Entry("ja", "Japanese"),
        Entry("ko", "Korean"),
        Entry("sv", "Swedish"),
        Entry("da", "Danish"),
        Entry("nb", "Norwegian"),
        Entry("nn", "Nynorsk"),
        Entry("is", "Icelandic"),
        Entry("fo", "Faroese"),
        Entry("fi", "Finnish"),
        Entry("et", "Estonian"),
        Entry("lv", "Latvian"),
        Entry("lt", "Lithuanian"),
        Entry("cs", "Czech"),
        Entry("sk", "Slovak"),
        Entry("sl", "Slovenian"),
        Entry("hr", "Croatian"),
        Entry("bs", "Bosnian"),
        Entry("sr", "Serbian"),
        Entry("mk", "Macedonian"),
        Entry("bg", "Bulgarian"),
        Entry("ro", "Romanian"),
        Entry("hu", "Hungarian"),
        Entry("el", "Greek"),
        Entry("af", "Afrikaans"),
        Entry("sw", "Swahili"),
        Entry("yo", "Yoruba"),
        Entry("ha", "Hausa"),
        Entry("so", "Somali"),
        Entry("am", "Amharic"),
        Entry("az", "Azerbaijani"),
        Entry("ka", "Georgian"),
        Entry("hy", "Armenian"),
        Entry("kk", "Kazakh"),
        Entry("uz", "Uzbek"),
        Entry("ky", "Kyrgyz"),
        Entry("mn", "Mongolian"),
        Entry("be", "Belarusian"),
        Entry("ca", "Catalan"),
        Entry("gl", "Galician"),
        Entry("eu", "Basque"),
        Entry("cy", "Welsh"),
        Entry("ga", "Irish"),
        Entry("mt", "Maltese"),
        Entry("sq", "Albanian"),
        Entry("la", "Latin"),
        Entry("no", "Norwegian"),
        Entry("mi", "Maori"),
        Entry("sa", "Sanskrit"),
        Entry("sd", "Sindhi"),
        Entry("ps", "Pashto"),
        Entry("gu", "Gujarati"),
        Entry("pa", "Punjabi"),
        Entry("or", "Oriya"),
        Entry("as", "Assamese"),
        Entry("ba", "Bashkir"),
        Entry("tt", "Tatar"),
        Entry("tg", "Tajik"),
        Entry("tk", "Turkmen"),
        Entry("sn", "Shona"),
        Entry("ln", "Lingala"),
        Entry("lg", "Luganda"),
        Entry("yi", "Yiddish"),
    )

    private val byCode: Map<String, Entry> = all.associateBy { it.code }

    /** True if [code] is a known ISO-639-1 Whisper language code. */
    fun isValid(code: String): Boolean = byCode.containsKey(code)

    /** Resolve a stored preference; unknown/blank codes are dropped. */
    fun byCodeOrNull(code: String?): Entry? =
        code?.takeIf { it.isNotBlank() }?.let { byCode[it] }

    data class Entry(val code: String, val displayName: String)
}
