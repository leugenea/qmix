package com.qmix.tv

import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** qmix#315: one native history, four diagnostic frames; not the final #70 gallery. */
@RunWith(AndroidJUnit4::class)
class Qmix70ScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val pending = mutableStateOf(false)
    private var creates = 0
    private lateinit var focusManager: FocusManager
    private lateinit var button: SemanticsNodeInteraction
    private lateinit var buttonLabel: String
    private lateinit var backendLabel: String
    private lateinit var identity: SemanticsNode
    private lateinit var collector: NativeCaptureCollector
    private lateinit var colors: ColorScheme
    private var held = false
    private var defaultPaint: NativePaintEvidence? = null
    private var focusedPaint: NativePaintEvidence? = null

    @Test
    fun shared_setup_button_states_contrast_and_captures() {
        val inspectorBefore = isDebugInspectorInfoEnabled
        var failure: Throwable? = null
        try {
            isDebugInspectorInfoEnabled = true
            collector = NativeCaptureCollector()
            collector.persist()
            startSetup()
            composeRule.runOnIdle { focusManager.clearFocus(force = true) }
            settleScale(1f, "default")
            stateCheckpoint(collector.planned[0])
            button.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue("STATE_FOCUS: request rejected", it()) }
            settleScale(1.1f, "focused")
            stateCheckpoint(collector.planned[1])
            held = true // Arm cleanup before the real key injection can fail.
            button.performKeyInput { keyDown(Key.DirectionCenter); assertTrue("STATE_HELD: key not down", isKeyDown(Key.DirectionCenter)) }
            settleScale(1f, "held_pressed")
            stateCheckpoint(collector.planned[2])
            releaseHeldKey()
            composeRule.runOnIdle { assertEquals("STATE_RELEASE: exactly one callback", 1, creates) }
            collector.receipts.put("releaseCallbacks", creates)
            composeRule.runOnIdle { pending.value = true }
            settleScale(1f, "disabled")
            button.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
            composeRule.runOnIdle { assertEquals("STATE_DISABLED: zero callback delta", 1, creates) }
            collector.receipts.put("disabledDelta", creates - collector.receipts.getInt("releaseCallbacks"))
            stateCheckpoint(collector.planned[3])
            collector.persist()
        } catch (error: Throwable) {
            failure = error
            if (::collector.isInitialized) {
                collector.failure = error.javaClass.name + ": " + error.message
                preserveFailure(error) { collector.persist() }
            }
            throw error
        } finally {
            preserveFailure(failure) {
                try { releaseHeldKey() } finally { isDebugInspectorInfoEnabled = inspectorBefore }
            }
        }
    }

    private fun startSetup() {
        buttonLabel = instrumentation.targetContext.getString(R.string.create_room)
        backendLabel = instrumentation.targetContext.getString(R.string.backend_url)
        composeRule.setContent {
            QMixTvTheme {
                focusManager = LocalFocusManager.current
                colors = MaterialTheme.colorScheme
                SetupScreen(
                    HostingState.Setup("https://api.example", "https://guest.example"),
                    pending.value, null, { _, _ -> }, { creates++ },
                )
            }
        }
        var observed = 0
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                observed = composeRule.onAllNodesWithText(buttonLabel).fetchSemanticsNodes().size
                observed == 1
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("STATE_SETUP: last matching Button count=$observed", error)
        }
        composeRule.waitForIdle()
        button = composeRule.onNodeWithText(buttonLabel)
        identity = button.fetchSemanticsNode()
    }

    private fun settleScale(expected: Float, step: String) {
        var observed = Float.NaN
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                observed = composeRule.runOnIdle { nativeFrame(button.fetchSemanticsNode().layoutInfo.coordinates).scaleX }
                abs(observed - expected) <= 0.001f
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("STATE_SETTLE $step: expected scale=$expected; last=$observed callbacks=$creates", error)
        }
        composeRule.waitForIdle()
    }

    private fun releaseHeldKey() {
        if (held) {
            button.performKeyInput { keyUp(Key.DirectionCenter); assertTrue("STATE_RELEASE: key still down", !isKeyDown(Key.DirectionCenter)) }
            held = false
            composeRule.waitForIdle()
        }
    }

    private fun keyReceipt(): Boolean {
        var observed = false
        button.performKeyInput { observed = isKeyDown(Key.DirectionCenter) }
        return observed
    }

    private fun checkpointSnapshot(): NativeCheckpoint {
        val backend = textSnapshot(composeRule.onNodeWithText(backendLabel, useUnmergedTree = true), backendLabel)
        val text = textSnapshot(composeRule.onNodeWithText(buttonLabel, useUnmergedTree = true), buttonLabel)
        val keyDown = keyReceipt()
        return composeRule.runOnIdle {
            val node = button.fetchSemanticsNode()
            val info = node.layoutInfo
            val border = info.getModifierInfo().single { (it.modifier as? InspectableValue)?.nameFallback == "border" }
            val borderValues = (border.modifier as InspectableValue).inspectableElements.associate { it.name to it.value }
            val background = info.getModifierInfo().single { (it.modifier as? InspectableValue)?.nameFallback == "background" }
            val backgroundValues = (background.modifier as InspectableValue).inspectableElements.associate { it.name to it.value }
            val outer = nativeFrame(border.coordinates)
            val inner = nativeFrame(info.coordinates)
            val root = nativeFrame(info.coordinates.findRootCoordinates())
            val configuration = instrumentation.targetContext.resources.configuration
            NativeCheckpoint(
                node, text, backend, outer, inner, root,
                roundedOutline(borderValues.getValue("shape") as Shape, border.coordinates, info.density, info.layoutDirection),
                roundedOutline(backgroundValues.getValue("shape") as Shape, info.coordinates, info.density, info.layoutDirection),
                (borderValues.getValue("width") as Dp).value * info.density.density,
                borderValues.getValue("color") as Color, backgroundValues.getValue("color") as Color,
                node.config.getOrElse(SemanticsProperties.Focused) { false },
                !node.config.contains(SemanticsProperties.Disabled), creates, keyDown,
                configuration.locales.toLanguageTags(), info.density.density, info.density.fontScale,
            )
        }
    }

    private fun textSnapshot(node: SemanticsNodeInteraction, expectedText: String): NativeTextSnapshot {
        val layouts = mutableListOf<TextLayoutResult>()
        var accepted = false
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> accepted = action(layouts) }
        return composeRule.runOnIdle {
            val semantics = node.fetchSemanticsNode()
            NativeTextSnapshot(layouts.single(), nativeFrame(semantics.layoutInfo.coordinates), semantics.boundsInRoot, expectedText, accepted)
        }
    }

    private fun stateCheckpoint(spec: NativeCaptureSpec) {
        button.assertTextEquals(buttonLabel).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val before = checkpointSnapshot()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "CAPTURE_${spec.state}: no native screenshot" }
        var primary: Throwable? = null
        try {
            val bytes = encodePng(bitmap)
            writeCaptureBytes(spec.name, bytes)
            // This checkpoint exists only after checked native bytes were renamed successfully.
            val frame = collector.complete(spec, before, bitmap.width, bitmap.height, sha256(bytes))
            collector.persist() // oraclePassed=false survives any subsequent visual assertion.
            val after = checkpointSnapshot()
            verifyCheckpoint(spec, before, after)
            val evidence = frame.getJSONObject("evidence")
            verifyNativeImage(bitmap, before, evidence, spec.state)
            verifyCheckpoint(spec, before, checkpointSnapshot())
            frame.put("oraclePassed", true)
            evidence.put("stage", "all_oracles_and_postcapture_receipts_passed")
            collector.persist()
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            preserveFailure(primary) { bitmap.recycle() }
        }
    }

    private fun verifyCheckpoint(spec: NativeCaptureSpec, before: NativeCheckpoint, after: NativeCheckpoint) {
        assertEquals("STATE_${spec.state}: focus", spec.focused, before.focused)
        assertEquals("STATE_${spec.state}: enabled", spec.enabled, before.enabled)
        assertEquals("STATE_${spec.state}: callbacks", spec.callbacks, before.callbacks)
        assertEquals("STATE_${spec.state}: held key", spec.keyDown, before.keyDown)
        assertEquals("STATE_IDENTITY: semantic ID", identity.id, before.node.id)
        assertSame("STATE_IDENTITY: layout owner", identity.layoutInfo, before.node.layoutInfo)
        assertSame("STATE_IDENTITY: root owner", identity.root, before.node.root)
        assertEquals("STATE_POSTCAPTURE: immutable state observations", before.receipt(), after.receipt())
        assertSame("STATE_POSTCAPTURE: layout owner", before.node.layoutInfo, after.node.layoutInfo)
        assertSame("STATE_POSTCAPTURE: root owner", before.node.root, after.node.root)
        assertTrue("STATE_POSTCAPTURE: outer moved", rectDistance(before.outer.screen, after.outer.screen) <= 0.5f)
        assertTrue("STATE_POSTCAPTURE: inner moved", rectDistance(before.inner.screen, after.inner.screen) <= 0.5f)
        assertTrue("STATE_POSTCAPTURE: Button Text moved", rectDistance(before.text.frame.screen, after.text.frame.screen) <= 0.5f)
        verifyContentProvider(spec, before.text.layout.layoutInput.style.color)
    }

    private fun verifyContentProvider(spec: NativeCaptureSpec, actual: Color) {
        val expected = when {
            !spec.enabled -> colors.onSurface.copy(alpha = 0.4f)
            spec.focused -> colors.inverseOnSurface
            else -> colors.onSurface.copy(alpha = 0.8f)
        }
        assertTrue("NATIVE_PROVIDER: Button must shadow inherited onBackground", colorDistance(actual, expected) <= 2f / 255f)
        assertEquals("NATIVE_PROVIDER: pinned state color alpha", expected.alpha, actual.alpha, 0.001f)
    }

    private fun verifyNativeImage(bitmap: Bitmap, snapshot: NativeCheckpoint, evidence: JSONObject, state: String) {
        val root = snapshot.root.screen
        assertTrue("NATIVE_ROOT: bitmap containment", contained(root, Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())))
        verifyFrame(snapshot.outer, root)
        verifyFrame(snapshot.inner, root)
        verifyFrame(snapshot.text.frame, root)
        verifyFrame(snapshot.backend.frame, root)
        assertTrue("NATIVE_TEXT: clipped semantics", rectDistance(snapshot.text.frame.root, snapshot.text.clipped) <= 0.5f)
        assertTrue("NATIVE_BACKEND: clipped semantics", rectDistance(snapshot.backend.frame.root, snapshot.backend.clipped) <= 0.5f)
        verifyStraightEdge(snapshot.outerOutline, snapshot.outer)
        verifyStraightEdge(snapshot.innerOutline, snapshot.inner)
        val text = glyphEvidence(bitmap, snapshot.text, state != "disabled")
        val backend = glyphEvidence(bitmap, snapshot.backend, true)
        evidence.put("buttonGlyph", text.json()).put("inheritedGlyph", backend.json())
        val paint = nativePaintEvidence(bitmap, snapshot, text.background)
        evidence.put("paint", paint.json()).put("geometry", snapshot.geometryJson())
        evidence.put("orderedAlphaLayers", snapshot.alphaLayers(state == "disabled"))
        verifyStatePaint(state, paint, snapshot)
    }

    private fun verifyStatePaint(state: String, paint: NativePaintEvidence, snapshot: NativeCheckpoint) {
        when (state) {
            "default" -> defaultPaint = paint
            "focused" -> {
                val baseline = requireNotNull(defaultPaint)
                assertTrue("STATE_FOCUS: native non-color extent cue missing", paint.width > baseline.width + 1f)
                assertTrue("STATE_FOCUS: extent does not match real 1.1 scale", abs(paint.width - baseline.width * 1.1f) <= 3f)
                assertTrue("STATE_FOCUS: no state-local container distinction", colorDistance(paint.fill, baseline.fill) > 4f / 255f)
                // The enlarged container's top straight-edge/root adjacency carries identification.
                // The unscaled white outline's inner/exterior ratios are diagnostic, not mandatory.
                assertTrue("STATE_FOCUS: relied-upon adjacency requires >=3; ${paint.adjacentRatio}", paint.adjacentRatio >= 3.0)
                assertTrue("STATE_FOCUS: intervening paint at essential edge", paint.adjacentGap <= 3)
                focusedPaint = paint
            }
            "held_pressed" -> {
                val focused = requireNotNull(focusedPaint)
                assertTrue("STATE_HELD: native contraction missing while key held", paint.width < focused.width - 1f)
                assertTrue("STATE_HELD: actual inner scale not 1", abs(snapshot.inner.scaleX - 1f) <= 0.001f)
            }
            "disabled" -> {
                val baseline = requireNotNull(defaultPaint)
                assertTrue("STATE_DISABLED: registered Button-local fill not distinct", colorDistance(paint.fill, baseline.fill) > 4f / 255f)
                assertTrue("STATE_DISABLED: ordered surface color alpha missing", snapshot.rawContainer.alpha < 1f)
            }
            else -> error("Unplanned state $state")
        }
    }
}

