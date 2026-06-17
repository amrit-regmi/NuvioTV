# NuvioTV — Private Backend Integration

This document is the complete brief for an agent (or developer) continuing work
on the private fork of NuvioTV. Read it fully before touching any code.

---

## What this is

NuvioTV is an Android TV app. This fork points it at a **self-hosted private
backend** instead of the public Nuvio cloud. Two things change:

1. **Auth** — login talks to our own Supabase instance
2. **Recommendations** — a new "Recommendations" home section fetches personalised
   rows from the taste-engine API (`GET /reco/for/{user_id}`)

Everything else (addons, Trakt, TMDB, playback) stays untouched.

---

## Backend services (already running)

| Service | Host | Purpose |
|---------|------|---------|
| taste-engine API | `http://localhost:8000` (LAN: `http://132.145.31.160:8000`) | Recommendation engine + auth |
| Supabase (self-hosted) | `https://hamrocinema.regmig.com` | Nuvio profile sync, watch progress |
| HamroCinema Catalog addon | mounted at `/catalog-addon` inside taste-engine | Trending/popular/new catalog rows |
| Public domain | `https://recoengine.regmig.com` | External HTTPS entry point via Caddy |

### HamroCinema Catalog addon

Stremio-compatible catalog addon serving trending, popular, new releases and
search from our local TMDB database. Pre-computed nightly — browse rows served
from disk cache, search queries hit the DB directly.

**Stremio / Nuvio install URL:**
```
https://recoengine.regmig.com/catalog-addon/manifest.json
```

**Home screen rows (movies only):**
- `trending` — currently trending by TMDB popularity score (updates daily)
- `popular` — all-time popular by weighted vote score
- `new_releases` — last 180 days with minimum vote threshold

**Browse rows (both types, not on home screen):**
- `top_rated` — highest rated movies + series (on-demand, not pre-cached)

**Search (both movie + series):**
- Full-text ILIKE search across title and original title, ordered by popularity
- Stremio sends `?search=query` — results are live (not cached)
- **This is the default metadata/search provider for Nuvio** — pre-install it
  so users get search results from our local DB instead of Cinemata/TMDB public

**This should be pre-installed in the private fork** (see Task 7 below) so
users don't need to manually add the addon and get search out of the box.

The Supabase instance is a **drop-in replacement** for the public Nuvio cloud.
Same schema, same API surface — only the URL changes.

### Key env values (already in `local.properties`)

```properties
# Change these to point at the private backend:
SUPABASE_URL=https://hamrocinema.regmig.com
SUPABASE_ANON_KEY=<from docker/.env.supabase.example — generate your own>
TV_LOGIN_WEB_BASE_URL=https://recoengine.regmig.com/recoengine/login
```

The taste-engine URL is **not yet a build config field** — that is one of the
tasks below.

---

## How the existing QR / TV-login flow works

The app already has a full QR login flow in:
- `app/src/main/java/com/nuvio/tv/core/auth/AuthManager.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/account/AccountViewModel.kt`

Flow:
1. App calls `AuthManager.startTvLoginSession(deviceNonce, deviceName, redirectBaseUrl)`
2. This hits `{TV_LOGIN_WEB_BASE_URL}` (a Supabase or backend endpoint) and gets back
   a `webUrl` + `code`
3. App shows QR of `webUrl`; user scans on phone/laptop and completes login there
4. App polls until the session is confirmed

**The existing QR flow already works with the private Supabase.** You only need
to change `TV_LOGIN_WEB_BASE_URL` and `SUPABASE_URL` in `local.properties`.

---

## What needs to be built

### Task 1 — Point at private Supabase (config change only)

File: `local.properties` (do NOT commit — gitignored)

```properties
SUPABASE_URL=https://hamrocinema.regmig.com
SUPABASE_ANON_KEY=eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9...  # from docker/.env.supabase
TV_LOGIN_WEB_BASE_URL=https://recoengine.regmig.com/recoengine/login
```

Verify login still works (QR flow → profile select → home screen).

---

### Task 2 — Add build config fields for taste-engine

File: `app/build.gradle.kts`

Add two new `buildConfigField` entries (alongside existing ones):

```kotlin
buildConfigField("String", "RECO_API_BASE_URL",
    "\"${localProps["RECO_API_BASE_URL"] ?: "https://recoengine.regmig.com"}\"")
buildConfigField("String", "RECO_MODE",
    "\"${localProps["RECO_MODE"] ?: "private"}\"")
```

And in `local.properties`:

```properties
RECO_API_BASE_URL=https://recoengine.regmig.com
RECO_MODE=private
```

---

### Task 3 — RecommendationRepository

