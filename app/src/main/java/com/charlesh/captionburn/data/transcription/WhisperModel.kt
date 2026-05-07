package com.charlesh.captionburn.data.transcription

import com.charlesh.captionburn.ui.onboarding.WhisperModelChoice

/**
 * Catalog of GGML Whisper variants we support.
 *
 * URLs and approximate sizes mirror the official whisper.cpp HuggingFace
 * mirror at https://huggingface.co/ggerganov/whisper.cpp. Sizes are exact at
 * time of writing; the downloader compares to the HEAD content-length of the
 * server response, not to this constant, so a future re-quant won't break us.
 */
data class WhisperModelSpec(
    val id: String,
    val choice: WhisperModelChoice,
    val filename: String,
    val url: String,
    val approxSizeBytes: Long,
    /** Expected SHA-256 of the model bytes (matches Hugging Face LFS oid for the file). */
    val sha256: String?,
)

object WhisperModelCatalog {

    private const val BASE_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val Tiny = WhisperModelSpec(
        id = "tiny",
        choice = WhisperModelChoice.Tiny,
        filename = "ggml-tiny.bin",
        url = "$BASE_URL/ggml-tiny.bin",
        approxSizeBytes = 77_691_713L,
        sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
    )

    val Base = WhisperModelSpec(
        id = "base",
        choice = WhisperModelChoice.Base,
        filename = "ggml-base.bin",
        url = "$BASE_URL/ggml-base.bin",
        approxSizeBytes = 147_951_465L,
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
    )

    val Small = WhisperModelSpec(
        id = "small",
        choice = WhisperModelChoice.Small,
        filename = "ggml-small.bin",
        url = "$BASE_URL/ggml-small.bin",
        approxSizeBytes = 487_601_967L,
        sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
    )

    val all = listOf(Tiny, Base, Small)

    fun byChoice(c: WhisperModelChoice): WhisperModelSpec = when (c) {
        WhisperModelChoice.Tiny -> Tiny
        WhisperModelChoice.Base -> Base
        WhisperModelChoice.Small -> Small
    }
}