private data class NativeCaptureSpec(
    val name: String, val state: String, val enabled: Boolean, val focused: Boolean, val callbacks: Int, val keyDown: Boolean,
) {
    fun json(): JSONObject = JSONObject().put("name", name).put("state", state).put("enabled", enabled)
        .put("focused", focused).put("callbacks", callbacks).put("keyDown", keyDown)
}

private data class NativeFrame(
    val origin: Offset, val unitX: Offset, val unitY: Offset, val local: Rect,
    val screen: Rect, val root: Rect, val clippedRoot: Rect,
) {
    val scaleX: Float get() = unitX.x
    fun toLocal(point: Offset): Offset = Offset((point.x - origin.x) / unitX.x, (point.y - origin.y) / unitY.y)
    fun json(): JSONObject = JSONObject().put("screen", screen.array()).put("root", root.array())
        .put("clippedRoot", clippedRoot.array()).put("local", local.array())
        .put("origin", JSONArray(listOf(origin.x, origin.y))).put("unitX", JSONArray(listOf(unitX.x, unitX.y)))
        .put("unitY", JSONArray(listOf(unitY.x, unitY.y)))
}

private fun nativeFrame(coordinates: LayoutCoordinates): NativeFrame {
    val root = coordinates.findRootCoordinates()
    val size = coordinates.size.toSize()
    val origin = coordinates.localToScreen(Offset.Zero)
    val unitX = coordinates.localToScreen(Offset(1f, 0f)) - origin
    val unitY = coordinates.localToScreen(Offset(0f, 1f)) - origin
    val bottomRight = coordinates.localToScreen(Offset(size.width, size.height))
    return NativeFrame(origin, unitX, unitY, Rect(Offset.Zero, size), Rect(origin, bottomRight),
        root.localBoundingBoxOf(coordinates, clipBounds = false), root.localBoundingBoxOf(coordinates, clipBounds = true))
}

