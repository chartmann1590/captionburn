package com.charlesh.captionburn.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProjectStatus { Imported, Transcribing, Ready, Translating, Burning, Done, Failed }

@Serializable
data class Project(
    val id: String,
    val sourceUri: String,
    val displayName: String,
    val durationMs: Long,
    val widthPx: Int,
    val heightPx: Int,
    val createdAt: Long,
    val status: ProjectStatus,
    val transcript: Transcript? = null,
    val style: CaptionStyle = CaptionStyle(),
    val outputUri: String? = null,
    val errorMessage: String? = null,
)