New file:
`app/src/main/java/com/nuvio/tv/core/reco/RecommendationRepository.kt`

```kotlin
package com.nuvio.tv.core.reco

import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RecoItem(
    val tmdb_id: Int,
    val kind: String,          // "movie" | "tv"
    val title: String,
    val year: Int? = null,
    val poster_path: String? = null,
    val score: Double = 0.0,
)

@Serializable
data class RecoRow(
    val label: String,
    val reason_type: String,
    val items: List<RecoItem>,
)

@Serializable
data class RecoResponse(val rows: List<RecoRow>)

@Singleton
class RecommendationRepository @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    /**
     * Fetch personalised recommendation rows for the logged-in user.
     * Uses the saved config on the server — no payload needed.
     *
     * Returns empty list on any error (recommendations are non-critical).
     */
    suspend fun fetchRows(userId: String, bearerToken: String): List<RecoRow> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${BuildConfig.RECO_API_BASE_URL}/reco/for/$userId")
                    .header("Authorization", "Bearer $bearerToken")
                    .build()
                val body = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
                json.decodeFromString<RecoResponse>(body).rows
            }.getOrElse {
                android.util.Log.w("RecoRepo", "Failed to fetch recommendations", it)
                emptyList()
            }
        }

    /**
     * Issue a one-time QR key so the user can open the RecoEngine config UI
     * (Watchly) on another device without typing a password.
     * Returns the URL to encode as a QR code, or null on failure.
     */
    suspend fun issueWatchlyKey(bearerToken: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${BuildConfig.RECO_API_BASE_URL}/recoengine/issue-key")
                    .header("Authorization", "Bearer $bearerToken")
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .build()
                val body = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
                json.decodeFromString<Map<String, String>>(body)["url"]
            }.getOrNull()
        }
}
```

Wire it into the DI graph (Hilt module — look at how `AddonRepository` is provided
for the pattern).

---

### Task 4 — Fire watch event when playback completes

When the user finishes watching (progress ≥ 80%) or stops playback, call
`POST /events/watched`. This triggers the background recompute on the server.

**Where to hook in:**

Find where Nuvio updates watch progress to Supabase. Look for:
- `WatchProgressSyncService` or similar in `core/sync/`
- Any class that calls the Supabase `sync_push_watch_progress` RPC
- The playback ViewModel / player event callbacks in `MainActivity.kt`

**What to call:**

```kotlin
// In RecommendationRepository — add this method:
suspend fun reportWatched(
    bearerToken: String,
    tmdbId: Int,
    kind: String,          // "movie" or "tv"
    progress: Float,       // 0.0–1.0
    season: Int? = null,   // for series — the season number just finished
    episode: Int? = null,  // for series — the episode number just finished
) {
    if (progress < 0.8f) return  // only fire when substantially complete
    withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("tmdb_id", tmdbId)
                put("kind", kind)
                put("progress", progress)
                season?.let { put("season", it) }
                episode?.let { put("episode", it) }
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BuildConfig.RECO_API_BASE_URL}/events/watched")
                .header("Authorization", "Bearer $bearerToken")
                .post(body)
                .build()
            httpClient.newCall(request).execute().close()
        }
        // Ignore failures — this is fire-and-forget
    }
}
```

**Series:** Fire the event per season/episode completion (not just per series).
The server treats a completed episode as a signal that the user is engaged with
that show. Season number and episode number are passed through so the engine
can track progress through a series arc.

**Trigger points:**
- Movie: progress ≥ 0.8 OR playback stopped after ≥ 0.8
- TV episode: episode ends (progress ~= 1.0) or user navigates to next episode
- TV season: when last episode of a season is completed

Call `reportWatched` from the same place the app updates Supabase watch_progress.
Both calls happen together — one syncs to Supabase, the other triggers reco recompute.

---

### Task 4b — Fetch reco rows and inject into home screen

The home screen already renders `CatalogRow` items fetched from addons.
Recommendation rows slot into the same component.

**Where to add the fetch:**

`HomeViewModel` / `HomeViewModelCatalogPipeline.kt` — specifically inside
`observeInstalledAddonsPipeline()` after `loadAllCatalogsPipeline(addons)`.

**What to do:**

After the user's session is confirmed (`AuthState.Authenticated`):

```kotlin
viewModelScope.launch {
    val rows = recommendationRepository.fetchRows(userId, bearerToken)
    // Prepend reco rows before addon catalog rows
    // Map RecoRow → whatever UI model the home screen uses
}
```