private fun roundedOutline(shape: Shape, coordinates: LayoutCoordinates, density: androidx.compose.ui.unit.Density,
    direction: androidx.compose.ui.unit.LayoutDirection): Outline.Rounded =
    shape.createOutline(coordinates.size.toSize(), direction, density) as Outline.Rounded

private data class NativeTextSnapshot(
    val layout: TextLayoutResult, val frame: NativeFrame, val clipped: Rect, val expectedText: String, val actionAccepted: Boolean,
)

private data class NativeCheckpoint(
    val node: SemanticsNode, val text: NativeTextSnapshot, val backend: NativeTextSnapshot,
    val outer: NativeFrame, val inner: NativeFrame, val root: NativeFrame,
    val outerOutline: Outline.Rounded, val innerOutline: Outline.Rounded,
    val borderWidth: Float, val borderColor: Color, val rawContainer: Color,
    val focused: Boolean, val enabled: Boolean, val callbacks: Int, val keyDown: Boolean,
    val locale: String, val density: Float, val fontScale: Float,
) {
    fun receipt(): List<Any> = listOf(node.id, focused, enabled, callbacks, keyDown, locale, density, fontScale)
    fun geometryJson(): JSONObject = JSONObject().put("outerFoundationBorder", outer.json())
        .put("innerScaledSurface", inner.json()).put("scaledText", text.frame.json()).put("root", root.json())
        .put("outerBorderWidthPx", borderWidth)
    fun alphaLayers(disabled: Boolean): JSONObject = JSONObject()
        .put("containerColorAlpha", rawContainer.alpha).put("containerRawArgb", rawContainer.toArgb())
        .put("resolvedContentColorAlpha", text.layout.layoutInput.style.color.alpha)
        .put("contentGroupAlpha", if (disabled) 0.8 else 1.0)
        .put("surfaceGroupAlpha", if (disabled) 0.8 else 1.0)
        .put("order", JSONArray(listOf("raw container color alpha over root; background precedes surface layer",
            "resolved Text color alpha inside content group", "content group inside focused disabled surface group",
            "nested content layers over container; container is outside both groups",
            "outer unscaled foundation border draws last outside TV scale and alpha")))
        .put("groupAlphaBinding", "pinned tv-material 1.1.0 SurfaceImpl: enabled=1; disabled focused, unselected, not pressed=0.8")
        .put("numericContrastExempt", disabled)
}

