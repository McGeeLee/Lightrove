@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.mcgeelee.photos.ui

import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.StringRes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import coil3.video.videoFrameOption
import com.mcgeelee.photos.R
import com.mcgeelee.photos.ui.theme.PhotosTheme
import java.util.Locale

private data class Destination(@param:StringRes val labelRes: Int, val icon: ImageVector)
private val destinations = listOf(
    Destination(R.string.ui_nav_search, Icons.Outlined.Search),
    Destination(R.string.ui_nav_library, Icons.Outlined.PhotoLibrary),
    Destination(R.string.ui_nav_settings, Icons.Outlined.Settings),
)

@Composable
fun PhotosApp(state: PhotosUiState, actions: PhotosActions) {
    var selectedDestination by rememberSaveable { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val navigate: (Int) -> Unit = { destination ->
        focus.clearFocus()
        keyboard?.hide()
        selectedDestination = destination
    }
    val navigateToSearch: (String) -> Unit = { query ->
        navigate(0)
        actions.queryChanged(query)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        Scaffold(
            topBar = {
                AppTopBar(
                    onSettings = { navigate(2) },
                    modelReady = state.modelStatus == ModelStatus.READY,
                )
            },
            bottomBar = {
                if (!expanded) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        destinations.forEachIndexed { index, destination ->
                            NavigationBarItem(
                                selected = selectedDestination == index,
                                onClick = { navigate(index) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(stringResource(destination.labelRes)) },
                                modifier = Modifier.testTag("nav_$index"),
                            )
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { contentPadding ->
            Row(Modifier.fillMaxSize().padding(contentPadding)) {
                if (expanded) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        Spacer(Modifier.height(20.dp))
                        destinations.forEachIndexed { index, destination ->
                            NavigationRailItem(
                                selected = selectedDestination == index,
                                onClick = { navigate(index) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(stringResource(destination.labelRes)) },
                                modifier = Modifier.padding(vertical = 8.dp).testTag("nav_$index"),
                            )
                        }
                    }
                }
                AnimatedContent(
                    targetState = selectedDestination,
                    modifier = Modifier.weight(1f),
                    label = "navigation",
                ) { destination ->
                    when (destination) {
                        0 -> SearchScreen(state, actions, navigateToSearch, { navigate(2) })
                        1 -> LibraryScreen(state, actions)
                        else -> SettingsScreen(state, actions)
                    }
                }
            }
        }
    }
    state.selectedItem?.let { item ->
        MediaDetailSheet(item = item, onDismiss = { actions.selectItem(null) })
    }
}

@Composable
private fun AppTopBar(onSettings: () -> Unit, modelReady: Boolean) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Text(stringResource(R.string.app_name), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Icon(
            if (modelReady) Icons.Outlined.CheckCircle else Icons.Outlined.Lock,
            stringResource(if (modelReady) R.string.ui_local_ready else R.string.ui_local_processing),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        IconButton(onClick = onSettings, modifier = Modifier.testTag("open_settings")) {
            Icon(Icons.Outlined.Settings, stringResource(R.string.ui_nav_settings))
        }
    }
}

@Composable
private fun SearchScreen(
    state: PhotosUiState,
    actions: PhotosActions,
    onExample: (String) -> Unit,
    onModelSettings: () -> Unit,
) {
    val hasQuery = state.query.isNotBlank() || state.queryAttachmentLabel != null
    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().testTag("search_grid"),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ui_search_title), style = MaterialTheme.typography.titleLarge)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SearchInput(state, actions) }
        item(span = { GridItemSpan(maxLineSpan) }) { FilterRow(state.filter, actions.filterChanged) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = actions.pickImageQuery, modifier = Modifier.testTag("image_query"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                    Icon(Icons.Outlined.ImageSearch, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_search_image))
                }
                FilledTonalButton(onClick = actions.pickAudioQuery, modifier = Modifier.testTag("audio_query"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                    Icon(Icons.Outlined.GraphicEq, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_search_audio))
                }
            }
        }
        state.error?.let { error ->
            item(span = { GridItemSpan(maxLineSpan) }) { ErrorCard(error, actions.retry, actions.clearError) }
        }
        if (state.isIndexing) {
            item(span = { GridItemSpan(maxLineSpan) }) { IndexCard(state, actions, compact = true) }
        }
        if (!state.hasMediaPermission && state.media.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { PermissionCard(actions) }
        } else if (state.indexedCount == 0 && !state.isIndexing) {
            item(span = { GridItemSpan(maxLineSpan) }) { IndexCard(state, actions, compact = true) }
        }
        if (hasQuery) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.isSearching) stringResource(R.string.ui_search_busy) else pluralStringResource(R.plurals.ui_result_count, state.results.size, state.results.size), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (state.isSearching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (state.results.isEmpty() && !state.isSearching) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        Icons.Outlined.SearchOff,
                        stringResource(R.string.ui_no_results_title),
                        if (state.modelStatus != ModelStatus.READY) stringResource(R.string.ui_no_results_ocr) else stringResource(R.string.ui_no_results_hint),
                    )
                }
            }
            items(state.results, key = { "${it.id}:${it.timestampMillis}:${it.matchSource}" }) { item ->
                MediaCard(item, onClick = { actions.selectItem(item) })
            }
        } else {
            if (state.media.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ui_recent_title), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.weight(1f))
                        Text(pluralStringResource(R.plurals.ui_item_count, state.media.size, state.media.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.media.take(6), key = { it.id }) { item -> MediaCard(item, onClick = { actions.selectItem(item) }) }
            }
            if (state.modelStatus != ModelStatus.READY) {
                item(span = { GridItemSpan(maxLineSpan) }) { ModelNudge(onModelSettings) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SearchExamples(onExample) }
        }
    }
}

