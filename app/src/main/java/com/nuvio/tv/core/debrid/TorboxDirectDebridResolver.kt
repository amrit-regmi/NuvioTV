package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.TorboxCreateTorrentDataDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import android.util.Log
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
private const val RATE_LIMIT_COOLDOWN_MS = 60_000L

@Singleton
class TorboxDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: TorboxApi,
    private val fileSelector: TorboxFileSelector
) {
    private val rateLimitedUntilMs = AtomicLong(0L)

    private fun isRateLimited(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs.get()

    private fun triggerCooldown() {
        rateLimitedUntilMs.set(System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS)
        Log.w(TORBOX_RESOLVER_TAG, "TorBox API returned 429 — backing off for ${RATE_LIMIT_COOLDOWN_MS / 1000}s")
    }

    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        if (isRateLimited()) return DirectDebridResolveResult.RateLimited
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = dataStore.settings.first().torboxApiKey.trim()
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: buildMagnetUri(resolve)
            ?: return DirectDebridResolveResult.Stale
        val authorization = "Bearer $apiKey"

        return try {
            val create = api.createTorrent(
                authorization = authorization,
                magnet = magnet.toTextPart(),
                addOnlyIfCached = "true".toTextPart(),
                allowZip = "false".toTextPart()
            )
            val torrentId = create.extractTorrentId() ?: return create.toFailureForCreate()

            val torrent = api.getTorrent(
                authorization = authorization,
                id = torrentId,
                bypassCache = true
            )
            if (torrent.code() == 429) { triggerCooldown(); return DirectDebridResolveResult.RateLimited }
            if (!torrent.isSuccessful) return DirectDebridResolveResult.Stale
            val files = torrent.body()?.data?.files.orEmpty()
            val file = fileSelector.selectFile(files, resolve, season, episode)
                ?: return DirectDebridResolveResult.Stale
            val fileId = file.id ?: return DirectDebridResolveResult.Stale

            val link = api.requestDownloadLink(
                authorization = authorization,
                token = apiKey,
                torrentId = torrentId,
                fileId = fileId,
                zipLink = false,
                redirect = false,
                appendName = false
            )
            if (link.code() == 429) { triggerCooldown(); return DirectDebridResolveResult.RateLimited }
            if (!link.isSuccessful) return DirectDebridResolveResult.Stale
            val url = link.body()?.data?.takeIf { it.isNotBlank() }
                ?: return DirectDebridResolveResult.Stale

            DirectDebridResolveResult.Success(
                url = url,
                filename = file.displayName().takeIf { it.isNotBlank() },
                videoSize = file.size
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
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
                Log.d(TORBOX_RESOLVER_TAG, "createTorrent failed with HTTP ${code()}")
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