private fun verifyFrame(frame: NativeFrame, rootScreen: Rect) {
    assertTrue("NATIVE_TRANSFORM: non-axis-aligned", abs(frame.unitX.y) <= 0.001f && abs(frame.unitY.x) <= 0.001f)
    assertTrue("NATIVE_TRANSFORM: invalid scale", frame.unitX.x > 0f && frame.unitY.y > 0f)
    assertTrue("NATIVE_GEOMETRY: unclipped paint frame outside root ${frame.screen}", contained(frame.screen, rootScreen))
    assertTrue("NATIVE_GEOMETRY: clipping disagrees with transformed frame", rectDistance(frame.root, frame.clippedRoot) <= 0.5f)
}

private fun verifyStraightEdge(outline: Outline.Rounded, frame: NativeFrame) {
    val shape = outline.roundRect
    val x = frame.local.center.x
    assertTrue("NATIVE_ROUNDING: degenerate horizontal straight edge", shape.right - shape.left > shape.topLeftCornerRadius.x + shape.topRightCornerRadius.x)
    assertTrue("NATIVE_ROUNDING: sample left of straight top edge", x >= shape.left + shape.topLeftCornerRadius.x)
    assertTrue("NATIVE_ROUNDING: sample right of straight top edge", x <= shape.right - shape.topRightCornerRadius.x)
}