@Composable
private fun SearchInput(state: PhotosUiState, actions: PhotosActions) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            value = state.query,
            onValueChange = actions.queryChanged,
            modifier = Modifier.fillMaxWidth().testTag("search_input"),
            placeholder = { Text(stringResource(R.string.ui_search_hint), style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (state.query.isNotEmpty() || state.queryAttachmentLabel != null) {
                { IconButton(onClick = { actions.queryChanged("") }) { Icon(Icons.Outlined.Close, stringResource(R.string.ui_clear_search)) } }
            } else null,
            shape = RoundedCornerShape(28.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        state.queryAttachmentLabel?.let {
            AssistChip(onClick = { actions.queryChanged("") }, label = { Text(it) }, trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.ui_remove_attachment), Modifier.size(16.dp)) })
        }
    }
}

@Composable
private fun FilterRow(filter: SearchFilter, onFilter: (SearchFilter) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("search_filters")) {
        items(SearchFilter.entries) { item ->
            FilterChip(
                selected = filter == item,
                modifier = Modifier.testTag("filter_${item.name}"),
                onClick = { onFilter(item) },
                label = { Text(stringResource(item.labelRes)) },
                leadingIcon = if (filter == item) {
                    { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
                } else null,
            )
        }
    }
}

@Composable
private fun PermissionCard(actions: PhotosActions) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .65f)) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.padding(12.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(R.string.ui_permission_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = actions.requestPermission,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("allow_library"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.ui_permission_allow), textAlign = TextAlign.Center)
                }
                TextButton(
                    onClick = actions.pickMedia,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("select_media"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.ui_permission_select), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun ModelNudge(onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.ui_model_nudge_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, stringResource(R.string.ui_model_settings), Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SearchExamples(onExample: (String) -> Unit) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ui_examples_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            ExampleChip(stringResource(R.string.ui_example_sunset), Icons.Outlined.Image, onExample, "example_sunset")
            ExampleChip(stringResource(R.string.ui_example_sign), Icons.Outlined.TextFields, onExample, "example_sign")
            ExampleChip(stringResource(R.string.ui_example_birthday), Icons.Outlined.VideoLibrary, onExample, "example_birthday")
            ExampleChip(stringResource(R.string.ui_example_rain), Icons.Outlined.GraphicEq, onExample, "example_rain")
        }
    }
}

@Composable
private fun ExampleChip(text: String, icon: ImageVector, onClick: (String) -> Unit, tag: String) {
    AssistChip(onClick = { onClick(text) }, modifier = Modifier.testTag(tag), label = { Text(text) }, leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) }, shape = CircleShape)
}

@Composable
private fun LibraryScreen(state: PhotosUiState, actions: PhotosActions) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().testTag("library_grid"),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ui_library_title), style = MaterialTheme.typography.headlineMedium)
                Text(pluralStringResource(R.plurals.ui_library_summary, state.totalCount, state.totalCount, state.indexedCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    FilledTonalButton(onClick = actions.pickMedia) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ui_library_add))
                    }
                    OutlinedButton(onClick = actions.requestPermission) { Text(stringResource(R.string.ui_library_access)) }
                }
            }
        }
        state.error?.let { error -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorCard(error, actions.retry, actions.clearError) } }
        if (state.isLimitedAccess) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                InfoRow(Icons.Outlined.Shield, stringResource(R.string.ui_limited_access))
            }
        }
        if (state.media.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { IndexCard(state, actions) }
            items(state.media, key = { it.id }) { item -> MediaCard(item, onClick = { actions.selectItem(item) }) }
        } else if (!state.hasMediaPermission) {
            item(span = { GridItemSpan(maxLineSpan) }) { PermissionCard(actions) }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(Icons.Outlined.PhotoLibrary, stringResource(R.string.ui_library_empty_title), stringResource(R.string.ui_library_empty_body)) }
        }
    }
}

