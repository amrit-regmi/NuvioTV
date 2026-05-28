package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.ui.components.TraktTenStarRow
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroContentSection(
    meta: Meta,
    nextEpisode: Video?,
    nextToWatch: NextToWatch?,
    onPlayClick: () -> Unit,
    onPlayLongPress: (() -> Unit)? = null,
    isInLibrary: Boolean,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    isMovieWatched: Boolean,
    isMovieWatchedPending: Boolean,
    onToggleMovieWatched: () -> Unit,
    trailerAvailable: Boolean = false,
    onTrailerClick: () -> Unit = {},
    hideLogoDuringTrailer: Boolean = false,
    mdbListRatings: MDBListRatings? = null,
    hideMetaInfoImdb: Boolean = false,
    tmdbRating: Float? = null,
    showFullReleaseDate: Boolean = true,
    isTrailerPlaying: Boolean = false,
    playButtonFocusRequester: FocusRequester? = null,
    restorePlayFocusToken: Int = 0,
    onHeroActionFocused: () -> Unit = {},
    onPlayFocusRestored: () -> Unit = {},
    traktAuthenticated: Boolean = false,
    isRatingLoaded: Boolean = false,
    userRating: Int? = null,
    showRatingPicker: Boolean = false,
    ratingPickerDefault: Int = 6,
    isRatingPending: Boolean = false,
    onReactionSelected: (TraktReaction) -> Unit = {},
    onRatingSelected: (Int) -> Unit = {},
    onDismissRatingPicker: () -> Unit = {},
    onSubmitRating: () -> Unit = {}
) {
    val context = LocalContext.current
    val isSeriesApi = remember(meta.apiType) {
        meta.apiType.equals("series", ignoreCase = true) || meta.apiType.equals("tv", ignoreCase = true)
    }
    val logoModel = remember(context, meta.logo) {
        meta.logo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .crossfade(true)
                .build()
        }
    }
    var logoLoadFailed by remember(meta.logo) { mutableStateOf(false) }
    val shouldShowLogo =
        !meta.logo.isNullOrBlank() &&
            !logoLoadFailed &&
            !(isTrailerPlaying && hideLogoDuringTrailer)
    val libraryAddPainter = rememberRawSvgPainter(
        context = context,
        rawRes = com.nuvio.tv.R.raw.library_add_plus
    )
    val trailerPainter = rememberRawSvgPainter(
        context = context,
        rawRes = com.nuvio.tv.R.raw.trailer_play_button
    )
    val strCreator = stringResource(R.string.hero_creator)
    val strDirector = stringResource(R.string.hero_director)
    val strWriter = stringResource(R.string.hero_writer)
    val creditLine = remember(meta.director, meta.writer, isSeriesApi) {
        val directorLine = meta.director.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val writerLine = meta.writer.takeIf { it.isNotEmpty() }?.joinToString(", ")
        when {
            !directorLine.isNullOrBlank() -> {
                if (isSeriesApi) strCreator.format(directorLine) else strDirector.format(directorLine)
            }
            !writerLine.isNullOrBlank() -> strWriter.format(writerLine)
            else -> null
        }
    }

    // Animate logo properties for trailer mode
    val logoHeight by animateDpAsState(
        targetValue = if (isTrailerPlaying) 60.dp else 100.dp,
        animationSpec = tween(600),
        label = "logoHeight"
    )
    val logoBottomPadding by animateDpAsState(
        targetValue = if (isTrailerPlaying) 24.dp else 16.dp,
        animationSpec = tween(600),
        label = "logoPadding"
    )
    val logoMaxWidth by animateFloatAsState(
        targetValue = if (isTrailerPlaying) 0.25f else 0.4f,
        animationSpec = tween(600),
        label = "logoWidth"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(540.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(600))
                .padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Logo/Title — always visible during trailer, animates size
            if (shouldShowLogo) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = meta.name,
                    onError = { logoLoadFailed = true },
                    modifier = Modifier
                        .height(logoHeight)
                        .fillMaxWidth(logoMaxWidth)
                        .padding(bottom = logoBottomPadding),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart
                )
            } else {
                // Text title hides entirely during trailer
                AnimatedVisibility(
                    visible = !isTrailerPlaying,
                    enter = fadeIn(tween(400)),
                    exit = fadeOut(tween(400))
                ) {
                    Text(
                        text = meta.name,
                        style = MaterialTheme.typography.displayMedium,
                        color = NuvioColors.TextPrimary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            // Everything below the logo fades out during trailer
            AnimatedVisibility(
                visible = isTrailerPlaying && !hideLogoDuringTrailer,
                enter = fadeIn(tween(600)),
                exit = fadeOut(tween(300))
            ) {
                Text(
                    text = stringResource(R.string.hero_press_back_trailer),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioColors.TextTertiary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Everything below the logo fades out during trailer
            AnimatedVisibility(
                visible = !isTrailerPlaying,
                enter = fadeIn(tween(400)),
                exit = fadeOut(tween(400))
            ) {
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayButton(
                            text = nextToWatch?.displayText ?: when {
                                nextEpisode != null && nextEpisode.season != null && nextEpisode.episode != null ->
                                    stringResource(R.string.hero_play_episode, nextEpisode.season, nextEpisode.episode)
                                nextEpisode != null -> stringResource(R.string.hero_play)
                                else -> stringResource(R.string.hero_play)
                            },
                            onClick = onPlayClick,
                            onLongPress = onPlayLongPress,
                            focusRequester = playButtonFocusRequester,
                            restoreFocusToken = restorePlayFocusToken,
                            onFocusRestored = {
                                onHeroActionFocused()
                                onPlayFocusRestored()
                            }
                        )

                        ActionIconButton(
                            icon = if (isInLibrary) Icons.Default.Check else null,
                            painter = if (!isInLibrary) {
                                libraryAddPainter
                            } else {
                                null
                            },
                            contentDescription = if (isInLibrary) stringResource(R.string.hero_remove_from_library) else stringResource(R.string.hero_add_to_library),
                            onClick = onToggleLibrary,
                            onLongPress = onLibraryLongPress,
                            onFocused = onHeroActionFocused
                        )

                        if (meta.apiType == "movie") {
                            ActionIconButton(
                                icon = if (isMovieWatched) {
                                    Icons.Default.Visibility
                                } else {
                                    Icons.Default.VisibilityOff
                                },
                                contentDescription = if (isMovieWatched) {
                                    stringResource(R.string.hero_mark_unwatched)
                                } else {
                                    stringResource(R.string.hero_mark_watched)
                                },
                                onClick = onToggleMovieWatched,
                                enabled = !isMovieWatchedPending,
                                selected = isMovieWatched,
                                selectedContainerColor = Color.White,
                                selectedContentColor = Color.Black,
                                onFocused = onHeroActionFocused
                            )
                        }

                        if (trailerAvailable) {
                            ActionIconButtonPainter(
                                painter = trailerPainter,
                                contentDescription = stringResource(R.string.hero_play_trailer),
                                onClick = onTrailerClick,
                                onFocused = onHeroActionFocused
                            )
                        }

                        if (traktAuthenticated && isRatingLoaded && (meta.apiType != "movie" || isMovieWatched)) {
                            TraktRatingArea(
                                userRating = userRating,
                                showRatingPicker = showRatingPicker,
                                ratingPickerDefault = ratingPickerDefault,
                                isRatingPending = isRatingPending,
                                onReactionSelected = onReactionSelected,
                                onRatingSelected = onRatingSelected,
                                onSubmitRating = onSubmitRating,
                                onHeroActionFocused = onHeroActionFocused
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Director/Writer line above description
                    if (!creditLine.isNullOrBlank()) {
                        Text(
                            text = creditLine,
                            style = MaterialTheme.typography.labelLarge,
                            color = NuvioTheme.extendedColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(0.6f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (mdbListRatings?.isEmpty() == false) {
                        MDBListRatingsRow(ratings = mdbListRatings)
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Always show series/movie description, not episode description
                    if (meta.description != null) {
                        Text(
                            text = meta.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextPrimary,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .padding(bottom = 12.dp)
                        )
                    }

                    MetaInfoRow(
                        meta = meta,
                        hideImdbRating = hideMetaInfoImdb,
                        showFullReleaseDate = showFullReleaseDate,
                        tmdbRating = tmdbRating
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun PlayButton(
    text: String,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    restoreFocusToken: Int = 0,
    onFocusRestored: () -> Unit = {}
) {
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()

    LaunchedEffect(restoreFocusToken) {
        if (restoreFocusToken > 0 && focusRequester != null) {
            focusRequester.requestFocusAfterFrames()
        }
    }
    val context = LocalContext.current
    val playPainter = rememberRawSvgPainter(
        context = context,
        rawRes = com.nuvio.tv.R.raw.ic_player_play
    )

    Button(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                onClick()
            }
        },
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused) {
                    onFocusRestored()
                }
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (onLongPress != null && native.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }
                }
                if (onLongPress != null &&
                    longPressKeyTracker.handle(native, ::isSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                    }
                ) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) {
                        longPressTriggered = false
                    }
                    return@onPreviewKeyEvent true
                }

                if (native.action == AndroidKeyEvent.ACTION_UP && longPressTriggered) {
                    if (isSelectOrMenuKey(native.keyCode)) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusProperties { up = FocusRequester.Cancel },
        colors = ButtonDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.White,
            focusedContainerColor = androidx.compose.ui.graphics.Color.White,
            contentColor = androidx.compose.ui.graphics.Color.Black,
            focusedContentColor = androidx.compose.ui.graphics.Color.Black
        ),
        shape = ButtonDefaults.shape(
            shape = RoundedCornerShape(32.dp)
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(32.dp)
            )
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = playPainter,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ActionIconButtonPainter(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .onFocusChanged { state ->
                if (state.isFocused) onFocused()
            }
            .focusProperties { up = FocusRequester.Cancel },
        colors = IconButtonDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.Secondary,
            contentColor = NuvioColors.TextPrimary,
            focusedContentColor = NuvioColors.OnSecondary
        ),
        border = IconButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = CircleShape
            )
        ),
        shape = IconButtonDefaults.shape(
            shape = CircleShape
        )
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    painter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    selectedContainerColor: Color = Color(0xFF7CFF9B),
    selectedContentColor: Color = Color.Black,
    containerColorOverride: Color? = null,
    contentColorOverride: Color? = null,
    focusedContainerColor: Color = NuvioColors.Secondary,
    focusedContentColor: Color = NuvioColors.OnSecondary,
    showFocusBorder: Boolean = true,
    iconScale: Float = 1f,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {}
) {
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()

    IconButton(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                onClick()
            }
        },
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state ->
                if (state.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (onLongPress != null && native.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }
                }
                if (onLongPress != null &&
                    longPressKeyTracker.handle(native, ::isSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                    }
                ) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) {
                        longPressTriggered = false
                    }
                    return@onPreviewKeyEvent true
                }

                if (native.action == AndroidKeyEvent.ACTION_UP && longPressTriggered) {
                    if (isSelectOrMenuKey(native.keyCode)) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusProperties { up = FocusRequester.Cancel },
        colors = IconButtonDefaults.colors(
            containerColor = containerColorOverride ?: if (selected) selectedContainerColor else NuvioColors.BackgroundCard,
            focusedContainerColor = focusedContainerColor,
            contentColor = contentColorOverride ?: if (selected) selectedContentColor else NuvioColors.TextPrimary,
            focusedContentColor = focusedContentColor
        ),
        border = IconButtonDefaults.border(
            focusedBorder = if (showFocusBorder) Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = CircleShape
            ) else Border.None
        ),
        shape = IconButtonDefaults.shape(
            shape = CircleShape
        )
    ) {
        when {
            painter != null -> Icon(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp).scale(iconScale)
            )
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp).scale(iconScale)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaInfoRow(
    meta: Meta,
    hideImdbRating: Boolean,
    showFullReleaseDate: Boolean = true,
    tmdbRating: Float? = null
) {
    val context = LocalContext.current
    val genresText = remember(meta.genres) { meta.genres.joinToString(" • ") }
    val runtimeText = remember(meta.runtime) { meta.runtime?.let { formatRuntime(it) } }
    val yearText = remember(meta.releaseInfo, meta.released, meta.type, showFullReleaseDate) {
        if (showFullReleaseDate && meta.type == ContentType.MOVIE) {
            meta.released
                ?.let { runCatching { java.time.OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }
                ?.let { val locale = java.util.Locale.getDefault(); java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMMy"), locale).format(java.util.Date(it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())) }
                ?: formatYearRange(meta.releaseInfo)
        } else {
            formatYearRange(meta.releaseInfo)
        }
    }
    val imdbRating = if (hideImdbRating) null else meta.imdbRating
    val shouldShowImdbRating = imdbRating != null
    val imdbModel = remember(context) {
        ImageRequest.Builder(context)
            .data(com.nuvio.tv.R.raw.imdb_logo_2016)
            .build()
    }
    val shouldShowTmdbRating = tmdbRating != null
    val tmdbModel = remember(context) {
        ImageRequest.Builder(context)
            .data(com.nuvio.tv.R.raw.mdblist_tmdb)
            .build()
    }
    val ageRatingBadge = remember(meta.ageRating) {
        meta.ageRating?.trim()?.takeIf { it.isNotBlank() }
    }
    val isSeries = meta.type == ContentType.SERIES || meta.type == ContentType.TV
    val strStatusEnded = stringResource(if (isSeries) R.string.series_status_ended else R.string.movie_status_ended)
    val strStatusContinuing = stringResource(if (isSeries) R.string.series_status_continuing else R.string.movie_status_continuing)
    val strStatusCurrent = stringResource(if (isSeries) R.string.series_status_current else R.string.movie_status_current)
    val strStatusCancelled = stringResource(if (isSeries) R.string.series_status_cancelled else R.string.movie_status_cancelled)
    val strStatusReleased = stringResource(if (isSeries) R.string.series_status_released else R.string.movie_status_released)
    val strStatusPlanned = stringResource(if (isSeries) R.string.series_status_planned else R.string.movie_status_planned)
    val strStatusRumored = stringResource(if (isSeries) R.string.series_status_rumored else R.string.movie_status_rumored)
    val strStatusInProduction = stringResource(if (isSeries) R.string.series_status_in_production else R.string.movie_status_in_production)
    val strStatusPostProduction = stringResource(if (isSeries) R.string.series_status_post_production else R.string.movie_status_post_production)
    val statusBadge = remember(meta.status, isSeries) {
        when (meta.status?.trim()?.lowercase()) {
            "ended" -> strStatusEnded.uppercase()
            "continuing", "returning series" -> strStatusContinuing.uppercase()
            "current" -> strStatusCurrent.uppercase()
            "cancelled", "canceled" -> strStatusCancelled.uppercase()
            "released" -> strStatusReleased.uppercase()
            "planned" -> strStatusPlanned.uppercase()
            "rumored" -> strStatusRumored.uppercase()
            "in production" -> strStatusInProduction.uppercase()
            "post production" -> strStatusPostProduction.uppercase()
            else -> meta.status?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
        }
    }
    Log.d("HeroBadge", "name=${meta.name} ageRating=${meta.ageRating} status=${meta.status} ageRatingBadge=$ageRatingBadge statusBadge=$statusBadge")
    val secondaryItems = remember(runtimeText, meta.country, meta.language) {
        buildList<String> {
            runtimeText?.takeIf { it.isNotBlank() }?.let { add(it) }
            meta.country?.trim()?.takeIf { it.isNotBlank() }?.let { add(normalizeCountryLabel(it)) }
            meta.language?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Primary row: Genres, Release, Ratings
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Show all genres
            if (meta.genres.isNotEmpty()) {
                Text(
                    text = genresText,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.extendedColors.textSecondary
                )
                if (yearText != null || shouldShowImdbRating || shouldShowTmdbRating) {
                    MetaInfoDivider()
                }
            }

            yearText?.let { year ->
                Text(
                    text = year,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.extendedColors.textSecondary
                )
                if (shouldShowImdbRating || shouldShowTmdbRating) {
                    MetaInfoDivider()
                }
            }

            imdbRating?.let { rating ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AsyncImage(
                        model = imdbModel,
                        contentDescription = stringResource(R.string.cd_rating),
                        modifier = Modifier.size(30.dp),
                        contentScale = ContentScale.Fit
                    )
                    val ratingText = remember(rating) { String.format("%.1f", rating) }
                    Text(
                        text = ratingText,
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.extendedColors.textSecondary
                    )
                }
            }

            tmdbRating?.let { rating ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AsyncImage(
                        model = tmdbModel,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        contentScale = ContentScale.Fit
                    )
                    val ratingText = remember(rating) { (rating * 10).toInt().toString() }
                    Text(
                        text = ratingText,
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.extendedColors.textSecondary
                    )
                }
            }
        }

        // Secondary row: Runtime, Age Rating, Status, Country, Language
        if (ageRatingBadge != null || statusBadge != null || secondaryItems.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ageRatingBadge != null && statusBadge != null) {
                    CombinedMetaBadge(
                        leftText = ageRatingBadge,
                        leftColor = NuvioColors.TextSecondary,
                        rightText = statusBadge,
                        rightColor = NuvioColors.TextPrimary
                    )
                } else {
                    ageRatingBadge?.let { badge ->
                        HeroMetaBadge(text = badge)
                    }
                    statusBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            contentColor = NuvioColors.TextPrimary
                        )
                    }
                }
                if ((ageRatingBadge != null || statusBadge != null) && secondaryItems.isNotEmpty()) {
                    MetaInfoDivider()
                }
                secondaryItems.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioColors.TextPrimary
                    )
                    if (index < secondaryItems.lastIndex) {
                        MetaInfoDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroMetaBadge(
    text: String,
    contentColor: Color = NuvioColors.TextSecondary
) {
    Box(
        modifier = Modifier
            .border(
                border = BorderStroke(1.dp, contentColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun CombinedMetaBadge(
    leftText: String,
    leftColor: Color = NuvioColors.TextSecondary,
    rightText: String,
    rightColor: Color = NuvioColors.TextPrimary
) {
    val dividerColor = leftColor.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .border(
                border = BorderStroke(1.dp, dividerColor),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = leftText,
            style = MaterialTheme.typography.labelMedium,
            color = leftColor,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(12.dp)
                .background(dividerColor)
        )
        Text(
            text = rightText,
            style = MaterialTheme.typography.labelMedium,
            color = rightColor,
            maxLines = 1
        )
    }
}

private fun normalizeCountryLabel(raw: String): String {
    val displayLocale = Locale.getDefault()
    return raw
        .split(",")
        .joinToString(", ") { part ->
            val code = part.trim()
            if (code.matches(Regex("[A-Za-z]{2}"))) {
                Locale("", code).getDisplayCountry(displayLocale).takeIf { it.isNotBlank() } ?: code
            } else {
                code
            }
        }
}

@Composable
private fun MDBListRatingsRow(ratings: MDBListRatings) {
    val context = LocalContext.current
    val items = remember(ratings) {
        listOf(
            Triple("trakt", com.nuvio.tv.R.raw.mdblist_trakt, ratings.trakt),
            Triple("imdb", com.nuvio.tv.R.raw.imdb_logo_2016, ratings.imdb),
            Triple("tmdb", com.nuvio.tv.R.raw.mdblist_tmdb, ratings.tmdb),
            Triple("letterboxd", com.nuvio.tv.R.raw.mdblist_letterboxd, ratings.letterboxd),
            Triple("tomatoes", com.nuvio.tv.R.raw.mdblist_tomatoes, ratings.tomatoes)
        ).filter { it.third != null }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (provider, logoRes, rating) ->
            val resolvedRating = rating ?: return@forEach
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val model = remember(context, logoRes) {
                    ImageRequest.Builder(context)
                        .data(logoRes)
                        .build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Fit
                )
                Text(
                    text = formatMDBListRating(provider, resolvedRating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }

        ratings.audience?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = com.nuvio.tv.R.drawable.mdblist_audience),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = formatMDBListRating("audience", rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }

        ratings.metacritic?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = com.nuvio.tv.R.drawable.mdblist_metacritic),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = formatMDBListRating("metacritic", rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

private fun isSelectOrMenuKey(keyCode: Int): Boolean {
    return isSelectKey(keyCode) || keyCode == AndroidKeyEvent.KEYCODE_MENU
}

private fun formatMDBListRating(provider: String, rating: Double): String {
    return when (provider) {
        "imdb", "tmdb", "letterboxd" -> String.format("%.1f", rating)
        else -> {
            if (rating % 1.0 == 0.0) rating.toInt().toString() else String.format("%.1f", rating)
        }
    }
}


private fun formatYearRange(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return releaseInfo.trim()
}

private fun formatRuntime(runtime: String): String {
    val trimmed = runtime.trim()
    // Already in "Xh Ym" or "Xh" format
    if (trimmed.contains('h') || trimmed.contains('m')) {
        val hours = Regex("(\\d+)\\s*h").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = Regex("(\\d+)\\s*m").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + mins
        if (total > 0) return if (total >= 60) {
            val h = total / 60; val m = total % 60
            if (m > 0) "${h}h ${m}m" else "${h}h"
        } else "${total}m"
    }
    // "H:MM" or "HH:MM" format
    if (trimmed.contains(':')) {
        val parts = trimmed.split(':')
        val hours = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val mins = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + mins
        if (total > 0) return if (total >= 60) {
            val m = total % 60
            if (m > 0) "${hours}h ${m}m" else "${hours}h"
        } else "${total}m"
    }
    // Plain number (minutes)
    val minutes = trimmed.filter { it.isDigit() }.toIntOrNull() ?: return runtime
    return if (minutes >= 60) {
        val hours = minutes / 60
        val mins = minutes % 60
        if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
    } else {
        "${minutes}m"
    }
}

@Composable
private fun rememberRawSvgPainter(
    context: android.content.Context,
    @androidx.annotation.RawRes rawRes: Int
): Painter {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { 24.dp.roundToPx() }
    val model = remember(rawRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(rawRes)
            .size(sizePx)
            .build()
    }
    return coil3.compose.rememberAsyncImagePainter(model = model)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaInfoDivider() {
    Text(
        text = "•",
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.extendedColors.textTertiary
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TraktRatingArea(
    userRating: Int?,
    showRatingPicker: Boolean,
    ratingPickerDefault: Int,
    isRatingPending: Boolean,
    onReactionSelected: (TraktReaction) -> Unit,
    onRatingSelected: (Int) -> Unit,
    onSubmitRating: () -> Unit,
    onHeroActionFocused: () -> Unit = {}
) {
    var isExpanded by remember { mutableStateOf(false) }
    var expandBlocked by remember { mutableStateOf(false) }
    var pickerWasOpen by remember { mutableStateOf(false) }
    var isRotating by remember { mutableStateOf(false) }
    var isCelebrating by remember { mutableStateOf(false) }
    var ratingWasChanged by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val collapseJob = remember { arrayOf<Job?>(null) }

    val dislikeFR = remember { FocusRequester() }
    val likeFR = remember { FocusRequester() }
    val loveFR = remember { FocusRequester() }
    val pickerFR = remember { FocusRequester() }
    val collapsedFR = remember { FocusRequester() }

    // Picker open/close: handles animation sequencing AND focus.
    // Focus is requested here (not in LaunchedEffect(areaState)) because in the no-change
    // close path there are no suspension points — LaunchedEffect(showRatingPicker) runs to
    // completion before LaunchedEffect(areaState) gets scheduled, so any flag set here is
    // already reset by the time a second effect reads it.
    LaunchedEffect(showRatingPicker) {
        if (showRatingPicker) {
            delay(50) // let AnimatedContent compose and lay out state 2
            try { pickerFR.requestFocus() } catch (_: Exception) {}
        } else if (pickerWasOpen) {
            expandBlocked = true
            isExpanded = false
            delay(50)                // let state 0 compose
            if (ratingWasChanged) {
                isRotating = true
                isCelebrating = true
                try { collapsedFR.requestFocus() } catch (_: Exception) {}
                delay(500)           // wobble
                isRotating = false   // spring back
                delay(500)           // color lingers
                isCelebrating = false
                delay(50)
                try { collapsedFR.requestFocus() } catch (_: Exception) {}
                delay(100)
            } else {
                try { collapsedFR.requestFocus() } catch (_: Exception) {}
                delay(100)
            }
            expandBlocked = false
            ratingWasChanged = false
        }
        pickerWasOpen = showRatingPicker
    }

    // 3-button row expansion focus: fires whenever isExpanded flips to true.
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            val fr = when {
                userRating != null && userRating <= 4 -> dislikeFR
                userRating != null && userRating >= 8 -> loveFR
                else -> likeFR
            }
            try { fr.requestFocus() } catch (_: Exception) {}
        }
    }

    // pickerWasOpen is still true for one recomposition after showRatingPicker→false;
    // using it here prevents a 2→1→0 double-transition that causes a visible flash.
    val areaState = when {
        showRatingPicker -> 2
        isExpanded && !pickerWasOpen -> 1
        else -> 0
    }

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .height(56.dp)
            .onFocusChanged { state ->
                if (!state.hasFocus && !showRatingPicker) {
                    collapseJob[0]?.cancel()
                    collapseJob[0] = scope.launch {
                        delay(400)
                        isExpanded = false
                    }
                } else if (state.hasFocus) {
                    collapseJob[0]?.cancel()
                }
            }
    ) {
        AnimatedContent(
            targetState = areaState,
            transitionSpec = {
                // Content cuts instantly (no color bleed). Size animates smoothly with clip=false
                // so content renders at its full natural size throughout — no bounding-box rectangle.
                (fadeIn(tween(0)) togetherWith fadeOut(tween(0)))
                    .using(SizeTransform(clip = false) { _, _ -> tween(150) })
            },
            label = "traktRatingArea"
        ) { state ->
            when (state) {
                0 -> {
                    val isDislike = userRating != null && userRating <= 4
                    val isLike    = userRating != null && userRating in 5..7
                    val isLove    = userRating != null && userRating >= 8
                    val (icon, selectedColor) = when {
                        isDislike -> Icons.Default.ThumbDown to Color(0xFFE53935)
                        isLike    -> Icons.Default.ThumbUp   to Color(0xFF43A047)
                        isLove    -> Icons.Default.Favorite  to Color(0xFFE91E63)
                        else      -> Icons.Default.Star      to NuvioColors.Secondary
                    }
                    val targetRot = when {
                        !isRotating -> 0f
                        isDislike   -> 15f
                        isLike      -> -15f
                        else        -> 0f
                    }
                    val celebRot by animateFloatAsState(
                        targetValue = targetRot,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "celebRot"
                    )
                    val celebScale by animateFloatAsState(
                        targetValue = if (isRotating && isLove) 1.4f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "celebScale"
                    )
                    Box(modifier = Modifier.rotate(celebRot)) {
                        ActionIconButton(
                            icon = icon,
                            contentDescription = if (userRating != null) "$userRating/10 on Trakt" else "Rate on Trakt",
                            onClick = { if (!expandBlocked) { ratingWasChanged = false; isExpanded = true } },
                            selected = userRating != null,
                            selectedContainerColor = selectedColor,
                            selectedContentColor = Color.White,
                            focusedContainerColor = if (isCelebrating && userRating != null) selectedColor else NuvioColors.Secondary,
                            focusedContentColor = if (isCelebrating && userRating != null) Color.White else NuvioColors.OnSecondary,
                            iconScale = celebScale,
                            focusRequester = collapsedFR,
                            onFocused = { onHeroActionFocused() }
                        )
                    }
                }
                1 -> {
                    val currentReaction = when {
                        userRating == null -> null
                        userRating <= 4    -> TraktReaction.DISLIKE
                        userRating in 5..7 -> TraktReaction.LIKE
                        else               -> TraktReaction.LOVE
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionIconButton(
                            icon = Icons.Default.ThumbDown,
                            contentDescription = stringResource(R.string.trakt_rating_dislike),
                            onClick = {
                                if (currentReaction != TraktReaction.DISLIKE) ratingWasChanged = true
                                onReactionSelected(TraktReaction.DISLIKE)
                            },
                            selected = userRating != null && userRating <= 4,
                            selectedContainerColor = Color(0xFFE53935),
                            selectedContentColor = Color.White,
                            focusRequester = dislikeFR,
                            onFocused = onHeroActionFocused
                        )
                        ActionIconButton(
                            icon = Icons.Default.ThumbUp,
                            contentDescription = stringResource(R.string.trakt_rating_like),
                            onClick = {
                                if (currentReaction != TraktReaction.LIKE) ratingWasChanged = true
                                onReactionSelected(TraktReaction.LIKE)
                            },
                            selected = userRating != null && userRating in 5..7,
                            selectedContainerColor = Color(0xFF43A047),
                            selectedContentColor = Color.White,
                            focusRequester = likeFR,
                            onFocused = onHeroActionFocused
                        )
                        ActionIconButton(
                            icon = Icons.Default.Favorite,
                            contentDescription = stringResource(R.string.trakt_rating_love),
                            onClick = {
                                if (currentReaction != TraktReaction.LOVE) ratingWasChanged = true
                                onReactionSelected(TraktReaction.LOVE)
                            },
                            selected = userRating != null && userRating >= 8,
                            selectedContainerColor = Color(0xFFE91E63),
                            selectedContentColor = Color.White,
                            focusRequester = loveFR,
                            onFocused = onHeroActionFocused
                        )
                    }
                }
                else -> {
                    InlineStarPicker(
                        rating = ratingPickerDefault,
                        isSubmitting = isRatingPending,
                        onRatingSelected = { r -> ratingWasChanged = true; onRatingSelected(r) },
                        onAutoSubmit = onSubmitRating,
                        focusRequester = pickerFR
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineStarPicker(
    rating: Int,
    isSubmitting: Boolean,
    onRatingSelected: (Int) -> Unit,
    onAutoSubmit: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    var selectedRating by remember { mutableIntStateOf(rating) }
    var isPersisting by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var readyToAutoSubmit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dwellJob = remember { arrayOf<Job?>(null) }
    val autoCloseJob = remember { arrayOf<Job?>(null) }

    LaunchedEffect(rating) { selectedRating = rating }

    // Guard: don't trigger focus-away submit during AnimatedContent transition (first ~400ms)
    LaunchedEffect(Unit) {
        delay(400)
        readyToAutoSubmit = true
    }

    val persistIconSize by animateDpAsState(
        targetValue = if (isPersisting) 28.dp else 24.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "starBloom"
    )

    fun triggerSubmit() {
        if (isPersisting) return
        autoCloseJob[0]?.cancel()
        dwellJob[0]?.cancel()
        scope.launch {
            isPersisting = true
            delay(300)
            onAutoSubmit()
        }
    }

    fun restartDwell() {
        autoCloseJob[0]?.cancel()
        dwellJob[0]?.cancel()
        dwellJob[0] = scope.launch {
            delay(1500)
            triggerSubmit()
        }
    }

    // Auto-submit 1.5s after picker opens if user doesn't interact (same as dwell timer)
    DisposableEffect(Unit) {
        autoCloseJob[0] = scope.launch {
            delay(1500)
            triggerSubmit()
        }
        onDispose { autoCloseJob[0]?.cancel() }
    }

    val reactionIcon = when {
        selectedRating <= 4 -> Icons.Default.ThumbDown
        selectedRating <= 7 -> Icons.Default.ThumbUp
        else -> Icons.Default.Favorite
    }
    val reactionTint = when {
        selectedRating <= 4 -> Color(0xFFE53935)
        selectedRating <= 7 -> Color(0xFF43A047)
        else -> Color(0xFFE91E63)
    }

    Row(
        modifier = modifier
            .background(
                NuvioColors.Surface.copy(alpha = if (isFocused) 0.95f else 0.88f),
                RoundedCornerShape(24.dp)
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NuvioColors.FocusRing else NuvioColors.TextDisabled.copy(alpha = 0.22f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (!state.isFocused && !state.hasFocus && readyToAutoSubmit) {
                    dwellJob[0]?.cancel()
                    triggerSubmit()
                }
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (selectedRating > 1) {
                            selectedRating--
                            onRatingSelected(selectedRating)
                            restartDwell()
                            true
                        } else false  // let focus exit at left edge
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (selectedRating < 10) {
                            selectedRating++
                            onRatingSelected(selectedRating)
                            restartDwell()
                            true
                        } else false  // let focus exit at right edge
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        triggerSubmit()
                        true
                    }
                    else -> false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = reactionIcon,
            contentDescription = null,
            tint = reactionTint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        // 5-star display: rating 1-10 maps to 0.5–5 stars (½★=1, ★=2, ★½=3, …, ★★★★★=10)
        val fullStars = selectedRating / 2
        val hasHalfStar = selectedRating % 2 == 1
        repeat(5) { index ->
            val icon = when {
                index < fullStars -> Icons.Default.Star
                index == fullStars && hasHalfStar -> Icons.Default.StarHalf
                else -> Icons.Default.StarBorder
            }
            val isActive = index < fullStars || (index == fullStars && hasHalfStar)
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) NuvioColors.Secondary else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(if (isPersisting && isActive) persistIconSize else 24.dp)
            )
        }
    }
}
