package com.nuvio.tv.ui.screens.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Dismissable one-time prompt shown after the user's first login, nudging them to set up
 * personalized recommendations on the web (personalization is web-only). Deep-links via a QR
 * to the web personalization settings; fully optional — the user can dismiss with "Maybe later".
 */
@Composable
fun PersonalizeNudgeDialog(
    personalizeUrl: String,
    onDismiss: (dontShowAgain: Boolean) -> Unit
) {
    val qrBitmap = remember(personalizeUrl) {
        runCatching { QrCodeGenerator.generate(personalizeUrl, 360) }.getOrNull()
    }
    // "Do not show this again" — when checked, the caller persists the per-profile
    // don't-show flag so the nudge never auto-shows for this profile again.
    var dontShowAgain by remember { mutableStateOf(false) }
    val dismissFocusRequester = remember { FocusRequester() }
    var dismissFocused by remember { mutableStateOf(false) }
    var dontShowFocused by remember { mutableStateOf(false) }
    // Retry focus until the "Maybe later" button actually gains focus: a single requestFocus()
    // can race the dialog's layout/attach, leaving the remote unable to interact (the reported
    // "couldn't click Maybe later" bug).
    LaunchedEffect(dismissFocused) {
        if (dismissFocused) return@LaunchedEffect
        repeat(20) {
            runCatching { dismissFocusRequester.requestFocus() }
            kotlinx.coroutines.delay(80)
            if (dismissFocused) return@LaunchedEffect
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .background(
                    color = NuvioTheme.colors.BackgroundCard,
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                )
                .border(
                    BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing.copy(alpha = 0.25f)),
                    RoundedCornerShape(NuvioTheme.radii.md)
                )
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.personalize_nudge_title),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.personalize_nudge_title),
                    modifier = Modifier
                        .size(180.dp)
                        .background(Color.White, RoundedCornerShape(NuvioTheme.radii.sm))
                        .padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Text(
                text = stringResource(R.string.personalize_nudge_body),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.personalize_nudge_url_hint),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                textAlign = TextAlign.Center
            )

            // "Do not show this again" — a focusable checkbox row. Toggling it flips the local
            // state; the persisted flag is written on dismissal (so either button path honours it).
            Button(
                onClick = { dontShowAgain = !dontShowAgain },
                modifier = Modifier
                    .onFocusChanged { dontShowFocused = it.isFocused },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                ),
                border = ButtonDefaults.border(
                    border = Border(
                        border = BorderStroke(
                            NuvioTheme.spacing.xxs,
                            NuvioTheme.colors.FocusRing.copy(alpha = if (dontShowFocused) 1f else 0.3f)
                        ),
                        shape = RoundedCornerShape(NuvioTheme.radii.sm)
                    ),
                    focusedBorder = Border(
                        border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                        shape = RoundedCornerShape(NuvioTheme.radii.sm)
                    )
                )
            ) {
                Text(
                    text = (if (dontShowAgain) "☑  " else "☐  ") +
                        stringResource(R.string.personalize_nudge_dont_show)
                )
            }
            Text(
                text = stringResource(R.string.personalize_nudge_dont_show_hint),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = { onDismiss(dontShowAgain) },
                modifier = Modifier
                    .focusRequester(dismissFocusRequester)
                    .onFocusChanged { dismissFocused = it.isFocused },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    contentColor = NuvioTheme.colors.OnSecondary
                ),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                        shape = RoundedCornerShape(NuvioTheme.radii.sm)
                    )
                )
            ) {
                Text(text = stringResource(R.string.personalize_nudge_dismiss))
            }
        }
    }
}