@Composable
private fun IndexCard(state: PhotosUiState, actions: PhotosActions, compact: Boolean = false) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.isIndexing) LoadingIndicator(modifier = Modifier.size(36.dp))
                else Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(if (state.isIndexing) stringResource(R.string.ui_index_busy) else if (state.indexedCount > 0) stringResource(R.string.ui_index_update_title) else stringResource(R.string.ui_index_create_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.indexStatus.ifBlank { stringResource(R.string.ui_index_progress, state.indexedCount, state.totalCount) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.isIndexing) IconButton(onClick = actions.cancelIndexing, modifier = Modifier.testTag("cancel_index")) { Icon(Icons.Outlined.Close, stringResource(R.string.ui_index_stop)) }
            }
            if (state.isIndexing) {
                if (state.totalCount > 0) LinearProgressIndicator(
                    progress = { (state.indexedCount.toFloat() / state.totalCount).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                ) else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                if (!compact && state.modelStatus != ModelStatus.READY) Text(
                    stringResource(R.string.ui_index_ocr_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = actions.startIndexing, modifier = Modifier.testTag("start_index"), enabled = state.totalCount > 0 || state.media.isNotEmpty()) {
                    Text(if (state.indexedCount > 0) stringResource(R.string.ui_index_update) else stringResource(R.string.ui_index_start))
                }
            }
        }
    }
}

