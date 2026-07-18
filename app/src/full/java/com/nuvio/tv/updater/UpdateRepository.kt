package com.nuvio.tv.updater

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.updater.model.AppUpdate
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SECURITY: update APKs may only ever be fetched over https from GitHub-owned
 * hosts (github.com, *.github.com, *.githubusercontent.com). Anything else is
 * rejected before a single byte is downloaded.
 */
internal object UpdateAssetUrlValidator {
    fun isTrusted(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "github.com" ||
            host.endsWith(".github.com") ||
            host.endsWith(".githubusercontent.com")
    }
}

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseApi: GitHubReleaseApi
) {

    suspend fun getLatestUpdate(): Result<AppUpdate> {
        return runCatching {
            val owner = BuildConfig.GITHUB_OWNER
            val repo = BuildConfig.GITHUB_REPO

            val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
            if (!response.isSuccessful) {
                error("GitHub API error: ${response.code()}")
            }

            val dto = response.body() ?: error("Empty GitHub release response")
            if (dto.draft || dto.prerelease) {
                error("Latest release is draft/prerelease")
            }

            val tag = dto.tagName?.takeIf { it.isNotBlank() }
                ?: dto.name?.takeIf { it.isNotBlank() }
                ?: error("Release has no tag/name")

            val asset = AbiSelector.chooseBestApkAsset(dto.assets)
                ?: error("No APK asset found in release")

            if (!UpdateAssetUrlValidator.isTrusted(asset.browserDownloadUrl)) {
                Log.e(TAG, "Rejected update asset URL (non-https or untrusted host): ${asset.browserDownloadUrl}")
                error("Update asset URL failed validation")
            }

            AppUpdate(
                tag = tag,
                title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = dto.body.orEmpty(),
                releaseUrl = dto.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size
            )
        }
    }

    private companion object {
        const val TAG = "UpdateRepository"
    }
}
