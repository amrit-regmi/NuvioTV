@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api

@Composable
internal fun BuiltInProvidersSettingsContent(
    initialFocusRequester: FocusRequester,
    onConfigureOnAnotherDevice: () -> Unit,
) {
    LaunchedEffect(Unit) {
        runCatching { initialFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "Built-in providers",
            subtitle = "Catalog and recommendation engine powered by your private backend"
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
                    item(key = "builtin_catalog") {
                        SettingsToggleRow(
                            title = "Catalog provider",
                            subtitle = "Browse movies and TV shows from the built-in catalog",
                            checked = true,
                            enabled = false,
                            onToggle = {},
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .focusRequester(initialFocusRequester)
                        )
                    }
                    item(key = "builtin_reco") {
                        SettingsToggleRow(
                            title = "Recommendation engine",
                            subtitle = "AI-powered recommendations personalized for you",
                            checked = true,
                            enabled = false,
                            onToggle = {}
                        )
                    }
                    item(key = "builtin_configure") {
                        SettingsActionRow(
                            title = "Configure on another device",
                            subtitle = "Open the Reco engine UI on your phone or laptop",
                            onClick = onConfigureOnAnotherDevice
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
}
