package com.mcgeelee.photos.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcgeelee.photos.ui.theme.PhotosTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstAppearanceDoesNotRequestAccessDownloadOrIndex() {
        var requests = 0
        var completed = 0
        show {
            OnboardingScreen(PhotosUiState(), PhotosActions(
                requestPermission = { requests++ }, pickMedia = { requests++ },
                downloadModel = { requests++ }, importModel = { requests++ },
                startIndexing = { requests++ },
            ), onComplete = { completed++ })
        }

        compose.onNodeWithTag("onboarding_step_0").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_next").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("onboarding_skip").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(0, completed) }
    }

    @Test fun nextAndBackNavigateWithoutTakingSetupActions() {
        var requests = 0
        show {
            OnboardingScreen(PhotosUiState(), PhotosActions(
                requestPermission = { requests++ }, downloadModel = { requests++ },
            ), onComplete = { requests++ })
        }

        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithTag("onboarding_step_1").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_back").performClick()
        compose.onNodeWithTag("onboarding_step_0").assertIsDisplayed()
        goToModel()
        compose.onNodeWithTag("onboarding_step_2").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_back").performClick()
        compose.onNodeWithTag("onboarding_step_1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun reviewingTheGuideCanCloseAfterNavigatingWithoutCompletingSetup() {
        var dismissed = 0
        var completed = 0
        var setupRequests = 0
        show {
            OnboardingScreen(PhotosUiState(), PhotosActions(
                requestPermission = { setupRequests++ },
                downloadModel = { setupRequests++ },
            ), onComplete = { completed++ }, isReview = true, onDismissReview = { dismissed++ })
        }

        compose.onNodeWithTag("onboarding_close").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithTag("onboarding_step_1").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_back").performClick()
        compose.onNodeWithTag("onboarding_step_0").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_close").performClick()
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, completed)
            assertEquals(0, setupRequests)
        }
    }

    @Test fun libraryAccessAndPhotoPickerRequireTheirOwnExplicitActions() {
        var permissionRequests = 0
        var pickerRequests = 0
        var settingsRequests = 0
        var state by mutableStateOf(PhotosUiState())
        show {
            OnboardingScreen(state, PhotosActions(
                requestPermission = { permissionRequests++ },
                pickMedia = { pickerRequests++ },
                openAppSettings = { settingsRequests++ },
            ), onComplete = {})
        }

        compose.onNodeWithTag("onboarding_next").performClick()
        compose.runOnIdle { assertEquals(0, permissionRequests); assertEquals(0, pickerRequests) }
        reveal("onboarding_allow_library").performClick()
        compose.runOnIdle { assertEquals(1, permissionRequests); assertEquals(0, pickerRequests) }
        reveal("onboarding_pick_media").performClick()
        reveal("onboarding_system_settings").performClick()
        compose.runOnIdle {
            assertEquals(1, permissionRequests)
            assertEquals(1, pickerRequests)
            assertEquals(1, settingsRequests)
            // The result of a limited-access grant comes from the host, never from a UI timer.
            state = state.copy(hasMediaPermission = true, isLimitedAccess = true, totalCount = 3)
        }
        reveal("onboarding_access_status").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_next").assertIsEnabled().performClick()
        compose.onNodeWithTag("onboarding_step_2").assertIsDisplayed()
    }

    @Test fun skipAndFinishWorkWithoutPermissionOrAModelAndPreventDuplicateSaving() {
        var completed = 0
        var setupRequests = 0
        var state by mutableStateOf(PhotosUiState())
        show {
            OnboardingScreen(state, PhotosActions(
                requestPermission = { setupRequests++ }, downloadModel = { setupRequests++ },
            ), onComplete = { completed++ })
        }

        compose.onNodeWithTag("onboarding_skip").performClick()
        compose.runOnIdle { assertEquals(1, completed) }
        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithTag("onboarding_skip").performClick()
        compose.runOnIdle { assertEquals(2, completed) }
        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithTag("onboarding_finish").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(3, completed)
            assertEquals(0, setupRequests)
            state = state.copy(isCompletingOnboarding = true)
        }
        compose.onNodeWithTag("onboarding_finish").assertIsNotEnabled()
    }

    @Test fun modelControlsFollowHostDownloadImportCancellationFailureAndReadyStates() {
        var downloads = 0
        var imports = 0
        var cancellations = 0
        var state by mutableStateOf(PhotosUiState())
        show {
            OnboardingScreen(state, PhotosActions(
                downloadModel = {
                    downloads++
                    state = state.copy(modelStatus = ModelStatus.DOWNLOADING, modelProgress = 0.35f,
                        modelMessage = "Downloading and verifying the official model")
                },
                importModel = {
                    imports++
                    state = state.copy(modelStatus = ModelStatus.DOWNLOADING, modelProgress = null,
                        modelMessage = "Importing the selected model")
                },
                cancelModelDownload = {
                    cancellations++
                    state = state.copy(modelStatus = ModelStatus.NOT_INSTALLED, modelProgress = null,
                        modelMessage = "Model preparation cancelled")
                },
            ), onComplete = {})
        }

        goToModel()
        compose.runOnIdle { assertEquals(0, downloads); assertEquals(0, imports) }
        reveal("onboarding_download").performClick()
        reveal("onboarding_model_progress").assertRangeInfoEquals(ProgressBarRangeInfo(0.35f, 0f..1f))
        revealText("Downloading and verifying the official model").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_download").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_import").assertDoesNotExist()
        // A download can continue after onboarding; completion must not force a 485 MB wait.
        compose.onNodeWithTag("onboarding_finish").assertIsEnabled()
        reveal("onboarding_cancel_download").performClick()
        reveal("onboarding_import").performClick()
        reveal("onboarding_model_progress").assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        revealText("Importing the selected model").assertIsDisplayed()
        reveal("onboarding_cancel_download").performClick()
        compose.runOnIdle {
            assertEquals(1, downloads); assertEquals(1, imports); assertEquals(2, cancellations)
            state = state.copy(modelStatus = ModelStatus.ERROR, modelMessage = "Model checksum verification failed")
        }
        revealText("Model checksum verification failed").assertIsDisplayed()
        reveal("onboarding_download").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, downloads)
            state = state.copy(modelStatus = ModelStatus.READY, modelProgress = 1f, modelMessage = "Verified model ready")
        }
        reveal("onboarding_model_status").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_download").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_import").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_cancel_download").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_finish").assertIsEnabled()
    }

    @Test fun englishActionsAndGuidanceRemainReadableAtDoubleFontSize() = checkLargeText("en")

    @Test fun chineseActionsAndGuidanceRemainReadableAtDoubleFontSize() = checkLargeText("zh-CN")

    @Test fun systemLanguageSettingsAndSavedStateRestorationKeepTheCurrentStep() {
        val restoration = StateRestorationTester(compose)
        var state by mutableStateOf(PhotosUiState(appLanguageTag = "en"))
        var settingsOpens = 0
        restoration.setContent {
            Localized(state.appLanguageTag) {
                OnboardingScreen(state, PhotosActions(openAppLanguageSettings = { settingsOpens++ }), onComplete = {})
            }
        }

        compose.onNodeWithTag("onboarding_next").performClick()
        val englishTitle = stepText(1)
        compose.onNodeWithTag("onboarding_language").performClick()
        compose.onNodeWithTag("language_dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, settingsOpens)
            // Android returns a new locale to the host after the system settings visit.
            state = state.copy(appLanguageTag = "zh-CN")
        }
        compose.onNodeWithTag("onboarding_step_1").assertIsDisplayed()
        val chineseTitle = stepText(1)
        assertTrue("Selecting Chinese should translate the current step", chineseTitle != englishTitle)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("onboarding_step_1").assertIsDisplayed()
        assertEquals(chineseTitle, stepText(1))
        compose.onNodeWithTag("onboarding_next").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("onboarding_step_2").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_finish").assertIsDisplayed().assertIsEnabled()
    }

    private fun checkLargeText(language: String) {
        show(language, fontScale = 2f) { OnboardingScreen(PhotosUiState(), PhotosActions(), onComplete = {}) }
        compose.onNodeWithTag("onboarding_next").assertIsDisplayed()
        assertRenderedTextDoesNotOverflow()
        compose.onNodeWithTag("onboarding_next").performClick()
        reveal("onboarding_allow_library").assertIsDisplayed()
        assertRenderedTextDoesNotOverflow()
        reveal("onboarding_pick_media").assertIsDisplayed()
        assertRenderedTextDoesNotOverflow()
        compose.onNodeWithTag("onboarding_next").assertIsDisplayed().performClick()
        reveal("onboarding_download").assertIsDisplayed()
        assertRenderedTextDoesNotOverflow()
        reveal("onboarding_import").assertIsDisplayed()
        assertRenderedTextDoesNotOverflow()
        compose.onNodeWithTag("onboarding_finish").assertIsDisplayed().assertIsEnabled()
    }

    private fun assertRenderedTextDoesNotOverflow() {
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
            hasAnyAncestor(hasTestTag("onboarding_root"))
        val nodes = compose.onAllNodes(matcher, useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        assertTrue("Onboarding should expose readable text", count > 0)
        repeat(count) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTextFitsVisibleBounds(layouts,
                "Onboarding text must wrap without visible clipping:\n${describeLayouts(layouts)}")
        }
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

    private fun goToModel() {
        compose.onNodeWithTag("onboarding_next").performClick()
        compose.onNodeWithTag("onboarding_next").performClick()
    }

    private fun stepText(step: Int): String = compose.onNodeWithTag("onboarding_step_$step")
        .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun reveal(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("onboarding_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun revealText(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("onboarding_content").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

    private fun show(language: String = "en", fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent { Localized(language, fontScale, content) }
    }

    @Composable
    private fun Localized(language: String, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val context = remember(language, fontScale) { localizedContext(language, fontScale) }
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides context.resources.configuration,
            LocalResources provides context.resources,
            LocalDensity provides Density(context),
        ) {
            PhotosTheme(dynamicColor = false) { content() }
        }
    }

    private fun localizedContext(language: String, fontScale: Float): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
            this.fontScale = fontScale
        }
        return base.createConfigurationContext(configuration)
    }
}
