package com.mcgeelee.photos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextVisibilityAssertionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualClippingAndEllipsisAreRejectedWhileWrappedContentWidthIsAccepted() {
        compose.setContent {
            val exactClipText = "Two physical pixels clipped"
            val exactClipStyle = TextStyle(fontSize = 20.sp, lineHeight = 24.sp)
            val naturalLayout = rememberTextMeasurer().measure(exactClipText, style = exactClipStyle)
            val twoPixelsShort = with(LocalDensity.current) { (naturalLayout.size.height - 2).toDp() }
            Column {
                Box(Modifier.width(240.dp)) {
                    Text("Visible", Modifier.testTag("visible"), textAlign = TextAlign.Center)
                }
                Text("A long sentence cannot fit on this short line", Modifier.width(70.dp).testTag("ellipsis"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("The height clips this sentence", Modifier.width(100.dp).height(8.dp).testTag("clipped"))
                Text(exactClipText, Modifier.height(twoPixelsShort).testTag("two_pixel_clip"), style = exactClipStyle)
            }
        }
        assertTrue("A shorter text in a wider parent must not be reported as clipped",
            layouts("visible").flatMap(::visibleTextProblems).isEmpty())
        assertTrue("A hidden ellipsized tail must still fail the visibility check",
            layouts("ellipsis").flatMap(::visibleTextProblems).isNotEmpty())
        assertTrue("A real height constraint must still fail the visibility check",
            layouts("clipped").flatMap(::visibleTextProblems).isNotEmpty())
        assertTrue("Even two physical pixels of actual height clipping must still fail",
            layouts("two_pixel_clip").flatMap(::visibleTextProblems).isNotEmpty())
    }

    private fun layouts(tag: String): List<TextLayoutResult> = mutableListOf<TextLayoutResult>().also { results ->
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("The text must expose its measured layout", results.isNotEmpty())
    }
}
