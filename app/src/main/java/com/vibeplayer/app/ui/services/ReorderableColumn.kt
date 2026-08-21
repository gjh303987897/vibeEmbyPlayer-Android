package com.vibeplayer.app.ui.services

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp

/**
 * A long-press drag-and-drop reorderable LazyColumn. Dragging a row over the
 * centre of another row swaps them; the resulting moves are reported through
 * [onReorder] so the caller can persist the new order.
 */
@Composable
fun <T> ReorderableLazyColumn(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    header: (@Composable () -> Unit)? = null,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    itemContent: @Composable (T) -> Unit
) {
    val listState = rememberLazyListState()
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val itemBounds = remember { mutableStateMapOf<Any, Rect>() }

    val currentItems by rememberUpdatedState(items)
    val currentKey by rememberUpdatedState(key)
    val currentOnReorder by rememberUpdatedState(onReorder)

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement
    ) {
        if (header != null) {
            item(key = "reorder_header") { header() }
        }
        items(items, key = key) { item ->
            val k = key(item)
            val isDragging = draggingKey == k
            Box(
                modifier = Modifier
                    .onGloballyPositioned { coords -> itemBounds[k] = coords.boundsInRoot() }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                    .pointerInput(k) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingKey = k
                                dragOffsetY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount.y
                                val from = currentItems.indexOfFirst { currentKey(it) == k }
                                val dragged = itemBounds[k] ?: return@detectDragGesturesAfterLongPress
                                val centerY = dragged.center.y + dragOffsetY
                                val targetKey = itemBounds.entries
                                    .firstOrNull { (other, b) -> other != k && b.top < centerY && centerY < b.bottom }
                                    ?.key
                                if (targetKey != null) {
                                    val to = currentItems.indexOfFirst { currentKey(it) == targetKey }
                                    if (from >= 0 && to >= 0 && to != from) {
                                        currentOnReorder(from, to)
                                        dragOffsetY = 0f
                                    }
                                }
                            },
                            onDragEnd = {
                                draggingKey = null
                                dragOffsetY = 0f
                            },
                            onDragCancel = {
                                draggingKey = null
                                dragOffsetY = 0f
                            }
                        )
                    }
            ) {
                itemContent(item)
            }
        }
    }
}
