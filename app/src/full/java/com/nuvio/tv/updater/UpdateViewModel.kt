package com.nuvio.tv.updater

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class UpdateUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showDialog: Boolean = false,
    val showNoUpdateToastHint: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    // Live "install unknown apps" (REQUEST_INSTALL_PACKAGES appop) grant state. Refreshed
    // when the dialog is shown and on every ON_RESUME so the install step can present a
    // single permission-state-aware primary action instead of two competing buttons.
    val installPermissionGranted: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateRepository: UpdateRepository,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, errorMessage = null, showNoUpdateToastHint = false) }

            val ignoredTag = updatePreferences.ignoredTag.first()

            val result = updateRepository.getLatestUpdate()
            updatePreferences.setLastCheckAtMs(System.currentTimeMillis())

            result
                .onSuccess { update ->
                    val remoteNewer = VersionUtils.isRemoteNewer(update.tag, BuildConfig.VERSION_NAME)
                    val shouldShow = remoteNewer && (ignoredTag == null || ignoredTag != update.tag)

                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            update = update,
                            isUpdateAvailable = remoteNewer,
                            showDialog = shouldShow || force,
                            showNoUpdateToastHint = showNoUpdateFeedback && !remoteNewer,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            update = null,
                            isUpdateAvailable = false,
                            showDialog = force, // show error dialog if user forced a check
                            errorMessage = e.message ?: context.getString(R.string.update_error_check_failed)
                        )
                    }
                }
        }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(showDialog = false, showUnknownSourcesDialog = false, errorMessage = null) }
    }

    fun ignoreThisVersion() {
        viewModelScope.launch {
            val tag = _uiState.value.update?.tag
            updatePreferences.setIgnoredTag(tag)
            _uiState.update { it.copy(showDialog = false) }
        }
    }

    fun downloadUpdate() {
        val update = _uiState.value.update ?: return

        // SECURITY: never download an update APK from a non-https URL or a host
        // outside GitHub (github.com / *.github.com / *.githubusercontent.com).
        if (!UpdateAssetUrlValidator.isTrusted(update.assetUrl)) {
            Log.e(TAG, "Rejected update download (non-https or untrusted host): ${update.assetUrl}")
            _uiState.update {
                it.copy(
                    isDownloading = false,
                    downloadProgress = null,
                    downloadedApkPath = null,
                    errorMessage = context.getString(R.string.update_error_download_failed)
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadProgress = 0f, errorMessage = null) }

            val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dest = File(File(context.cacheDir, "updates"), safeName)

            val result = withContext(Dispatchers.IO) {
                apkDownloader.download(update.assetUrl, dest) { downloaded, total ->
                    val progress = if (total != null && total > 0) {
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    _uiState.update { it.copy(downloadProgress = progress) }
                }
            }

            result
                .onSuccess { file ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = 1f,
                            downloadedApkPath = file.absolutePath,
                            errorMessage = null
                        )
                    }
                    // Publish the live grant state so the dialog shows the right single
                    // primary action. Only auto-launch the OS install UI when the grant is
                    // already present; otherwise land on the "ready to install" state whose
                    // "Enable & Install" button opens settings on an explicit user tap.
                    refreshInstallPermission()
                    if (ApkInstaller.canRequestPackageInstalls(context)) {
                        installUpdateOrRequestPermission()
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = null,
                            downloadedApkPath = null,
                            errorMessage = e.message ?: context.getString(R.string.update_error_download_failed)
                        )
                    }
                }
        }
    }

    /**
     * Re-read the OS "install unknown apps" grant and publish it to the UiState so the
     * dialog can render the correct single primary action (Install vs Enable & Install).
     * Cheap; called when the dialog is shown and on every ON_RESUME.
     */
    fun refreshInstallPermission() {
        _uiState.update { it.copy(installPermissionGranted = ApkInstaller.canRequestPackageInstalls(context)) }
    }

    fun installUpdateOrRequestPermission() {
        val apkPath = _uiState.value.downloadedApkPath ?: return
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.update_error_apk_missing)) }
            return
        }

        val granted = ApkInstaller.canRequestPackageInstalls(context)
        if (!granted) {
            // Not granted: surface the settings step and arm the ON_RESUME recheck so we
            // auto-proceed to install once the user flips the toggle and returns.
            _uiState.update { it.copy(showUnknownSourcesDialog = true, installPermissionGranted = false) }
            openUnknownSourcesSettings()
            return
        }

        _uiState.update { it.copy(showUnknownSourcesDialog = false, installPermissionGranted = true) }
        ApkInstaller.launchInstall(context, apkFile)
    }

    /**
     * Re-query the OS "install unknown apps" grant FRESH and poll for it, because the
     * appop that backs canRequestPackageInstalls() propagates ASYNCHRONOUSLY on Android TV
     * (BRAVIA): right after the user returns from Settings the value can still read false
     * for a short window even though the grant is really ON.
     *
     * Called on ON_RESUME and from the manual "I've enabled it" button. If the grant
     * becomes true within the poll window, dismiss the unknown-sources dialog and launch
     * the install; otherwise keep the dialog up.
     */
    fun recheckInstallPermissionAndProceed() {
        // Only meaningful while we're actually waiting on the unknown-sources grant.
        if (!_uiState.value.showUnknownSourcesDialog) return

        val apkPath = _uiState.value.downloadedApkPath ?: return
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.update_error_apk_missing)) }
            return
        }

        viewModelScope.launch {
            // Poll ~1.5-2s total so the appop has time to propagate on TV.
            repeat(PERMISSION_POLL_ATTEMPTS) { attempt ->
                if (ApkInstaller.canRequestPackageInstalls(context)) {
                    _uiState.update { it.copy(showUnknownSourcesDialog = false, installPermissionGranted = true) }
                    ApkInstaller.launchInstall(context, apkFile)
                    return@launch
                }
                if (attempt < PERMISSION_POLL_ATTEMPTS - 1) {
                    kotlinx.coroutines.delay(PERMISSION_POLL_INTERVAL_MS)
                }
            }
            // Still not granted: leave the "Enable & Install" action in place.
            _uiState.update { it.copy(installPermissionGranted = false) }
            Log.d(TAG, "Install permission still not granted after poll window")
        }
    }

    fun openUnknownSourcesSettings() {
        // Arm the ON_RESUME recheck so that, when the user returns from Settings with the
        // grant now ON, recheckInstallPermissionAndProceed() auto-launches the install.
        _uiState.update { it.copy(showUnknownSourcesDialog = true) }
        val intent = ApkInstaller.buildUnknownSourcesSettingsIntent(context)
        if (intent != null) {
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open unknown-sources settings", e)
                _uiState.update { it.copy(errorMessage = context.getString(R.string.update_error_open_settings)) }
            }
        } else {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.update_error_open_settings)) }
        }
    }

    private companion object {
        const val TAG = "UpdateViewModel"

        // Poll the install-permission appop ~1.6s total (8 x 200ms) to absorb the
        // asynchronous grant propagation on Android TV / BRAVIA.
        const val PERMISSION_POLL_ATTEMPTS = 8
        const val PERMISSION_POLL_INTERVAL_MS = 200L
    }
}
