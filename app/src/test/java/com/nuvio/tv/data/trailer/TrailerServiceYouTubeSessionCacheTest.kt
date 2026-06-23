package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.data.remote.api.TrailerResponse
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class TrailerServiceYouTubeSessionCacheTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `reuses successful playback source for same youtube key within session`() = runTest {
        val trailerApi = mockk<TrailerApi>()
        val tmdbApi = mockk<TmdbApi>()
        val extractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(language = "en"))
        every { tmdbService.apiKey() } returns "tmdb-key"
        val service = TrailerService(trailerApi, tmdbApi, extractor, tmdbSettingsDataStore, tmdbService)

        // Backend-first — the backend resolves the trailer, so the direct
        // in-app YouTube extractor is never called, and the result is session-cached.
        coEvery { trailerApi.getTrailer(any(), any(), any()) } returnsMany listOf(
            Response.success(TrailerResponse(url = "https://cdn.example/video.mp4")),
            Response.success(TrailerResponse(url = "https://cdn.example/should-not-be-used.mp4"))
        )
        coEvery { extractor.extractPlaybackSource(any()) } returns
            TrailerPlaybackSource(videoUrl = "https://cdn.example/should-not-be-used.mp4")

        val first = service.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val second = service.getTrailerPlaybackSourceFromYouTubeUrl("https://youtu.be/dQw4w9WgXcQ")

        assertEquals("https://cdn.example/video.mp4", first?.videoUrl)
        assertEquals("https://cdn.example/video.mp4", second?.videoUrl)
        coVerify(exactly = 1) { trailerApi.getTrailer(any(), any(), any()) }
        coVerify(exactly = 0) { extractor.extractPlaybackSource(any()) }
    }

    @Test
    fun `failed youtube extraction is not cached for same key`() = runTest {
        val trailerApi = mockk<TrailerApi>()
        val tmdbApi = mockk<TmdbApi>()
        val extractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(language = "en"))
        every { tmdbService.apiKey() } returns "tmdb-key"
        val service = TrailerService(trailerApi, tmdbApi, extractor, tmdbSettingsDataStore, tmdbService)

        // Backend-first, in-app extraction as fallback. Backend returns nothing
        // both times, so the extractor fallback runs each call. A failed first attempt
        // must NOT be cached, so the retry can still succeed.
        coEvery { trailerApi.getTrailer(any(), any(), any()) } returns Response.success(TrailerResponse(url = null))
        coEvery { extractor.extractPlaybackSource("https://www.youtube.com/watch?v=dQw4w9WgXcQ") } returnsMany listOf(
            null,
            TrailerPlaybackSource(videoUrl = "https://cdn.example/video-after-retry.mp4")
        )

        val first = service.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val second = service.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        assertNull(first)
        assertEquals("https://cdn.example/video-after-retry.mp4", second?.videoUrl)
        coVerify(exactly = 2) { trailerApi.getTrailer(any(), any(), any()) }
        coVerify(exactly = 2) { extractor.extractPlaybackSource("https://www.youtube.com/watch?v=dQw4w9WgXcQ") }
    }
}
