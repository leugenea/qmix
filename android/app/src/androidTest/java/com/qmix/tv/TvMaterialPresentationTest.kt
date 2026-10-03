package com.qmix.tv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** qmix#315: tests-only tracer; native execution and RED qualification belong to hosted CI. */
@RunWith(AndroidJUnit4::class)
class TvMaterialPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setup_inherited_backend_label_has_readable_native_glyph_contrast() {
        composeRule.setContent {
            // No fixture theme, Surface, content-color provider or Text replacement.
            HostingScreen(HostingState.Setup("https://api.example", "https://guest.example"), { _, _ -> }, {}, {})
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.backend_url)
        awaitText(label, "READABILITY_SETUP: real Setup label")
        val node = composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("READABILITY_LAYOUT: semantics action returned false", action(layouts))
        }
        assertEquals("READABILITY_LAYOUT: expected one real Text layout", 1, layouts.size)
        val layout = layouts.single()
        assertEquals("READABILITY_LAYOUT: wrong text", label, layout.layoutInput.text.text)
        assertEquals("READABILITY_LAYOUT: label must be a single line", 1, layout.lineCount)
        assertTrue("READABILITY_LAYOUT: label overflowed", !layout.hasVisualOverflow)

        val semantics = node.fetchSemanticsNode()
        val bounds = semantics.boundsInRoot
        val unclipped = Rect(semantics.positionInRoot, semantics.size.toSize())
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "READABILITY_GEOMETRY: label outside root; root=$root unclipped=$unclipped",
            unclipped.left >= root.left && unclipped.top >= root.top &&
                unclipped.right <= root.right && unclipped.bottom <= root.bottom,
        )
        assertTrue(
            "READABILITY_GEOMETRY: label clipped; bounds=$bounds unclipped=$unclipped",
            maxOf(
                abs(bounds.left - unclipped.left), abs(bounds.top - unclipped.top),
                abs(bounds.right - unclipped.right), abs(bounds.bottom - unclipped.bottom),
            ) <= 0.5f,
        )
        assertEquals("READABILITY_GEOMETRY: text and semantics sizes differ", layout.size, semantics.size)
        val pixels = node.captureToImage().toPixelMap()
        assertEquals("READABILITY_GEOMETRY: crop width", bounds.right.roundToInt() - bounds.left.roundToInt(), pixels.width)
        assertEquals("READABILITY_GEOMETRY: crop height", bounds.bottom.roundToInt() - bounds.top.roundToInt(), pixels.height)
        // PixelCopy rounds root bounds. Translate pixel centers back to unrounded Text-local coordinates.
        val cropOrigin = Offset(bounds.left.roundToInt().toFloat(), bounds.top.roundToInt().toFloat()) - semantics.positionInRoot
        val samples = pixelSamples(pixels.width, pixels.height) { x, y -> pixels[x, y] }
        val background = samples.groupingBy { it.toArgb() }.eachCount().maxBy { it.value }.key.let { Color(it) }
        val boxes = label.indices.filterNot { label[it].isWhitespace() }.map { layout.getBoundingBox(it) }
        val glyphPixels = pixelSamples(pixels.width, pixels.height) { x, y ->
            val center = cropOrigin + Offset(x + 0.5f, y + 0.5f)
            if (boxes.any { it.contains(center) }) pixels[x, y] else null
        }
        assertTrue("READABILITY_PIXELS: no non-space character-box samples", glyphPixels.isNotEmpty())
        // Select foreground from the native glyph crop, NOT from expected theme tokens.
        val foreground = glyphPixels.maxBy { contrast(it, background) }
        val ratio = contrast(foreground, background)
        val resolved = layout.layoutInput.style.color
        assertTrue("READABILITY_STYLE: unresolved native layout color=$resolved text='$label'", resolved != Color.Unspecified && resolved.alpha > 0f)
        val composited = resolved.compositeOver(background)
        val coreCount = glyphPixels.count { colorDistance(it, foreground) <= 2f / 255f }
        val edgePixels = (0 until pixels.width).flatMap { x -> listOf(pixels[x, 0], pixels[x, pixels.height - 1]) }
        val backgroundCount = edgePixels.count { colorDistance(it, background) <= 2f / 255f }
        val evidence = "text='$label' resolved=$resolved nativeForeground=$foreground nativeBackground=$background " +
            "layout=${layout.size} crop=${pixels.width}x${pixels.height} bounds=$bounds origin=$cropOrigin " +
            "glyphSamples=${glyphPixels.size} coreSamples=$coreCount backgroundEdgeSamples=$backgroundCount/${edgePixels.size} contrast=$ratio"
        println("READABILITY_PREREQUISITES: $evidence")
        assertTrue("READABILITY_STYLE: no resolved foreground; $evidence", resolved != Color.Unspecified && resolved.alpha > 0f)
        assertTrue("READABILITY_PIXELS: crop edges are not a uniform background; $evidence", backgroundCount >= edgePixels.size * 0.9)
        assertTrue("READABILITY_PIXELS: no positive visible glyph evidence; $evidence", coreCount >= 5 && colorDistance(foreground, background) > 4f / 255f)
        // Full-coverage cores must bind to the actual layout style after alpha compositing.
        // This excludes blank crops and unrelated pixels; antialiased fringes are not the contrast oracle.
        assertTrue("READABILITY_STYLE: measured core is not the rendered layout foreground; $evidence", colorDistance(foreground, composited) <= 3f / 255f)
        println("READABILITY_REACHED: $evidence")
        assertTrue("READABILITY_RED: active inherited Setup text requires unrounded contrast >=4.5; $evidence", ratio >= 4.5)
    }

    @Test
    fun setup_button_retains_name_role_focus_and_enabled_activation() {
        val state = mutableStateOf<HostingState>(HostingState.Setup("https://api.example", "https://guest.example"))
        var creates = 0
        composeRule.setContent {
            HostingScreen(state.value, { _, _ -> }, { creates++ }, {})
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.create_room)
        awaitText(label, "BUTTON_SETUP: production create action")
        val button = composeRule.onNodeWithText(label)
        button.assertTextEquals(label).assertIsDisplayed().assertIsFocused().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertEquals("BUTTON_ACTIVE: remote OK must invoke exactly once", 1, creates) }
        composeRule.runOnIdle { state.value = HostingState.Pending("https://api.example", "https://guest.example") }
        composeRule.waitForIdle()
        button.assertTextEquals(label).assertIsFocused().assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertEquals("BUTTON_DISABLED: remote OK must not invoke again", 1, creates) }
    }

    private fun awaitText(text: String, step: String) {
        var observed = 0
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                observed = composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size
                observed > 0
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$step: timed out after 5000ms; last observed matching nodes=$observed", timeout)
        }
        composeRule.waitForIdle()
    }

}

