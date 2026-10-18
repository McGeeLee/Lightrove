package com.mcgeelee.photos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcgeelee.photos.ml.ModelManager
import com.mcgeelee.photos.localization.AppLanguage
import com.mcgeelee.photos.localization.AppLanguageSettings
import com.mcgeelee.photos.ui.PhotosActions
import com.mcgeelee.photos.ui.PhotosApp
import com.mcgeelee.photos.ui.OnboardingScreen
import com.mcgeelee.photos.ui.theme.PhotosTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Older builds offered an in-app picker. Android 12 now follows device settings,
        // so remove any saved override that would otherwise mask the system language.
        if (Build.VERSION.SDK_INT <= 32 && AppLanguage.selectedTag(this).isNotEmpty()) {
            AppLanguage.select("")
        }
        enableEdgeToEdge()
        setContent {
            val model: PhotosViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val onboardingVisible by model.onboardingVisible.collectAsStateWithLifecycle()
            val onboardingReview by model.onboardingReview.collectAsStateWithLifecycle()
            val homeState = rememberSaveableStateHolder()
            val configuration = LocalConfiguration.current
            var appLanguageTag by remember(configuration) { mutableStateOf(AppLanguage.selectedTag(this@MainActivity)) }
            LaunchedEffect(configuration.locales.toLanguageTags()) { model.refreshLocalizedText() }
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                if (granted.isNotEmpty() && granted.values.none { it }) model.showError(R.string.activity_access_denied)
                model.refreshLibrary()
            }
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.startIndexing() }
            val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
                uris.forEach { uri ->
                    try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: SecurityException) { model.showError(R.string.activity_persist_access_failed) }
                }
                model.refreshLibrary()
            }
            val image = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(model::imageQuery) }
            val audio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::audioQuery) }
            val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importModel) }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        appLanguageTag = AppLanguage.selectedTag(this@MainActivity)
                        model.refreshLocalizedText()
                        model.refreshLibrary()
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            val actions = PhotosActions(
                    queryChanged = model::queryChanged,
                    filterChanged = model::filterChanged,
                    searchThresholdChanged = model::setSearchThreshold,
                    requestPermission = { permissions.launch(mediaPermissions()) },
                    pickMedia = { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    pickImageQuery = { image.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    pickAudioQuery = { audio.launch(arrayOf("audio/*", "video/*")) },
                    startIndexing = {
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else model.startIndexing()
                    },
                    cancelIndexing = model::cancelIndexing,
                    videoOcrChanged = model::setVideoOcrEnabled,
                    downloadModel = model::downloadModel,
                    importModel = { import.launch(arrayOf("*/*")) },
                    cancelModelDownload = model::cancelModelDownload,
                    openModelSource = {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelManager.MODEL_PAGE))) }
                        catch (_: android.content.ActivityNotFoundException) { model.showError(R.string.activity_browser_unavailable) }
                    },
                    openAppLanguageSettings = {
                        if (!AppLanguageSettings.open(this@MainActivity)) {
                            model.showError(R.string.activity_settings_unavailable)
                        }
                    },
                    showOnboarding = model::showOnboarding,
                    openAppSettings = {
                        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                        catch (_: android.content.ActivityNotFoundException) { model.showError(R.string.activity_settings_unavailable) }
                    },
                    selectItem = model::selected,
                    retry = { model.clearError(); model.refreshLibrary() },
                    clearError = model::clearError,
                )
            PhotosTheme {
                when (onboardingVisible) {
                    null -> Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    true -> OnboardingScreen(state.copy(appLanguageTag = appLanguageTag), actions, model::completeOnboarding,
                        isReview = onboardingReview, onDismissReview = model::dismissOnboardingReview)
                    false -> homeState.SaveableStateProvider("home") {
                        PhotosApp(state.copy(appLanguageTag = appLanguageTag), actions)
                    }
                }
            }
        }
    }

    private fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
