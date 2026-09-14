package com.vibeplayer.app.ui.home

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.vibeplayer.app.R
import com.vibeplayer.app.model.MediaItem
import com.vibeplayer.app.model.MediaLibrary
import com.vibeplayer.app.ui.components.pressScale
import com.vibeplayer.app.ui.components.AppMessageCard
import com.vibeplayer.app.ui.navigation.Routes
import com.vibeplayer.app.util.seasonEpisodeText
import kotlinx.coroutines.delay

/** Server home: cinematic recommended hero, continue watching and libraries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(state.serverName) },
                navigationIcon = {
                    // Return to the service (main) page.
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.search(state.serverId)) }) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search))
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.loading && state.libraries.isEmpty() && state.continueWatching.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        if (state.suggestedSeries.isNotEmpty()) {
                            item {
                                SuggestedHero(
                                    items = state.suggestedSeries,
                                    onItemClick = { item ->
                                        navController.navigate(Routes.details(state.serverId, item.id))
                                    },
                                    onPlay = { item ->
                                        navController.navigate(Routes.player(state.serverId, item.id))
                                    }
                                )
                            }
                        }
                        if (state.continueWatching.isNotEmpty()) {
                            item { RailHeader(stringResource(R.string.continue_watching)) }
                            item {
                                ContinueWatchingHero(
                                    items = state.continueWatching,
                                    onOpen = { item ->
                                        navController.navigate(Routes.details(state.serverId, item.id))
                                    },
                                    onResume = { item ->
                                        navController.navigate(Routes.player(state.serverId, item.id))
                                    }
                                )
                            }
                        }
                        if (state.libraries.isNotEmpty()) {
                            item { RailHeader(stringResource(R.string.libraries)) }
                            items(state.libraries.chunked(2)) { row ->
                                LibraryRow(
                                    libraries = row,
                                    modifier = Modifier.animateItem(),
                                    onLibraryClick = { library ->
                                        navController.navigate(Routes.library(state.serverId, library.id))
                                    }
                                )
                            }
                        }
                        val homeError = state.error
                        if (homeError != null) {
                            item {
                                AppMessageCard(
                                    message = homeError,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    actionLabel = stringResource(R.string.message_retry),
                                    onAction = viewModel::load
                                )
                            }
                        }
                        if (state.error == null && state.continueWatching.isEmpty() && state.libraries.isEmpty() && state.suggestedSeries.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.home_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shared cinematic poster carousel used by both home modules: a full-bleed
 * backdrop per page with overlaid metadata, an auto-advance that never fights a
 * manual swipe, a depth transition (the incoming poster slides in while easing up
 * to full size and opacity) and an animated pill page indicator.
 */
@Composable
private fun PosterHeroCarousel(
    items: List<MediaItem>,
    modifier: Modifier = Modifier,
    showResumeProgress: Boolean = false,
    firstAdvanceDelayMillis: Long = HERO_AUTO_ADVANCE_MS,
    onItemClick: (MediaItem) -> Unit,
    overlay: @Composable (MediaItem) -> Unit
) {
    val count = items.size
    if (count == 0) return
    val pagerState = rememberPagerState { count }
    // The lists refresh in place, so a page index that no longer exists is dropped.
    LaunchedEffect(items) {
        if (pagerState.currentPage >= count) pagerState.scrollToPage(0)
    }
    var step by remember { mutableIntStateOf(1) }
    LaunchedEffect(count) {
        if (count < 2) return@LaunchedEffect
        // Count idle time instead of scheduling fixed ticks: any settled page
        // change (our own advance or a manual swipe) restarts the countdown, and
        // the carousel never moves while the user is dragging.
        var elapsed = 0L
        var settledPage = pagerState.currentPage
        var first = true
        while (isActive) {
            delay(HERO_TICK_MS)
            if (pagerState.isScrollInProgress) continue
            val page = pagerState.currentPage
            if (page != settledPage) {
                settledPage = page
                elapsed = 0L
            }
            elapsed += HERO_TICK_MS
            val interval = if (first) firstAdvanceDelayMillis else HERO_AUTO_ADVANCE_MS
            if (elapsed < interval) continue
            first = false
            elapsed = 0L
            var next = settledPage + step
            if (next !in 0 until count) {
                // Bounce at the ends: flying backwards past every page reads as a glitch.
                step = -step
                next = settledPage + step
            }
            if (next in 0 until count) {
                settledPage = next
                pagerState.animateScrollToPage(next)
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth().height(260.dp).background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = count > 1
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            // 0 for the settled page, 1 for its neighbour: drives the depth effect.
            val distance = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                .absoluteValue.coerceIn(0f, 1f)
            val settled = 1f - distance
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = lerp(0.90f, 1f, settled)
                        scaleY = lerp(0.90f, 1f, settled)
                        alpha = lerp(0.45f, 1f, settled)
                    }
            ) {
                AsyncImage(
                    model = item.backdropImageUrl.takeIf { it.isNotBlank() }
                        ?: item.seriesImageUrl.takeIf { it.isNotBlank() }
                        ?: item.imageUrl.takeIf { it.isNotBlank() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Left-to-right scrim + bottom-up scrim keep the overlaid text
                // legible over any artwork, mirroring the desktop client's hero.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.55f),
                                    Color.Black.copy(alpha = 0.20f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.35f),
                                    Color.Black.copy(alpha = 0.88f)
                                )
                            )
                        )
                )
                // Tap the artwork to open details (mirrors desktop hero navigation).
                // Drawn below the action column so the button stays interactive.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            onItemClick(item)
                        }
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = 16.dp,
                            end = 88.dp,
                            bottom = if (showResumeProgress) 24.dp else 20.dp
                        )
                ) {
                    overlay(item)
                }
                if (showResumeProgress) {
                    val watched = (item.playedPercentage.coerceIn(0.0, 100.0) / 100.0).toFloat()
                    val progressWidth by animateFloatAsState(
                        targetValue = watched,
                        animationSpec = tween(420),
                        label = "resumeProgress"
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.28f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressWidth)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
        if (count > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(count.coerceAtMost(HERO_MAX_DOTS)) { dot ->
                    val active = dot == pagerState.currentPage
                    val dotWidth by animateDpAsState(
                        targetValue = if (active) 22.dp else 7.dp,
                        animationSpec = tween(280),
                        label = "heroDotWidth"
                    )
                    val dotAlpha by animateFloatAsState(
                        targetValue = if (active) 1f else 0.55f,
                        animationSpec = tween(280),
                        label = "heroDotAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .height(7.dp)
                            .width(dotWidth)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = dotAlpha))
                    )
                }
            }
        }
    }
}

