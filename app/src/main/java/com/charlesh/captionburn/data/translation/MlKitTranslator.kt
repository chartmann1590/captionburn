package com.charlesh.captionburn.data.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.charlesh.captionburn.data.settings.SettingsRepository
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class MlKitTranslator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    fun supportedLanguages(): List<TranslationLanguage> {
        val displayLocale = Locale.getDefault()
        return TranslateLanguage.getAllLanguages()
            .map { code ->
                TranslationLanguage(
                    code = code,
                    displayName = Locale.forLanguageTag(code)
                        .getDisplayLanguage(displayLocale)
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(displayLocale) else it.toString() },
                )
            }
            .sortedWith(compareBy<TranslationLanguage> { it.displayName }.thenBy { it.code })
    }

    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        whileDownloadingModel: suspend CoroutineScope.() -> Unit = {},
    ): String {
        if (text.isBlank()) return text
        val source = sourceLanguage.toMlKitLanguage()
        val target = targetLanguage.toMlKitLanguage()
        if (source == target) return text

        return withTranslator(source, target, whileDownloadingModel) { translator ->
            translator.translate(text).awaitResult()
        }
    }

    suspend fun translateBatch(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        whileDownloadingModel: suspend CoroutineScope.() -> Unit = {},
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        val source = sourceLanguage.toMlKitLanguage()
        val target = targetLanguage.toMlKitLanguage()
        if (source == target) return texts

        return withTranslator(source, target, whileDownloadingModel) { translator ->
            val total = texts.size
            texts.mapIndexed { index, text ->
                val translated = if (text.isBlank()) text else translator.translate(text).awaitResult()
                onProgress?.invoke(index + 1, total)
                translated
            }
        }
    }

    private suspend fun <T> withTranslator(
        sourceLanguage: String,
        targetLanguage: String,
        whileDownloadingModel: suspend CoroutineScope.() -> Unit,
        block: suspend (Translator) -> T,
    ): T {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
        val translator = Translation.getClient(options)
        return try {
            val wifiOnly = settingsRepository.wifiOnlyDownloads.first()
            val downloadConditions = DownloadConditions.Builder().apply {
                if (wifiOnly) requireWifi()
            }.build()
            if (wifiOnly && !isConnectedToWifi() && needsTranslationModelDownload(sourceLanguage, targetLanguage)) {
                throw TranslationException.ModelDownloadRequiresWifi
            }
            coroutineScope {
                val side = launch {
                    whileDownloadingModel()
                }
                try {
                    translator.downloadModelIfNeeded(downloadConditions).awaitResult()
                } finally {
                    side.cancel()
                }
            }
            block(translator)
        } catch (error: TranslationException) {
            throw error
        } catch (error: Exception) {
            throw TranslationException.ModelOrTranslationFailed(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                cause = error,
            )
        } finally {
            translator.close()
        }
    }

    private fun String.toMlKitLanguage(): String =
        TranslateLanguage.fromLanguageTag(normalizeLanguageTag())
            ?: throw TranslationException.UnsupportedLanguage(this)

    private fun String.normalizeLanguageTag(): String =
        lowercase()
            .replace('_', '-')
            .substringBefore('-')

    private suspend fun needsTranslationModelDownload(sourceLanguage: String, targetLanguage: String): Boolean {
        val manager = RemoteModelManager.getInstance()
        val sourceModel = TranslateRemoteModel.Builder(sourceLanguage).build()
        val targetModel = TranslateRemoteModel.Builder(targetLanguage).build()
        val sourceDownloaded = manager.isModelDownloaded(sourceModel).awaitResult()
        val targetDownloaded = manager.isModelDownloaded(targetModel).awaitResult()
        return !sourceDownloaded || !targetDownloaded
    }

    private fun isConnectedToWifi(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

data class TranslationLanguage(
    val code: String,
    val displayName: String,
)

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { error ->
        if (cont.isActive) cont.resumeWithException(error)
    }
    addOnCanceledListener {
        if (cont.isActive) cont.cancel()
    }
}

sealed class TranslationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class UnsupportedLanguage(language: String) :
        TranslationException("Unsupported language: $language")

    object ModelDownloadRequiresWifi : TranslationException(
        "Translation model download needs Wi-Fi. Connect to Wi-Fi or turn off Wi-Fi-only downloads in Settings."
    )

    class ModelOrTranslationFailed(
        sourceLanguage: String,
        targetLanguage: String,
        cause: Throwable,
    ) : TranslationException(
        message = "Translation failed from $sourceLanguage to $targetLanguage",
        cause = cause,
    )
}
