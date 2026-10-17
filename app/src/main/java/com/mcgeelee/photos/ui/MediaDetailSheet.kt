@file:kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mcgeelee.photos.ui

import android.content.Context

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.mcgeelee.photos.R

@Composable
internal fun MediaDetailSheet(item: PhotoItem, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        LazyColumn(
            modifier = Modifier.testTag("media_details"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (item.isVideo) stringResource(R.string.ui_detail_video) else stringResource(R.string.ui_detail_photo), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("detail_close")) { Icon(Icons.Outlined.Close, stringResource(R.string.ui_detail_close)) }
                }
            }
            item {
                if (item.isVideo) {
                    VideoPreview(item)
                } else {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 420.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
            if (item.matchSource.isNotBlank() || item.matchText.isNotBlank()) {
                item {
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.matchSource.ifBlank { stringResource(R.string.ui_detail_match) }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            item.timestampMillis?.let {
                                Text(stringResource(R.string.ui_detail_timestamp, formatDuration(it)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            if (item.matchText.isNotBlank()) SelectionContainer {
                                Text(item.matchText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            }
            if (item.ocrText.isNotBlank()) {
                item { RecognizedText(stringResource(R.string.ui_detail_ocr_title), item.ocrText) }
            }
            if (item.transcript.isNotBlank()) {
                item { RecognizedText(stringResource(R.string.ui_detail_audio_title), item.transcript) }
            }
            if (item.ocrText.isBlank()) {
                item {
                    Text(stringResource(R.string.ui_detail_no_ocr), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RecognizedText(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.TextFields, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            SelectionContainer { Text(body, Modifier.fillMaxWidth().padding(18.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        Text(stringResource(R.string.ui_detail_copy_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPreview(item: PhotoItem) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(item.uri) {
        createVideoPreviewPlayer(context).apply {
            setMediaItem(MediaItem.fromUri(item.uri), item.timestampMillis ?: 0L)
            playWhenReady = false
            prepare()
        }
    }
    LaunchedEffect(item.timestampMillis) { player.seekTo(item.timestampMillis ?: 0L) }
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(24.dp)),
        )
        item.timestampMillis?.let { timestamp ->
            FilledTonalButton(onClick = { player.seekTo(timestamp); player.play() }) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_detail_play_from, formatDuration(timestamp)))
            }
        }
    }
}

/** Shared by the detail player and its real-media seek regression test. */
@OptIn(UnstableApi::class)
internal fun createVideoPreviewPlayer(context: Context): ExoPlayer {
    val renderers = DefaultRenderersFactory(context).setMediaCodecSelector { mimeType, secure, tunneling ->
        val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
        // The API 37 Goldfish AVC decoder can emit B-frames in decode order, showing an 8 s
        // frame after a 5 s seek. Prefer the enumerated software AVC decoder only for Goldfish;
        // retain the platform's usual ordering on physical devices and for other formats.
        if (decoders.any { it.name == "c2.goldfish.h264.decoder" } &&
            decoders.any { it.name == "c2.android.avc.decoder" }
        ) {
            decoders.sortedBy { if (it.name == "c2.android.avc.decoder") 0 else 1 }
        } else {
            decoders
        }
    }
    return ExoPlayer.Builder(context, renderers).build()
}
