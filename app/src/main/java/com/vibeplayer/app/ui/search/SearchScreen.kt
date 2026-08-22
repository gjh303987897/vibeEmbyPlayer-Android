package com.vibeplayer.app.ui.search

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vibeplayer.app.R
import com.vibeplayer.app.model.MediaItem
import com.vibeplayer.app.ui.components.MediaPoster
import com.vibeplayer.app.ui.components.pressScale
import com.vibeplayer.app.ui.navigation.Routes
import kotlinx.coroutines.flow.distinctUntilChanged

/** Search across the active media server with a results grid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    val keyboard = LocalSoftwareKeyboardController.current
    val hasMore by rememberUpdatedState(state.hasMore)
    val loadingMore by rememberUpdatedState(state.loadingMore)

    LaunchedEffect(gridState, state.results.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastIndex ->
                val total = state.results.size
                if (lastIndex != null && total > 0 && lastIndex >= total - 12 && hasMore && !loadingMore) {
                    viewModel.loadMore()
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize().imePadding()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboard?.hide()
                                viewModel.submitSearch()
                            }
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                keyboard?.hide()
                                viewModel.submitSearch()
                            }) {
                                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when {
                    state.loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    state.activeTerm.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.search_prompt),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp)
                        )
                    }
                    state.results.isEmpty() && state.error == null -> {
                        Text(
                            text = stringResource(R.string.no_results),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp)
                        )
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.results, key = { it.id }) { item ->
                                val interactionSource = remember { MutableInteractionSource() }
                                Column(
                                    modifier = Modifier
                                        .animateItem()
                                        .fillMaxWidth()
                                        .pressScale(interactionSource)
                                        .clickable(interactionSource = interactionSource, indication = LocalIndication.current) {
                                            navController.navigate(Routes.details(state.serverId, item.id))
                                        }
                                ) {
                                    MediaPoster(
                                        url = item.imageUrl,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (state.loadingMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

