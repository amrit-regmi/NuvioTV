@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.shares.InboxItemDto
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun InboxScreen(
    onBackPress: () -> Unit = {},
    viewModel: InboxViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshNow()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxl))
        Text(
            text = stringResource(R.string.inbox_title),
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        if (uiState.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.inbox_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                items(uiState.items, key = { it.id }) { item ->
                    val isPending = uiState.pendingIds.contains(item.id)
                    when (item.type) {
                        "permission_request" -> PermissionRequestRow(
                            item = item,
                            isPending = isPending,
                            onAllow = { viewModel.allowPermissionRequest(item) },
                            onDeny = { viewModel.denyPermissionRequest(item) }
                        )
                        else -> ShareRow(
                            item = item,
                            isPending = isPending,
                            onAdd = { viewModel.addToWatchlist(item) },
                            onDismiss = { viewModel.dismissShare(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareRow(
    item: InboxItemDto,
    isPending: Boolean,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = NuvioTheme.colors.BackgroundCard, shape = RoundedCornerShape(NuvioTheme.radii.md))
            .padding(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!item.poster.isNullOrBlank()) {
            AsyncImage(
                model = item.poster,
                contentDescription = null,
                modifier = Modifier
                    .size(width = 56.dp, height = 84.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name ?: item.contentId.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
            Text(
                text = stringResource(R.string.inbox_share_recommends, item.senderName ?: item.senderUserId.orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
        Button(
            onClick = onAdd,
            enabled = !isPending,
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.Secondary,
                contentColor = NuvioTheme.colors.OnSecondary
            )
        ) {
            Text(stringResource(R.string.inbox_add_to_watchlist))
        }
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
        Button(
            onClick = onDismiss,
            enabled = !isPending,
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                contentColor = NuvioTheme.colors.TextSecondary
            )
        ) {
            Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.inbox_dismiss))
        }
    }
}

@Composable
private fun PermissionRequestRow(
    item: InboxItemDto,
    isPending: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = NuvioTheme.colors.BackgroundCard, shape = RoundedCornerShape(NuvioTheme.radii.md))
            .padding(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.PersonAdd,
            contentDescription = null,
            tint = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Text(
            text = stringResource(
                R.string.inbox_permission_request,
                item.requesterName ?: item.requesterId.orEmpty()
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
        Button(
            onClick = onAllow,
            enabled = !isPending,
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.Secondary,
                contentColor = NuvioTheme.colors.OnSecondary
            )
        ) {
            Text(stringResource(R.string.inbox_allow))
        }
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
        Button(
            onClick = onDeny,
            enabled = !isPending,
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                contentColor = NuvioTheme.colors.TextSecondary
            )
        ) {
            Text(stringResource(R.string.inbox_deny))
        }
    }
}