// Geometry-independent native helpers shared with the same-widget capture history.
internal fun pixelSamples(width: Int, height: Int, sample: (Int, Int) -> Color?): List<Color> {
    val colors = mutableListOf<Color>()
    for (y in 0 until height) {
        for (x in 0 until width) {
            sample(x, y)?.let { colors.add(it) }
        }
    }
    return colors
}

internal fun colorDistance(first: Color, second: Color): Float = maxOf(
    abs(first.red - second.red), abs(first.green - second.green), abs(first.blue - second.blue),
)

internal fun contrast(first: Color, second: Color): Double {
    val firstLuminance = first.luminance().toDouble()
    val secondLuminance = second.luminance().toDouble()
    return (maxOf(firstLuminance, secondLuminance) + 0.05) / (minOf(firstLuminance, secondLuminance) + 0.05)
}

internal data class NativeGlyphEvidence(val foreground: Color, val background: Color, val cores: Int, val ratio: Double)

internal fun nativeGlyphEvidence(glyphs: List<Color>, background: Color, resolved: Color, active: Boolean): NativeGlyphEvidence {
    assertTrue("NATIVE_GLYPH: missing character-box pixels", glyphs.isNotEmpty())
    val foreground = glyphs.maxBy { contrast(it, background) }
    val cores = glyphs.count { colorDistance(it, foreground) <= 2f / 255f }
    assertTrue("NATIVE_GLYPH: missing visible full-coverage cores", cores >= 5 && colorDistance(foreground, background) > 4f / 255f)
    assertTrue("NATIVE_GLYPH: unresolved Text layout color", resolved != Color.Unspecified && resolved.alpha > 0f)
    val ratio = contrast(foreground, background)
    if (active) {
        assertTrue("NATIVE_GLYPH: core/style mismatch", colorDistance(foreground, resolved.compositeOver(background)) <= 3f / 255f)
        assertTrue("NATIVE_GLYPH: active text requires >=4.5; measured=$ratio", ratio >= 4.5)
    }
    return NativeGlyphEvidence(foreground, background, cores, ratio)
}
