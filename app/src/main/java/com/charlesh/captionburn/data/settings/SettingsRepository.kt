package com.charlesh.captionburn.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.charlesh.captionburn.ui.onboarding.WhisperModelChoice
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "captionburn_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val ds = ctx.dataStore

    val installedModel: Flow<WhisperModelChoice?> = ds.data.map { prefs ->
        prefs[KEY_INSTALLED_MODEL]?.let { runCatching { WhisperModelChoice.valueOf(it) }.getOrNull() }
    }

    val wifiOnlyDownloads: Flow<Boolean> = ds.data.map { it[KEY_WIFI_ONLY] ?: true }

    val onboardingComplete: Flow<Boolean> = ds.data.map { it[KEY_ONBOARDED] ?: false }

    suspend fun setInstalledModel(choice: WhisperModelChoice) {
        ds.edit { it[KEY_INSTALLED_MODEL] = choice.name }
    }

    suspend fun clearInstalledModel() {
        ds.edit { it.remove(KEY_INSTALLED_MODEL) }
    }

    suspend fun setWifiOnly(value: Boolean) {
        ds.edit { it[KEY_WIFI_ONLY] = value }
    }

    suspend fun setOnboardingComplete() {
        ds.edit { it[KEY_ONBOARDED] = true }
    }

    private companion object {
        val KEY_INSTALLED_MODEL = stringPreferencesKey("installed_model")
        val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarding_complete")
    }
}
