@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.ui.theme.NuvioTheme

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.shares.MyPermissionDto
import com.nuvio.tv.core.shares.RosterEntryDto
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.components.NuvioDialog

private const val SHOW_SYNC_CODE_FEATURES = false

@Composable
fun AccountScreen(
    onNavigateToAuthSignIn: () -> Unit = {},
    onNavigateToSyncGenerate: () -> Unit = {},
    onNavigateToSyncClaim: () -> Unit = {},
    onBackPress: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.authState, uiState.isPrimaryProfileActive) {
        // F28: only the primary/admin profile manages linked devices, so don't even
        // fetch the device list for secondary profiles.
        if (uiState.authState is AuthState.FullAccount && uiState.isPrimaryProfileActive) {
            viewModel.loadLinkedDevices()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xxxl),
        contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        item {
            Text(
                text = stringResource(R.string.account_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        }

        when (val authState = uiState.authState) {
            is AuthState.Loading -> {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.account_loading),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }

            is AuthState.SignedOut -> {
                item {
                    Text(
                        text = stringResource(R.string.account_sign_in_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
                item {
                    AccountInfoCard(
                        label = stringResource(R.string.account_sync_backend_label),
                        value = uiState.syncBackendName
                    )
                }
                item {
                    AccountActionCard(
                        icon = Icons.Default.Person,
                        title = stringResource(R.string.account_signin_create_title),
                        description = stringResource(R.string.account_signin_create_desc),
                        onClick = onNavigateToAuthSignIn
                    )
                }
                if (SHOW_SYNC_CODE_FEATURES) {
                    item {
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        Text(
                            text = stringResource(R.string.account_sync_code_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = NuvioTheme.colors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                        Text(
                            text = stringResource(R.string.account_sync_code_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                    item {
                        AccountActionCard(
                            icon = Icons.Default.VpnKey,
                            title = stringResource(R.string.sync_generate_title),
                            description = stringResource(R.string.account_generate_sync_desc),
                            onClick = onNavigateToSyncGenerate
                        )
                    }
                    item {
                        AccountActionCard(
                            icon = Icons.Default.Sync,
                            title = stringResource(R.string.sync_claim_title),
                            description = stringResource(R.string.account_enter_sync_desc),
                            onClick = onNavigateToSyncClaim
                        )
                    }
                }
            }

            is AuthState.FullAccount -> {
                item {
                    AccountInfoCard(
                        label = stringResource(R.string.account_signed_in_as),
                        value = authState.email
                    )
                }
                item {
                    AccountInfoCard(
                        label = stringResource(R.string.account_sync_backend_label),
                        value = uiState.syncBackendName
                    )
                }
                // Connected-devices feature gated by super-admin availability (GET /api/me)
                // AND restricted to the primary/admin profile (F28: mirrors the dashboard
                // "Connected Devices = primary/admin only" rule; hidden for secondary profiles).
                if (uiState.connectedDevicesAvailable && uiState.isPrimaryProfileActive) {
                    item {
                        LinkedDevicesSection(
                            devices = uiState.linkedDevices,
                            onUnlink = { viewModel.unlinkDevice(it) }
                        )
                    }
                }
                if (SHOW_SYNC_CODE_FEATURES) {
                    item {
                        AccountActionCard(
                            icon = Icons.Default.VpnKey,
                            title = stringResource(R.string.sync_generate_title),
                            description = stringResource(R.string.account_generate_sync_signed_in_desc),
                            onClick = onNavigateToSyncGenerate
                        )
                    }
                }
                item {
                    ReceiveRecommendationsSection(
                        permissions = uiState.receiveFromPermissions,
                        onAdd = { viewModel.openRosterPicker() },
                        onEditAlias = { id, alias -> viewModel.startAliasEdit(id, alias) }
                    )
                }
                item {
                    SignOutButton(onClick = { viewModel.signOut() })
                }
            }

        }
    }

    if (uiState.rosterPickerActive) {
        RosterPickerDialog(
            roster = viewModel.filteredRoster(),
            query = uiState.rosterFilterQuery,
            isPending = uiState.rosterPickerPending,
            error = uiState.rosterPickerError,
            onQueryChanged = { viewModel.setRosterFilterQuery(it) },
            onPick = { userId -> viewModel.requestReceiveFrom(userId) },
            onDismiss = { viewModel.closeRosterPicker() }
        )
    }

    if (uiState.aliasEditTargetId != null) {
        AliasEditDialog(
            value = uiState.aliasEditValue,
            onValueChanged = { viewModel.updateAliasEditValue(it) },
            onSave = { viewModel.saveAliasEdit() },
            onDismiss = { viewModel.cancelAliasEdit() }
        )
    }
}

@Composable
private fun AccountActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.TextPrimary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = NuvioTheme.colors.Secondary
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun AccountInfoCard(label: String, value: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioTheme.colors.BackgroundCard,
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
            .padding(NuvioTheme.spacing.lg)
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextTertiary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LinkedDevicesSection(
    devices: List<com.nuvio.tv.data.remote.supabase.SupabaseLinkedDevice>,
    onUnlink: (String) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Devices,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NuvioTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Text(
                text = stringResource(R.string.account_linked_devices, devices.size),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        if (devices.isEmpty()) {
            Text(
                text = stringResource(R.string.account_no_linked_devices),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary
            )
        } else {
            devices.forEach { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = NuvioTheme.colors.BackgroundCard,
                            shape = RoundedCornerShape(NuvioTheme.radii.sm)
                        )
                        .padding(NuvioTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = device.deviceName ?: stringResource(R.string.account_unknown_device),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onUnlink(device.deviceUserId) },
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFFC62828).copy(alpha = 0.2f),
                            focusedContainerColor = Color(0xFFC62828).copy(alpha = 0.4f),
                            contentColor = Color(0xFFF44336),
                            focusedContentColor = Color(0xFFF44336)
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.sm))
                    ) {
                        Icon(
                            imageVector = Icons.Default.LinkOff,
                            contentDescription = stringResource(R.string.cd_unlink),
                            modifier = Modifier.size(NuvioTheme.spacing.lg)
                        )
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                        Text(stringResource(R.string.account_unlink), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            }
        }
    }
}

@Composable
private fun ReceiveRecommendationsSection(
    permissions: List<MyPermissionDto>,
    onAdd: () -> Unit,
    onEditAlias: (id: String, currentAlias: String?) -> Unit
) {
    val allowed = permissions.filter { it.status == "allowed" }
    val pending = permissions.filter { it.status == "pending" }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.ThumbUp,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NuvioTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Text(
                text = stringResource(R.string.account_receive_recommendations_title),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onAdd,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                Text(stringResource(R.string.account_receive_recommendations_add))
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        if (allowed.isEmpty() && pending.isEmpty()) {
            Text(
                text = stringResource(R.string.account_receive_recommendations_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary
            )
        } else {
            allowed.forEach { permission ->
                PermissionRow(
                    label = permission.alias?.takeIf { it.isNotBlank() }
                        ?: permission.sourceName
                        ?: permission.sourceId,
                    trailingLabel = stringResource(R.string.account_receive_recommendations_edit_alias),
                    onTrailingClick = { onEditAlias(permission.id, permission.alias) }
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            }
            if (pending.isNotEmpty()) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    text = stringResource(R.string.account_receive_recommendations_pending, pending.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextTertiary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                pending.forEach { permission ->
                    PermissionRow(
                        label = permission.sourceName ?: permission.sourceId,
                        trailingLabel = null,
                        onTrailingClick = null
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    trailingLabel: String?,
    onTrailingClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioTheme.colors.BackgroundCard,
                shape = RoundedCornerShape(NuvioTheme.radii.sm)
            )
            .padding(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailingLabel != null && onTrailingClick != null) {
            Button(
                onClick = onTrailingClick,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                Text(trailingLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RosterPickerDialog(
    roster: List<RosterEntryDto>,
    query: String,
    isPending: Boolean,
    error: String?,
    onQueryChanged: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { primaryFocusRequester.requestFocus() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.account_receive_recommendations_add),
        subtitle = stringResource(R.string.account_roster_picker_subtitle),
        width = 500.dp
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            singleLine = true,
            shape = RoundedCornerShape(NuvioTheme.radii.md),
            placeholder = {
                Text(
                    text = stringResource(R.string.account_roster_picker_filter_placeholder),
                    color = NuvioTheme.colors.TextTertiary
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
                focusedIndicatorColor = NuvioTheme.colors.FocusRing,
                unfocusedIndicatorColor = NuvioTheme.colors.Border
            )
        )

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        if (!error.isNullOrBlank()) {
            Text(text = error, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFB6B6))
        }

        if (roster.isEmpty() && !isPending && error.isNullOrBlank()) {
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
            items(roster, key = { it.userId }) { entry ->
                Button(
                    onClick = { onPick(entry.userId) },
                    enabled = !isPending,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(text = entry.name ?: entry.userId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AliasEditDialog(
    value: String,
    onValueChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { primaryFocusRequester.requestFocus() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.account_receive_recommendations_edit_alias),
        width = 420.dp
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            singleLine = true,
            shape = RoundedCornerShape(NuvioTheme.radii.md),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
                focusedIndicatorColor = NuvioTheme.colors.FocusRing,
                unfocusedIndicatorColor = NuvioTheme.colors.Border
            )
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Button(
                onClick = onSave,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
private fun SignOutButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFFC62828).copy(alpha = 0.15f),
            focusedContainerColor = Color(0xFFC62828).copy(alpha = 0.3f),
            contentColor = Color(0xFFF44336),
            focusedContentColor = Color(0xFFF44336)
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Logout,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Text(
                text = stringResource(R.string.account_sign_out),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
