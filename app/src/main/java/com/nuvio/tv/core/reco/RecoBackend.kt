package com.nuvio.tv.core.reco

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.BackendBaseUrlDataStore

/**
 * Single source of truth for the reco / taste-engine backend host.
 *
 * The built-in host is defined by `RECO_API_BASE_URL` (debug + release) in
 * `app/build.gradle.kts`, but it can be overridden at runtime by the user via the
 * Advanced settings "Backend URL" field (persisted in [BackendBaseUrlDataStore]).
 * The effective base = (user override ?? built-in default).
 *
 * Everything below derives from [baseUrl], so that ONE value sets the host everywhere
 * in the app: all reco API calls, the built-in catalog-addon URL, the TMDB/image proxy,
 * and every host-matching check.
 *
 * The override is loaded ONCE at app startup ([init]). Changing it mid-session does NOT
 * hot-swap — the user is prompted to log out + back in (the override only takes effect on
 * the next launch, after [init] re-reads it). This avoids tearing a live authenticated
 * session across two different backends.
 *
 * Do NOT hardcode any reco host anywhere else — reference these members instead.
 */
object RecoBackend {

    /**
     * Effective full base URL of the reco backend (no trailing slash). Initialized from
     * [BuildConfig.RECO_API_BASE_URL] and overridden by [init] from the persisted user value.
     * Read-only to the rest of the app; only [init] mutates it (once, at startup).
     */
    @Volatile
    var baseUrl: String = BuildConfig.RECO_API_BASE_URL
        private set

    /**
     * Bare host of [baseUrl]. Used for host-matching the built-in catalog-addon
     * and ordering it first.
     */
    @Volatile
    var host: String = hostOf(baseUrl)
        private set

    /** Canonical built-in catalog-addon URL served by the backend (no trailing slash). */
    @Volatile
    var catalogAddonUrl: String = "$baseUrl/catalog-addon"
        private set

    /**
     * Catalog-addon base WITH trailing slash — the form expected by Retrofit base URLs and the
     * legacy `BuildConfig.CATALOG_ADDON_BASE_URL` comparisons. Derived from [baseUrl] so the user
     * override applies to the catalog-addon / parental-guide / device-profile calls too.
     */
    @Volatile
    var catalogAddonBaseUrl: String = "$baseUrl/catalog-addon/"
        private set

    /**
     * Drop-in replacement base for `https://api.themoviedb.org/3/`. The backend TMDB
     * proxy is local-first + Tor-on-miss + cached, injects the server-side api_key, and
     * requires the user Bearer token (attached by RecoAuthInterceptor since same host).
     * TmdbService/TmdbApi go through this rather than TMDB directly.
     */
    @Volatile
    var tmdbProxyBaseUrl: String = "$baseUrl/tmdb/3/"
        private set

    private fun hostOf(url: String): String = url
        .substringAfter("://")
        .substringBefore("/")
        .substringBefore(":")

    /**
     * Applies the persisted user override (if any) on top of the built-in default. Call ONCE,
     * synchronously, at the very start of app launch (NuvioApplication.onCreate) — before any
     * network client or the Hilt graph is built — so every derived value reflects the override.
     * Idempotent and cheap (a single SharedPreferences read).
     */
    fun init(context: Context) {
        val override = BackendBaseUrlDataStore.readOverrideSync(context)
            ?.let { BackendBaseUrlDataStore.normalizeOrNull(it) }
        val effective = override ?: BuildConfig.RECO_API_BASE_URL
        baseUrl = effective
        host = hostOf(effective)
        catalogAddonUrl = "$effective/catalog-addon"
        catalogAddonBaseUrl = "$effective/catalog-addon/"
        tmdbProxyBaseUrl = "$effective/tmdb/3/"
    }

    /**
     * Rewrites a TMDB image URL (or bare `/path.jpg`) to go through our `/image` proxy
     * so Coil never hits `image.tmdb.org` directly. Mirrors the reco pipeline's rewrite.
     * In non-private/open mode (RECO_MODE != "private") returns the original TMDB URL.
     *
     * Accepts either a full `https://image.tmdb.org/t/p/<size>/<path>` URL or a bare
     * `/<path>` and produces `${baseUrl}/image/t/p/<size>/<path>` (or `${baseUrl}/image/<path>`).
     */
    fun proxiedTmdbImageUrl(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (com.nuvio.tv.BuildConfig.RECO_MODE != "private") return raw
        // Already proxied through our backend — leave as-is.
        if (raw.startsWith("$baseUrl/image")) return raw
        val path = when {
            raw.startsWith("https://image.tmdb.org") ->
                raw.substringAfter("image.tmdb.org")
            raw.startsWith("http") -> return raw // some non-TMDB absolute URL, leave it
            raw.startsWith("/") -> raw
            else -> "/$raw"
        }
        return "$baseUrl/image$path"
    }

    /**
     * Re-homes a stored image URL that points at the reco `/image` (or
     * `/metadata/images`) proxy onto the CURRENT [baseUrl] host.
     *
     * Why: library/saved entries persist the poster URL at save time. A poster
     * persisted against a host other than the current [host] is dead — its host is
     * not [host], so [RecoAuthInterceptor] never attaches the bearer token AND that
     * host serves nothing. Freshly-fetched catalog/CW posters are fine because they
     * always carry the live host; only persisted entries can go stale.
     *
     * This rewrites ONLY reco image-proxy URLs (any scheme/host ending in the
     * `/image/...` or `/metadata/images/...` path) to the live [baseUrl], keeping
     * the path/query intact. Non-proxy absolute URLs (TMDB, third-party addon
     * images) and blank/null values are returned untouched. Idempotent: a URL
     * already on the live host is returned unchanged.
     */
    fun rehomeImageUrl(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotBlank() } ?: return url
        // Only touch our image-proxy URLs; leave TMDB / addon / other images alone.
        val marker = Regex("/(image|metadata/images)/").find(raw) ?: return raw
        val proxyPath = raw.substring(marker.range.first) // includes leading "/image/..."
        val rebuilt = "$baseUrl$proxyPath"
        return if (rebuilt == raw) raw else rebuilt
    }
}
