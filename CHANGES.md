# NuvioTV Fork — Changes

This document lists every file changed from the upstream NuvioTV repository and why,
to make rebasing on future upstream updates easier.

---

## Goal

Re-enable instant stream playback using the user's own self-hosted AIOStreams instance
instead of Nuvio's server (`debrid.nuvioapp.space`), which was blanked out in 0.6.19.
Also pre-warm subtitle metadata so subtitles load instantly on playback.

---

## Files Changed

### `gradle.properties`

**Change:** Restored `org.gradle.jvmargs=-Xmx4096m` (was `-Xmx2048m` in a prior session).

**Why:** The packaging step (`packageFullDebug`) runs out of heap with 2048m when building
all ABI splits in parallel.

**On rebase:** Keep `-Xmx4096m` or higher.

---

### `app/src/main/java/com/nuvio/tv/core/debrid/DirectDebridStreamSource.kt`

**Change:** Significant refactor.

- Removed `DirectDebridConfigEncoder` and `baseUrlProvider` (BuildConfig string) dependencies
- Injected `AddonRepository` to find the first installed addon with `stream` resource
- URL now built as `{addon.baseUrl}/stream/{type}/{videoId}.json` — calls AIOStreams directly
- `sourceNames()` returns `emptyList()` always — removes the "TorBox Instant" tab from the
  stream selection UI (the tab required `clientResolve.isCached` which AIOStreams doesn't return)
- `preloadStreams()` simplified to just call `streamWarmer.warm(type, videoId)` — no direct
  API calls from this class; warming is fully delegated to `StreamWarmer`
- Removed `SubtitleWarmer` injection (subtitle warming now triggered from `StreamWarmer`)

**Why:** Nuvio's server URL was blanked to disable the feature. We bypass it by reading the
user's installed addons and calling their AIOStreams instance directly.

**On rebase:** This is the core change. If upstream modifies `DirectDebridStreamSource.kt`,
carefully re-apply these changes on top.

---

### `app/src/test/java/com/nuvio/tv/core/debrid/DirectDebridStreamSourceTest.kt`

**Change:** Updated to match new constructor (removed `subtitleWarmer` param). Removed the
`concurrent preload and fetch share in flight request` test (preload no longer triggers an
API call directly). Renamed remaining preload test to reflect new behavior.

**On rebase:** If upstream adds new tests, merge carefully.

---

### `app/src/main/java/com/nuvio/tv/core/stream/StreamWarmer.kt` *(NEW FILE)*

**Change:** New singleton class.

- Pre-fetches **all installed stream addons** in parallel when a detail screen opens
- LRU cache: 50 entries, 15-minute TTL
- In-flight deduplication via `Deferred` — concurrent callers await the same fetch
- `warm(type, videoId)` — fire-and-forget, called from `DirectDebridStreamSource.preloadStreams()`,
  `PlayerRuntimeControllerMetadata` (next episode), and `HomeViewModel` (Continue Watching)
- `awaitWarm(baseUrl, type, videoId)` — called from `StreamRepositoryImpl` to serve cache hits
- After caching, triggers `SubtitleWarmer.warm()` for the **top 3** streams (not just top 1)
- After caching, fires parallel HEAD probes (2s timeout) to validate URLs; detects Comet stub
  videos (<1 MB); reorders list with valid URLs first; pre-connects via player's HTTP client
- `isKnownStub(url)` — used by `NuvioNavHost` to filter known-bad URLs from fast-path
- `stubUrls` — LRU-capped set (500 entries) so stale stub entries are evicted over time

**Why:** Allows stream list to appear instantly. Warming next episode during playback and top
CW items on home load further reduces latency for the most common user flows.

**On rebase:** This file is entirely new — no conflicts expected.

---

### `app/src/main/java/com/nuvio/tv/core/subtitle/SubtitleWarmer.kt` *(REWRITTEN)*

**Change:** Full rewrite of the original stub.

- In-flight deduplication via `Deferred` (same pattern as `StreamWarmer`)
- `warm(type, videoId, filename, videoSize)` — fire-and-forget; skips if cached or in-flight
- `awaitWarm(filename, videoSize)` — returns cached result or awaits in-flight fetch; prevents
  parallel duplicate fetch when user taps play before warm completes
- Fetches from first installed addon with `subtitles` resource
- LRU cache: 100 entries, 15-minute TTL

**Why:** Eliminates the OpenSubtitles API round-trip on playback. The metadata list
(5–50KB JSON) is fetched in background; the player downloads the actual .srt files itself.

**On rebase:** Check if upstream modified `SubtitleWarmer.kt` — if they added new fields or
changed the subtitle response model, update `fetchSubtitles()` and `CachedSubtitles` accordingly.

---

### `app/src/main/java/com/nuvio/tv/data/repository/StreamRepositoryImpl.kt`

**Change:**

- Injected `StreamWarmer`
- Added cache-hit check at the top of `getStreamsFromAddon()`: if `StreamWarmer.awaitWarm()`
  returns a result, it is returned immediately as `NetworkResult.Success` without an HTTP call

**Why:** Serves the pre-warmed stream list instantly instead of making a fresh network request.

**On rebase:** If upstream changes `getStreamsFromAddon()` signature or adds new logic before
the HTTP call, re-insert the `streamWarmer.awaitWarm()` check near the top of the method.

---

### `app/src/main/java/com/nuvio/tv/data/repository/SubtitleRepositoryImpl.kt`

**Change:** Changed `subtitleWarmer.getCached(filename, videoSize)` call to
`subtitleWarmer.awaitWarm(filename, videoSize)`.

Also rebased to include upstream fix: URL-encodes `filename` in `buildExtraParams()` (0.6.20).

**Why:** If the subtitle warm is still in-flight when the user taps play, `awaitWarm()` waits
for it to complete rather than returning null and starting a redundant parallel fetch.

**On rebase:** If upstream changes how `SubtitleRepositoryImpl` fetches subtitles, re-apply
the `awaitWarm()` call before the network fetch.

---

### `app/src/main/java/com/nuvio/tv/core/player/PlayerPreWarmer.kt` *(NEW FILE)*

**Change:** New singleton that holds warm `WarmSession` objects so the fast-play path in
`NuvioNavHost` can skip the stream selection screen.

- LRU cache: up to **10 sessions** (covers catalog browsing without eviction), 15-min TTL
- `isProbed: Boolean` field on `WarmSession` — gate used by `NuvioNavHost` fast-path; true only
  after `StreamWarmer.probeAndReorder()` confirms stream[0] is live and non-stub
- `setFpsJob` / `awaitFps` — shares the in-flight AFR detection `Deferred` with the player
  so the AFR preflight can await the pre-detected value instead of re-probing
- `getNextStream()` — advances to next stream on playback error (auto-play mode only)
- `clearSession(type, videoId)` — removes one entry when all probes are invalid

**Why:** Decouples stream probe state from `StreamWarmer` and lets the player UI know when
a stream is confirmed ready, enabling zero-wait navigation directly to the player screen.

**On rebase:** This file is entirely new — no conflicts expected.

---

### `app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt`

**Change:** Added fast-play path in the `onPlayClick` handler of `MetaDetailsScreen`.

- Retrieves `PlayerPreWarmer` and `StreamWarmer` via Hilt `EntryPointAccessors`
- On Play tap: if a warm session exists whose top stream is not a known stub, navigates
  **directly to `PlayerScreen`** with `autoPlayNav=true`, bypassing `StreamScreen` entirely
- Does not require `isProbed=true` — navigates as soon as any warm session exists and the top
  URL is not in the known-stub set; probing continues in the background
- Falls back to the normal `StreamScreen` flow if no warm session is available

**Why:** Eliminates the stream-selection screen hop for the common case where AIOStreams has
already returned and probed the streams in the background.

**On rebase:** If upstream changes `MetaDetailsScreen`'s `onPlayClick` signature or the
`Screen.Player.createRoute()` parameter list, re-apply the fast-path branching logic.

---

### `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerViewModel.kt`

**Change:** Added `StreamWarmer` to the Hilt-injected constructor and passed it to
`PlayerRuntimeController`.

**On rebase:** If upstream adds new constructor parameters to `PlayerViewModel` or
`PlayerRuntimeController`, add `streamWarmer` alongside the existing `subtitleWarmer` and
`playerPreWarmer` parameters.

---

### `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeController.kt`

**Change:** Added `internal val streamWarmer: StreamWarmer` constructor parameter.

---

### `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt`

**Change:** In `recomputeNextEpisode()`, after `nextEpisodeVideo` is resolved, fires
`streamWarmer.warm(contentType, resolvedNext.id)` for both the normal series path and the
"other" type (anime) path.

**Why:** Pre-warms the next episode's stream list the moment the player knows what the next
episode is (i.e. when the current episode starts playing), so auto-advance to the next episode
is instant.

**On rebase:** If upstream modifies `recomputeNextEpisode()`, re-insert the `streamWarmer.warm()`
call after `nextEpisodeVideo` is set to a non-null value.

---

### `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

**Change:** Reduced `bufferForPlaybackMs` in `DefaultLoadControl` from the default (1500 ms)
to 500 ms.

**Why:** Halves the time ExoPlayer waits before starting playback once it has buffered enough
data. Combined with the pre-connected TCP/TLS pool from `StreamWarmer`, content starts
rendering almost immediately after navigation.

**On rebase:** If upstream modifies `DefaultLoadControl` configuration, keep `bufferForPlaybackMs`
at 500 ms (or lower if latency permits).

---

### `app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModel.kt`

**Change:** Added `StreamWarmer` to the Hilt-injected constructor. Added a `distinctUntilChanged`
observer on `continueWatchingItems` that fires `streamWarmer.warm()` for the **top 3** CW items
whenever the list changes.

**Why:** The Continue Watching row is the first thing users interact with after opening the app.
Pre-warming their most recent in-progress shows and next-up episodes means tapping Play from
the home screen is instant.

**On rebase:** If upstream adds new constructor parameters to `HomeViewModel`, add `streamWarmer`
alongside the other singletons.

---

## What Was NOT Changed

These files are untouched from upstream and should remain so:

- `DirectDebridStreamPreparer.kt`
- `DirectDebridResolver.kt`
- `TorboxDirectDebridResolver.kt`
- `RealDebridDirectDebridResolver.kt`
- `MetaDetailsViewModel.kt`
- `DebridSettingsDataStore.kt`
- All debrid UI settings screens
- `app/build.gradle.kts`
- `local.properties` (machine-local, not in git)

---

## How to Rebase on Upstream Updates

```bash
git remote add upstream https://github.com/NuvioMedia/NuvioTV.git
git fetch upstream
git rebase upstream/dev
```

Conflicts are most likely in:
1. `DirectDebridStreamSource.kt` — re-apply addon-based URL construction and `sourceNames()` empty return
2. `StreamRepositoryImpl.kt` — re-insert `streamWarmer.awaitWarm()` check
3. `SubtitleRepositoryImpl.kt` — re-insert `awaitWarm()` call

The two new files (`StreamWarmer.kt`, rewritten `SubtitleWarmer.kt`) should have no conflicts.
