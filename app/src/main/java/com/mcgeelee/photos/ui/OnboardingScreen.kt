package com.mcgeelee.photos.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mcgeelee.photos.R

/** Setup is optional: entering a step never requests access, downloads, or indexes media. */
@Composable
fun OnboardingScreen(
    state: PhotosUiState,
    actions: PhotosActions,
    onComplete: () -> Unit,
    isReview: Boolean = false,
    onDismissReview: () -> Unit = {},
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(step) { listState.scrollToItem(0) }
    BackHandler(enabled = step > 0 || isReview) {
        if (!state.isCompletingOnboarding) {
            if (step > 0) step-- else onDismissReview()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("onboarding_root"),
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) IconButton(
                        onClick = { step-- },
                        enabled = !state.isCompletingOnboarding,
                        modifier = Modifier.testTag("onboarding_back"),
                    ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.onboarding_back)) }
                    else if (isReview) IconButton(
                        onClick = onDismissReview,
                        enabled = !state.isCompletingOnboarding,
                        modifier = Modifier.testTag("onboarding_close"),
                    ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.onboarding_close)) }
                    Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = actions.openAppLanguageSettings, modifier = Modifier.testTag("onboarding_language")) {
                        Icon(Icons.Outlined.Language, stringResource(if (Build.VERSION.SDK_INT >= 33) R.string.ui_language_settings_action else R.string.ui_language_settings_action_legacy))
                    }
                }
                Text(stringResource(R.string.onboarding_step, step + 1), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { if (step < 2) step++ else onComplete() },
                            enabled = !state.isCompletingOnboarding,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(if (step < 2) "onboarding_next" else "onboarding_finish"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            if (state.isCompletingOnboarding) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(stringResource(if (step < 2) R.string.onboarding_next else R.string.onboarding_finish), textAlign = TextAlign.Center)
                        }
                        if (step < 2) TextButton(
                            onClick = onComplete,
                            enabled = !state.isCompletingOnboarding,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding_skip"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        ) { Text(stringResource(R.string.onboarding_skip), textAlign = TextAlign.Center) }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = 680.dp).fillMaxSize().testTag("onboarding_content"),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    val icon = when (step) { 0 -> Icons.Outlined.AutoAwesome; 1 -> Icons.Outlined.PhotoLibrary; else -> Icons.Outlined.ImageSearch }
                    val title = when (step) { 0 -> R.string.onboarding_welcome_title; 1 -> R.string.onboarding_access_title; else -> R.string.onboarding_model_title }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(icon, null, Modifier.padding(12.dp).size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text(stringResource(title), Modifier.testTag("onboarding_step_$step"), style = MaterialTheme.typography.headlineMedium)
                        if (step == 2 && state.modelStatus != ModelStatus.READY) Text(stringResource(R.string.onboarding_model_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.error?.let { message ->
                    item {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
                            Column(Modifier.padding(16.dp)) {
                                Text(message, Modifier.testTag("onboarding_error"), color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = actions.clearError, modifier = Modifier.testTag("onboarding_dismiss_error")) {
                                    Text(stringResource(R.string.ui_error_dismiss), color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
                when (step) {
                    0 -> {
                        item { OnboardingFeature(Icons.Outlined.TextFields, stringResource(R.string.onboarding_text_title)) }
                        item { OnboardingFeature(Icons.Outlined.ImageSearch, stringResource(R.string.onboarding_image_title)) }
                        item { OnboardingFeature(Icons.Outlined.GraphicEq, stringResource(R.string.onboarding_audio_title)) }
                        item { OnboardingFeature(Icons.Outlined.Shield, stringResource(R.string.onboarding_privacy_title)) }
                    }
                    1 -> item { OnboardingAccess(state, actions) }
                    else -> {
                        item { OnboardingModel(state, actions) }
                        item { Text(stringResource(R.string.onboarding_model_finish_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingFeature(icon: ImageVector, title: String) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun OnboardingAccess(state: PhotosUiState, actions: PhotosActions) {
    val available = maxOf(state.totalCount, state.media.size)
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when {
                    state.isLimitedAccess -> stringResource(R.string.onboarding_access_limited)
                    state.hasMediaPermission -> stringResource(R.string.onboarding_access_granted)
                    available > 0 -> stringResource(R.string.onboarding_access_selected)
                    else -> stringResource(R.string.onboarding_access_waiting)
                },
                modifier = Modifier.testTag("onboarding_access_status"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(pluralStringResource(R.plurals.onboarding_access_count, available, available), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.requestPermission, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding_allow_library"), contentPadding = PaddingValues(16.dp)) {
                Text(stringResource(R.string.onboarding_allow_library), textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = actions.pickMedia, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding_pick_media"), contentPadding = PaddingValues(16.dp)) {
                Text(stringResource(R.string.ui_permission_select), textAlign = TextAlign.Center)
            }
            TextButton(onClick = actions.openAppSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding_system_settings"), contentPadding = PaddingValues(12.dp)) {
                Text(stringResource(R.string.ui_open_system_settings), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun OnboardingModel(state: PhotosUiState, actions: PhotosActions) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.ui_model_name), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                stringResource(when (state.modelStatus) {
                    ModelStatus.NOT_INSTALLED -> R.string.ui_model_missing_title
                    ModelStatus.DOWNLOADING -> R.string.ui_model_downloading_title
                    ModelStatus.READY -> R.string.ui_model_ready_title
                    ModelStatus.ERROR -> R.string.ui_model_error_title
                }),
                modifier = Modifier.testTag("onboarding_model_status"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (state.modelMessage.isNotBlank() && (state.modelStatus == ModelStatus.DOWNLOADING || state.modelStatus == ModelStatus.ERROR)) {
                Text(state.modelMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            when (state.modelStatus) {
                ModelStatus.DOWNLOADING -> {
                    val progress = state.modelProgress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().testTag("onboarding_model_progress"))
                        Text(stringResource(R.string.ui_download_percent, (progress.coerceIn(0f, 1f) * 100).toInt()), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("onboarding_model_progress"))
                    OutlinedButton(onClick = actions.cancelModelDownload, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding_cancel_download"), contentPadding = PaddingValues(16.dp)) {
                        Text(stringResource(R.string.ui_download_cancel), textAlign = TextAlign.Center)
                    }
                }
                ModelStatus.READY -> Unit
                else -> {
                    Button(onClick = actions.downloadModel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding_download"), contentPadding = PaddingValues(16.dp)) {
                        Text(stringResource(if (state.modelStatus == ModelStatus.ERROR) R.string.ui_download_retry else R.string.ui_download_start), textAlign = TextAlign.Center)
                    }
                    OutlinedButton(onClick = actions.importModel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding_import"), contentPadding = PaddingValues(16.dp)) {
                        Text(stringResource(R.string.ui_model_import), textAlign = TextAlign.Center)
                    }
                }
            }
            TextButton(onClick = actions.openModelSource, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                Text(stringResource(R.string.ui_model_terms), textAlign = TextAlign.Center)
            }
        }
    }
}
