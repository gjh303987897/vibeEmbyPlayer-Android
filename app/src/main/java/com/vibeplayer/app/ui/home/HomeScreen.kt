package com.vibeplayer.app.ui.home

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
                windowInsets = WindowInsets(0, 0, 0, 0),
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
                                ContinueWatchingRail(items = state.continueWatching) { item ->
                                    navController.navigate(Routes.details(state.serverId, item.id))
                                }
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
                                Text(
                                    text = homeError,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                        if (state.continueWatching.isEmpty() && state.libraries.isEmpty() && state.suggestedSeries.isEmpty()) {
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
 * Cinematic featured hero for the recommended-series module, mirroring the
 * desktop client's "trendy" home: a full-width backdrop with overlaid title,
 * metadata, overview, a play action and a dot page indicator. Auto-advances
 * every few seconds and taps through to the item's details.
 */
@Composable
private fun SuggestedHero(
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit
) {
    val count = items.size
    var index by remember { mutableIntStateOf(0) }
    val current = items.getOrNull(index % count.coerceAtLeast(1)) ?: return
    val seriesName = current.seriesName.takeIf { it.isNotBlank() }
    val title = seriesName ?: current.name.takeIf { it.isNotBlank() }
    val itemName = current.name.takeIf { it.isNotBlank() && it != seriesName }

    LaunchedEffect(count) {
        if (count > 1) {
            while (true) {
                delay(10_000)
                index = (index + 1) % count
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Color.Black)
    ) {
        AsyncImage(
            model = current.backdropImageUrl.takeIf { it.isNotBlank() }
                ?: current.seriesImageUrl.takeIf { it.isNotBlank() }
                ?: current.imageUrl.takeIf { it.isNotBlank() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Left-to-right scrim + bottom-up scrim, mirroring the desktop hero's
        // dark gradient so the overlaid text stays legible over any artwork.
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
        // Drawn below the action column so the play button stays interactive.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    onItemClick(current)
                }
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 88.dp, bottom = 20.dp)
        ) {
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
                HeroMetaRow(current)
            }
            current.overview.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
            // Play action, mirroring the desktop hero's primary button.
            Button(
                onClick = { onPlay(current) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.play),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        if (count > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(count.coerceAtMost(8)) { dot ->
                    val active = dot == (index % count)
                    Box(
                        modifier = Modifier
                            .height(7.dp)
                            .width(if (active) 22.dp else 7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (active) Color.White else Color.White.copy(alpha = 0.55f))
                    )
                }
            }
        }
    }
}

/** Rating, year, official rating and runtime, mirroring the desktop hero meta. */
@Composable
private fun HeroMetaRow(item: MediaItem) {
    val symbols = buildList {
        item.communityRating.takeIf { it.isNotBlank() }?.let { add("★ $it") }
        item.productionYear.takeIf { it.isNotBlank() }?.let { add(it) }
        item.runTime.takeIf { it.isNotBlank() }?.let { add(it) }
        val seasonEp = seasonEpisodeText(item)
        if (seasonEp.isNotBlank()) add(seasonEp)
    }
    if (symbols.isEmpty()) return
    Text(
        text = symbols.joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    item.officialRating.takeIf { it.isNotBlank() }?.let { rating ->
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.18f))
                .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(
                text = rating,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
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

/**
 * Continue-watching rail. Episodes prefer the parent series poster (not the
 * episode's own thumbnail) and show the season / episode index plus a watched
 * progress bar, matching the desktop client.
 */
@Composable
private fun ContinueWatchingRail(items: List<MediaItem>, onItemClick: (MediaItem) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items) { item ->
            val interactionSource = remember { MutableInteractionSource() }
            val progress = (item.playedPercentage.coerceIn(0.0, 100.0) / 100.0).toFloat()
            Column(
                modifier = Modifier
                    .animateItem()
                    .width(268.dp)
                    .pressScale(interactionSource)
                    .clickable(interactionSource = interactionSource, indication = LocalIndication.current) { onItemClick(item) }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    AsyncImage(
                        model = item.backdropImageUrl.takeIf { it.isNotBlank() }
                            ?: item.seriesImageUrl.takeIf { it.isNotBlank() }
                            ?: item.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(58.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                                )
                            )
                    )
                    if (progress > 0f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 9.dp)
                                .height(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.34f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = continueWatchingTitle(item),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = seasonEpisodeText(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (progress > 0f) {
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/** Title: parent series name for episodes, otherwise the item name. */
private fun continueWatchingTitle(item: MediaItem): String =
    item.seriesName.takeIf { it.isNotBlank() } ?: item.name

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
