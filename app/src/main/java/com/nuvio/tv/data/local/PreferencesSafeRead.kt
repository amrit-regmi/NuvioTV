package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences

/**
 * Type-tolerant DataStore Preferences read.
 *
 * A settings-sync import (ProfileSettingsSyncService) shares the per-profile
 * DataStores and can write a value under the wrong type (e.g. an int-typed key
 * encoded as a String in the remote settings blob). A typed read via
 * `prefs[key]` then throws ClassCastException inside the read-flow `.map { }`
 * and crashes the app at launch. Reading via `.safe(key)` swallows the
 * mismatch so the caller's existing `?: default` fallback kicks in;
 * correctly-typed values behave identically.
 *
 * Every store whose feature is listed in ProfileSettingsSyncService.syncedFeatures
 * MUST read through this helper.
 */
internal inline fun <reified T> Preferences.safe(key: Preferences.Key<T>): T? =
    this.asMap()[key] as? T
