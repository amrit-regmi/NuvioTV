@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R

@Composable
fun MDBListSettingsContent(
    viewModel: MDBListSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.mdblist_title),
            subtitle = stringResource(R.string.mdblist_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val mdbListState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = mdbListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "mdblist_enabled") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_enable_title),
                        subtitle = stringResource(R.string.mdblist_enable_subtitle),
                        checked = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleEnabled(!uiState.enabled)) },
                        modifier = Modifier
                            .padding(top = NuvioTheme.spacing.xxs)
                            .then(
                                if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        })
                    )
                }

                item(key = "mdblist_trakt") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_trakt_title),
                        subtitle = stringResource(R.string.mdblist_trakt_subtitle),
                        checked = uiState.showTrakt,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTrakt(!uiState.showTrakt)) }
                    )
                }

                item(key = "mdblist_imdb") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_imdb_title),
                        subtitle = stringResource(R.string.mdblist_imdb_subtitle),
                        checked = uiState.showImdb,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleImdb(!uiState.showImdb)) }
                    )
                }

                item(key = "mdblist_tmdb") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_tmdb_title),
                        subtitle = stringResource(R.string.mdblist_tmdb_subtitle),
                        checked = uiState.showTmdb,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTmdb(!uiState.showTmdb)) }
                    )
                }

                item(key = "mdblist_letterboxd") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_letterboxd_title),
                        subtitle = stringResource(R.string.mdblist_letterboxd_subtitle),
                        checked = uiState.showLetterboxd,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleLetterboxd(!uiState.showLetterboxd)) }
                    )
                }

                item(key = "mdblist_tomatoes") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_tomatoes_title),
                        subtitle = stringResource(R.string.mdblist_tomatoes_subtitle),
                        checked = uiState.showTomatoes,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTomatoes(!uiState.showTomatoes)) }
                    )
                }

                item(key = "mdblist_audience") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_audience_title),
                        subtitle = stringResource(R.string.mdblist_audience_subtitle),
                        checked = uiState.showAudience,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleAudience(!uiState.showAudience)) }
                    )
                }

                item(key = "mdblist_metacritic") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_metacritic_title),
                        subtitle = stringResource(R.string.mdblist_metacritic_subtitle),
                        checked = uiState.showMetacritic,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleMetacritic(!uiState.showMetacritic)) }
                    )
                }
            }
            SettingsVerticalScrollIndicators(state = mdbListState)
            }
        }
    }
}
