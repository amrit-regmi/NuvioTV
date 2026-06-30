package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.reco.RecoBackend
import com.nuvio.tv.core.subtitle.SubtitleWarmer
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.DeviceProfileDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.SubtitleRepository
import com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class SubtitleRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepositoryImpl,
    private val subtitleWarmer: SubtitleWarmer,
    private val deviceProfileDataStore: DeviceProfileDataStore
) : SubtitleRepository {

    companion object {
        private const val TAG = "SubtitleRepository"
        private const val PER_ADDON_TIMEOUT_MS = 20_000L
        private const val BEST_PER_ADDON_TIMEOUT_MS = 8_000L
    }

    override suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)?
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val requestType = canonicalSubtitleType(type)
        // For series, the backend best-per-lang id is the season/episode-scoped videoId
        // (imdb:s:e / imdb-sNNeNN form) — the same id the full /subtitles list uses below.
        val bestId = if (requestType == "series" && videoId != null) videoId else id

        // Get installed addons up-front; they're needed by both the best-per-lang and full paths.
        val addons = try {
            addonRepository.getInstalledAddons().first().enabledAddons()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get installed addons", e)
            emptyList()
        }

        // #88 (api_bridge "best-subtitle-per-language"): when the built-in subtitle provider is ON,
        // ask OUR backend for the prewarmed BEST subtitle per language (≤3, already moviehash/
        // release-matched and SERVER-ORDERED primary→secondary→en) via `/subtitles/best`. Per the
        // frozen contract, when ON the player exposes ONLY these ≤3 entries — NOT the full
        // variant/language list — so they bundle at the initial prepare and every switch is pure
        // track-selection. A true miss (endpoint absent / empty) falls through to the full
        // `/subtitles` list below so the user is never left with nothing.
        val subtitleProviderEnabled = try {
            deviceProfileDataStore.getBuiltinSubtitlesEnabled()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read useBuiltinSubtitles flag; defaulting to enabled", e)
            true
        }
        if (subtitleProviderEnabled) {
            val best = fetchBestPerLanguageSubtitles(addons, requestType, bestId)
            if (best.isNotEmpty()) {
                Log.d(TAG, "Best-per-lang HIT: ${best.size} langs=${best.map { it.lang }} for id=$bestId")
                return@withContext best
            }
            Log.d(TAG, "Best-per-lang MISS for id=$bestId → falling through to full /subtitles list")
        }

        // The warmer pre-fetches ONLY the first subtitle addon (which, in private mode, is the
        // built-in catalog-addon prepended to the list). Treat its result as a contribution from
        // that one addon — NOT a complete answer — so we still query the profile's other subtitle
        // addons (e.g. OpenSubtitles) and merge. A non-empty warm hit must NOT short-circuit
        // the whole fetch, otherwise only built-in subtitles would appear.
        val warmSubtitles: List<Subtitle> = if (filename != null) {
            subtitleWarmer.awaitWarm(filename, videoSize)?.also { cached ->
                Log.d(TAG, "Subtitle warm hit: ${cached.size} subs for filename=$filename")
            }.orEmpty()
        } else {
            emptyList()
        }
        // displayNames already covered by the warm result — skip re-fetching those addons.
        val warmedAddonNames = warmSubtitles.mapNotNull { it.addonName }.toSet()

        val startedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Fetching subtitles for type=$requestType, id=$id, videoId=$videoId")

        if (addons.isEmpty()) {
            return@withContext warmSubtitles
        }

        // Filter addons that support subtitles resource
        val subtitleAddons = addons.filter { addon ->
            addon.resources.any { resource ->
                isSubtitleResource(resource.name) && supportsType(resource, requestType, id)
            }
        }

        Log.d(TAG, "Found ${subtitleAddons.size} subtitle addons: ${subtitleAddons.map { it.name }}")

        // Only fetch addons not already covered by the warm result.
        val addonsToFetch = subtitleAddons.filter { it.displayName !in warmedAddonNames }

        if (addonsToFetch.isEmpty()) {
            return@withContext warmSubtitles
        }

        val total = addonsToFetch.size
        val completedCount = AtomicInteger(0)
        onProgress?.invoke(0, total, null)

        // Fetch subtitles from all addons in parallel
        val fetched = coroutineScope {
            addonsToFetch.map { addon ->
                async {
                    val addonStartMs = System.currentTimeMillis()
                    val subtitles = withTimeoutOrNull(PER_ADDON_TIMEOUT_MS) {
                        fetchSubtitlesFromAddon(addon, type, id, videoId, videoHash, videoSize, filename)
                    }
                    onProgress?.invoke(completedCount.incrementAndGet(), total, addon.displayName)
                    if (subtitles == null) {
                        Log.w(
                            TAG,
                            "Subtitle fetch timed out for addon=${addon.name} after ${PER_ADDON_TIMEOUT_MS}ms"
                        )
                        emptyList()
                    } else {
                        Log.d(
                            TAG,
                            "Subtitle fetch done for addon=${addon.name} count=${subtitles.size} in ${System.currentTimeMillis() - addonStartMs}ms"
                        )
                        subtitles
                    }
                }
            }.awaitAll().flatten()
        }
        // Merge warm (built-in) + freshly fetched (other addons), de-duplicating by id+url.
        val result = (warmSubtitles + fetched).distinctBy { "${it.id}|${it.url}" }
        Log.d(
            TAG,
            "Subtitle fetch completed total=${result.size} (warm=${warmSubtitles.size} fetched=${fetched.size}) fromAddons=${addonsToFetch.size} in ${System.currentTimeMillis() - startedAtMs}ms"
        )
        result
    }

    /**
     * #88 — fetch the BEST subtitle per language from OUR backend's catalog-addon
     * `/subtitles/best/{type}/{id}.json`. Iterates the backend (reco-host) subtitle addons; the
     * FIRST one that returns a non-empty `best` array wins, and its server-side
     * primary→secondary→en order is preserved verbatim (no client re-sort). `lang` in the response
     * is 3-letter ISO-639-2 (eng/swe/fin) and is mapped to our normalized codes for labels +
     * track selection. Returns empty on a true miss so the caller falls through to the full list.
     */
    private suspend fun fetchBestPerLanguageSubtitles(
        addons: List<Addon>,
        requestType: String,
        bestId: String
    ): List<Subtitle> {
        // Best-per-lang is a private-backend feature: only OUR catalog-addon (reco host) serves it.
        val backendAddons = addons.filter { addon ->
            addon.baseUrl.contains(RecoBackend.host, ignoreCase = true) &&
                addon.resources.any { resource ->
                    isSubtitleResource(resource.name) && supportsType(resource, requestType, bestId)
                }
        }
        if (backendAddons.isEmpty()) return emptyList()

        for (addon in backendAddons) {
            val bestUrl = buildBestSubtitleUrl(addon, requestType, bestId)
            Log.d(TAG, "Fetching best-per-lang from ${addon.name}: $bestUrl")
            val items = withTimeoutOrNull(BEST_PER_ADDON_TIMEOUT_MS) {
                when (val result = safeApiCall(context) { api.getBestSubtitles(bestUrl) }) {
                    is NetworkResult.Success -> result.data.best.orEmpty()
                    is NetworkResult.Error -> {
                        Log.w(TAG, "Best-per-lang fetch failed for ${addon.name}: code=${result.code} message=${result.message}")
                        emptyList()
                    }
                    NetworkResult.Loading -> emptyList()
                }
            }.orEmpty()

            if (items.isEmpty()) continue

            // First backend addon that returns a best set wins — keep its server order verbatim.
            return items.mapIndexedNotNull { index, dto ->
                val url = dto.url.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val normalizedLang = normalizeIso6392ToAppCode(dto.lang)
                Subtitle(
                    id = "${addon.displayName}_best_${normalizedLang}_$index",
                    url = url,
                    lang = normalizedLang,
                    addonName = addon.displayName,
                    addonLogo = addon.logo
                )
            }
        }
        return emptyList()
    }

    /** Builds `{base}/subtitles/best/{type}/{id}.json`, preserving any base query string. */
    private fun buildBestSubtitleUrl(addon: Addon, requestType: String, bestId: String): String {
        val rawBaseUrl = addon.baseUrl.trimEnd('/')
        val queryStart = rawBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) rawBaseUrl.substring(0, queryStart).trimEnd('/') else rawBaseUrl
        val baseQuery = if (queryStart >= 0) rawBaseUrl.substring(queryStart) else ""
        return "$basePath/subtitles/best/$requestType/$bestId.json$baseQuery"
    }

    /**
     * Maps the contract's 3-letter ISO-639-2 `lang` (eng/swe/fin/…) to our normalized app code,
     * mirroring the mobile mapping. Falls back to PlayerSubtitleUtils normalization for any other
     * value so non-shorthand codes still resolve to a real language.
     */
    private fun normalizeIso6392ToAppCode(rawLang: String): String {
        return when (rawLang.trim().lowercase()) {
            "eng" -> "en"
            "swe" -> "sv"
            "fin" -> "fi"
            else -> PlayerSubtitleUtils.normalizeLanguageCode(rawLang).ifBlank { rawLang }
        }
    }

    private fun canonicalSubtitleType(type: String): String {
        return if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()
    }
    
    private fun supportsType(resource: com.nuvio.tv.domain.model.AddonResource, type: String, id: String): Boolean {
        // Check if type is supported
        if (resource.types.isNotEmpty() && resource.types.none { it.equals(type, ignoreCase = true) }) {
            return false
        }
        
        // Check if id prefix is supported
        val idPrefixes = resource.idPrefixes
        if (idPrefixes != null && idPrefixes.isNotEmpty()) {
            return idPrefixes.any { prefix -> id.startsWith(prefix) }
        }
        
        return true
    }

    private fun isSubtitleResource(name: String): Boolean {
        return name.equals("subtitles", ignoreCase = true) ||
            name.equals("subtitle", ignoreCase = true)
    }
    
    private suspend fun fetchSubtitlesFromAddon(
        addon: Addon,
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): List<Subtitle> {
        val normalizedType = canonicalSubtitleType(type)
        val actualId = if (normalizedType == "series" && videoId != null) {
            // For series, use videoId which includes season/episode
            videoId
        } else {
            id
        }
        
        // Build the subtitle URL with optional extra parameters
        val rawBaseUrl = addon.baseUrl.trimEnd('/')
        val queryStart = rawBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) rawBaseUrl.substring(0, queryStart).trimEnd('/') else rawBaseUrl
        val baseQuery = if (queryStart >= 0) rawBaseUrl.substring(queryStart) else ""
        val extraParams = buildExtraParams(videoHash, videoSize, filename)
        val subtitleUrl = if (extraParams.isNotEmpty()) {
            "$basePath/subtitles/$normalizedType/$actualId/$extraParams.json$baseQuery"
        } else {
            "$basePath/subtitles/$normalizedType/$actualId.json$baseQuery"
        }
        
        Log.d(TAG, "Fetching subtitles from ${addon.name}: $subtitleUrl")
        
        return try {
            when (val result = safeApiCall(context) { api.getSubtitles(subtitleUrl) }) {
                is NetworkResult.Success -> {
                    val subtitles = result.data.subtitles?.mapNotNull { dto ->
                        Subtitle(
                            id = dto.id ?: "${dto.lang}-${dto.url.hashCode()}",
                            url = dto.url,
                            lang = dto.lang,
                            addonName = addon.displayName,
                            addonLogo = addon.logo
                        )
                    } ?: emptyList()
                    
                    Log.d(TAG, "Got ${subtitles.size} subtitles from ${addon.name}")
                    subtitles
                }
                is NetworkResult.Error -> {
                    Log.e(TAG, "Failed to fetch subtitles from ${addon.name}: code=${result.code} message=${result.message}")
                    emptyList()
                }
                NetworkResult.Loading -> emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching subtitles from ${addon.name}", e)
            emptyList()
        }
    }
    
    private fun buildExtraParams(
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): String {
        val params = mutableListOf<String>()
        
        videoHash?.let { params.add("videoHash=$it") }
        videoSize?.let { params.add("videoSize=$it") }
        filename?.let {
            params.add("filename=${java.net.URLEncoder.encode(it, "UTF-8")}")
        }
        
        return if (params.isNotEmpty()) {
            params.joinToString("&")
        } else {
            ""
        }
    }
}
