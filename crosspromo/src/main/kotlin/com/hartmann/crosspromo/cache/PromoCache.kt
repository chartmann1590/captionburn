package com.hartmann.crosspromo.cache

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hartmann.crosspromo.model.CachedRecommendations
import com.hartmann.crosspromo.model.PromoResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.promoDataStore by preferencesDataStore(name = "hartmann_crosspromo")

/**
 * Stale-while-revalidate cache of recommendations keyed by source package +
 * placement. Backed by DataStore (no Room needed for a single JSON blob).
 */
class PromoCache(private val context: Context) {

    private fun keyFor(sourcePackage: String, placement: String) =
        stringPreferencesKey("rec_${sourcePackage}_$placement")

    private fun generatedAtKey(sourcePackage: String, placement: String) =
        longPreferencesKey("gen_${sourcePackage}_$placement")

    private fun expiresAtKey(sourcePackage: String, placement: String) =
        longPreferencesKey("exp_${sourcePackage}_$placement")

    suspend fun get(sourcePackage: String, placement: String): CachedRecommendations? {
        val prefs = context.promoDataStore.data.first()
        val raw = prefs[keyFor(sourcePackage, placement)] ?: return null
        val generatedAt = prefs[generatedAtKey(sourcePackage, placement)] ?: return null
        val expiresAt = prefs[expiresAtKey(sourcePackage, placement)] ?: return null
        val response = PromoResponse.fromJson(raw) ?: return null
        return CachedRecommendations(
            response = response,
            generatedAtMs = generatedAt,
            expiresAtMs = expiresAt,
            sourcePackage = sourcePackage,
            placement = placement,
        )
    }

    suspend fun put(cached: CachedRecommendations) {
        context.promoDataStore.edit { prefs ->
            prefs[keyFor(cached.sourcePackage, cached.placement)] =
                PromoResponse.json.encodeToString(PromoResponse.serializer(), cached.response)
            prefs[generatedAtKey(cached.sourcePackage, cached.placement)] = cached.generatedAtMs
            prefs[expiresAtKey(cached.sourcePackage, cached.placement)] = cached.expiresAtMs
        }
    }

    suspend fun clear(sourcePackage: String, placement: String) {
        context.promoDataStore.edit { prefs ->
            prefs.remove(keyFor(sourcePackage, placement))
            prefs.remove(generatedAtKey(sourcePackage, placement))
            prefs.remove(expiresAtKey(sourcePackage, placement))
        }
    }
}
