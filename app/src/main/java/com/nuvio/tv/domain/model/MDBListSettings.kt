package com.nuvio.tv.domain.model

data class MDBListSettings(
    // Ratings are backend-enriched by default; the MDBList settings UI was removed, so
    // this defaults to true (always fetch + show). The `enabled` flag gates the network
    // fetch in MDBListRepository — keeping it true means ratings simply display.
    val enabled: Boolean = true,
    val showTrakt: Boolean = true,
    val showImdb: Boolean = true,
    val showTmdb: Boolean = true,
    val showLetterboxd: Boolean = true,
    val showTomatoes: Boolean = true,
    val showAudience: Boolean = true,
    val showMetacritic: Boolean = true
)
