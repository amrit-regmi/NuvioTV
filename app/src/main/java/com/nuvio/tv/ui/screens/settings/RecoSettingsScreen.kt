@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

@Composable
internal fun RecoSettingsContent(
    initialFocusRequester: FocusRequester,
    viewModel: RecoSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        runCatching { initialFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "Recommendations",
            subtitle = "Configure your personal recommendation engine"
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    uiState.error != null -> {
                        Text(
                            text = uiState.error!!,
                            color = NuvioColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.issueWatchlyKey() },
                            modifier = Modifier.focusRequester(initialFocusRequester)
                        ) {
                            Text("Retry")
                        }
                    }

                    uiState.url != null -> {
                        if (uiState.qrBitmap != null) {
                            Image(
                                bitmap = uiState.qrBitmap!!.asImageBitmap(),
                                contentDescription = "Reco engine QR code",
                                modifier = Modifier
                                    .size(200.dp)
                                    .background(Color.White, RoundedCornerShape(12.dp)),
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
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Generating key…",
                                color = NuvioColors.TextSecondary
                            )
                        }
                    }

                    else -> {
                        Text(
                            text = "Open the Reco engine UI on your phone or laptop by scanning the QR code. The key expires after 15 minutes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.issueWatchlyKey() },
                            modifier = Modifier.focusRequester(initialFocusRequester)
                        ) {
                            Text("Configure on another device")
                        }
                    }
                }
            }
        }
    }
}
