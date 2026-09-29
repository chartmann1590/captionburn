package com.charlesh.captionburn.ui.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.charlesh.captionburn.data.feedback.FeedbackRepository
import com.charlesh.captionburn.data.feedback.GithubCommentResponse
import com.charlesh.captionburn.data.feedback.GithubIssueReporter
import com.charlesh.captionburn.data.feedback.GithubIssueResponse
import com.charlesh.captionburn.data.feedback.GithubUser
import com.charlesh.captionburn.data.feedback.SavedFeedbackIssue
import com.charlesh.captionburn.data.settings.SettingsRepository
import com.charlesh.captionburn.data.transcription.DownloadEvent
import com.charlesh.captionburn.data.transcription.ModelDownloader
import com.charlesh.captionburn.data.transcription.WhisperModelCatalog
import com.charlesh.captionburn.ui.onboarding.WhisperModelChoice
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val context = mockk<Context>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val modelDownloader = mockk<ModelDownloader>(relaxed = true)
    private val githubIssueReporter = mockk<GithubIssueReporter>(relaxed = true)
    private val feedbackRepository = mockk<FeedbackRepository>(relaxed = true)

    private val installedModelFlow = MutableStateFlow<WhisperModelChoice?>(null)
    private val wifiOnlyFlow = MutableStateFlow(true)
    private val telemetryEnabledFlow = MutableStateFlow(true)
    private val transcriptionLanguageFlow = MutableStateFlow<String?>(null)
    private val savedIssuesFlow = MutableStateFlow<List<SavedFeedbackIssue>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Mock state flows from repositories
        every { settingsRepository.installedModel } returns installedModelFlow
        every { settingsRepository.wifiOnlyDownloads } returns wifiOnlyFlow
        every { settingsRepository.telemetryEnabled } returns telemetryEnabledFlow
        every { settingsRepository.transcriptionLanguage } returns transcriptionLanguageFlow
        every { feedbackRepository.savedIssues } returns savedIssuesFlow

        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.encodeToString(any(), any()) } returns "mocked_base64_string"

        // Explicitly stub methods returning inline Result class to prevent MockK ClassCastException
        coEvery { githubIssueReporter.createIssue(any(), any()) } returns Result.failure(Exception("Stub"))
        coEvery { githubIssueReporter.fetchIssue(any()) } returns Result.failure(Exception("Stub"))
        coEvery { githubIssueReporter.fetchComments(any()) } returns Result.failure(Exception("Stub"))
        coEvery { githubIssueReporter.addComment(any(), any()) } returns Result.failure(Exception("Stub"))
        coEvery { githubIssueReporter.uploadImage(any(), any()) } returns Result.failure(Exception("Stub"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(
            context = context,
            settingsRepository = settingsRepository,
            modelDownloader = modelDownloader,
            githubIssueReporter = githubIssueReporter,
            feedbackRepository = feedbackRepository
        )
    }

    @Test
    fun init_loadsInitialStateFromRepositories() = runTest {
        installedModelFlow.value = WhisperModelChoice.Tiny
        wifiOnlyFlow.value = false
        telemetryEnabledFlow.value = true
        savedIssuesFlow.value = listOf(
            SavedFeedbackIssue(1, "Test Bug", 123456789L, "https://github.com/test/1", "open")
        )

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.installedModel).isEqualTo(WhisperModelChoice.Tiny)
        assertThat(state.wifiOnly).isFalse()
        assertThat(state.telemetryEnabled).isTrue()
        assertThat(state.savedIssues).hasSize(1)
        assertThat(state.savedIssues[0].title).isEqualTo("Test Bug")
    }

    @Test
    fun init_loadsTranscriptionLanguageFromRepository() = runTest {
        transcriptionLanguageFlow.value = "de"

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.transcriptionLanguage).isEqualTo("de")
    }

    @Test
    fun setTranscriptionLanguage_persistsSelectedCode() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.setTranscriptionLanguage("fr")
        testScheduler.advanceUntilIdle()

        coVerify { settingsRepository.setTranscriptionLanguage("fr") }
    }

    @Test
    fun setTranscriptionLanguage_unknownCodeIsIgnored() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.setTranscriptionLanguage("xx")
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setTranscriptionLanguage(any<String>()) }
    }

    @Test
    fun setTranscriptionLanguage_nullIsIgnored() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.setTranscriptionLanguage(null)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setTranscriptionLanguage(any<String>()) }
    }

    @Test
    fun clearTranscriptionLanguage_resetsRepositoryValue() = runTest {
        transcriptionLanguageFlow.value = "de"
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.clearTranscriptionLanguage()
        testScheduler.advanceUntilIdle()

        coVerify { settingsRepository.setTranscriptionLanguage(null) }
    }

    @Test
    fun toggleWifiOnly_callsRepository() = runTest {
        wifiOnlyFlow.value = true
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.toggleWifiOnly()
        testScheduler.advanceUntilIdle()

        coVerify { settingsRepository.setWifiOnly(false) }
    }

    @Test
    fun toggleTelemetry_callsRepository() = runTest {
        telemetryEnabledFlow.value = true
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.toggleTelemetry()
        testScheduler.advanceUntilIdle()

        coVerify { settingsRepository.setTelemetryEnabled(false) }
    }

    @Test
    fun installOrSwitchModel_downloadsSuccessfully() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val spec = WhisperModelCatalog.byChoice(WhisperModelChoice.Tiny)
        coEvery { modelDownloader.isInstalled(spec) } returns false
        coEvery { modelDownloader.download(spec) } returns flowOf(
            DownloadEvent.Progress(50, 100),
            DownloadEvent.Complete(mockk(relaxed = true))
        )

        viewModel.installOrSwitchModel(WhisperModelChoice.Tiny)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.isDownloading).isFalse()
        assertThat(viewModel.state.value.downloadProgress).isEqualTo(1f)
        coVerify { settingsRepository.setInstalledModel(WhisperModelChoice.Tiny) }
    }

    @Test
    fun deleteInstalledModel_callsDownloaderAndRepository() = runTest {
        installedModelFlow.value = WhisperModelChoice.Tiny
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.deleteInstalledModel()
        testScheduler.advanceUntilIdle()

        coVerify { modelDownloader.delete(WhisperModelCatalog.byChoice(WhisperModelChoice.Tiny)) }
        coVerify { modelDownloader.deleteAll() }
        coVerify { settingsRepository.clearInstalledModel() }
    }

    @Test
    fun submitFeedback_success_savesIssue() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val mockResponse = GithubIssueResponse(
            number = 42,
            html_url = "https://github.com/test/issues/42",
            state = "open",
            title = "App crash on start",
            body = "some body",
            created_at = "2026-06-08T12:00:00Z"
        )
        coEvery { githubIssueReporter.createIssue(any(), any()) } returns Result.success(mockResponse)

        viewModel.submitFeedback(
            title = "App crash on start",
            description = "It crashed when I opened it.",
            name = "John Doe",
            email = "john@example.com",
            includeDeviceInfo = false
        )
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.isSendingFeedback).isFalse()
        assertThat(state.showFeedbackDialog).isFalse()
        assertThat(state.statusMessage).contains("Feedback submitted successfully!")

        coVerify {
            feedbackRepository.saveIssue(
                match {
                    it.number == 42 &&
                    it.title == "App crash on start" &&
                    it.htmlUrl == "https://github.com/test/issues/42" &&
                    it.status == "open"
                }
            )
        }
    }

    @Test
    fun submitFeedback_failure_showsError() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        coEvery { githubIssueReporter.createIssue(any(), any()) } returns Result.failure(Exception("Network error"))

        viewModel.submitFeedback(
            title = "App crash on start",
            description = "It crashed when I opened it.",
            name = "",
            email = "",
            includeDeviceInfo = false
        )
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.isSendingFeedback).isFalse()
        assertThat(state.errorMessage).isEqualTo("Network error")
    }

    @Test
    fun submitFeedback_withScreenshot_uploadsImage() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val mockUri = mockk<Uri>()
        viewModel.setFeedbackScreenshotUri(mockUri)

        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        val mockInputStream = ByteArrayInputStream("dummy_image_data".toByteArray())
        every { resolver.openInputStream(mockUri) } returns mockInputStream

        coEvery { githubIssueReporter.uploadImage(any(), "mocked_base64_string") } returns Result.success("https://github.com/img.png")
        coEvery { githubIssueReporter.createIssue(any(), any()) } returns Result.success(
            GithubIssueResponse(12, "url", "open", "title", "body", "date")
        )

        viewModel.submitFeedback(
            title = "Screenshot bug",
            description = "Look at this",
            name = "",
            email = "",
            includeDeviceInfo = false
        )
        testScheduler.advanceUntilIdle()

        coVerify { githubIssueReporter.uploadImage(any(), "mocked_base64_string") }
        coVerify { githubIssueReporter.createIssue("Screenshot bug", match { it.contains("https://github.com/img.png") }) }
        assertThat(viewModel.state.value.feedbackScreenshotUri).isNull()
    }

    @Test
    fun selectIssue_loadsCommentsAndStatus() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val issue = SavedFeedbackIssue(42, "Title", 1234L, "url", "open")
        val mockComments = listOf(
            GithubCommentResponse(1, "Fix it please", "date", GithubUser("developer"))
        )
        val mockDetails = GithubIssueResponse(42, "url", "closed", "Title", "body", "date")

        coEvery { githubIssueReporter.fetchIssue(42) } returns Result.success(mockDetails)
        coEvery { githubIssueReporter.fetchComments(42) } returns Result.success(mockComments)

        viewModel.selectIssue(issue)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.selectedIssue?.status).isEqualTo("closed")
        assertThat(state.issueComments).hasSize(1)
        assertThat(state.issueComments[0].body).isEqualTo("Fix it please")
        coVerify { feedbackRepository.updateIssueStatus(42, "closed") }
    }

    @Test
    fun postComment_success_reloadsComments() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val issue = SavedFeedbackIssue(42, "Title", 1234L, "url", "open")
        viewModel.selectIssue(issue)
        testScheduler.advanceUntilIdle()

        coEvery { githubIssueReporter.addComment(42, match { it.contains("My comment") }) } returns Result.success(
            GithubCommentResponse(2, "My comment", "date", GithubUser("me"))
        )
        coEvery { githubIssueReporter.fetchIssue(42) } returns Result.success(
            GithubIssueResponse(42, "url", "open", "Title", "body", "date")
        )
        coEvery { githubIssueReporter.fetchComments(42) } returns Result.success(emptyList())

        viewModel.postComment(42, "My comment")
        testScheduler.advanceUntilIdle()

        coVerify { githubIssueReporter.addComment(42, match { it.contains("My comment") }) }
        coVerify { githubIssueReporter.fetchComments(42) } // reloaded
    }
}