Map `RecoRow` → `CatalogRow` (or the equivalent UI model):
- `label` → row title
- `items` → list of content cards
- Each `RecoItem`: `tmdb_id` + `kind` ("movie"/"tv") + `title` + `poster_path`
  → use the same `ContentCard` component already used for addon catalog items

Reco rows should appear **at the top of the home screen**, above addon rows.

**Only fetch when `BuildConfig.RECO_MODE == "private"`.** This keeps the build
flag as a clean gate so a future public build skips this entirely.

---

### Task 5 — "Configure Recommendations" settings entry

Add a new entry to the existing Settings screen (wherever Trakt, Debrid, etc. live):

```
Settings
  └── Recommendations
        [Button] Configure on another device
```

On tap:
1. Call `recommendationRepository.issueWatchlyKey(bearerToken)`
2. Show a QR code (use existing `QrCodeGenerator.generate(url, 420)`)
3. Show the URL as text below the QR (for non-camera devices)
4. Show a 15-minute countdown (the key expires)

The URL opens `https://recoengine.regmig.com/recoengine/login?key=…` which
logs the user into the Watchly config UI on their phone/laptop.

---

### Task 6 — Bearer token for taste-engine

The taste-engine uses **the same Supabase JWT** the app already holds after login.
No new token system needed.

After `auth.sessionStatus` emits `Authenticated`, get the JWT:

```kotlin
val jwt = auth.currentSessionOrNull()?.accessToken ?: return
```

Pass this as `bearerToken` to `RecommendationRepository`. The taste-engine
verifies it against the same JWT secret as Supabase.

---

### Task 7 — Pre-install the HamroCinema Catalog addon

The HamroCinema Catalog addon (`https://recoengine.regmig.com/catalog-addon/manifest.json`)
serves trending, popular, and new releases from our local database.
It should be pre-installed in the private fork so users see it without
manually adding it.

**Where addons are seeded:**

Find where the app defines default/bundled addons. Look for:
- A hardcoded list of addon manifest URLs loaded at first launch
- `AddonRepository` or `DefaultAddonsProvider` or similar
- Any `assets/` JSON file listing default addons
- `AppOnboardingDataStore` (already found — check if it seeds addons)

**What to add:**

```kotlin
// In whatever initialises the default addon list:
val PRIVATE_CATALOG_ADDON = Addon(
    id          = "community.hamrocinema-catalog",
    name        = "HamroCinema Catalog",
    manifestUrl = "https://recoengine.regmig.com/catalog-addon/manifest.json",
    isDefault   = true,
    isRemovable = false,   // prevent accidental removal
)
```

Only add this when `BuildConfig.RECO_MODE == "private"`.

**Catalog rows on home screen (movies only):**
- Trending Movies
- Popular Movies
- New Releases Movies

**Also provides search** — when pre-installed, Nuvio uses this addon as the
default metadata/search provider. Users typing in the search bar will get
results from our local TMDB database. No external Cinemata or TMDB API needed.

The `BuildConfig.CATALOG_ADDON_URL` field already contains the manifest URL.
Use it instead of hardcoding:

```kotlin
val PRIVATE_CATALOG_ADDON = Addon(
    id          = "community.hamrocinema-catalog",
    name        = "HamroCinema Catalog",
    manifestUrl = BuildConfig.CATALOG_ADDON_URL,
    isDefault   = true,
    isRemovable = false,
)
```

- Popular Movies / Popular Series
- New Releases Movies / New Releases Series

These fill the home screen immediately before the user has any watch history,
making the first-launch experience non-empty.

---

## Recommendation architecture — pre-compute on watch event

**Do not compute recommendations on request.** Instead:

```
User finishes watching (Nuvio app)
  → POST /events/watched  (fire-and-forget, non-blocking)
  → taste-engine queues background recompute for this user
  → recompute runs, stores result in user_reco_cache table
  → metadata pre-warmer fetches poster/backdrop/title for all items in cache

Next time Nuvio fetches recommendations:
  → GET /reco/for/{user_id}
  → reads from user_reco_cache (instant, no computation)
  → if cache is empty → fall through to live compute, then store result
```

**Why:** Reco computation takes 50–200ms per request. Pre-computing means the
home screen loads instantly. Metadata is already cached so no loading spinners
for posters.

---

### Backend changes needed in taste-engine (separate task from Nuvio work)

The following endpoints and tables need to be added to taste-engine.
**This is backend work, not Android work.** The Android task is only to call
`POST /events/watched` at the right moment.

#### DB table

```sql
CREATE TABLE user_reco_cache (
    user_id      UUID PRIMARY KEY,
    rows_json    JSONB NOT NULL,          -- full RecoResponse serialised
    computed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    item_count   INT,                     -- total items across all rows
    trigger      TEXT                     -- 'watch_event' | 'manual' | 'startup'
);
```

