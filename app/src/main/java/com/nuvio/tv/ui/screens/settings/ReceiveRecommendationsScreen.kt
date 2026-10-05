@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.shares.RosterEntryDto
import com.nuvio.tv.ui.components.NuvioDialog

@Composable
fun ReceiveRecommendationsScreen(
    viewModel: ReceiveRecommendationsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.account_receive_recommendations_title),
        subtitle = stringResource(R.string.poster_options_recommend_to)
    ) {
        ReceiveRecommendationsContent(viewModel = viewModel)
    }
}

@Composable
fun ReceiveRecommendationsContent(
    viewModel: ReceiveRecommendationsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val allowed = uiState.mine.filter { it.status == "allowed" }
    val pending = uiState.mine.filter { it.status == "pending" }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.account_receive_recommendations_title),
            subtitle = stringResource(R.string.account_receive_recommendations_empty)
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionRow(
                title = stringResource(R.string.account_receive_recommendations_add),
                subtitle = null,
                onClick = { viewModel.openRosterPicker() },
                leadingIcon = Icons.Default.Add,
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )

            if (allowed.isEmpty()) {
                if (!uiState.isLoading) {
                    Text(
                        text = stringResource(R.string.account_receive_recommendations_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                    )
                }
            } else {
                allowed.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = row.sourceName?.takeIf { it.isNotBlank() } ?: row.sourceId,
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
        }

        if (pending.isNotEmpty()) {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.account_receive_recommendations_pending, pending.size)
            ) {
                pending.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = row.sourceName?.takeIf { it.isNotBlank() } ?: row.sourceId,
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
        }
    }

    if (uiState.showRosterPicker) {
        ReceiveRecommendationsRosterDialog(
            roster = uiState.roster,
            isPending = uiState.isRosterLoading,
            requestedIds = uiState.requestedIds,
            onPick = { viewModel.requestPermission(it) },
            onDismiss = { viewModel.dismissRosterPicker() }
        )
    }
}

@Composable
private fun ReceiveRecommendationsRosterDialog(
    roster: List<RosterEntryDto>,
    isPending: Boolean,
    requestedIds: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.account_receive_recommendations_add),
        subtitle = stringResource(R.string.account_roster_picker_subtitle),
        width = 500.dp
    ) {
        if (roster.isEmpty() && !isPending) {
            Text(
                text = stringResource(R.string.account_roster_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(roster, key = { it.userId }) { user ->
                val alreadyRequested = user.userId in requestedIds
                Button(
                    onClick = { onPick(user.userId) },
                    enabled = !isPending && !alreadyRequested,
                    modifier = if (user.userId == roster.firstOrNull()?.userId) {
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryFocusRequester)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = user.name ?: user.userId,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
