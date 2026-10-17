package com.mcgeelee.photos.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcgeelee.photos.R
import com.mcgeelee.photos.ui.theme.PhotosTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotosAppTest {
    @get:Rule val compose = createComposeRule()
    private var environmentDescription = "No test content created"

    private fun localizedContext(language: String, fontScale: Float = 1f): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
            this.fontScale = fontScale
        }
        return base.createConfigurationContext(configuration)
    }

    private fun show(language: String = "en", fontScale: Float = 1f, content: @Composable () -> Unit): Context {
        val context = localizedContext(language, fontScale)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                // Explicit resources also reach separate Dialog/ModalBottomSheet compositions,
                // whose Android owner may provide its own LocalContext/LocalConfiguration.
                LocalResources provides context.resources,
                // Use the platform's nonlinear font scaling, as a real Android app does.
                LocalDensity provides Density(context),
            ) {
                environmentDescription = "requested=$language scale=$fontScale; " +
                    "configuration=${LocalConfiguration.current}; " +
                    "resources=${LocalResources.current.configuration}; density=${LocalDensity.current}"
                PhotosTheme(dynamicColor = false) { content() }
            }
        }
        return context
    }

    @Test fun firstLaunchExplainsAccessAndDoesNotStartDownloads() {
        var permissionRequests = 0
        var downloads = 0
        val context = show {
            PhotosApp(PhotosUiState(), PhotosActions(requestPermission = { permissionRequests++ }, downloadModel = { downloads++ }))
        }
        compose.onNodeWithTag("search_input").assertIsDisplayed()
        compose.onNodeWithTag("search_filters").assertIsDisplayed()
        compose.onNodeWithTag("search_grid").performScrollToNode(hasTestTag("allow_library"))
        compose.onNodeWithText(context.getString(R.string.ui_permission_title)).assertIsDisplayed()
        compose.onNodeWithTag("allow_library").performClick()
        compose.runOnIdle { assertEquals(1, permissionRequests); assertEquals(0, downloads) }
    }

    @Test fun examplePromptUsesTheCurrentLanguage() {
        var query: String? = null
        val context = show { PhotosApp(PhotosUiState(), PhotosActions(queryChanged = { query = it })) }
        compose.onNodeWithTag("search_grid").performScrollToNode(hasTestTag("example_sunset"))
        compose.onNodeWithTag("example_sunset").performClick()
        compose.runOnIdle { assertEquals(context.getString(R.string.ui_example_sunset), query) }
    }

    @Test fun searchTextAndSourceFiltersAreWiredAndImeClearsFocus() {
        var state by mutableStateOf(PhotosUiState())
        show {
            PhotosApp(state, PhotosActions(queryChanged = { state = state.copy(query = it) }, filterChanged = { state = state.copy(filter = it) }))
        }
        compose.onNodeWithTag("search_input").performTextInput("coffee")
        compose.onNodeWithTag("search_input").performImeAction()
        compose.onNodeWithTag("search_input").assertIsNotFocused()
        compose.onNodeWithTag("search_filters").performScrollToNode(hasTestTag("filter_TEXT"))
        compose.onNodeWithTag("filter_TEXT").performClick().assertIsSelected()
        compose.onNodeWithTag("search_input").assertTextContains("coffee")
        compose.runOnIdle { assertEquals("coffee", state.query); assertEquals(SearchFilter.TEXT, state.filter) }
    }

    @Test fun modelDownloadAndImportRequireExplicitUserActions() {
        var downloads = 0
        var imports = 0
        show { PhotosApp(PhotosUiState(), PhotosActions(downloadModel = { downloads++ }, importModel = { imports++ })) }
        compose.onNodeWithTag("nav_2").performClick()
        compose.runOnIdle { assertEquals(0, downloads) }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("download_model"))
        compose.onNodeWithTag("download_model").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("import_model"))
        compose.onNodeWithTag("import_model").performClick()
        compose.runOnIdle { assertEquals(1, downloads); assertEquals(1, imports) }
    }

    @Test fun videoResultPreservesSourceAndSeekTimestamp() {
        val result = PhotoItem("fixture-video", "content://com.mcgeelee.photos.test/video/1", "Birthday.mp4", true, 92_000,
            matchSource = "Video audio", matchText = "Birthday song", timestampMillis = 12_000)
        var selected: PhotoItem? = null
        val context = show {
            PhotosApp(PhotosUiState(query = "birthday", hasMediaPermission = true, indexedCount = 1, totalCount = 1,
                modelStatus = ModelStatus.READY, results = listOf(result)), PhotosActions(selectItem = { selected = it }))
        }
        compose.onNodeWithTag("search_grid").performScrollToNode(hasTestTag("media_fixture-video"))
        compose.onNodeWithText(context.getString(R.string.ui_match_timestamp, formatDuration(12_000))).assertIsDisplayed()
        compose.onNodeWithText(result.matchSource).assertIsDisplayed()
        compose.onNodeWithTag("media_fixture-video").performClick()
        compose.runOnIdle { assertEquals(result, selected) }
    }

    @Test fun chineseDetailShowsRecognizedTextAndCanBeDismissed() = withFailureDiagnostics("chinese-detail") {
        var state by mutableStateOf(PhotosUiState(selectedItem = PhotoItem("fixture-image", "content://com.mcgeelee.photos.test/image/1", "咖啡招牌.jpg", ocrText = "今日咖啡 18 元")))
        val context = show("zh-CN") { PhotosApp(state, PhotosActions(selectItem = { state = state.copy(selectedItem = it) })) }
        compose.onNodeWithTag("media_details").performScrollToNode(hasText(context.getString(R.string.ui_detail_ocr_title)))
        compose.onNodeWithText(context.getString(R.string.ui_detail_ocr_title)).assertIsDisplayed()
        compose.onNodeWithTag("media_details").performScrollToNode(hasText("今日咖啡 18 元"))
        compose.onNodeWithText("今日咖啡 18 元").assertIsDisplayed()
        compose.onNodeWithTag("media_details").performScrollToNode(hasTestTag("detail_close"))
        compose.onNodeWithTag("detail_close").performClick()
        compose.onNodeWithTag("media_details").assertDoesNotExist()
        compose.runOnIdle { assertNull(state.selectedItem) }
    }

    @Test fun languageEntryOpensSystemSettingsAndRestoresTheSettingsPage() {
        var opens = 0
        val restoration = StateRestorationTester(compose)
        val context = localizedContext("en")
        restoration.setContent {
            CompositionLocalProvider(LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources, LocalDensity provides Density(context)) {
                PhotosTheme(dynamicColor = false) {
                    PhotosApp(PhotosUiState(appLanguageTag = "en-US"), PhotosActions(openAppLanguageSettings = { opens++ }))
                }
            }
        }
        compose.onNodeWithTag("nav_2").performClick()
        compose.onNodeWithText("English (United States)").assertIsDisplayed()
        compose.onNodeWithTag("language_settings").performClick()
        compose.onNodeWithTag("language_dialog").assertDoesNotExist()
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("nav_2").assertIsSelected()
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun deniedAccessHasADirectRouteToSystemSettings() {
        var opens = 0
        show { PhotosApp(PhotosUiState(), PhotosActions(openAppSettings = { opens++ })) }
        compose.onNodeWithTag("nav_2").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("system_settings"))
        compose.onNodeWithTag("system_settings").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun searchingDoesNotShowTheNoResultsState() {
        val context = show {
            PhotosApp(PhotosUiState(query = "coffee", isSearching = true, hasMediaPermission = true,
                indexedCount = 1, totalCount = 1, modelStatus = ModelStatus.READY), PhotosActions())
        }
        compose.onNodeWithText(context.getString(R.string.ui_search_busy)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ui_no_results_title)).assertDoesNotExist()
    }

    @Test fun englishLongTextAndActionsWrapAtLargeFontSize() = checkLargeText("en", 1.6f)
    @Test fun simplifiedChineseLongTextAndActionsWrapAtLargeFontSize() = checkLargeText("zh-CN", 1.8f)

    private fun checkLargeText(language: String, scale: Float) = withFailureDiagnostics("large-$language-$scale") {
        val context = show(language, scale) { PhotosApp(PhotosUiState(), PhotosActions()) }
        compose.onNodeWithTag("search_grid").performScrollToNode(hasTestTag("allow_library"))
        compose.onNodeWithTag("allow_library").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(context.getString(R.string.ui_permission_allow), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("The localized action must expose its text layout", layouts.isNotEmpty())
        assertTextFitsVisibleBounds(layouts, "The full translated action must be readable\n${describeLayouts(layouts)}")
        compose.onNodeWithTag("search_grid").performScrollToNode(hasText(context.getString(R.string.ui_permission_title)))
        layouts.clear()
        compose.onNodeWithText(context.getString(R.string.ui_permission_title), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("The library access title must expose its text layout", layouts.isNotEmpty())
        assertTextFitsVisibleBounds(layouts, "The library access title must remain readable\n${describeLayouts(layouts)}")
    }

    private fun describeLayouts(layouts: List<TextLayoutResult>): String = layouts.joinToString("\n") { layout ->
        buildString {
            appendLine("text=${layout.layoutInput.text.text}")
            appendLine("size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}, " +
                "exceededMaxLines=${layout.multiParagraph.didExceedMaxLines}")
            appendLine("lines=${layout.lineCount}, maxLines=${layout.layoutInput.maxLines}, " +
                "softWrap=${layout.layoutInput.softWrap}, overflow=${layout.layoutInput.overflow}, " +
                "density=${layout.layoutInput.density}, style=${layout.layoutInput.style}")
            repeat(layout.lineCount) { line ->
                appendLine("line[$line] start=${layout.getLineStart(line)} end=${layout.getLineEnd(line)} " +
                    "left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} " +
                    "top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)} " +
                    "ellipsized=${layout.isLineEllipsized(line)}")
            }
        }
    }

    /** Keep the original assertion intact and save every Compose root, including dialog windows. */
    private fun withFailureDiagnostics(name: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: AssertionError) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.cacheDir, "photos-ui-diagnostics").apply { mkdirs() }
            val report = StringBuilder("$environmentDescription\n\n$failure\n")
            runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                repeat(roots.fetchSemanticsNodes().size) { index ->
                    report.appendLine("\nROOT $index\n${roots[index].printToString()}")
                    runCatching {
                        val bitmap = roots[index].captureToImage().asAndroidBitmap()
                        val image = File(directory, "$name-root-$index.png")
                        image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        report.appendLine("Screenshot: ${image.absolutePath}")
                    }.onFailure { report.appendLine("Screenshot failed: $it") }
                }
            }.onFailure { report.appendLine("Semantics capture failed: $it") }
            val reportFile = File(directory, "$name.txt")
            runCatching { reportFile.writeText(report.toString()) }
            Log.e("PhotosUiTest", "Failure diagnostics: ${reportFile.absolutePath}\n$report")
            failure.addSuppressed(AssertionError("UI diagnostics saved to ${directory.absolutePath}/$name*"))
            throw failure
        }
    }

    @Test fun unsupportedLanguageFallsBackToEnglish() {
        val context = show("fr") { PhotosApp(PhotosUiState(), PhotosActions()) }
        val englishTitle = localizedContext("en").getString(R.string.ui_search_title)
        assertEquals(englishTitle, context.getString(R.string.ui_search_title))
        compose.onNodeWithText(englishTitle).assertIsDisplayed()
    }

    @Test fun compactHomeKeepsLibraryWithinTheFirstViewport() {
        val item = PhotoItem("first-photo", "content://com.mcgeelee.photos.test/image/1", "Memory.jpg")
        show { PhotosApp(PhotosUiState(media = listOf(item), totalCount = 1, indexedCount = 1,
            hasMediaPermission = true, modelStatus = ModelStatus.READY), PhotosActions()) }
        compose.onNodeWithTag("search_input").assertIsDisplayed()
        compose.onNodeWithTag("search_filters").assertIsDisplayed()
        compose.onNodeWithTag("media_first-photo").assertIsDisplayed()
    }
}