private fun glyphEvidence(bitmap: Bitmap, text: NativeTextSnapshot, active: Boolean): NativeGlyphEvidence {
    val layout = text.layout
    assertTrue("NATIVE_LAYOUT: semantics action rejected", text.actionAccepted)
    assertEquals("NATIVE_LAYOUT: wrong native Text", text.expectedText, layout.layoutInput.text.text)
    assertEquals("NATIVE_LAYOUT: line count", 1, layout.lineCount)
    assertTrue("NATIVE_LAYOUT: overflow", !layout.hasVisualOverflow)
    assertEquals("NATIVE_LAYOUT: local size", layout.size.toSize(), text.frame.local.size)
    val bounds = text.frame.screen
    val left = floor(bounds.left).toInt()
    val top = floor(bounds.top).toInt()
    val width = ceil(bounds.right).toInt() - left
    val height = ceil(bounds.bottom).toInt() - top
    val boxes = layout.layoutInput.text.text.indices.filterNot { layout.layoutInput.text.text[it].isWhitespace() }
        .map { layout.getBoundingBox(it) }
    val samples = pixelSamples(width, height) { x, y ->
        val local = text.frame.toLocal(Offset(left + x + 0.5f, top + y + 0.5f))
        if (text.frame.local.contains(local)) bitmap.color(left + x, top + y) else null
    }
    val background = Color(samples.groupingBy { it.toArgb() }.eachCount().maxBy { it.value }.key)
    val glyphs = pixelSamples(width, height) { x, y ->
        val local = text.frame.toLocal(Offset(left + x + 0.5f, top + y + 0.5f))
        if (boxes.any { it.contains(local) }) bitmap.color(left + x, top + y) else null
    }
    return nativeGlyphEvidence(glyphs, background, layout.layoutInput.style.color, active)
}

