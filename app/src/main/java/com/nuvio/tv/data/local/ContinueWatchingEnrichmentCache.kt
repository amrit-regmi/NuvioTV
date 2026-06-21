package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val sortTimestamp: Long,
    val releaseTimestamp: Long? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val contentLanguage: String? = null
)

data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float?,
    val episodeThumbnail: String? = null,
    val episodeDescription: String? = null,
    val episodeImdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val contentLanguage: String? = null
)

@Singleton
class ContinueWatchingEnrichmentCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "CwEnrichCache"
        private const val THROTTLE_MS = 1_000L
    }

    private val gson = Gson()
    private val mutex = Mutex()
    // Throttle/dedup state is keyed per profile. A process-wide hash could suppress a
    // legitimate snapshot write for one profile because the value matched another
    // profile's last write, leaving stale on-disk data after a profile switch.
    private val lastNextUpWriteMsByProfile = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val lastInProgressWriteMsByProfile = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val lastNextUpHashByProfile = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val lastInProgressHashByProfile = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** Incremented when cache is cleared; observers can collect to trigger refresh. */
    private val _cacheCleared = kotlinx.coroutines.flow.MutableStateFlow(0)
    val cacheCleared: kotlinx.coroutines.flow.StateFlow<Int> = _cacheCleared

    /** Incremented on every successful snapshot write; channel sync observes this. */
    private val _snapshotVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val snapshotVersion: kotlinx.coroutines.flow.StateFlow<Int> = _snapshotVersion

    // --- Next Up snapshot cache ---

    private fun nextUpFile(): File {
        val profileId = profileManager.activeProfileId.value
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "nextup_${profileId}.json")
    }

    suspend fun getNextUpSnapshot(): List<CachedNextUpItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = nextUpFile()
                if (!file.exists()) return@withContext emptyList()
                gson.fromJson(file.readText(), object : TypeToken<List<CachedNextUpItem>>() {}.type)
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read next-up cache: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveNextUpSnapshot(items: List<CachedNextUpItem>, force: Boolean = false) = withContext(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        val contentHash = items.hashCode()
        if (!force) {
            if (contentHash == lastNextUpHashByProfile[profileId]) return@withContext
            val now = System.currentTimeMillis()
            if (now - (lastNextUpWriteMsByProfile[profileId] ?: 0L) < THROTTLE_MS) return@withContext
        }
        mutex.withLock {
            try {
                val file = nextUpFile()
                atomicWrite(file, gson.toJson(items))
                lastNextUpWriteMsByProfile[profileId] = System.currentTimeMillis()
                lastNextUpHashByProfile[profileId] = contentHash
                _snapshotVersion.value++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write next-up cache: ${e.message}")
            }
        }
    }

    // --- In-progress snapshot cache ---

    private fun inProgressFile(): File {
        val profileId = profileManager.activeProfileId.value
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "inprogress_${profileId}.json")
    }

    suspend fun getInProgressSnapshot(): List<CachedInProgressItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = inProgressFile()
                if (!file.exists()) return@withContext emptyList()
                gson.fromJson(file.readText(), object : TypeToken<List<CachedInProgressItem>>() {}.type)
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read in-progress cache: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveInProgressSnapshot(items: List<CachedInProgressItem>, force: Boolean = false) = withContext(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        val contentHash = items.hashCode()
        if (!force) {
            if (contentHash == lastInProgressHashByProfile[profileId]) return@withContext
            val now = System.currentTimeMillis()
            if (now - (lastInProgressWriteMsByProfile[profileId] ?: 0L) < THROTTLE_MS) return@withContext
        }
        mutex.withLock {
            try {
                val file = inProgressFile()
                atomicWrite(file, gson.toJson(items))
                lastInProgressWriteMsByProfile[profileId] = System.currentTimeMillis()
                lastInProgressHashByProfile[profileId] = contentHash
                _snapshotVersion.value++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write in-progress cache: ${e.message}")
            }
        }
    }

    /**
     * Deletes all CW enrichment cache files for the active profile.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                nextUpFile().delete()
                inProgressFile().delete()
                Log.d(TAG, "Cleared CW enrichment cache for profile ${profileManager.activeProfileId.value}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear CW enrichment cache: ${e.message}")
            }
        }
        _cacheCleared.value++
    }

    /**
     * Deletes the CW enrichment snapshots for ALL profiles (the entire cw_enrichment dir).
     * Used by the one-time launch migration that purges the cross-profile-leak poisoned rows
     * server-side cleaned, so stale local snapshots don't re-render or re-push them.
     */
    suspend fun clearAllProfiles() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val dir = File(context.filesDir, "cw_enrichment")
                if (dir.exists()) {
                    dir.listFiles()?.forEach { it.delete() }
                }
                lastNextUpWriteMsByProfile.clear()
                lastInProgressWriteMsByProfile.clear()
                lastNextUpHashByProfile.clear()
                lastInProgressHashByProfile.clear()
                Log.d(TAG, "Cleared CW enrichment cache for ALL profiles (migration)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear CW enrichment cache for all profiles: ${e.message}")
            }
        }
        _cacheCleared.value++
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo can fail on some filesystems; fall back to copy+delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

}
