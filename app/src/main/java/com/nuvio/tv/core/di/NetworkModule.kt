package com.nuvio.tv.core.di

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.api.CatalogAddonApi
import com.nuvio.tv.data.remote.api.DonationsApi
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.data.remote.api.ImdbTapframeApi
import com.nuvio.tv.data.remote.api.ParentalGuideApi
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.api.SeriesGraphApi
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.api.UniqueContributionsApi
import com.nuvio.tv.LocaleCache
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.reco.RecoBackend
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Named
import javax.inject.Singleton

private fun normalizedBaseUrl(rawUrl: String, fallback: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return fallback
    return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
}

/**
 * Builds the value sent in the `Accept-Language` request header so addons can
 * serve localized payloads (catalog names, descriptions, etc.). Falls back to
 * the system locale when the user has not overridden the app language. The
 * primary tag is given the highest q-weight; English is appended at q=0.7 as
 * a sensible fallback for addons that don't support the requested locale.
 */
private fun buildAcceptLanguageHeader(): String {
    val explicit = LocaleCache.localeTag
        .takeIf { it.isNotBlank() && it != LocaleCache.UNSET }
    val primaryTag = (explicit ?: java.util.Locale.getDefault().toLanguageTag())
        .takeIf { it.isNotBlank() && it != "und" }
        ?: "en"
    return if (primaryTag.equals("en", ignoreCase = true) ||
        primaryTag.startsWith("en-", ignoreCase = true)
    ) {
        primaryTag
    } else {
        "$primaryTag,en;q=0.7"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        // dagger.Lazy breaks a Hilt dependency cycle: RecoAuthTokenProvider's subgraph
        // (SyncBackendSupabaseProvider -> SyncBackendRepository) transitively needs this
        // very OkHttpClient. Deferring resolution lets the client build first; the token
        // provider is created only on the first reco-backend request.
        recoAuthTokenProvider: dagger.Lazy<com.nuvio.tv.core.reco.RecoAuthTokenProvider>,
        // ServerHealthNotifier has no OkHttpClient dependency, but keep it Lazy for symmetry
        // and to avoid any eager-init surprises; resolved on the first reco-host request.
        serverHealthNotifier: dagger.Lazy<com.nuvio.tv.core.network.ServerHealthNotifier>,
    ): OkHttpClient {
        // SECURITY (#15): No trust-all X509TrustManager / custom sslSocketFactory /
        // permissive hostnameVerifier here. The upstream trust-all (commit 000b4d68,
        // "Allow for addons with selfsigned ssl - fixes #1157") disabled ALL TLS cert
        // validation on this SHARED client — the same client that carries the user's
        // Supabase bearer token to our backend (RecoBackend.host) — making the token
        // stealable via MITM. Our backend (hamrocinema.regmig.com, Caddy/Let's Encrypt)
        // and all third-party hosts (TMDB/Trakt/etc.) present valid CA-signed certs, so
        // the default system-CA validation works. Player/stream clients keep their own
        // self-signed handling (PlayerPlaybackNetworking, PlayerMediaSourceFactory).
        return OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024 * 1024)) // 50 MB disk cache
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val version = BuildConfig.VERSION_NAME.ifBlank { "dev" }
                val original = chain.request()
                val builder = original.newBuilder()
                    .header("User-Agent", "Nuvio/$version")
                    .header("Accept-Language", buildAcceptLanguageHeader())
                // SECURITY (FIX 2): scope X-Profile-Id to OUR backend only. Sending it to
                // every host (TMDB/Trakt/skip-intro/ARM/MDBList/etc.) leaks a stable
                // profile id usable for cross-service correlation. Mirror RecoAuthInterceptor's
                // host scoping.
                if (original.url.host.equals(RecoBackend.host, ignoreCase = true)) {
                    builder.header("X-Profile-Id", ProfileManager.currentProfileId.toString())
                }
                chain.proceed(builder.build())
            }
            // F32 (api_bridge.md): attach the Nuvio/Supabase bearer token to every
            // reco-backend (RecoBackend.host) request so private-mode metadata/data
            // endpoints stay accessible. Host-scoped + skips already-authed and public
            // paths, so TMDB/Trakt/etc. and the catalog-addon secret are untouched.
            .addInterceptor(
                com.nuvio.tv.core.reco.RecoAuthInterceptor(
                    tokenProvider = { recoAuthTokenProvider.get() },
                    serverHealthNotifier = { serverHealthNotifier.get() }
                )
            )
            // Prevent OkHttp from caching error responses (4xx/5xx).
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (!response.isSuccessful) {
                    response.newBuilder()
                        .header("Cache-Control", "no-store")
                        .build()
                } else {
                    response
                }
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                        else HttpLoggingInterceptor.Level.NONE
            })
            .build()
    }

    @Provides
    @Singleton
    @Named("directDebrid")
    fun provideDirectDebridOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val version = BuildConfig.VERSION_NAME.ifBlank { "dev" }
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Nuvio/$version")
                    .header("Accept-Language", buildAcceptLanguageHeader())
                    .build()
                chain.proceed(request)
            }
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://placeholder.nuvio.tv/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("tmdb")
    fun provideTmdbRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        // FIX 1: route TMDB through our backend proxy (RecoBackend.tmdbProxyBaseUrl,
        // a drop-in for api.themoviedb.org/3/). Uses the SHARED okHttpClient so
        // RecoAuthInterceptor attaches the user Bearer token (same host = our backend);
        // the proxy 401s without it. The server injects the api_key and strips any we send.
        Retrofit.Builder()
            .baseUrl(RecoBackend.tmdbProxyBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideAddonApi(retrofit: Retrofit): AddonApi =
        retrofit.create(AddonApi::class.java)

    @Provides
    @Singleton
    @Named("torbox")
    fun provideTorboxRetrofit(
        @Named("directDebrid") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.torbox.app/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideTorboxApi(@Named("torbox") retrofit: Retrofit): TorboxApi =
        retrofit.create(TorboxApi::class.java)

    @Provides
    @Singleton
    @Named("catalogAddon")
    fun provideCatalogAddonRetrofit(
        okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit {
        val rawBaseUrl = BuildConfig.CATALOG_ADDON_BASE_URL.trim()
        val baseUrl = if (rawBaseUrl.isNotBlank()) {
            if (rawBaseUrl.endsWith('/')) rawBaseUrl else "$rawBaseUrl/"
        } else {
            "http://localhost/"
        }
        // F72 (api_bridge.md): do NOT attach `Bearer <CATALOG_SECRET>` here. The shared
        // okHttpClient already carries RecoAuthInterceptor, which attaches the user's
        // Supabase `Authorization: Bearer <access_token>` to catalog-addon (reco-host)
        // DATA calls. The baked secret as Bearer is no longer sufficient (it was the hole)
        // and would block the user token (RecoAuthInterceptor skips already-authed requests).
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideCatalogAddonApi(@Named("catalogAddon") retrofit: Retrofit): CatalogAddonApi =
        retrofit.create(CatalogAddonApi::class.java)

    @Provides
    @Singleton
    @Named("realdebrid")
    fun provideRealDebridRetrofit(
        @Named("directDebrid") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.real-debrid.com/rest/1.0/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideRealDebridApi(@Named("realdebrid") retrofit: Retrofit): RealDebridApi =
        retrofit.create(RealDebridApi::class.java)

    @Provides
    @Singleton
    @Named("premiumize")
    fun providePremiumizeRetrofit(
        @Named("directDebrid") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://www.premiumize.me/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun providePremiumizeApi(@Named("premiumize") retrofit: Retrofit): PremiumizeApi =
        retrofit.create(PremiumizeApi::class.java)

    @Provides
    @Singleton
    fun provideTmdbApi(@Named("tmdb") retrofit: Retrofit): TmdbApi =
        retrofit.create(TmdbApi::class.java)

    @Provides
    @Singleton
    @Named("parentalGuide")
    fun provideParentalGuideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.imdbapi.dev/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideParentalGuideApi(@Named("parentalGuide") retrofit: Retrofit): ParentalGuideApi =
        retrofit.create(ParentalGuideApi::class.java)

    // --- Skip Intro ---
    // Skip-intro timestamps now come from OUR backend via CatalogAddonApi
    // (`/catalog-addon/skip/{type}/{id}.json`). The direct third-party providers
    // (IntroDb / AniSkip / Anime-Skip / ARM) have been removed — no runtime calls
    // to api.introdb.app / api.aniskip.com / api.anime-skip.com / arm.haglund.dev.

    // --- GitHub Releases API (in-app updates) ---

    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideGitHubReleaseApi(@Named("github") retrofit: Retrofit): GitHubReleaseApi =
        retrofit.create(GitHubReleaseApi::class.java)

    @Provides
    @Singleton
    @Named("uniqueContributionsBaseUrl")
    fun provideUniqueContributionsBaseUrl(): String =
        BuildConfig.UNIQUE_CONTRIBUTIONS_BASE_URL

    @Provides
    @Singleton
    @Named("uniqueContributions")
    fun provideUniqueContributionsRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(normalizedBaseUrl(BuildConfig.UNIQUE_CONTRIBUTIONS_BASE_URL, "https://localhost/"))
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideUniqueContributionsApi(@Named("uniqueContributions") retrofit: Retrofit): UniqueContributionsApi =
        retrofit.create(UniqueContributionsApi::class.java)

    @Provides
    @Singleton
    @Named("donations")
    fun provideDonationsRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        val baseUrl = BuildConfig.DONATIONS_BASE_URL
            .takeIf { it.isNotBlank() }
            ?: error("DONATIONS_BASE_URL is missing. Set it in local.properties or local.dev.properties.")

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideDonationsApi(@Named("donations") retrofit: Retrofit): DonationsApi =
        retrofit.create(DonationsApi::class.java)

    // --- Trailer API ---

    @Provides
    @Singleton
    @Named("trailer")
    fun provideTrailerRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.TRAILER_API_URL.ifEmpty { "https://localhost/" })
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideTrailerApi(@Named("trailer") retrofit: Retrofit): TrailerApi =
        retrofit.create(TrailerApi::class.java)

    // --- MDBList ratings ---
    // Ratings now come from OUR backend via CatalogAddonApi
    // (`/catalog-addon/ratings/{id}.json`). The direct api.mdblist.com client has been
    // removed — no runtime calls to api.mdblist.com.

    // --- SeriesGraph API ---

    @Provides
    @Singleton
    @Named("seriesGraph")
    fun provideSeriesGraphRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        val rawBaseUrl = BuildConfig.IMDB_RATINGS_API_BASE_URL
        val normalizedBaseUrl = if (rawBaseUrl.isNotBlank()) {
            if (rawBaseUrl.endsWith('/')) rawBaseUrl else "$rawBaseUrl/"
        } else {
            "http://localhost/"
        }
        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideSeriesGraphApi(@Named("seriesGraph") retrofit: Retrofit): SeriesGraphApi =
        retrofit.create(SeriesGraphApi::class.java)

    @Provides
    @Singleton
    @Named("imdbTapframe")
    fun provideImdbTapframeRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        val rawBaseUrl = BuildConfig.IMDB_TAPFRAME_API_BASE_URL
        val normalizedBaseUrl = if (rawBaseUrl.isNotBlank()) {
            if (rawBaseUrl.endsWith('/')) rawBaseUrl else "$rawBaseUrl/"
        } else {
            "http://localhost/"
        }
        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideImdbTapframeApi(@Named("imdbTapframe") retrofit: Retrofit): ImdbTapframeApi =
        retrofit.create(ImdbTapframeApi::class.java)
}
