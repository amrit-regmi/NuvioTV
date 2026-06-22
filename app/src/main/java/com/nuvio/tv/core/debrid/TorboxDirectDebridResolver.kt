package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
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
    private val sharedTorboxKeyService: SharedTorboxKeyService,
    private val authManager: AuthManager
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
     *
     * The shared key is gated behind a FULL Nuvio account ([AuthManager.isAuthenticated]).
     * When unauthenticated (signed out, or only the anonymous QR session) the shared key
     * is never used — only the user's OWN TorBox key can resolve streams. This keeps the
     * built-in/shared backend access locked to authenticated users while leaving the
     * user's own debrid key working.
     */
    private suspend fun resolveApiKey(): String? {
        val userKey = dataStore.settings.first().torboxApiKey.trim()
        if (userKey.isNotBlank()) return userKey
        if (!authManager.isAuthenticated) {
            Log.d(TAG, "Unauthenticated: shared TorBox key disabled; only the user's own key is allowed")
            return null
        }
        return sharedTorboxKeyService.getKey()
    }

    /**
     * Resolves a Torbox stream to a fresh playable CDN link.
     *
     * @param forceFresh when true, the stored [StreamClientResolve.torrentId] is treated as
     *  untrusted and IGNORED — resolution always goes through createTorrent(infoHash) to obtain
     *  a CURRENT torrent_id. Use this on the stale-recovery path: the hash is the stable
     *  identifier, but the torrent_id is ephemeral and may have been PRUNED from the account
     *  (checkcached still returns the hash globally, but the old torrent_id is gone). Reusing a
     *  pruned torrent_id returns Stale forever; re-creating from the hash gets a live one.
     */
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?,
        forceFresh: Boolean = false
    ): DirectDebridResolveResult {
        if (isRateLimited()) return DirectDebridResolveResult.RateLimited
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = resolveApiKey() ?: return DirectDebridResolveResult.MissingApiKey
        val authorization = "Bearer $apiKey"

        return try {
            // Fast path: if torrentId is already known, skip createTorrent entirely.
            // On forceFresh (stale recovery) the stored torrentId is untrusted (may be pruned),
            // so we ignore it and always create-from-hash to obtain a current torrent_id.
            val knownTorrentId = if (forceFresh) {
                if (resolve.torrentId != null) {
                    Log.d(TAG, "forceFresh: ignoring stored torrentId=${resolve.torrentId} (may be pruned), creating from hash")
                }
                null
            } else {
                resolve.torrentId
            }

            val torrentId: Int
            if (knownTorrentId != null) {
                torrentId = knownTorrentId
                Log.d(TAG, "Using pre-known torrentId=$torrentId, skipping createTorrent")
            } else {
                // Slow path: create/find torrent by magnet URI (stable identity = infoHash)
                when (val created = createTorrentFromHash(resolve, authorization)) {
                    is CreateTorrentResult.Created -> torrentId = created.torrentId
                    is CreateTorrentResult.Failed -> return created.result
                }
            }

            // Fast path: if fileIdx is known, try requestdl directly without getTorrent.
            // If it fails (stale fileIdx from when stream was tier-0), fall through to slow path.
            // Skipped on forceFresh — we already have a fresh torrent_id, so go through getTorrent
            // to (re)select the correct file id for the fresh torrent.
            val knownFileIdx = resolve.fileIdx
            if (!forceFresh && knownTorrentId != null && knownFileIdx != null) {
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

            // Run the getTorrent → selectFile → requestdl tail against the current torrent_id.
            val tail = resolveTail(resolve, apiKey, authorization, torrentId, season, episode)
            // If the torrent_id we used was the STORED one (not forceFresh) and it turns out to be
            // dead (pruned), the tail returns Stale — recover by re-creating from the hash ONCE
            // and retrying the tail against the live torrent_id, instead of surfacing Stale.
            if (tail is DirectDebridResolveResult.Stale && knownTorrentId != null) {
                Log.d(TAG, "tail Stale on stored torrentId=$torrentId — re-creating from hash")
                when (val fresh = createTorrentFromHash(resolve, authorization)) {
                    is CreateTorrentResult.Created ->
                        if (fresh.torrentId != torrentId) {
                            resolveTail(resolve, apiKey, authorization, fresh.torrentId, season, episode)
                        } else {
                            tail
                        }
                    is CreateTorrentResult.Failed -> fresh.result
                }
            } else {
                tail
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "resolve: failed with ${error::class.simpleName}: ${error.message}")
            DirectDebridResolveResult.Error
        }
    }

    /**
     * Creates (or finds) a torrent on the account from the stream's stable identity (infoHash /
     * magnet) and returns a CURRENT, account-live torrent_id — or a [CreateTorrentResult.Failed]
     * carrying the mapped result (NotCached / RateLimited / Error / Stale).
     */
    private suspend fun createTorrentFromHash(
        resolve: StreamClientResolve,
        authorization: String
    ): CreateTorrentResult {
        val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: buildMagnetUri(resolve)
            ?: return CreateTorrentResult.Failed(DirectDebridResolveResult.Stale)
        Log.d(TAG, "resolve: createTorrent hash=${resolve.infoHash?.take(12)}...")
        val createStartMs = System.currentTimeMillis()
        val create = api.createTorrent(
            authorization = authorization,
            magnet = magnet.toTextPart(),
            addOnlyIfCached = "true".toTextPart(),
            allowZip = "false".toTextPart()
        )
        Log.d(TAG, "resolve: createTorrent done in ${System.currentTimeMillis() - createStartMs}ms code=${create.code()}")
        val torrentId = create.extractTorrentId()
            ?: return CreateTorrentResult.Failed(create.toFailureForCreate())
        return CreateTorrentResult.Created(torrentId)
    }

    /**
     * Given a live torrent_id, fetches the torrent, selects the right file, and requests a fresh
     * download link. Returns Stale when the torrent_id is dead/unindexed or no file matches.
     */
    private suspend fun resolveTail(
        resolve: StreamClientResolve,
        apiKey: String,
        authorization: String,
        torrentId: Int,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        return try {
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

    private sealed interface CreateTorrentResult {
        data class Created(val torrentId: Int) : CreateTorrentResult
        data class Failed(val result: DirectDebridResolveResult) : CreateTorrentResult
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
