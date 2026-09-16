package com.nuvio.tv.core.stream

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries the backend's on-demand-scrape signal from the stream repository (which
 * sees the raw `/stream` response) to the Stream screen view-model, which decides
 * whether to auto-poll for streams that are still being scraped server-side.
 *
 * The backend returns `{"streams": [], "notice": {retry: true, ...}}` ONLY when a
 * title was uncovered, a background scrape was just fired, and the bounded wait
 * window expired with nothing cached yet. That is the single reliable "a scrape is
 * actually running, come back shortly" marker — a genuinely empty/covered/unreleased
 * title returns no notice, so the view-model never polls pointlessly.
 *
 * Keyed by (type|videoId); videoId already carries season:episode for series.
 */
@Singleton
class OnDemandScrapeTracker @Inject constructor() {

    private val pendingSince = ConcurrentHashMap<String, Long>()

    private fun key(type: String, videoId: String): String = "$type|$videoId"

    /** Record whether the last backend `/stream` response for this title signalled an
     *  in-flight scrape (empty streams + retry notice). */
    fun record(type: String, videoId: String, scrapePending: Boolean) {
        val k = key(type, videoId)
        if (scrapePending) {
            pendingSince[k] = System.currentTimeMillis()
        } else {
            pendingSince.remove(k)
        }
    }

    /** True when the backend recently told us a scrape is in flight for this title.
     *  The freshness window guards against acting on a stale marker. */
    fun isScrapePending(type: String, videoId: String, withinMs: Long = 120_000L): Boolean {
        val since = pendingSince[key(type, videoId)] ?: return false
        return System.currentTimeMillis() - since <= withinMs
    }
}
