package com.charlesh.captionburn.data.transcription

/**
 * Languages the bundled whisper.cpp can transcribe, exposed as the exact codes
 * its `g_lang` map accepts (see `third_party/whisper.cpp/src/whisper.cpp`).
 * Any code here resolves through `whisper_lang_id()`; codes outside this set
 * make it return -1 and would poison the transcription prompt. Auto-detection
 * is represented by `null`/empty at the call sites — never by a value here.
 *
 * The list mirrors the bundled map entry-for-entry (100 languages), including
 * its three-letter codes `haw` (Hawaiian) and `yue` (Cantonese), and is ordered
 * by the map's own language ids (roughly the model's training prevalence).
 */
object TranscriptionLanguages {

    /** Stable list for UI pickers; order matches whisper.cpp's `g_lang` ids. */
    val all: List<Entry> = listOf(
        Entry("en", "English"),
        Entry("zh", "Chinese"),
        Entry("de", "German"),
        Entry("es", "Spanish"),
        Entry("ru", "Russian"),
        Entry("ko", "Korean"),
        Entry("fr", "French"),
        Entry("ja", "Japanese"),
        Entry("pt", "Portuguese"),
        Entry("tr", "Turkish"),
        Entry("pl", "Polish"),
        Entry("ca", "Catalan"),
        Entry("nl", "Dutch"),
        Entry("ar", "Arabic"),
        Entry("sv", "Swedish"),
        Entry("it", "Italian"),
        Entry("id", "Indonesian"),
        Entry("hi", "Hindi"),
        Entry("fi", "Finnish"),
        Entry("vi", "Vietnamese"),
        Entry("he", "Hebrew"),
        Entry("uk", "Ukrainian"),
        Entry("el", "Greek"),
        Entry("ms", "Malay"),
        Entry("cs", "Czech"),
        Entry("ro", "Romanian"),
        Entry("da", "Danish"),
        Entry("hu", "Hungarian"),
        Entry("ta", "Tamil"),
        Entry("no", "Norwegian"),
        Entry("th", "Thai"),
        Entry("ur", "Urdu"),
        Entry("hr", "Croatian"),
        Entry("bg", "Bulgarian"),
        Entry("lt", "Lithuanian"),
        Entry("la", "Latin"),
        Entry("mi", "Maori"),
        Entry("ml", "Malayalam"),
        Entry("cy", "Welsh"),
        Entry("sk", "Slovak"),
        Entry("te", "Telugu"),
        Entry("fa", "Persian"),
        Entry("lv", "Latvian"),
        Entry("bn", "Bengali"),
        Entry("sr", "Serbian"),
        Entry("az", "Azerbaijani"),
        Entry("sl", "Slovenian"),
        Entry("kn", "Kannada"),
        Entry("et", "Estonian"),
        Entry("mk", "Macedonian"),
        Entry("br", "Breton"),
        Entry("eu", "Basque"),
        Entry("is", "Icelandic"),
        Entry("hy", "Armenian"),
        Entry("ne", "Nepali"),
        Entry("mn", "Mongolian"),
        Entry("bs", "Bosnian"),
        Entry("kk", "Kazakh"),
        Entry("sq", "Albanian"),
        Entry("sw", "Swahili"),
        Entry("gl", "Galician"),
        Entry("mr", "Marathi"),
        Entry("pa", "Punjabi"),
        Entry("si", "Sinhala"),
        Entry("km", "Khmer"),
        Entry("sn", "Shona"),
        Entry("yo", "Yoruba"),
        Entry("so", "Somali"),
        Entry("af", "Afrikaans"),
        Entry("oc", "Occitan"),
        Entry("ka", "Georgian"),
        Entry("be", "Belarusian"),
        Entry("tg", "Tajik"),
        Entry("sd", "Sindhi"),
        Entry("gu", "Gujarati"),
        Entry("am", "Amharic"),
        Entry("yi", "Yiddish"),
        Entry("lo", "Lao"),
        Entry("uz", "Uzbek"),
        Entry("fo", "Faroese"),
        Entry("ht", "Haitian Creole"),
        Entry("ps", "Pashto"),
        Entry("tk", "Turkmen"),
        Entry("nn", "Nynorsk"),
        Entry("mt", "Maltese"),
        Entry("sa", "Sanskrit"),
        Entry("lb", "Luxembourgish"),
        Entry("my", "Burmese (Myanmar)"),
        Entry("bo", "Tibetan"),
        Entry("tl", "Tagalog"),
        Entry("mg", "Malagasy"),
        Entry("as", "Assamese"),
        Entry("tt", "Tatar"),
        Entry("haw", "Hawaiian"),
        Entry("ln", "Lingala"),
        Entry("ha", "Hausa"),
        Entry("ba", "Bashkir"),
        Entry("jw", "Javanese"),
        Entry("su", "Sundanese"),
        Entry("yue", "Cantonese"),
    )

    private val byCode: Map<String, Entry> = all.associateBy { it.code }

    /** True if [code] is a language code the bundled whisper.cpp accepts. */
    fun isValid(code: String): Boolean = byCode.containsKey(code)

    /** Resolve a stored preference; unknown/blank codes are dropped. */
    fun byCodeOrNull(code: String?): Entry? =
        code?.takeIf { it.isNotBlank() }?.let { byCode[it] }

    data class Entry(val code: String, val displayName: String)
}
