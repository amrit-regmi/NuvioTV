package com.nuvio.tv.core.reco

import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronous source of the current Nuvio/Supabase access token for attaching
 * `Authorization: Bearer <token>` to reco-backend ([RecoBackend]) requests.
 *
 * Wraps the Supabase in-memory session ([SyncBackendSupabaseProvider]) — the SAME
 * source [com.nuvio.tv.core.auth.AuthManager] uses for `currentAccessToken()`. It
 * deliberately depends only on the Supabase provider (no `OkHttpClient`) so it can
 * be injected into the OkHttp interceptor and the Coil image loader without creating
 * a dependency cycle.
 *
 * Returns `null` when signed out / no session — callers must then send the request
 * unauthenticated (the backend's public endpoints still work).
 */
@Singleton
class RecoAuthTokenProvider @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
) {
    /** The current Supabase access token, or `null` when there is no active session. */
    fun currentToken(): String? = runCatching {
        supabaseProvider.auth.currentAccessTokenOrNull()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
