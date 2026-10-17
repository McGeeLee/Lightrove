package com.mcgeelee.photos.ui

import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertTrue

/**
 * Compose 1.12 ParagraphLayoutCache.slowCreateTextLayoutResultOrNull reconstructs a semantics
 * MultiParagraph at prevConstraints.maxWidth, but pairs it with the narrower drawn layoutSize.
 * Its hasVisualOverflow and centered line offsets can therefore describe a different paragraph
 * from the one drawn. Rebuild at the actual text box and verify line and character-advance boxes.
 */
internal fun assertTextFitsVisibleBounds(layouts: List<TextLayoutResult>, message: String) {
    assertTrue("$message\nNo text layout returned", layouts.isNotEmpty())
    val problems = layouts.flatMap(::visibleTextProblems)
    assertTrue("$message\n${problems.joinToString("\n")}", problems.isEmpty())
}

internal fun visibleTextProblems(layout: TextLayoutResult): List<String> {
    val input = layout.layoutInput
    if (input.text.isEmpty()) return emptyList()
    val paragraph = MultiParagraph(
        intrinsics = MultiParagraphIntrinsics(
            annotatedString = input.text,
            style = resolveDefaults(input.style, input.layoutDirection),
            placeholders = input.placeholders,
            density = input.density,
            fontFamilyResolver = input.fontFamilyResolver,
            softWrap = input.softWrap,
        ),
        constraints = Constraints(maxWidth = layout.size.width),
        maxLines = input.maxLines,
        overflow = input.overflow,
    )
    val problems = mutableListOf<String>()
    val lineEpsilon = 0.51f
    // Android TextLayout.getBoundingBox uses insertion/caret positions, not ink bounds.
    // Layout.getLineLeft/Right uses floor/ceil for centered text, whereas caret advances
    // retain fractions. Their endpoints can differ by less than one physical pixel.
    // Allow exactly this one-pixel horizontal quantization cell only for advance boxes;
    // line/height bounds, maxLines and ellipsis retain their stricter checks.
    val advanceEpsilon = 1f
    if (paragraph.didExceedMaxLines) problems += "Text omitted by maxLines: ${input.text}"
    repeat(paragraph.lineCount) { line ->
        val left = paragraph.getLineLeft(line)
        val right = paragraph.getLineRight(line)
        val top = paragraph.getLineTop(line)
        val bottom = paragraph.getLineBottom(line)
        if (paragraph.isLineEllipsized(line)) problems += "Line $line is ellipsized: ${input.text}"
        if (left < -lineEpsilon || right > layout.size.width + lineEpsilon ||
            top < -lineEpsilon || bottom > layout.size.height + lineEpsilon) {
            problems += "Line $line ($left,$top)-($right,$bottom) is outside ${layout.size}: ${input.text}"
        }
    }
    if (!paragraph.didExceedMaxLines) {
        input.text.text.forEachIndexed { offset, character ->
            if (!character.isWhitespace()) {
                val advanceBox = paragraph.getBoundingBox(offset)
                if (advanceBox.left < -advanceEpsilon || advanceBox.right > layout.size.width + advanceEpsilon ||
                    advanceBox.top < -lineEpsilon || advanceBox.bottom > layout.size.height + lineEpsilon) {
                    problems += "Character $offset ($character) advance bounds $advanceBox are outside ${layout.size}"
                }
            }
        }
    }
    return problems
}
