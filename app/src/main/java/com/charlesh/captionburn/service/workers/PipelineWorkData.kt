package com.charlesh.captionburn.service.workers

import androidx.work.Data

object PipelineWorkData {
    const val KEY_PROJECT_ID = "project_id"
    const val KEY_STAGE = "stage"
    const val KEY_PROGRESS = "progress"
    const val KEY_ERROR_MESSAGE = "error_message"
    const val KEY_RETRYABLE = "retryable"
    const val KEY_ASS_PATH = "ass_path"
    const val KEY_OUTPUT_URI = "output_uri"

    fun input(projectId: String): Data = Data.Builder()
        .putString(KEY_PROJECT_ID, projectId)
        .build()

    fun progress(stage: String, progress: Float): Data = Data.Builder()
        .putString(KEY_STAGE, stage)
        .putFloat(KEY_PROGRESS, progress.coerceIn(0f, 1f))
        .build()

    fun failure(message: String, retryable: Boolean): Data = Data.Builder()
        .putString(KEY_ERROR_MESSAGE, message)
        .putBoolean(KEY_RETRYABLE, retryable)
        .build()
}
