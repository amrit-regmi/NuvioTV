package com.nuvio.tv.core.profile

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileManager @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MAX_PROFILES = 5
        // Updated whenever the active profile changes; read by the global OkHttp interceptor
        // so every request (reco, events, streams) carries the correct profile identity.
        @Volatile var currentProfileId: Int = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeProfileId: StateFlow<Int> = profileDataStore.activeProfileId
        .stateIn(scope, SharingStarted.Eagerly, 1)

    val activeProfileReady: StateFlow<Boolean> = profileDataStore.activeProfileId
        .map { true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val hasEverSelectedProfile: StateFlow<Boolean> = profileDataStore.hasEverSelectedProfile
        .stateIn(scope, SharingStarted.Eagerly, false)

    val rememberLastProfileEnabled: StateFlow<Boolean> = profileDataStore.rememberLastProfileEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

    val profiles: StateFlow<List<UserProfile>> = profileDataStore.profilesList
        .stateIn(scope, SharingStarted.Eagerly, listOf(
            UserProfile(id = 1, name = context.getString(R.string.profile_default_name, 1), avatarColorHex = "#1E88E5")
        ))

    val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    val isPrimaryProfileActive: Boolean
        get() = activeProfileId.value == 1

    /**
     * Authoritative per-profile "is the active profile the primary/owner?" flow, resolved from the
     * REAL profile identity (the active profile's own [UserProfile.isPrimary], i.e. real id == 1),
     * NOT any addon-effective id (a secondary profile that `usesPrimaryAddons` is remapped to id 1
     * for addon storage only — it must still read as a SECONDARY profile here).
     *
     * Seeds `false` (fail-safe): until the datastore delivers the real active id + profile list, a
     * secondary profile must NOT momentarily look primary and expose primary-only device/account UI.
     * Every primary-only gate (Device Profile, Account device management, Profiles management)
     * should observe THIS flow so the definition stays consistent across screens.
     */
    val isPrimaryProfileActiveFlow: StateFlow<Boolean> = combine(
        activeProfileId,
        profiles
    ) { activeId, list ->
        // Real identity: the active profile's own isPrimary. Fall back to id==1 only if the active
        // profile isn't in the list yet (transient), still using the REAL active id (never effective).
        (list.firstOrNull { it.id == activeId }?.isPrimary) ?: (activeId == 1)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val canCreateProfile: Boolean
        get() = profiles.value.size < MAX_PROFILES

    suspend fun setActiveProfile(id: Int) {
        val exists = profiles.value.any { it.id == id }
        if (exists) {
            currentProfileId = id
            profileDataStore.setActiveProfile(id)
        }
    }

    suspend fun setRememberLastProfileEnabled(enabled: Boolean) {
        profileDataStore.setRememberLastProfileEnabled(enabled)
    }

    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        usesPrimaryAddons: Boolean = false,
        usesPrimaryPlugins: Boolean = false,
        avatarId: String? = null
    ): Boolean {
        val current = profiles.value
        if (current.size >= MAX_PROFILES) return false

        val usedIds = current.map { it.id }.toSet()
        val nextId = (2..MAX_PROFILES).firstOrNull { it !in usedIds } ?: return false

        val profile = UserProfile(
            id = nextId,
            name = name.trim().ifEmpty { context.getString(R.string.profile_default_name, nextId) },
            avatarColorHex = avatarColorHex,
            usesPrimaryAddons = usesPrimaryAddons,
            usesPrimaryPlugins = usesPrimaryPlugins,
            avatarId = avatarId
        )
        factory.markProfileCreated(nextId)
        profileDataStore.upsertProfile(profile)
        return true
    }

    suspend fun deleteProfile(id: Int): Boolean {
        if (id == 1) return false
        if (profiles.value.none { it.id == id }) return false
        deleteProfileDataAsync(id)
        profileDataStore.deleteProfile(id)
        return true
    }

    suspend fun updateProfile(profile: UserProfile): Boolean {
        if (profiles.value.none { it.id == profile.id }) return false
        profileDataStore.upsertProfile(profile)
        return true
    }

    private suspend fun deleteProfileDataAsync(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == 1) return@withContext

        factory.clearProfile(profileId)

        val suffixWithExtension = "_p${profileId}.preferences_pb"
        val dataStoreDir = File(context.filesDir, "datastore")
        if (dataStoreDir.exists()) {
            dataStoreDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(suffixWithExtension)) {
                    file.delete()
                }
            }
        }

        val pluginCodeDir = File(context.filesDir, "plugin_code_p${profileId}")
        if (pluginCodeDir.exists()) {
            pluginCodeDir.deleteRecursively()
        }
    }
}
