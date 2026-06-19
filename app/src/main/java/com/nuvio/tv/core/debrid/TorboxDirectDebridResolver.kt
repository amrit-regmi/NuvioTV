package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.TorboxCreateTorrentDataDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TORBOX_RESOLVER_TAG = "TorboxDebridResolver"
private const val TAG = TORBOX_RESOLVER_TAG
private const val RATE_LIMIT_COOLDOWN_MS = 60_000L

@Singleton
class TorboxDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: TorboxApi,
    private val fileSelector: TorboxFileSelector,
    private val sharedTorboxKeyService: SharedTorboxKeyService
) {
    private val rateLimitedUntilMs = AtomicLong(0L)

    private fun isRateLimited(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs.get()

    private fun triggerCooldown() {
        rateLimitedUntilMs.set(System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS)
        Log.w(TORBOX_RESOLVER_TAG, "TorBox API returned 429 — backing off for ${RATE_LIMIT_COOLDOWN_MS / 1000}s")
    }

    /**
     * Resolves the effective Torbox API key: user's own key takes priority,
     * falls back to the shared key fetched from the catalog-addon backend.
     * Returns null if no key is available.
     */
    private suspend fun resolveApiKey(): String? {
        val userKey = dataStore.settings.first().torboxApiKey.trim()
        if (userKey.isNotBlank()) return userKey
        return sharedTorboxKeyService.getKey()
    }

    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        if (isRateLimited()) return DirectDebridResolveResult.RateLimited
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = resolveApiKey() ?: return DirectDebridResolveResult.MissingApiKey
        val authorization = "Bearer $apiKey"

        return try {
            // Fast path: if torrentId is already known, skip createTorrent entirely
            val knownTorrentId = resolve.torrentId

            val torrentId: Int
            if (knownTorrentId != null) {
                torrentId = knownTorrentId
                Log.d(TAG, "Using pre-known torrentId=$torrentId, skipping createTorrent")
            } else {
                // Slow path: create/find torrent by magnet URI
                val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
                    ?: buildMagnetUri(resolve)
                    ?: return DirectDebridResolveResult.Stale
                Log.d(TAG, "resolve: createTorrent hash=${resolve.infoHash?.take(12)}...")
                val createStartMs = System.currentTimeMillis()
                val create = api.createTorrent(
                    authorization = authorization,
                    magnet = magnet.toTextPart(),
                    addOnlyIfCached = "true".toTextPart(),
                    allowZip = "false".toTextPart()
                )
                Log.d(TAG, "resolve: createTorrent done in ${System.currentTimeMillis() - createStartMs}ms code=${create.code()}")
                torrentId = create.extractTorrentId() ?: return create.toFailureForCreate()
            }

            // Fast path: if fileIdx is known, try requestdl directly without getTorrent.
            // If it fails (stale fileIdx from when stream was tier-0), fall through to slow path.
            val knownFileIdx = resolve.fileIdx
            if (knownTorrentId != null && knownFileIdx != null) {
                Log.d(TAG, "Using pre-known fileIdx=$knownFileIdx, skipping getTorrent")
                val link = api.requestDownloadLink(
                    authorization = authorization,
                    token = apiKey,
                    torrentId = torrentId,
                    fileId = knownFileIdx,
                    zipLink = false,
                    redirect = false,
                    appendName = false
                )
                if (link.code() == 429) { triggerCooldown(); return DirectDebridResolveResult.RateLimited }
                if (link.isSuccessful) {
                    val url = link.body()?.data?.takeIf { it.isNotBlank() }
                        ?: return DirectDebridResolveResult.Stale
                    return DirectDebridResolveResult.Success(
                        url = url,
                        filename = resolve.filename,
                        videoSize = stream.behaviorHints?.videoSize
                    )
                }
                // Fast path failed (fileIdx stale or file not yet indexed) — fall through to slow path
                Log.d(TAG, "Fast path requestdl failed (${link.code()}), falling back to getTorrent")
            }

            Log.d(TAG, "resolve: getTorrent id=$torrentId")
            val getTorrentStartMs = System.currentTimeMillis()
            val torrent = api.getTorrent(
                authorization = authorization,
                id = torrentId,
                bypassCache = true
            )
            Log.d(TAG, "resolve: getTorrent done in ${System.currentTimeMillis() - getTorrentStartMs}ms code=${torrent.code()}")
            if (torrent.code() == 429) { triggerCooldown(); return DirectDebridResolveResult.RateLimited }
            if (!torrent.isSuccessful) return DirectDebridResolveResult.Stale
            val files = torrent.body()?.data?.files.orEmpty()
            Log.d(TAG, "resolve: getTorrent files=${files.size} ids=${files.map { it.id }}")
            val file = fileSelector.selectFile(files, resolve, season, episode)
            Log.d(TAG, "resolve: selected file=${file?.name} id=${file?.id}")
            // If no file selected (files empty or selector found no match), return Stale.
            // Don't fall back to fileId=0 — TorBox rejects that with HTTP 500.
            val fileId = file?.id ?: return DirectDebridResolveResult.Stale
            val filename = file?.displayName()?.takeIf { it.isNotBlank() }
            val videoSize = file?.size

            Log.d(TAG, "resolve: requestDownloadLink torrentId=$torrentId fileId=$fileId")
            val linkStartMs = System.currentTimeMillis()
            val link = api.requestDownloadLink(
                authorization = authorization,
                token = apiKey,
                torrentId = torrentId,
                fileId = fileId,
                zipLink = false,
                redirect = false,
                appendName = false
            )
            Log.d(TAG, "resolve: requestDownloadLink done in ${System.currentTimeMillis() - linkStartMs}ms code=${link.code()}")
            if (link.code() == 429) { triggerCooldown(); return DirectDebridResolveResult.RateLimited }
            if (!link.isSuccessful) return DirectDebridResolveResult.Stale
            val url = link.body()?.data?.takeIf { it.isNotBlank() }
                ?: return DirectDebridResolveResult.Stale

            DirectDebridResolveResult.Success(
                url = url,
                filename = filename,
                videoSize = videoSize
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "resolve: failed with ${error::class.simpleName}: ${error.message}")
            DirectDebridResolveResult.Error
        }
    }

    private fun Response<com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>.extractTorrentId(): Int? {
        if (!isSuccessful) return null
        val body = body()
        if (body?.success == false) return null
        return body?.data?.resolvedTorrentId()
    }

    private fun Response<com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>.toFailureForCreate(): DirectDebridResolveResult {
        return when (code()) {
            401, 403 -> DirectDebridResolveResult.Error
            409 -> DirectDebridResolveResult.NotCached
            429 -> {
                triggerCooldown()
                DirectDebridResolveResult.RateLimited
            }
            else -> {
                val errorBody = runCatching { errorBody()?.string() }.getOrNull()
                Log.d(TORBOX_RESOLVER_TAG, "createTorrent failed with HTTP ${code()} body=$errorBody")
                DirectDebridResolveResult.Stale
            }
        }
    }

    private fun buildMagnetUri(resolve: StreamClientResolve): String? {
        val hash = resolve.infoHash?.takeIf { it.isNotBlank() } ?: return null
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            resolve.sources
                ?.filter { it.isNotBlank() }
                ?.forEach { source ->
                    append("&tr=")
                    append(java.net.URLEncoder.encode(source, "UTF-8"))
                }
        }
    }

    private fun String.toTextPart(): RequestBody {
        return toRequestBody("text/plain".toMediaType())
    }
}

sealed class DirectDebridResolveResult {
    data class Success(
        val url: String,
        val filename: String?,
        val videoSize: Long?
    ) : DirectDebridResolveResult()

    data object MissingApiKey : DirectDebridResolveResult()
    data object NotCached : DirectDebridResolveResult()
    data object RateLimited : DirectDebridResolveResult()
    data object Stale : DirectDebridResolveResult()
    data object Error : DirectDebridResolveResult()
}