private data class NativePaintEvidence(
    val fill: Color, val root: Color, val firstLocal: Float, val lastLocal: Float,
    val outerWidth: Float, val adjacentRatio: Double, val adjacentGap: Int,
    val outline: Color, val outlineExterior: Color, val outlineInterior: Color,
) {
    val width: Float get() = lastLocal - firstLocal + 1f
    fun json(): JSONObject = JSONObject().put("fillArgb", fill.toArgb()).put("rootArgb", root.toArgb())
        .put("firstPaintButtonLocalPx", firstLocal).put("lastPaintButtonLocalPx", lastLocal)
        .put("registeredWidthPx", width).put("outerWidthPx", outerWidth)
        .put("normalizedExtent", width / outerWidth).put("essentialFeature", "focused enlarged container top straight edge")
        .put("reliedAdjacentPair", "native container fill / native root immediately outside that edge")
        .put("adjacentRatio", adjacentRatio).put("adjacentTransitionPixels", adjacentGap)
        .put("outlineArgb", outline.toArgb()).put("outlineExteriorArgb", outlineExterior.toArgb())
        .put("outlineInteriorArgb", outlineInterior.toArgb())
        .put("outlineExteriorRatioDiagnostic", contrast(outline, outlineExterior))
        .put("outlineInteriorRatioDiagnostic", contrast(outline, outlineInterior))
}

private fun nativePaintEvidence(bitmap: Bitmap, snapshot: NativeCheckpoint, fill: Color): NativePaintEvidence {
    val outer = snapshot.outer.screen
    val inner = snapshot.inner.screen
    val y = inner.center.y.roundToInt()
    val margin = ceil(5f * snapshot.density).toInt()
    val left = floor(minOf(outer.left, inner.left)).toInt() - margin
    val right = ceil(maxOf(outer.right, inner.right)).toInt() + margin
    val root = bitmap.color(left, y)
    assertTrue("NATIVE_PAINT: root endpoints disagree", colorDistance(root, bitmap.color(right, y)) <= 2f / 255f)
    val leftEdge = edgePaint(bitmap, left, floor(outer.left + outer.width / 4f).toInt(), y, root, fill)
    val rightEdge = edgePaint(bitmap, ceil(outer.right - outer.width / 4f).toInt(), right, y, root, fill)
    val adjacencyGap = straightAdjacency(bitmap, snapshot, fill, root)
    val first = leftEdge.first { colorDistance(it.second, root) > 4f / 255f }.first
    val last = rightEdge.last { colorDistance(it.second, root) > 4f / 255f }.first
    val outlineX = (outer.left + snapshot.borderWidth / 2f).roundToInt()
    val outline = bitmap.color(outlineX, y)
    if (snapshot.focused) {
        assertTrue("NATIVE_BORDER: actual unscaled outline absent", colorDistance(outline, snapshot.borderColor) <= 3f / 255f)
    }
    return NativePaintEvidence(fill, root, first - outer.left, last - outer.left, outer.width,
        contrast(fill, root), adjacencyGap,
        outline, bitmap.color(floor(outer.left).toInt() - 2, y), bitmap.color(ceil(outer.left + snapshot.borderWidth).toInt() + 1, y))
}

