package com.charlesh.captionburn.data.project

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.charlesh.captionburn.domain.model.Project
import com.charlesh.captionburn.domain.model.ProjectStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.projectDataStore by preferencesDataStore(name = "captionburn_projects")

@Singleton
class ProjectRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val json: Json,
) {
    private val ds = ctx.projectDataStore

    val projects: Flow<List<Project>> = ds.data.map { prefs ->
        prefs[KEY_PROJECTS_JSON]?.let(::decodeProjects).orEmpty()
    }

    suspend fun getProject(projectId: String): Project? =
        projects.first().firstOrNull { it.id == projectId }

    suspend fun createImportedProject(
        id: String,
        sourceUri: String,
        displayName: String,
    ): Project {
        val metadata = readVideoMetadata(sourceUri)
        val project = Project(
            id = id,
            sourceUri = sourceUri,
            displayName = displayName,
            durationMs = metadata.durationMs,
            widthPx = metadata.widthPx,
            heightPx = metadata.heightPx,
            createdAt = System.currentTimeMillis(),
            status = ProjectStatus.Imported,
            transcript = null,
        )
        upsertProject(project)
        return project
    }

    suspend fun updateStyle(projectId: String, update: (Project) -> Project) {
        val items = projects.first().toMutableList()
        val index = items.indexOfFirst { it.id == projectId }
        if (index == -1) return
        items[index] = update(items[index])
        persist(items)
    }

    suspend fun updateProject(projectId: String, update: (Project) -> Project) {
        updateStyle(projectId, update)
    }

    suspend fun deleteProject(projectId: String) {
        val items = projects.first()
        val project = items.firstOrNull { it.id == projectId } ?: return
        persist(items.filterNot { it.id == projectId })
        cleanupPrivateProjectFiles(project)
    }

    suspend fun upsertProject(project: Project) {
        val items = projects.first().toMutableList()
        val index = items.indexOfFirst { it.id == project.id }
        if (index == -1) {
            items += project
        } else {
            items[index] = project
        }
        persist(items)
    }

    private suspend fun persist(items: List<Project>) {
        ds.edit { prefs ->
            prefs[KEY_PROJECTS_JSON] =
                json.encodeToString(ListSerializer(Project.serializer()), items)
        }
    }

    private fun decodeProjects(raw: String): List<Project> =
        runCatching {
            json.decodeFromString(ListSerializer(Project.serializer()), raw)
        }.getOrDefault(emptyList())

    private fun cleanupPrivateProjectFiles(project: Project) {
        deletePrivateFileUri(
            rawUri = project.sourceUri,
            allowedRoot = File(ctx.filesDir, "imports"),
        )
        runCatching { File(ctx.filesDir, "audio/${project.id}.wav").delete() }
        runCatching { File(ctx.cacheDir, "subtitles/${project.id}").deleteRecursively() }
        runCatching {
            File(ctx.cacheDir, "exports")
                .listFiles { file -> file.name.startsWith("${project.id}_") }
                ?.forEach { it.delete() }
        }
    }

    private fun deletePrivateFileUri(rawUri: String, allowedRoot: File) {
        runCatching {
            val uri = Uri.parse(rawUri)
            if (uri.scheme != "file") return
            val path = uri.path ?: return
            val file = File(path)
            if (file.isInside(allowedRoot)) {
                file.delete()
            }
        }
    }

    private fun File.isInside(root: File): Boolean {
        val candidate = canonicalFile
        val base = root.canonicalFile
        return candidate.path == base.path ||
            candidate.path.startsWith(base.path + File.separator)
    }

    private fun readVideoMetadata(sourceUri: String): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, Uri.parse(sourceUri))
            val durationMs = retriever.extractLong(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?: DEFAULT_DURATION_MS
            val width = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?: DEFAULT_WIDTH_PX
            val height = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?: DEFAULT_HEIGHT_PX
            val rotation = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
            if (rotation == 90 || rotation == 270) {
                VideoMetadata(durationMs = durationMs, widthPx = height, heightPx = width)
            } else {
                VideoMetadata(durationMs = durationMs, widthPx = width, heightPx = height)
            }
        } catch (_: Throwable) {
            VideoMetadata(
                durationMs = DEFAULT_DURATION_MS,
                widthPx = DEFAULT_WIDTH_PX,
                heightPx = DEFAULT_HEIGHT_PX,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.extractLong(key: Int): Long? =
        extractMetadata(key)?.toLongOrNull()?.takeIf { it > 0L }

    private fun MediaMetadataRetriever.extractInt(key: Int): Int? =
        extractMetadata(key)?.toIntOrNull()?.takeIf { it > 0 }

    private companion object {
        const val DEFAULT_DURATION_MS = 60_000L
        const val DEFAULT_WIDTH_PX = 1920
        const val DEFAULT_HEIGHT_PX = 1080
        val KEY_PROJECTS_JSON = stringPreferencesKey("projects_json")
    }
}

private data class VideoMetadata(
    val durationMs: Long,
    val widthPx: Int,
    val heightPx: Int,
)
