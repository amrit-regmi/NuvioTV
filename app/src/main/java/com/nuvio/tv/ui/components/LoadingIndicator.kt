package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Primary full-screen / section loading indicator.
 *
 * Now renders the CineX brand loader (the animated dot-equaliser lens mark) instead of a plain
 * circular spinner, so every primary loader across the app shows the brand animation. Small inline,
 * button, and pagination-footer spinners intentionally keep plain [androidx.compose.material3.CircularProgressIndicator]s.
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        CineXLoader(size = size)
    }
}
