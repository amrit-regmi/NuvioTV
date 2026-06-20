package com.nuvio.tv.core.reco

import com.nuvio.tv.BuildConfig

/**
 * Single source of truth for the reco / taste-engine backend host.
 *
 * The platform is consolidating onto `hamrocinema.regmig.com` (dropping
 * `recoengine.regmig.com`). The actual cutover happens at deploy time.
 *
 * F32: switch to hamrocinema.regmig.com at deploy — change `RECO_API_BASE_URL`
 * (debug + release) in `app/build.gradle.kts`. Everything below derives from it,
 * so flipping that ONE value flips the host everywhere in the app: all reco API
 * calls (already on [BuildConfig.RECO_API_BASE_URL]), the built-in catalog-addon
 * URL, and every host-matching check.
 *
 * Do NOT hardcode `recoengine.regmig.com` (or any reco host) anywhere else —
 * reference these members instead.
 */
object RecoBackend {

    /** Full base URL, e.g. `https://recoengine.regmig.com`. The single switchable value. */
    val baseUrl: String = BuildConfig.RECO_API_BASE_URL

    /**
     * Bare host of [baseUrl], e.g. `recoengine.regmig.com`. Used for host-matching
     * the built-in catalog-addon and ordering it first.
     */
    val host: String = baseUrl
        .substringAfter("://")
        .substringBefore("/")
        .substringBefore(":")

    /** Canonical built-in catalog-addon URL served by the backend. */
    val catalogAddonUrl: String = "$baseUrl/catalog-addon"
}
