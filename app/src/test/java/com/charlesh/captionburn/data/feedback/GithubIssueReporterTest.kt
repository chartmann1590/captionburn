package com.charlesh.captionburn.data.feedback

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GithubIssueReporterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun createReporter(
        responseCode: Int,
        responseBody: String,
        assertionBlock: ((Request) -> Unit)? = null
    ): GithubIssueReporter {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                assertionBlock?.invoke(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message(if (responseCode in 200..299) "OK" else "Error")
                    .body(responseBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        return GithubIssueReporter(
            okHttpClient = client,
            json = json,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun createIssue_success() = runTest {
        val jsonResponse = """
            {
                "number": 101,
                "html_url": "https://github.com/test/issues/101",
                "state": "open",
                "title": "Bug Report",
                "body": "App crashed",
                "created_at": "2026-06-08T12:00:00Z"
            }
        """.trimIndent()

        val reporter = createReporter(201, jsonResponse) { request ->
            assertThat(request.url.toString()).isEqualTo("https://captionburn-github-feedback.charles-h-hartmann1.workers.dev/issue")
            assertThat(request.method).isEqualTo("POST")
        }

        val result = reporter.createIssue("Bug Report", "App crashed")
        assertThat(result.isSuccess).isTrue()
        
        val issue = result.getOrThrow()
        assertThat(issue.number).isEqualTo(101)
        assertThat(issue.html_url).isEqualTo("https://github.com/test/issues/101")
        assertThat(issue.state).isEqualTo("open")
        assertThat(issue.title).isEqualTo("Bug Report")
    }

    @Test
    fun createIssue_apiError_returnsFailure() = runTest {
        val reporter = createReporter(401, "{\"message\": \"Bad credentials\"}")
        val result = reporter.createIssue("Bug Report", "App crashed")
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("GitHub API returned error code 401")
    }

    @Test
    fun fetchIssue_success() = runTest {
        val jsonResponse = """
            {
                "number": 101,
                "html_url": "https://github.com/test/issues/101",
                "state": "closed",
                "title": "Bug Report",
                "created_at": "2026-06-08T12:00:00Z"
            }
        """.trimIndent()

        val reporter = createReporter(200, jsonResponse) { request ->
            assertThat(request.url.toString()).isEqualTo("https://captionburn-github-feedback.charles-h-hartmann1.workers.dev/issue/101")
            assertThat(request.method).isEqualTo("GET")
        }

        val result = reporter.fetchIssue(101)
        assertThat(result.isSuccess).isTrue()
        
        val issue = result.getOrThrow()
        assertThat(issue.number).isEqualTo(101)
        assertThat(issue.state).isEqualTo("closed")
    }

    @Test
    fun fetchComments_success() = runTest {
        val jsonResponse = """
            [
                {
                    "id": 12345,
                    "body": "We are looking into it",
                    "created_at": "2026-06-08T13:00:00Z",
                    "user": {
                        "login": "dev1"
                    }
                }
            ]
        """.trimIndent()

        val reporter = createReporter(200, jsonResponse) { request ->
            assertThat(request.url.toString()).isEqualTo("https://captionburn-github-feedback.charles-h-hartmann1.workers.dev/issue/101/comments")
            assertThat(request.method).isEqualTo("GET")
        }

        val result = reporter.fetchComments(101)
        assertThat(result.isSuccess).isTrue()
        
        val comments = result.getOrThrow()
        assertThat(comments).hasSize(1)
        assertThat(comments[0].id).isEqualTo(12345L)
        assertThat(comments[0].body).isEqualTo("We are looking into it")
        assertThat(comments[0].user.login).isEqualTo("dev1")
    }

    @Test
    fun addComment_success() = runTest {
        val jsonResponse = """
            {
                "id": 54321,
                "body": "Working on it",
                "created_at": "2026-06-08T14:00:00Z",
                "user": {
                    "login": "user1"
                }
            }
        """.trimIndent()

        val reporter = createReporter(201, jsonResponse) { request ->
            assertThat(request.url.toString()).isEqualTo("https://captionburn-github-feedback.charles-h-hartmann1.workers.dev/issue/101/comments")
            assertThat(request.method).isEqualTo("POST")
        }

        val result = reporter.addComment(101, "Working on it")
        assertThat(result.isSuccess).isTrue()
        
        val comment = result.getOrThrow()
        assertThat(comment.id).isEqualTo(54321L)
        assertThat(comment.body).isEqualTo("Working on it")
    }

    @Test
    fun uploadImage_success() = runTest {
        val jsonResponse = """
            {
                "content": {
                    "name": "screenshot.jpg",
                    "path": "feedback-assets/screenshot.jpg",
                    "download_url": "https://raw.githubusercontent.com/test/screenshot.jpg"
                }
            }
        """.trimIndent()

        val reporter = createReporter(201, jsonResponse) { request ->
            assertThat(request.url.toString()).isEqualTo("https://captionburn-github-feedback.charles-h-hartmann1.workers.dev/upload-image")
            assertThat(request.method).isEqualTo("POST")
        }

        val result = reporter.uploadImage("screenshot.jpg", "base64String")
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).isEqualTo("https://raw.githubusercontent.com/test/screenshot.jpg")
    }

    @Test
    fun networkException_returnsFailure() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                throw IOException("No internet connection")
            }
            .build()

        val reporter = GithubIssueReporter(
            okHttpClient = client,
            json = json,
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = reporter.createIssue("title", "body")
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }
}
