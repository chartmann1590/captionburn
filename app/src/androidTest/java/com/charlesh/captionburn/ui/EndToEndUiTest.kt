package com.charlesh.captionburn.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.charlesh.captionburn.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EndToEndUiTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var testVideoFile: File

    @Before
    fun setup() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        // 1. Clear application state completely to force onboarding
        File(targetContext.filesDir, "datastore").deleteRecursively()
        File(targetContext.filesDir, "models").deleteRecursively()
        File(targetContext.filesDir, "imports").deleteRecursively()
        File(targetContext.filesDir, "audio").deleteRecursively()
        File(targetContext.cacheDir, "subtitles").deleteRecursively()
        File(targetContext.cacheDir, "exports").deleteRecursively()

        val parent = targetContext.filesDir.parentFile
        if (parent != null) {
            File(parent, "databases").deleteRecursively()
            File(parent, "shared_prefs").deleteRecursively()
        }

        // 2. Extract our bundled test video fixture to local cache
        testVideoFile = File(targetContext.cacheDir, "jfk_speech_sample.mp4")
        testContext.assets.open("jfk_speech_sample.mp4").use { input ->
            testVideoFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    @Test
    fun testFullAppEndToEndFlow() {
        // Launch the activity manually after setup
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->

            // ------------------------------------------------------------
            // Phase 1: Onboarding and Speech Model Download
            // ------------------------------------------------------------
            // Check onboarding screen elements
            composeTestRule.onNodeWithText("Captions, on-device.").assertIsDisplayed()

            // Select the 'Tiny' model (75 MB, fastest for testing)
            composeTestRule.onNodeWithText("Tiny").performClick()

            // Toggle 'Wi-Fi only' to false to allow downloads without restriction during test runs
            composeTestRule.onNodeWithText("Wi-Fi only").performClick()

            // Click Continue to start the Whisper model download
            composeTestRule.onNodeWithText("Continue").performClick()

            // Wait for download to finish and auto-navigate to the Home screen (up to 3 minutes)
            composeTestRule.waitUntil(180_000) {
                composeTestRule.onAllNodesWithText("Your projects").fetchSemanticsNodes().isNotEmpty()
            }

            try {
                composeTestRule.onRoot(useUnmergedTree = true).printToLog("END_TO_END_TEST")
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // ------------------------------------------------------------
            // Phase 2: Video Import & Transcription
            // ------------------------------------------------------------
            // Initialize Espresso Intents for activity stubbing
            Intents.init()
            try {
                val resultData = Intent().apply {
                    data = Uri.fromFile(testVideoFile)
                }
                val result = Instrumentation.ActivityResult(Activity.RESULT_OK, resultData)

                // Stub all photo picker intent contracts
                intending(hasAction(Intent.ACTION_GET_CONTENT)).respondWith(result)
                intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(result)
                intending(hasAction("android.provider.action.PICK_IMAGES")).respondWith(result)

                // Wait for the Import video button to be displayed
                composeTestRule.waitUntil(10_000) {
                    composeTestRule.onAllNodesWithText("Import video", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                // Click Import Video button to launch the picker
                composeTestRule.onNodeWithText("Import video", useUnmergedTree = true).performClick()

                // Wait for transition to the Editor screen
                composeTestRule.waitUntil(15_000) {
                    composeTestRule.onAllNodesWithText("Editor").fetchSemanticsNodes().isNotEmpty()
                }
            } finally {
                // Always release Intents
                Intents.release()
            }

            // Wait for automated local audio extraction and transcription pipeline to complete
            // This is finished when the transcribing progress box disappears and "Export with captions" enables
            composeTestRule.waitUntil(180_000) {
                composeTestRule.onAllNodes(
                    hasText("Export with captions") and isEnabled()
                ).fetchSemanticsNodes().isNotEmpty()
            }

            // ------------------------------------------------------------
            // Phase 3: Transcript Editing
            // ------------------------------------------------------------
            // Edit the text of the transcribed segment
            composeTestRule.onNode(hasSetTextAction()).performTextReplacement("This is a verified test transcript.")

            // ------------------------------------------------------------
            // Phase 4: Styling customizer
            // ------------------------------------------------------------
            composeTestRule.onNodeWithText("Style controls").performClick()

            // Click various choices in the bottom sheet
            composeTestRule.onAllNodesWithText("Gold").onFirst().performClick() // text color
            composeTestRule.onNodeWithText("Top").performClick() // positioning
            composeTestRule.onNodeWithText("Grow word").performClick() // highlight animation mode
            composeTestRule.onNodeWithText("Done").performClick() // close sheet

            // ------------------------------------------------------------
            // Phase 5: Translate to multiple languages
            // ------------------------------------------------------------
            // Open target language dropdown menu
            composeTestRule.onNodeWithText("No translation").performClick()

            // Click Spanish (ES) option
            composeTestRule.onNodeWithText("Spanish (ES)").performClick()

            // Turn on Both (dual-language) captions
            composeTestRule.onNodeWithText("Both").performClick()

            // ------------------------------------------------------------
            // Phase 6: Export & Hard-burn Rendering
            // ------------------------------------------------------------
            composeTestRule.onNodeWithText("Export with captions").performClick()

            // The pipeline will run: translating, generating subtitles, burning in pixels, publishing to MediaStore.
            // Wait for completion (enabled "Save to device" button)
            composeTestRule.waitUntil(300_000) {
                composeTestRule.onAllNodes(
                    hasText("Save to device") and isEnabled()
                ).fetchSemanticsNodes().isNotEmpty()
            }

            // Click Done / Save to device to go back to Home
            composeTestRule.onNodeWithText("Save to device").performClick()

            // Wait for transition back to the Home screen
            composeTestRule.waitUntil(15_000) {
                composeTestRule.onAllNodesWithText("Your projects").fetchSemanticsNodes().isNotEmpty()
            }

            // ------------------------------------------------------------
            // Phase 7: Project Deletion
            // ------------------------------------------------------------
            // We should see our project row now. Let's delete it.
            composeTestRule.onNodeWithContentDescription("Delete project").performClick()
            composeTestRule.onNodeWithText("Delete").performClick()

            // Verify project list returns to empty state
            composeTestRule.onNodeWithText("No projects yet").assertIsDisplayed()

            // ------------------------------------------------------------
            // Phase 8: Settings interaction
            // ------------------------------------------------------------
            // Navigate to Settings
            composeTestRule.onNodeWithContentDescription("Settings").performClick()

            // Verify settings elements are shown
            composeTestRule.onNodeWithText("Model management").assertIsDisplayed()

            // Toggle Wi-Fi only downloads switch
            composeTestRule.onNodeWithText("Wi-Fi only downloads").performClick()

            // Go back to Home
            composeTestRule.onNodeWithContentDescription("Back").performClick()

            // Verify we are back on the Home screen
            composeTestRule.onNodeWithText("Your projects").assertIsDisplayed()
        }
    }
}
