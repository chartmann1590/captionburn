package com.charlesh.captionburn.data.feedback

import com.charlesh.captionburn.BuildConfig
import com.charlesh.captionburn.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class GithubIssueResponse(
    val number: Int,
    val html_url: String,
    val state: String,
    val title: String,
    val body: String? = null,
    val created_at: String
)

@Serializable
data class GithubCommentResponse(
    val id: Long,
    val body: String,
    val created_at: String,
    val user: GithubUser
)

@Serializable
data class GithubUser(
    val login: String
)

@Serializable
data class GithubContentResponse(
    val content: GithubContentInfo
)

@Serializable
data class GithubContentInfo(
    val download_url: String
)

@Serializable
private data class UploadContentRequest(
    val message: String,
    val content: String
)

@Singleton
class GithubIssueReporter @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun createIssue(title: String, body: String): Result<GithubIssueResponse> = withContext(ioDispatcher) {
        val token = BuildConfig.GITHUB_API_TOKEN
        val owner = BuildConfig.GITHUB_REPO_OWNER
        val repo = BuildConfig.GITHUB_REPO_NAME

        if (token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GitHub API token is not configured."))
        }

        val url = "https://api.github.com/repos/$owner/$repo/issues"

        val requestBodyJson = json.encodeToString(
            CreateIssueRequest(
                title = title,
                body = body
            )
        )

        val request = Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "CaptionBurn-Android")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val parsed = json.decodeFromString<GithubIssueResponse>(responseBody)
                    Result.success(parsed)
                } else {
                    Timber.e("GitHub API error: code=%d body=%s", response.code, responseBody)
                    Result.failure(Exception("GitHub API returned error code ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to GitHub API")
            Result.failure(e)
        }
    }

    suspend fun fetchIssue(number: Int): Result<GithubIssueResponse> = withContext(ioDispatcher) {
        val token = BuildConfig.GITHUB_API_TOKEN
        val owner = BuildConfig.GITHUB_REPO_OWNER
        val repo = BuildConfig.GITHUB_REPO_NAME

        if (token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GitHub API token is not configured."))
        }

        val url = "https://api.github.com/repos/$owner/$repo/issues/$number"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "CaptionBurn-Android")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val parsed = json.decodeFromString<GithubIssueResponse>(responseBody)
                    Result.success(parsed)
                } else {
                    Timber.e("GitHub API error: code=%d body=%s", response.code, responseBody)
                    Result.failure(Exception("GitHub API returned error code ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch GitHub issue status")
            Result.failure(e)
        }
    }

    suspend fun fetchComments(number: Int): Result<List<GithubCommentResponse>> = withContext(ioDispatcher) {
        val token = BuildConfig.GITHUB_API_TOKEN
        val owner = BuildConfig.GITHUB_REPO_OWNER
        val repo = BuildConfig.GITHUB_REPO_NAME

        if (token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GitHub API token is not configured."))
        }

        val url = "https://api.github.com/repos/$owner/$repo/issues/$number/comments"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "CaptionBurn-Android")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val parsed = json.decodeFromString<List<GithubCommentResponse>>(responseBody)
                    Result.success(parsed)
                } else {
                    Timber.e("GitHub API error: code=%d body=%s", response.code, responseBody)
                    Result.failure(Exception("GitHub API returned error code ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch GitHub issue comments")
            Result.failure(e)
        }
    }

    suspend fun addComment(number: Int, body: String): Result<GithubCommentResponse> = withContext(ioDispatcher) {
        val token = BuildConfig.GITHUB_API_TOKEN
        val owner = BuildConfig.GITHUB_REPO_OWNER
        val repo = BuildConfig.GITHUB_REPO_NAME

        if (token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GitHub API token is not configured."))
        }

        val url = "https://api.github.com/repos/$owner/$repo/issues/$number/comments"

        val requestBodyJson = json.encodeToString(
            AddCommentRequest(body = body)
        )

        val request = Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "CaptionBurn-Android")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val parsed = json.decodeFromString<GithubCommentResponse>(responseBody)
                    Result.success(parsed)
                } else {
                    Timber.e("GitHub API error: code=%d body=%s", response.code, responseBody)
                    Result.failure(Exception("GitHub API returned error code ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to post comment to GitHub")
            Result.failure(e)
        }
    }

    suspend fun uploadImage(filename: String, base64Content: String): Result<String> = withContext(ioDispatcher) {
        val token = BuildConfig.GITHUB_API_TOKEN
        val owner = BuildConfig.GITHUB_REPO_OWNER
        val repo = BuildConfig.GITHUB_REPO_NAME

        if (token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GitHub API token is not configured."))
        }

        val url = "https://api.github.com/repos/$owner/$repo/contents/feedback-assets/$filename"

        val requestBodyJson = json.encodeToString(
            UploadContentRequest(
                message = "Upload screenshot $filename",
                content = base64Content
            )
        )

        val request = Request.Builder()
            .url(url)
            .put(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "CaptionBurn-Android")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val parsed = json.decodeFromString<GithubContentResponse>(responseBody)
                    Result.success(parsed.content.download_url)
                } else {
                    Timber.e("GitHub Content API error: code=%d body=%s", response.code, responseBody)
                    Result.failure(Exception("GitHub Upload error code ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to upload image to GitHub")
            Result.failure(e)
        }
    }
}

@Serializable
private data class CreateIssueRequest(
    val title: String,
    val body: String
)

@Serializable
private data class AddCommentRequest(
    val body: String
)