#### Endpoints

`POST /events/watched`
- Auth: Bearer (Supabase JWT)
- Body: `{"tmdb_id": 550, "kind": "movie", "progress": 0.92}`
- Effect: upserts a `user_events` row, then spawns background thread to
  recompute reco for this user and write to `user_reco_cache`
- Returns immediately: `{"ok": true, "recompute": "queued"}`

`GET /reco/for/{user_id}`
- Auth: Bearer
- Reads from `user_reco_cache` if present and < 24h old
- Falls through to live compute + stores result if cache is missing/stale
- Response shape: same `{"rows": [...]}` as the live engine

#### Metadata pre-warmer (separate service, not blocking)

After writing to `user_reco_cache`, spawn a thread that:
1. Iterates all `tmdb_id` + `kind` in the cached rows
2. For each item, ensures `poster_path`, `backdrop_path`, `title`, `year`,
   `vote_average` are present in the local metadata DB (movies / tv_shows tables)
3. Optionally pre-fetches images to a local nginx cache
   (see `docker/nginx-image-cache.conf` — already set up for TMDB images)

This is a separate concern from the reco engine itself. The reco response
already includes `poster_path` from the local DB — the pre-warmer ensures
that path is cached at the nginx level so Nuvio loads posters without hitting
TMDB CDN cold.

---

## API reference (taste-engine)

### `POST /events/watched`

Signal that the user finished (or nearly finished) watching a title.
Fire-and-forget — call when progress ≥ 80%, or when user navigates to next episode.

**Headers:** `Authorization: Bearer {supabase_jwt}`

**Body — movie:**
```json
{"tmdb_id": 550, "kind": "movie", "progress": 0.92}
```

**Body — TV episode/season:**
```json
{"tmdb_id": 1399, "kind": "tv", "progress": 1.0, "season": 3, "episode": 10}
```

`progress` is 0–1. `kind` is `"movie"` or `"tv"`.
For TV, pass the season + episode just completed. The server fires a recompute
for both the episode signal AND accumulates series engagement signals.

**Response:** `{"ok": true, "recompute": "queued"}`

The app should not wait for the recompute — call this async and ignore the
response beyond checking for HTTP errors.

### `GET /reco/for/{user_id}`

Returns pre-computed recommendation rows. Instant if cache is warm.

**Headers:** `Authorization: Bearer {supabase_jwt}`

**Response:**
```json
{
  "rows": [
    {
      "label": "Top picks for you",
      "reason_type": "personal",
      "items": [
        {
          "tmdb_id": 550,
          "kind": "movie",
          "title": "Fight Club",
          "year": 1999,
          "poster_path": "/path.jpg",
          "score": 0.92
        }
      ]
    }
  ],
  "cached_at": "2026-06-07T14:00:00Z"
}
```

### `POST /recoengine/issue-key`

Issues a 15-minute one-time login key for the Watchly config UI.

**Headers:** `Authorization: Bearer {supabase_jwt}`

**Response:**
```json
{
  "url": "https://recoengine.regmig.com/recoengine/login?key=abc123",
  "expires_in": 900
}
```

---

## Existing files to reference

| File | Why |
|------|-----|
| `core/auth/AuthManager.kt` | Session state, bearer token retrieval |
| `core/qr/QrCodeGenerator.kt` | Already implemented — reuse for Watchly QR |
| `ui/screens/account/AccountViewModel.kt` | Pattern for QR display + countdown |
| `domain/repository/CatalogRepository.kt` | Interface pattern to follow |
| `data/repository/CatalogRepositoryImpl.kt` | HTTP fetch + JSON decode pattern |
| `ui/components/CatalogRowSection.kt` | The row UI component to reuse |
| `core/sync/HomeCatalogSettingsSyncService.kt` | How home catalog data flows |

---

## Build & run

```bash
cd /home/amrit/NuvioTV

# Ensure local.properties has the private backend URLs (see Task 1)
# Build debug APK
./gradlew :app:assembleDebug

# Install on connected device / emulator
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

The app uses build variants: `full` (with plugins) and `playstore`.
Use the `full` variant for local testing.

---

## What NOT to change

- Supabase schema — already deployed and working
- Trakt integration — unchanged
- Addon system — unchanged
- Playback / decoder stack — unchanged
- TMDB metadata fetching — unchanged

The only additions are:
1. Build config fields (`RECO_API_BASE_URL`, `RECO_MODE`)
2. `RecommendationRepository` (new file)
3. Home screen reco row injection (modify existing ViewModel)
4. Settings entry + QR screen (new screen, link from existing Settings)
