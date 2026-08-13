package com.charlesh.captionburn.data.feedback

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
private data class UploadImageRequest(
    val filename: String,
    val contentBase64: String
)

@Serializable
private data class UploadImageResponse(
    val content: GithubContentInfo
)

@Serializable
data class GithubContentInfo(
    val download_url: String
)

/**
 * Talks to the cloudflare-worker/ feedback relay, not api.github.com directly. See
 * cloudflare-worker/src/index.ts, which holds the GitHub token server-side as a Worker
 * secret. Previously this embedded BuildConfig.GITHUB_API_TOKEN client-side as a Bearer
 * header, which shipped a real repo-write PAT in every release build (extractable from
 * the APK).
 */
@Singleton
class GithubIssueReporter @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val baseUrl = "https://captionburn-github-feedback.charles-h-hartmann1.workers.dev"

    suspend fun createIssue(title: String, body: String): Result<GithubIssueResponse> = withContext(ioDispatcher) {
        val requestBodyJson = json.encodeToString(
            CreateIssueRequest(
                title = title,
                body = body
            )
        )

        val request = Request.Builder()
            .url("$baseUrl/issue")
            .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
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
        val request = Request.Builder()
            .url("$baseUrl/issue/$number")
            .get()
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
        val request = Request.Builder()
            .url("$baseUrl/issue/$number/comments")
            .get()
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
        val requestBodyJson = json.encodeToString(
            AddCommentRequest(body = body)
        )

        val request = Request.Builder()
            .url("$baseUrl/issue/$number/comments")
            .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
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
        val requestBodyJson = json.encodeToString(
            UploadImageRequest(
                filename = filename,
                contentBase64 = base64Content
            )
        )

        val request = Request.Builder()
            .url("$baseUrl/upload-image")
            .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val parsed = json.decodeFromString<UploadImageResponse>(responseBody)
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
