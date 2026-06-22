package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.subtitle.SubtitleWarmer
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.SubtitleRepository
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
    private val subtitleWarmer: SubtitleWarmer
) : SubtitleRepository {

    companion object {
        private const val TAG = "SubtitleRepository"
        private const val PER_ADDON_TIMEOUT_MS = 20_000L
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
        // The warmer pre-fetches ONLY the first subtitle addon (which, in private mode, is the
        // built-in catalog-addon prepended to the list). Treat its result as a contribution from
        // that one addon — NOT a complete answer — so we still query the profile's other subtitle
        // addons (e.g. OpenSubtitles) and merge. Previously a non-empty warm hit short-circuited
        // the whole fetch, so only built-in subtitles ever appeared.
        val warmSubtitles: List<Subtitle> = if (filename != null) {
            subtitleWarmer.awaitWarm(filename, videoSize)?.also { cached ->
                Log.d(TAG, "Subtitle warm hit: ${cached.size} subs for filename=$filename")
            }.orEmpty()
        } else {
            emptyList()
        }
        // displayNames already covered by the warm result — skip re-fetching those addons.
        val warmedAddonNames = warmSubtitles.mapNotNull { it.addonName }.toSet()

        val requestType = canonicalSubtitleType(type)
        val startedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Fetching subtitles for type=$requestType, id=$id, videoId=$videoId")

        // Get installed addons
        val addons = try {
            addonRepository.getInstalledAddons().first().enabledAddons()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get installed addons", e)
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
