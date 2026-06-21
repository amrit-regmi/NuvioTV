package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appOnboardingDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_onboarding")

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AppOnboardingDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    private val dataStore = context.appOnboardingDataStore
    private val hasSeenAuthQrOnFirstLaunchKey = booleanPreferencesKey("has_seen_auth_qr_on_first_launch")

    // One-time "Personalize your recommendations" nudge shown after each profile's FIRST login.
    // Keyed PER PROFILE: previously a single account-global key meant the first profile to see the
    // nudge suppressed it for every other profile (e.g. Amrit's showing hid it from Nirupa).
    private fun hasSeenPersonalizeNudgeKey(profileId: Int) =
        booleanPreferencesKey("has_seen_personalize_nudge_p$profileId")

    val hasSeenAuthQrOnFirstLaunch: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[hasSeenAuthQrOnFirstLaunchKey] ?: false
    }

    suspend fun setHasSeenAuthQrOnFirstLaunch(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[hasSeenAuthQrOnFirstLaunchKey] = value
        }
    }

    /** Emits the per-profile nudge-shown flag, switching when the active profile changes. */
    val hasSeenPersonalizeNudge: Flow<Boolean> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            dataStore.data.map { prefs -> prefs[hasSeenPersonalizeNudgeKey(pid)] ?: false }
        }

    suspend fun setHasSeenPersonalizeNudge(value: Boolean) {
        val pid = profileManager.activeProfileId.value
        dataStore.edit { prefs ->
            prefs[hasSeenPersonalizeNudgeKey(pid)] = value
        }
    }
}
