package com.mcgeelee.photos.ui

import androidx.annotation.StringRes
import com.mcgeelee.photos.R

/** Presentation models deliberately contain no database or inference implementation details. */
data class PhotosUiState(
    val appLanguageTag: String = "",
    val isCompletingOnboarding: Boolean = false,
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val media: List<PhotoItem> = emptyList(),
    val results: List<PhotoItem> = emptyList(),
    val hasMediaPermission: Boolean = false,
    val isLimitedAccess: Boolean = false,
    val isSearching: Boolean = false,
    val isIndexing: Boolean = false,
    val indexedCount: Int = 0,
    val totalCount: Int = 0,
    val indexStatus: String = "",
    val modelStatus: ModelStatus = ModelStatus.NOT_INSTALLED,
    val modelProgress: Float? = null,
    val modelMessage: String = "",
    val error: String? = null,
    val selectedItem: PhotoItem? = null,
    val queryAttachmentLabel: String? = null,
)

enum class SearchFilter(@param:StringRes val labelRes: Int) {
    ALL(R.string.ui_filter_all), IMAGE(R.string.ui_filter_image), VIDEO(R.string.ui_filter_video),
    TEXT(R.string.ui_filter_text), AUDIO(R.string.ui_filter_audio)
}

enum class ModelStatus { NOT_INSTALLED, DOWNLOADING, READY, ERROR }

data class PhotoItem(
    val id: String,
    val uri: String,
    val title: String,
    val isVideo: Boolean = false,
    val durationMillis: Long = 0,
    val matchSource: String = "",
    val matchText: String = "",
    val timestampMillis: Long? = null,
    val ocrText: String = "",
    val transcript: String = "",
)

data class PhotosActions(
    val showOnboarding: () -> Unit = {},
    val openAppLanguageSettings: () -> Unit = {},
    val openAppSettings: () -> Unit = {},
    val queryChanged: (String) -> Unit = {},
    val filterChanged: (SearchFilter) -> Unit = {},
    val requestPermission: () -> Unit = {},
    val pickMedia: () -> Unit = {},
    val pickImageQuery: () -> Unit = {},
    val pickAudioQuery: () -> Unit = {},
    val startIndexing: () -> Unit = {},
    val cancelIndexing: () -> Unit = {},
    val downloadModel: () -> Unit = {},
    val importModel: () -> Unit = {},
    val cancelModelDownload: () -> Unit = {},
    val openModelSource: () -> Unit = {},
    val selectItem: (PhotoItem?) -> Unit = {},
    val retry: () -> Unit = {},
    val clearError: () -> Unit = {},
)
