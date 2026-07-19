package com.nuvio.tv.core.badges

import com.nuvio.tv.domain.model.Stream

/**
 * Picks the most detailed RELEASE-NAME string to run the Elite-Badges regexes against
 * (#159). We want the raw torrent/file name like
 * "Dune.Part.Two.2024.2160p.BluRay.REMUX.HEVC.TrueHD.Atmos-GROUP", not the pretty
 * display title, because the badge patterns key off release-scene tokens.
 *
 * Preference order (most detailed → least):
 *   1. clientResolve.stream.raw.torrentName / filename  (parser's raw source name)
 *   2. clientResolve.torrentName / filename
 *   3. behaviorHints.filename                            (Stremio file name)
 *   4. title                                             (addons often put the release name here)
 *   5. name / description                                (last resort)
 *
 * Fields are concatenated defensively so a release name split across e.g. filename +
 * title still matches; duplicates collapse. Never throws.
 */
object StreamReleaseName {

    fun of(stream: Stream): String? {
        return try {
            val raw = stream.clientResolve?.stream?.raw
            val parts = linkedSetOf<String>()
            fun add(s: String?) {
                val v = s?.trim()
                if (!v.isNullOrBlank()) parts.add(v)
            }

            // Most detailed first — but we include several so codec/audio tokens that
            // live on one field but not another are all visible to the regexes.
            add(raw?.torrentName)
            add(raw?.filename)
            add(raw?.parsed?.rawTitle)
            add(stream.clientResolve?.torrentName)
            add(stream.clientResolve?.filename)
            add(stream.behaviorHints?.filename)
            add(stream.title)
            add(stream.name)
            add(stream.description)

            parts.joinToString("  ").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            stream.getDisplayNameOrNull()
        }
    }
}