/** Recommended-series hero: overview plus a play action. */
@Composable
private fun SuggestedHero(
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit
) {
    PosterHeroCarousel(items = items, onItemClick = onItemClick) { item ->
        HeroTitles(item)
        HeroMetaRow(item)
        HeroOverview(item)
        HeroActionButton(R.string.play) { onPlay(item) }
    }
}

/**
 * Continue watching now uses the same poster carousel as the recommended series,
 * with a watched-progress bar and a resume action instead of a plain play button.
 */
@Composable
private fun ContinueWatchingHero(
    items: List<MediaItem>,
    onOpen: (MediaItem) -> Unit,
    onResume: (MediaItem) -> Unit
) {
    PosterHeroCarousel(
        items = items,
        showResumeProgress = true,
        // Offset by half the interval so the two home carousels never switch on
        // the same frame.
        firstAdvanceDelayMillis = HERO_AUTO_ADVANCE_MS / 2,
        onItemClick = onOpen
    ) { item ->
        HeroTitles(item)
        HeroMetaRow(item, progressPercent = percentWatched(item))
        HeroOverview(item)
        HeroActionButton(R.string.resume) { onResume(item) }
    }
}

/** Whole percent watched, or null when there is no progress to report. */
private fun percentWatched(item: MediaItem): Int? =
    item.playedPercentage.takeIf { it > 0.0 && it < 100.0 }?.roundToInt()?.coerceAtLeast(1)

/**
 * Series (or item) title plus the episode line. The second line only exists when
 * the item belongs to a series - a bare Series entry would otherwise print its
 * own name twice.
 */
@Composable
private fun HeroTitles(item: MediaItem) {
    val seriesName = item.seriesName.takeIf { it.isNotBlank() }
    val title = seriesName ?: item.name.takeIf { it.isNotBlank() }
    val itemName = if (seriesName != null) {
        item.name.takeIf { it.isNotBlank() && it != seriesName }
    } else null
    title?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    itemName?.let {
        Spacer(Modifier.height(2.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (title != null || itemName != null) {
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeroOverview(item: MediaItem) {
    item.overview.takeIf { it.isNotBlank() }?.let { overview ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = overview,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.88f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** White pill action shared by both home carousels. */
@Composable
private fun HeroActionButton(labelRes: Int, onClick: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
    ) {
        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
    }
}

/** Rating, year, official rating, runtime and watched progress, as on desktop. */
@Composable
private fun HeroMetaRow(item: MediaItem, progressPercent: Int? = null) {
    val symbols = buildList {
        item.communityRating.takeIf { it.isNotBlank() }?.let { add("★ $it") }
        item.productionYear.takeIf { it.isNotBlank() }?.let { add(it) }
        item.runTime.takeIf { it.isNotBlank() }?.let { add(it) }
        val seasonEp = seasonEpisodeText(item)
        if (seasonEp.isNotBlank()) add(seasonEp)
    }
    if (symbols.isNotEmpty()) {
        Text(
            text = symbols.joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    val chips = listOfNotNull(
        item.officialRating.takeIf { it.isNotBlank() },
        progressPercent?.let { "$it%" }
    )
    if (chips.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { chip ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(text = chip, style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun RailHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun LibraryRow(
    libraries: List<MediaLibrary>,
    modifier: Modifier = Modifier,
    onLibraryClick: (MediaLibrary) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        libraries.forEach { library ->
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1.6f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pressScale(interactionSource)
                    .clickable(interactionSource = interactionSource, indication = LocalIndication.current) { onLibraryClick(library) },
                contentAlignment = Alignment.BottomStart
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = library.collectionType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (libraries.size == 1) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Poster auto-advance interval for the home carousels. */
private const val HERO_AUTO_ADVANCE_MS = 10_000L

/** Idle countdown resolution for the home carousels. */
private const val HERO_TICK_MS = 250L

/** Page dots beyond this count would clutter the poster. */
private const val HERO_MAX_DOTS = 8