@Composable
private fun MediaCard(item: PhotoItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnail = remember(context, item.uri, item.isVideo, item.timestampMillis) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .apply {
                if (item.isVideo) {
                    val frameMillis = (item.timestampMillis ?: 0L).coerceAtLeast(0L)
                    videoFrameMillis(frameMillis)
                    videoFrameOption(MediaMetadataRetriever.OPTION_CLOSEST)
                    memoryCacheKey("${item.uri}#video-frame=$frameMillis")
                }
            }
            .build()
    }
    Card(onClick = onClick, modifier = Modifier.testTag("media_${item.id}"), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Box(Modifier.fillMaxWidth().height(176.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            AsyncImage(
                model = thumbnail,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (item.isVideo) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    color = Color.Black.copy(alpha = .65f),
                    contentColor = Color.White,
                    shape = CircleShape,
                ) {
                    Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(15.dp))
                        Text(formatDuration(item.durationMillis), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (item.timestampMillis != null) {
                Surface(modifier = Modifier.align(Alignment.TopStart).padding(10.dp), color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Text(stringResource(R.string.ui_match_timestamp, formatDuration(item.timestampMillis)), Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.matchSource.isNotBlank()) {
                Text(item.matchSource, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.matchText.isNotBlank()) {
                Text(item.matchText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: PhotosUiState, actions: PhotosActions) {
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxSize().testTag("settings_list")) {
        item {
            Text(stringResource(R.string.ui_settings_title), style = MaterialTheme.typography.headlineMedium)
        }
        item { LanguageSettings(state.appLanguageTag, actions.openAppLanguageSettings) }
        item {
            Surface(
                onClick = actions.showOnboarding,
                modifier = Modifier.testTag("settings_onboarding"),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.onboarding_settings_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
                }
            }
        }
        item { AccessSettings(actions) }
        item { VideoOcrSettings(state, actions.videoOcrChanged) }
        item { ModelCard(state, actions) }
        state.error?.let { error -> item { ErrorCard(error, actions.retry, actions.clearError) } }
        item { IndexCard(state, actions) }
        item { InfoRow(Icons.Outlined.Shield, stringResource(R.string.ui_privacy_body)) }
    }
}

private data class AppLanguage(val tag: String, @param:StringRes val nameRes: Int)
private val appLanguages = listOf(
    AppLanguage("", R.string.ui_language_system),
    AppLanguage("en", R.string.ui_language_en),
    AppLanguage("zh-CN", R.string.ui_language_zh_cn),
    AppLanguage("zh-TW", R.string.ui_language_zh_tw),
    AppLanguage("ja", R.string.ui_language_ja),
    AppLanguage("ko", R.string.ui_language_ko),
    AppLanguage("hi", R.string.ui_language_hi),
)

@Composable
private fun LanguageSettings(currentTag: String, onOpenSettings: () -> Unit) {
    val currentLanguage = appLanguages.firstOrNull { it.tag == currentTag }
    val languageName = if (currentLanguage != null) stringResource(currentLanguage.nameRes) else {
        val locale = Locale.forLanguageTag(currentTag)
        locale.getDisplayName(locale)
    }
    Surface(
        onClick = onOpenSettings,
        modifier = Modifier.testTag("language_settings"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Language, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ui_language_title), style = MaterialTheme.typography.titleMedium)
                Text(languageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (Build.VERSION.SDK_INT < 33) Text(
                    stringResource(R.string.ui_language_settings_hint_legacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AccessSettings(actions: PhotosActions) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.ui_access_settings_title), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = actions.requestPermission) { Text(stringResource(R.string.ui_library_access)) }
                OutlinedButton(onClick = actions.openAppSettings, modifier = Modifier.testTag("system_settings")) {
                    Text(stringResource(R.string.ui_open_system_settings))
                }
            }
        }
    }
}

@Composable
private fun VideoOcrSettings(state: PhotosUiState, onChanged: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        // The whole card is one labelled switch; the visual control has no separate action/focus.
        Column(Modifier.fillMaxWidth().testTag("video_ocr_setting")
            .toggleable(value = state.videoOcrEnabled, enabled = !state.isSavingVideoOcrPreference,
                role = Role.Switch, onValueChange = onChanged)
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ui_video_ocr_title), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                Switch(checked = state.videoOcrEnabled, onCheckedChange = null,
                    enabled = !state.isSavingVideoOcrPreference, modifier = Modifier.clearAndSetSemantics {})
            }
            Text(stringResource(R.string.ui_video_ocr_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModelCard(state: PhotosUiState, actions: PhotosActions) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(24.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.padding(13.dp).size(26.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Text(stringResource(R.string.ui_model_name), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(
                when (state.modelStatus) {
                    ModelStatus.NOT_INSTALLED -> stringResource(R.string.ui_model_missing_title)
                    ModelStatus.DOWNLOADING -> stringResource(R.string.ui_model_downloading_title)
                    ModelStatus.READY -> stringResource(R.string.ui_model_ready_title)
                    ModelStatus.ERROR -> stringResource(R.string.ui_model_error_title)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (state.modelStatus == ModelStatus.NOT_INSTALLED || state.modelStatus == ModelStatus.ERROR) {
                Text(stringResource(R.string.ui_model_download_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (state.modelMessage.isNotBlank() && (state.modelStatus == ModelStatus.DOWNLOADING || state.modelStatus == ModelStatus.ERROR)) {
                Text(state.modelMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (state.modelStatus == ModelStatus.DOWNLOADING) {
                state.modelProgress?.let { progress ->
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.ui_download_percent, (progress.coerceIn(0f, 1f) * 100).toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = actions.cancelModelDownload, modifier = Modifier.testTag("cancel_download")) { Text(stringResource(R.string.ui_download_cancel)) }
            } else if (state.modelStatus != ModelStatus.READY) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.downloadModel, modifier = Modifier.testTag("download_model")) {
                        Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.modelStatus == ModelStatus.ERROR) stringResource(R.string.ui_download_retry) else stringResource(R.string.ui_download_start))
                    }
                    OutlinedButton(onClick = actions.importModel, modifier = Modifier.testTag("import_model")) {
                        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ui_model_import))
                    }
                }
            }
            TextButton(onClick = actions.openModelSource, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.ui_model_terms), modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, message: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, stringResource(R.string.ui_error_dismiss), tint = MaterialTheme.colorScheme.onErrorContainer) }
            }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.ui_retry), color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(icon, null, Modifier.padding(20.dp).size(32.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

internal fun formatDuration(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) / 1000)
    val hours = seconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

@Preview(showBackground = true, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
private fun SearchPreview() { PhotosTheme(dynamicColor = false) { PhotosApp(PhotosUiState(), PhotosActions()) } }

@Preview(showBackground = true, device = "spec:width=412dp,height=915dp,dpi=420", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SearchDarkPreview() { PhotosTheme(dynamicColor = false) { PhotosApp(PhotosUiState(), PhotosActions()) } }

@Preview(showBackground = true, device = "spec:width=1000dp,height=800dp,dpi=160")
@Composable
private fun SearchTabletPreview() { PhotosTheme(dynamicColor = false) { PhotosApp(PhotosUiState(), PhotosActions()) } }