private fun straightAdjacency(bitmap: Bitmap, snapshot: NativeCheckpoint, fill: Color, root: Color): Int {
    val outer = snapshot.outer.screen
    val inner = snapshot.inner.screen
    val x = inner.center.x.roundToInt()
    val start = floor(minOf(outer.top, inner.top) - 5f * snapshot.density).toInt()
    val end = ceil(inner.top + 6f * snapshot.density).toInt()
    val samples = (start..end).map { bitmap.color(x, it) }
    assertTrue("NATIVE_ADJACENCY: outside top is not root", colorDistance(samples.first(), root) <= 2f / 255f)
    val firstFill = samples.indexOfFirst { colorDistance(it, fill) <= 2f / 255f }
    assertTrue("NATIVE_ADJACENCY: no full-coverage straight-top container core", firstFill >= 0)
    val lastRoot = samples.take(firstFill).indexOfLast { colorDistance(it, root) <= 2f / 255f }
    assertTrue("NATIVE_ADJACENCY: no actual outside-root core", lastRoot >= 0)
    return firstFill - lastRoot
}

private fun edgePaint(bitmap: Bitmap, start: Int, end: Int, y: Int, root: Color, fill: Color): List<Pair<Int, Color>> {
    assertTrue("NATIVE_PAINT: invalid edge ROI", start < end)
    val result = (start..end).map { it to bitmap.color(it, y) }
    assertTrue("NATIVE_PAINT: no measurable fill/root distinction", colorDistance(fill, root) > 4f / 255f)
    return result
}

private fun Bitmap.color(x: Int, y: Int): Color {
    assertTrue("NATIVE_PIXELS: sample outside bitmap ($x,$y)", x in 0 until width && y in 0 until height)
    return Color(getPixel(x, y))
}

private fun contained(inner: Rect, outer: Rect): Boolean = inner.left >= outer.left && inner.top >= outer.top &&
    inner.right <= outer.right && inner.bottom <= outer.bottom

private fun rectDistance(a: Rect, b: Rect): Float = maxOf(abs(a.left - b.left), abs(a.top - b.top), abs(a.right - b.right), abs(a.bottom - b.bottom))
private fun Rect.array(): JSONArray = JSONArray(listOf(left, top, right, bottom))
private fun NativeGlyphEvidence.json(): JSONObject = JSONObject().put("foregroundArgb", foreground.toArgb())
    .put("backgroundArgb", background.toArgb()).put("fullCoverageCores", cores).put("unroundedContrast", ratio)

private class NativeCaptureCollector {
    val planned = listOf(
        NativeCaptureSpec("setup-button-default.png", "default", true, false, 0, false),
        NativeCaptureSpec("setup-button-focused.png", "focused", true, true, 0, false),
        NativeCaptureSpec("setup-button-held-pressed.png", "held_pressed", true, true, 0, true),
        NativeCaptureSpec("setup-button-disabled.png", "disabled", false, true, 1, false),
    )
    val receipts = JSONObject()
    var failure: String? = null
    private val completed = JSONArray()
    private val captureSha = JSONObject(shellExchange("/system/bin/cat /data/local/tmp/qmix70/provenance.json", null, 262_144))
        .getString("checkoutSha").also { require(Regex("[0-9a-f]{40}").matches(it)) }

    fun complete(spec: NativeCaptureSpec, snapshot: NativeCheckpoint, width: Int, height: Int, hash: String): JSONObject {
        val frame = spec.json().put("focused", snapshot.focused).put("enabled", snapshot.enabled)
            .put("callbacks", snapshot.callbacks).put("keyDown", snapshot.keyDown)
            .put("width", width).put("height", height).put("sha256", hash).put("locale", snapshot.locale)
            .put("density", snapshot.density).put("fontScale", snapshot.fontScale).put("api", Build.VERSION.SDK_INT)
            .put("buttonId", snapshot.node.id).put("layoutIdentity", System.identityHashCode(snapshot.node.layoutInfo))
            .put("rootIdentity", System.identityHashCode(snapshot.node.root)).put("oraclePassed", false)
            .put("evidence", JSONObject().put("stage", "native_png_written_oracles_pending"))
        completed.put(frame)
        return frame
    }

    fun persist() {
        val ledger = JSONObject().put("schema", 1).put("captureSha", captureSha)
            .put("planned", JSONArray(planned.map { it.json() })).put("completed", completed)
            .put("receipts", receipts).put("failure", failure ?: JSONObject.NULL)
        writeCaptureBytes("ledger.json", ledger.toString().toByteArray(Charsets.UTF_8))
    }
}

