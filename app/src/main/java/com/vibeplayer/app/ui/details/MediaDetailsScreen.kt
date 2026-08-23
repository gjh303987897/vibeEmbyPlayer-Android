package com.vibeplayer.app.ui.details

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import com.vibeplayer.app.ui.components.pressScale
import com.vibeplayer.app.ui.navigation.Routes
import com.vibeplayer.app.util.seasonEpisodeText

/** Media details with backdrop, metadata, play action and series browsing. */
@Composable
fun MediaDetailsScreen(
    serverId: String,
    itemId: String,
    navController: NavController,
    viewModel: MediaDetailsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(itemId) { viewModel.load(itemId) }

    val item = state.item

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.loading || item == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                BackdropHeader(
                    backdropUrl = item.backdropImageUrl,
                    bannerUrl = item.imageUrl
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.headlineMedium
                            )
                            MetaRow(item)
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { navController.navigate(Routes.player(serverId, item.id)) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (item.playbackPositionTicks > 0) {
                                        stringResource(R.string.resume)
                                    } else {
                                        stringResource(R.string.play)
                                    }
                                )
                            }
                            if (item.overview.isNotBlank()) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = item.overview,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (item.people.isNotBlank() || item.genres.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                item.genres.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                                item.people.takeIf { it.isNotBlank() }?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (state.seasons.isNotEmpty()) {
                                Spacer(Modifier.height(20.dp))
                                SeasonSelector(
                                    seasons = state.seasons,
                                    selectedId = state.selectedSeasonId,
                                    onSelect = viewModel::selectSeason
                                )
                            }
                        }
                    }

                    if (state.episodes.isNotEmpty()) {
                        item { Spacer(Modifier.height(8.dp)) }
                        items(state.episodes, key = { it.id }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                modifier = Modifier.animateItem(),
                                onClick = {
                                    navController.navigate(Routes.player(serverId, episode.id))
                                }
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
            }
        }
    }
}

@Composable
private fun BackdropHeader(backdropUrl: String, bannerUrl: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
    ) {
        AsyncImage(
            model = backdropUrl.takeIf { it.isNotBlank() } ?: bannerUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                    )
                )
        )
    }
}

@Composable
private fun MetaRow(item: MediaItem) {
    val parts = listOfNotNull(
        item.productionYear.takeIf { it.isNotBlank() },
        item.runTime.takeIf { it.isNotBlank() },
        item.officialRating.takeIf { it.isNotBlank() },
        item.communityRating.takeIf { it.isNotBlank() }?.let { "★ $it" }
    )
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun SeasonSelector(
    seasons: List<MediaItem>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    Text(
        text = stringResource(R.string.season),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        seasons.forEach { season ->
            FilterChip(
                selected = season.id == selectedId,
                onClick = { onSelect(season.id) },
                label = { Text(season.name) }
            )
        }
    }
}

@Composable
private fun EpisodeRow(episode: MediaItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .shadow(2.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = episode.imageUrl.takeIf { it.isNotBlank() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val seText = seasonEpisodeText(episode)
                if (seText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = seText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            episode.overview.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

