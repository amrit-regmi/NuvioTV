@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun BuiltInProvidersScreen(
    showBuiltInHeader: Boolean = true,
    onBackPress: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    BackHandler { onBackPress() }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(
                start = NuvioTheme.spacing.xxl,
                end = NuvioTheme.spacing.xxl,
                top = if (showBuiltInHeader) NuvioTheme.spacing.xl else 68.dp,
                bottom = NuvioTheme.spacing.xl
            )
    ) {
        SettingsWorkspaceSurface(modifier = Modifier.fillMaxSize()) {
            BuiltInProvidersSettingsContent(initialFocusRequester = focusRequester)
        }
    }
}

@Composable
internal fun BuiltInProvidersSettingsContent(
    initialFocusRequester: FocusRequester,
    onConfigureReco: () -> Unit = {},
    viewModel: BuiltInProvidersViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showProfileResolutionPicker by remember { mutableStateOf(false) }
    var showProfileHdrDialog by remember { mutableStateOf(false) }
    var showProfileCodecDialog by remember { mutableStateOf(false) }
    var showProfileAudioFormatDialog by remember { mutableStateOf(false) }
    var showProfileAudioChannelsPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { initialFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "Built-in providers",
            subtitle = "Private catalog and stream engine powered by shared TorBox"
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "builtin_catalog_section") {
                        BuiltInSectionLabel(text = "Catalog")
                    }

                    item(key = "builtin_catalog_toggle") {
                        SettingsToggleRow(
                            title = "Catalog provider",
                            subtitle = "Fetch movies and TV shows from your private catalog",
                            checked = uiState.isCatalogEnabled,
                            enabled = true,
                            onToggle = { viewModel.toggleCatalog(!uiState.isCatalogEnabled) },
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .focusRequester(initialFocusRequester)
                        )
                    }

                    item(key = "builtin_stream_engine_section") {
                        BuiltInSectionLabel(text = "Stream Engine")
                    }

                    item(key = "builtin_stream_engine_toggle") {
                        SettingsToggleRow(
                            title = "Use built-in stream engine",
                            subtitle = "Provides streams via shared TorBox account based on your device profile",
                            checked = uiState.streamEngineEnabled,
                            enabled = true,
                            onToggle = { viewModel.toggleStreamEngine(!uiState.streamEngineEnabled) }
                        )
                    }

                    if (uiState.streamEngineEnabled) {
                        item(key = "builtin_device_profile_section") {
                            BuiltInSectionLabel(text = "Device Profile")
                        }

                        item(key = "builtin_device_profile_auto_detect") {
                            SettingsToggleRow(
                                title = "Use auto-detected device profile",
                                subtitle = "Capabilities are detected automatically from your hardware",
                                checked = uiState.useAutoDetectedProfile,
                                enabled = true,
                                onToggle = { viewModel.toggleAutoDetect(!uiState.useAutoDetectedProfile) }
                            )
                        }

                        item(key = "builtin_device_profile_info") {
                            BuiltInInfoText(
                                text = if (uiState.useAutoDetectedProfile)
                                    "Stream filtering is handled by the backend based on your detected hardware capabilities."
                                else
                                    "Stream filtering uses the profile below. Changes are saved immediately."
                            )
                        }

                        item(key = "builtin_device_profile_resolution") {
                            SettingsActionRow(
                                title = "Max Resolution",
                                subtitle = null,
                                value = uiState.editMaxResolution,
                                onClick = { showProfileResolutionPicker = true },
                                enabled = !uiState.useAutoDetectedProfile
                            )
                        }

                        item(key = "builtin_device_profile_hdr") {
                            SettingsActionRow(
                                title = "HDR Types",
                                subtitle = null,
                                value = uiState.editHdrTypes.joinToString(", ").ifBlank { "None" },
                                onClick = { showProfileHdrDialog = true },
                                enabled = !uiState.useAutoDetectedProfile
                            )
                        }

                        item(key = "builtin_device_profile_codecs") {
                            SettingsActionRow(
                                title = "Codecs",
                                subtitle = null,
                                value = uiState.editCodecs.joinToString(", ").ifBlank { "None" },
                                onClick = { showProfileCodecDialog = true },
                                enabled = !uiState.useAutoDetectedProfile
                            )
                        }

                        item(key = "builtin_device_profile_audio_formats") {
                            SettingsActionRow(
                                title = "Audio Formats",
                                subtitle = null,
                                value = uiState.editAudioFormats.joinToString(", ").ifBlank { "None" },
                                onClick = { showProfileAudioFormatDialog = true },
                                enabled = !uiState.useAutoDetectedProfile
                            )
                        }

                        item(key = "builtin_device_profile_audio_channels") {
                            SettingsActionRow(
                                title = "Max Audio Channels",
                                subtitle = null,
                                value = uiState.editMaxAudioChannels,
                                onClick = { showProfileAudioChannelsPicker = true },
                                enabled = !uiState.useAutoDetectedProfile
                            )
                        }

                        item(key = "builtin_device_profile_speed") {
                            SettingsActionRow(
                                title = "Download Speed",
                                subtitle = null,
                                value = uiState.deviceProfile?.downloadSpeedMbps?.let { "${it.toInt()} Mbps (auto-detected)" } ?: "Not registered",
                                onClick = {},
                                enabled = false
                            )
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    if (showProfileResolutionPicker) {
        BuiltInProfileResolutionDialog(
            selectedValue = uiState.editMaxResolution,
            onSelected = { resolution ->
                viewModel.setProfileResolution(resolution)
                showProfileResolutionPicker = false
            },
            onDismiss = { showProfileResolutionPicker = false }
        )
    }

    if (showProfileHdrDialog) {
        val hdrOptions = listOf("HDR10", "HDR10+", "DolbyVision", "HLG")
        SettingsMultiChoiceDialog(
            title = "HDR Types",
            selectedValues = uiState.editHdrTypes.toList(),
            options = hdrOptions.map { SettingsPickerOption(it, it) },
            onValuesSelected = { selected ->
                viewModel.setProfileHdrTypes(selected.toSet())
                showProfileHdrDialog = false
            },
            onDismiss = { showProfileHdrDialog = false },
            width = 560.dp,
            maxHeight = 420.dp
        )
    }

    if (showProfileCodecDialog) {
        val codecOptions = listOf("H.265", "AV1", "H.264")
        SettingsMultiChoiceDialog(
            title = "Codecs",
            selectedValues = uiState.editCodecs.toList(),
            options = codecOptions.map { SettingsPickerOption(it, it) },
            onValuesSelected = { selected ->
                viewModel.setProfileCodecs(selected.toSet())
                showProfileCodecDialog = false
            },
            onDismiss = { showProfileCodecDialog = false },
            width = 560.dp,
            maxHeight = 420.dp
        )
    }

    if (showProfileAudioFormatDialog) {
        val audioFormatOptions = listOf("Dolby Atmos", "DTS:X", "DTS-HD", "AAC")
        SettingsMultiChoiceDialog(
            title = "Audio Formats",
            selectedValues = uiState.editAudioFormats.toList(),
            options = audioFormatOptions.map { SettingsPickerOption(it, it) },
            onValuesSelected = { selected ->
                viewModel.setProfileAudioFormats(selected.toSet())
                showProfileAudioFormatDialog = false
            },
            onDismiss = { showProfileAudioFormatDialog = false },
            width = 560.dp,
            maxHeight = 420.dp
        )
    }

    if (showProfileAudioChannelsPicker) {
        BuiltInProfileAudioChannelsDialog(
            selectedValue = uiState.editMaxAudioChannels,
            onSelected = { channels ->
                viewModel.setProfileAudioChannels(channels)
                showProfileAudioChannelsPicker = false
            },
            onDismiss = { showProfileAudioChannelsPicker = false }
        )
    }
}

@Composable
private fun InlineRecoSection(
    viewModel: RecoSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            uiState.error != null -> {
                Text(
                    text = uiState.error!!,
                    color = NuvioColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = { viewModel.issueWatchlyKey() }) {
                    Text("Retry")
                }
            }
            uiState.url != null -> {
                if (uiState.qrBitmap != null) {
                    Image(
                        bitmap = uiState.qrBitmap!!.asImageBitmap(),
                        contentDescription = "Reco engine QR code",
                        modifier = Modifier
                            .size(160.dp)
                            .background(Color.White, RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
                Text(
                    text = uiState.url!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                uiState.countdownSeconds?.let { remaining ->
                    val minutes = remaining / 60
                    val seconds = remaining % 60
                    Text(
                        text = "Expires in %d:%02d".format(minutes, seconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (remaining < 60) MaterialTheme.colorScheme.error
                                else NuvioColors.TextSecondary
                    )
                }
            }
            uiState.isLoading -> {
                Text(
                    text = "Generating key…",
                    color = NuvioColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            else -> {
                Text(
                    text = "Scan QR to configure on your phone or laptop. Key expires after 15 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = { viewModel.issueWatchlyKey() }) {
                    Text("Configure on another device")
                }
            }
        }
    }
}

@Composable
private fun BuiltInSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.sm)
    )
}

@Composable
private fun BuiltInInfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.sm)
    )
}

@Composable
private fun BuiltInProfileResolutionDialog(
    selectedValue: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf("2160p", "1080p", "720p")
    val labels = mapOf("2160p" to "2160p (4K)", "1080p" to "1080p", "720p" to "720p")

    SettingsSingleChoiceDialog(
        title = "Max Resolution",
        options = options.map { value ->
            SettingsPickerOption(value, labels[value] ?: value)
        },
        selectedValue = selectedValue,
        onOptionSelected = onSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 280.dp
    )
}

@Composable
private fun BuiltInProfileAudioChannelsDialog(
    selectedValue: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf("7.1", "5.1", "2.0")

    SettingsSingleChoiceDialog(
        title = "Max Audio Channels",
        options = options.map { value ->
            SettingsPickerOption(value, value)
        },
        selectedValue = selectedValue,
        onOptionSelected = onSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 280.dp
    )
}
