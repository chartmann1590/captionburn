package com.charlesh.captionburn.data.feedback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SavedFeedbackIssue(
    val number: Int,
    val title: String,
    val dateCreated: Long,
    val htmlUrl: String,
    val status: String
)

private val Context.feedbackDataStore by preferencesDataStore(name = "captionburn_feedback")

@Singleton
class FeedbackRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val json: Json
) {
    private val ds = ctx.feedbackDataStore
    private val KEY_ISSUES = stringPreferencesKey("feedback_issues")

    val savedIssues: Flow<List<SavedFeedbackIssue>> = ds.data.map { prefs ->
        val jsonStr = prefs[KEY_ISSUES] ?: "[]"
        try {
            json.decodeFromString<List<SavedFeedbackIssue>>(jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveIssue(issue: SavedFeedbackIssue) {
        ds.edit { prefs ->
            val jsonStr = prefs[KEY_ISSUES] ?: "[]"
            val currentList = try {
                json.decodeFromString<List<SavedFeedbackIssue>>(jsonStr).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            currentList.removeAll { it.number == issue.number }
            currentList.add(0, issue)
            prefs[KEY_ISSUES] = json.encodeToString(currentList)
        }
    }

    suspend fun updateIssueStatus(number: Int, newStatus: String) {
        ds.edit { prefs ->
            val jsonStr = prefs[KEY_ISSUES] ?: "[]"
            val currentList = try {
                json.decodeFromString<List<SavedFeedbackIssue>>(jsonStr).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            val index = currentList.indexOfFirst { it.number == number }
            if (index != -1) {
                currentList[index] = currentList[index].copy(status = newStatus)
                prefs[KEY_ISSUES] = json.encodeToString(currentList)
            }
        }
    }
}