private fun encodePng(bitmap: Bitmap): ByteArray {
    assertTrue("CAPTURE_ENCODING: UI-thread encoding forbidden", Looper.myLooper() != Looper.getMainLooper())
    val stream = ByteArrayOutputStream()
    stream.use { assertTrue("CAPTURE_ENCODING: PNG encoder failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    return stream.toByteArray()
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun writeCaptureBytes(name: String, bytes: ByteArray) {
    require(Regex("[a-z0-9][a-z0-9.-]*\\.(png|json)").matches(name) && name.length <= 120)
    require(bytes.isNotEmpty() && bytes.size <= 33_554_432)
    val command = "/system/bin/sh /data/local/tmp/qmix70/write-capture.sh $name ${bytes.size}"
    val sentinel = shellExchange(command, bytes, 512)
    assertEquals("CAPTURE_WRITE $name: missing exact checked-rename receipt", "QMIX70_OK $name ${bytes.size}\n", sentinel)
}

/** One deadline covers acquisition, write, EOF and completion; never runs shell IO on Main. */
private fun shellExchange(command: String, bytes: ByteArray?, outputLimit: Int): String {
    assertTrue("CAPTURE_IO: UI-thread IO forbidden", Looper.myLooper() != Looper.getMainLooper())
    val descriptors = AtomicReference<Array<ParcelFileDescriptor>?>()
    val cancelled = AtomicBoolean(false)
    val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "qmix70-capture-io").apply { isDaemon = true } }
    var primary: Throwable? = null
    val task = executor.submit<String> {
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw(command)
        descriptors.set(pipes)
        // Worker ownership closes descriptors even if acquisition returns after cancellation.
        var workerFailure: Throwable? = null
        try {
            require(pipes.size == 2) { "CAPTURE_IO: expected stdout/stdin descriptors" }
            require(pipes.all { it.fileDescriptor.valid() }) { "CAPTURE_IO: invalid descriptor" }
            if (cancelled.get()) error("CAPTURE_IO: deadline passed during descriptor acquisition")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { stream ->
                if (bytes != null) stream.write(bytes) // Checked count follows stdin EOF.
            }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { readBounded(it, outputLimit) }
        } catch (error: Throwable) {
            workerFailure = error
            throw error
        } finally {
            preserveFailure(workerFailure) { closeDescriptors(pipes) }
        }
    }
    try {
        return task.get(20, TimeUnit.SECONDS)
    } catch (error: Throwable) {
        val cause = if (error is ExecutionException) error.cause ?: error else error
        primary = AssertionError("CAPTURE_IO: 20-second write/completion boundary; command=$command", cause)
        throw primary
    } finally {
        cancelled.set(true)
        task.cancel(true)
        cleanupAll(primary,
            { closeDescriptors(descriptors.get()) },
            {
                executor.shutdownNow()
                assertTrue("CAPTURE_IO: worker did not terminate after descriptor cancellation", executor.awaitTermination(1, TimeUnit.SECONDS))
            },
        )
    }
}

private fun readBounded(stream: InputStream, maximum: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    var count = stream.read(buffer)
    while (count >= 0) {
        require(output.size() + count <= maximum) { "CAPTURE_IO: oversized completion/provenance response" }
        output.write(buffer, 0, count)
        count = stream.read(buffer)
    }
    return output.toString("UTF-8")
}

private fun closeDescriptors(pipes: Array<ParcelFileDescriptor>?) {
    var failure: Throwable? = null
    pipes?.forEach { pipe ->
        try { pipe.close() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}

private fun cleanupAll(primary: Throwable?, vararg actions: () -> Unit) {
    var failure = primary
    actions.forEach { action ->
        try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        }
    }
    if (primary == null) failure?.let { throw it }
}

private fun preserveFailure(primary: Throwable?, cleanup: () -> Unit) {
    try { cleanup() } catch (error: Throwable) {
        if (primary == null) throw error
        primary.addSuppressed(error)
    }
}
