package com.qmix.tv

import android.content.Context
import android.content.res.Resources
import android.content.res.Configuration
import android.os.LocaleList
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
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

/** qmix#315/#318/#319: continuous shared history plus bounded screen checkpoints; diagnostic captures. */
private const val FOCUS_PARKING_TAG = "qmix70-focus-parking"

@RunWith(AndroidJUnit4::class)
class Qmix70ScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val pending = mutableStateOf(false)
    private val screenState = mutableStateOf<HostingState?>(null)
    private val resourceContext = mutableStateOf<Context?>(null)
    private lateinit var renderedResources: Resources
    private lateinit var renderedContext: Context
    private lateinit var renderedDensity: Density
    private var screenActions = 0
    private val focusParking = FocusRequester()
    private var creates = 0
    private var settingsChanges = 0
    private lateinit var button: SemanticsNodeInteraction
    private lateinit var buttonLabel: String
    private lateinit var backendLabel: String
    private lateinit var identity: SemanticsNode
    private lateinit var collector: NativeCaptureCollector
    private lateinit var colors: ColorScheme
    private var held = false
    private var defaultPaint: NativePaintEvidence? = null
    private var focusedPaint: NativePaintEvidence? = null
    private var fieldFocusBaseline: ScreenElementSnapshot? = null
    private var defaultInputGlyphs: List<NativeGlyphEvidence> = emptyList()

    @Test
    fun shared_setup_button_states_contrast_and_captures() {
        val inspectorBefore = isDebugInspectorInfoEnabled
        var failure: Throwable? = null
        try {
            isDebugInspectorInfoEnabled = true
            collector = NativeCaptureCollector()
            collector.persist()
            startSetup()
            // Transfer focus without vacating the owner; an empty TV owner can
            // reacquire the initial Button focus after clearFocus.
            composeRule.runOnIdle { focusParking.requestFocus() }
            composeRule.onNodeWithTag(FOCUS_PARKING_TAG).assertIsFocused()
            button.assertIsNotFocused()
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
            verifyPendingFields()
            stateCheckpoint(collector.planned[3])
            capturePresentationScreens()
            captureLiveScreens()
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
            val context = resourceContext.value ?: LocalContext.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources,
            ) {
                renderedContext = LocalContext.current
                renderedResources = LocalResources.current
                renderedDensity = LocalDensity.current
                QMixTvTheme {
                    colors = MaterialTheme.colorScheme
                    Box(Modifier.fillMaxSize()) {
                        val state = screenState.value
                        if (state == null) {
                            SetupScreen(
                                HostingState.Setup("https://api.example", "https://guest.example"),
                                pending.value, null, { _, _ -> settingsChanges++ }, { creates++ },
                            )
                        } else {
                            HostingScreen(state, { _, _ -> }, { screenActions++ }, { screenActions++ },
                                onConfirmHttpWarning = { screenActions++ }, onCancelHttpWarning = { screenActions++ })
                        }
                        Spacer(Modifier.size(1.dp).testTag(FOCUS_PARKING_TAG).focusRequester(focusParking).focusable())
                    }
                }
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
                val node = button.fetchSemanticsNode()
                observed = composeRule.runOnIdle { nativeFrame(node.layoutInfo.coordinates).scaleX }
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

    private fun keyReceipt(action: SemanticsNodeInteraction = button): Boolean {
        var observed = false
        action.performKeyInput { observed = isKeyDown(Key.DirectionCenter) }
        return observed
    }

    private fun checkpointSnapshot(action: SemanticsNodeInteraction = button, label: String = buttonLabel): NativeCheckpoint {
        val backend = textSnapshot(composeRule.onNodeWithText(backendLabel, useUnmergedTree = true), backendLabel)
        val text = textSnapshot(composeRule.onNodeWithText(label, useUnmergedTree = true), label)
        val keyDown = keyReceipt(action)
        val node = action.fetchSemanticsNode()
        return composeRule.runOnIdle {
            val info = node.layoutInfo
            val border = info.getModifierInfo().single { (it.modifier as? InspectableValue)?.nameFallback == "border" }
            val borderValues = (border.modifier as InspectableValue).inspectableElements.associate { it.name to it.value }
            val background = info.getModifierInfo().single { (it.modifier as? InspectableValue)?.nameFallback == "background" }
            val backgroundValues = (background.modifier as InspectableValue).inspectableElements.associate { it.name to it.value }
            val outer = nativeFrame(border.coordinates)
            val inner = nativeFrame(info.coordinates)
            val root = nativeFrame(info.coordinates.findRootCoordinates())
            val configuration = renderedResources.configuration
            assertEquals("NATIVE_DENSITY: LocalDensity/layout density", renderedDensity.density, info.density.density, 0f)
            assertEquals("NATIVE_DENSITY: LocalDensity/layout fontScale", renderedDensity.fontScale, info.density.fontScale, 0f)
            NativeCheckpoint(
                node, text, backend, outer, inner, root,
                roundedOutline(borderValues.getValue("shape") as Shape, border.coordinates, info.density, info.layoutDirection),
                roundedOutline(backgroundValues.getValue("shape") as Shape, info.coordinates, info.density, info.layoutDirection),
                (borderValues.getValue("width") as Dp).value * info.density.density,
                borderValues.getValue("color") as Color, backgroundValues.getValue("color") as Color,
                node.config.getOrElse(SemanticsProperties.Focused) { false },
                !node.config.contains(SemanticsProperties.Disabled), captureCallbacks(), keyDown,
                configuration.locales.toLanguageTags(), info.density.density, info.density.fontScale,
            )
        }
    }

    private fun textSnapshot(node: SemanticsNodeInteraction, expectedText: String): NativeTextSnapshot {
        val layouts = mutableListOf<TextLayoutResult>()
        var accepted = false
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> accepted = action(layouts) }
        val semantics = node.fetchSemanticsNode()
        return composeRule.runOnIdle {
            val layout = layouts.single()
            val frame = if (semantics.config.contains(SemanticsProperties.EditableText)) inputGlyphFrame(semantics, layout)
                else nativeFrame(semantics.layoutInfo.coordinates)
            NativeTextSnapshot(layout, frame, semantics.boundsInRoot, expectedText, accepted, Rect(semantics.positionInRoot, semantics.size.toSize()))
        }
    }

    private fun stateCheckpoint(spec: NativeCaptureSpec) {
        button.assertTextEquals(buttonLabel).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val before = checkpointSnapshot()
        val inputs = sharedInputSnapshots()
        val body = sharedSetupTextSnapshots()
        captureCheckpoint(spec, before) { bitmap, evidence ->
            verifyCheckpoint(spec, before, checkpointSnapshot())
            verifyNativeImage(bitmap, before, evidence, spec.state)
            verifySharedInputs(bitmap, inputs, spec, evidence)
            verifySharedSetupGeometry(bitmap, before, inputs, body, evidence)
            body.zip(sharedSetupTextSnapshots()).forEach { (first, second) ->
                assertTrue("SHARED_SETUP_POSTCAPTURE: text moved", rectDistance(first.frame.screen, second.frame.screen) <= 0.5f)
            }
            assertEquals("SHARED_INPUT_POSTCAPTURE: fields moved/state changed", inputs.map { it.first.receipt() },
                sharedInputSnapshots().map { it.first.receipt() })
            verifyCheckpoint(spec, before, checkpointSnapshot())
        }
    }

    private fun captureCheckpoint(spec: NativeCaptureSpec, before: NativeCheckpoint, verify: (Bitmap, JSONObject) -> Unit) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "CAPTURE_${spec.state}: no native screenshot" }
        var primary: Throwable? = null
        try {
            val bytes = encodePng(bitmap)
            writeCaptureBytes(spec.name, bytes)
            // A checked write commits a pending-oracle receipt before visual assertions.
            val frame = collector.complete(spec, before, bitmap.width, bitmap.height, sha256(bytes))
            collector.persist()
            val evidence = frame.getJSONObject("evidence")
            verify(bitmap, evidence)
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

    private fun sharedInputSnapshots(): List<Pair<ScreenElementSnapshot, NativeTextSnapshot>> =
        listOf(R.string.backend_url to "https://api.example", R.string.guest_origin to "https://guest.example").map { (label, value) ->
            val input = composeRule.onNodeWithContentDescription(renderedContext.getString(label))
            val text = textSnapshot(input, value)
            val node = input.fetchSemanticsNode()
            composeRule.runOnIdle { screenElementSnapshot(node) } to text
        }

    private fun verifySharedInputs(bitmap: Bitmap, inputs: List<Pair<ScreenElementSnapshot, NativeTextSnapshot>>,
        spec: NativeCaptureSpec, evidence: JSONObject) {
        val glyphs = inputs.map { (element, text) ->
            assertEquals("SHARED_INPUT: disabled semantics", spec.enabled, element.enabled)
            verifyScreenTextLayout(text, ellipsized = false)
            val measured = glyphEvidence(bitmap, text, spec.enabled).glyph
            assertEquals("SHARED_INPUT: source alpha", if (spec.enabled) 1f else 0.4f, text.layout.layoutInput.style.color.alpha, 0.001f)
            assertTrue("SHARED_INPUT: actual composited glyph/source binding",
                colorDistance(measured.foreground, text.layout.layoutInput.style.color.compositeOver(measured.background)) <= 3f / 255f)
            measured
        }
        if (spec.state == "default") defaultInputGlyphs = glyphs
        if (!spec.enabled) {
            glyphs.zip(defaultInputGlyphs).forEach { (disabled, active) ->
                assertTrue("SHARED_INPUT: visibly disabled foreground missing", colorDistance(disabled.foreground, active.foreground) > 4f / 255f)
            }
        }
        evidence.put("inputGlyphs", JSONArray(glyphs.map { it.json().put("numericContrastExempt", !spec.enabled) }))
    }

    private fun sharedSetupTextSnapshots(): List<NativeTextSnapshot> {
        val resources = mutableListOf(R.string.setup_title, R.string.backend_url, R.string.guest_origin)
        if (pending.value) resources.add(R.string.creating_room)
        return resources.map { resource ->
            val label = renderedContext.getString(resource)
            textSnapshot(composeRule.onNodeWithText(label, useUnmergedTree = true), label)
        }
    }

    private fun verifySharedSetupGeometry(bitmap: Bitmap, native: NativeCheckpoint,
        inputs: List<Pair<ScreenElementSnapshot, NativeTextSnapshot>>, body: List<NativeTextSnapshot>, evidence: JSONObject) {
        val root = native.root.screen
        val safe = safeRectangle(native)
        val boxes = mutableListOf("primary action paint" to unionBounds(native.outer.screen, native.inner.screen))
        verifySafeFrame(native.outer, root, safe, "shared outline")
        verifySafeFrame(native.inner, root, safe, "shared surface")
        val textEvidence = JSONArray()
        body.forEach { text ->
            verifySafeFrame(text.frame, root, safe, text.expectedText)
            verifyScreenTextLayout(text, ellipsized = false)
            assertTrue("SHARED_SETUP: semantic text clipping", rectDistance(text.semanticUnclipped, text.clipped) <= 0.5f)
            assertTrue("SHARED_SETUP: raw semantic containment", contained(text.semanticUnclipped, safe.translate(-root.topLeft)))
            boxes.add(text.expectedText to text.frame.screen)
            textEvidence.put(glyphEvidence(bitmap, text, true, multiline = true).json().put("text", text.expectedText)
                .put("frame", text.frame.json()).put("lineCount", text.layout.lineCount))
        }
        inputs.forEach { (element, text) ->
            verifySafeFrame(element.frame, root, safe, text.expectedText)
            verifySafeFrame(requireNotNull(element.borderFrame), root, safe, text.expectedText + " outline")
            assertTrue("SHARED_INPUT: clipped semantic bounds", rectDistance(element.semanticUnclipped, element.semanticClipped) <= 0.5f)
            assertTrue("SHARED_INPUT: raw semantic containment", contained(element.semanticUnclipped, safe.translate(-root.topLeft)))
            boxes.add(text.expectedText to element.paintBounds())
        }
        verifyDisjoint(boxes)
        evidence.put("safeRectanglePx", safe.array()).put("setupBody", textEvidence)
            .put("inputGeometry", JSONArray(inputs.map { it.first.json() }))
    }

    private fun verifyPendingFields() {
        val context = instrumentation.targetContext
        listOf(R.string.backend_url to "https://api.example", R.string.guest_origin to "https://guest.example").forEach { (label, value) ->
            val input = composeRule.onNodeWithContentDescription(context.getString(label)).assertIsNotEnabled()
            val node = input.fetchSemanticsNode()
            assertEquals("PENDING_FIELD: full editable value", value, node.config[SemanticsProperties.EditableText].text)
            composeRule.runOnIdle {
                if (node.config.contains(SemanticsActions.SetText)) {
                    assertTrue("PENDING_FIELD: disabled edit accepted",
                        node.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("https://changed.example")) != true)
                }
                assertEquals("PENDING_FIELD: no settings callback", 0, settingsChanges)
            }
        }
    }

    private fun captureCallbacks(): Int = if (screenState.value == null) creates else screenActions

    private fun capturePresentationScreens() {
        val english = localizedContext("en")
        val russian = localizedContext("ru")
        showScreen(english, HostingState.Setup("https://api.example", "https://guest.example"),
            R.string.create_room, R.string.backend_url)
        val backend = composeRule.onNodeWithContentDescription(english.getString(R.string.backend_url))
        val beforeFocus = backend.fetchSemanticsNode()
        fieldFocusBaseline = composeRule.runOnIdle { screenElementSnapshot(beforeFocus) }
        assertTrue("FIELD_BASELINE: unexpectedly focused", !requireNotNull(fieldFocusBaseline).focused)
        val rootBeforeFocus = composeRule.runOnUiThread {
            nativeFrame(beforeFocus.layoutInfo.coordinates.findRootCoordinates()).screen
        }
        assertEquals("FIELD_ROOT_BASELINE: unshifted", Offset.Zero, rootBeforeFocus.topLeft)
        backend.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue("FIELD_FOCUS: rejected", it()) }
        backend.assertIsFocused()
        dismissPresentationIme(backend, rootBeforeFocus)
        settleScale(1f, "setup field focus")
        captureScreen(collector.planned[4], setupTextRequests(english), setupElements(english))
        captureWarning(english, 5)
        captureWarning(russian, 6)
        showScreen(russian, HostingState.Error(UserMessage.INVALID_ENDPOINT, "https://api.example", "https://guest.example"),
            R.string.retry, R.string.backend_url)
        settleScale(1.1f, "RU setup error")
        captureScreen(collector.planned[7], setupTextRequests(russian) + bodyText(russian.getString(UserMessage.INVALID_ENDPOINT.resourceId())),
            setupElements(russian))
        captureInvitation(english, 8)
        captureInvitation(russian, 9)
    }

    /** qmix#318: dismiss the observed platform IME without changing field focus or window policy. */
    private fun dismissPresentationIme(field: SemanticsNodeInteraction, expectedRoot: Rect) {
        val node = field.fetchSemanticsNode() // Instrumentation thread; no synchronizing fetch on Main.
        awaitPresentationWindow(node, expectedRoot, imeVisible = true, step = "backend IME shown")
        val device = UiDevice.getInstance(instrumentation)
        assertTrue("FIELD_IME: public system Back rejected", device.pressBack())
        awaitPresentationWindow(node, expectedRoot, imeVisible = false, step = "backend IME hidden/unshifted root")
        field.assertIsFocused()
    }

    private fun awaitPresentationWindow(node: SemanticsNode, expectedRoot: Rect, imeVisible: Boolean, step: String) {
        var observed: PresentationWindowReadiness? = null
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                val current = composeRule.runOnUiThread {
                    val view = node.root as View
                    PresentationWindowReadiness(
                        ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()),
                        view.hasWindowFocus(), nativeFrame(node.layoutInfo.coordinates.findRootCoordinates()).screen,
                    )
                }
                observed = current
                current.imeVisible == imeVisible && (imeVisible || (current.appWindowFocused && current.root == expectedRoot))
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("FIELD_WINDOW $step: expected IME=$imeVisible root=$expectedRoot; last=$observed", timeout)
        }
    }

    private data class PresentationWindowReadiness(val imeVisible: Boolean?, val appWindowFocused: Boolean, val root: Rect)

    private fun localizedContext(language: String): Context {
        val base = instrumentation.targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language)))
        }
        return base.createConfigurationContext(configuration)
    }

    private fun showScreen(context: Context, state: HostingState, action: Int, tracer: Int, vararg tracerArgs: Any) {
        buttonLabel = context.getString(action)
        backendLabel = context.getString(tracer, *tracerArgs)
        composeRule.runOnIdle {
            resourceContext.value = context
            screenState.value = state
        }
        var observed = 0
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                observed = composeRule.onAllNodesWithText(buttonLabel).fetchSemanticsNodes().size
                observed == 1
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("SCREEN_SHOW ${state.javaClass.simpleName}: last action count=$observed", timeout)
        }
        button = composeRule.onNodeWithText(buttonLabel)
        composeRule.runOnIdle {
            assertEquals("SCREEN_CONTEXT: observed rendered resources", context.resources.configuration.locales.toLanguageTags(),
                renderedResources.configuration.locales.toLanguageTags())
            assertSame("SCREEN_CONTEXT: effective resources/context", renderedContext.resources, renderedResources)
        }
    }

    private fun bodyText(text: String, ellipsized: Boolean = false): ScreenTextRequest =
        ScreenTextRequest(composeRule.onNodeWithText(text, useUnmergedTree = true), text, true, ellipsized)

    private fun setupTextRequests(context: Context): List<ScreenTextRequest> = listOf(
        bodyText(context.getString(R.string.setup_title)),
        bodyText(context.getString(R.string.backend_url)),
        bodyText(context.getString(R.string.guest_origin)),
        ScreenTextRequest(composeRule.onNodeWithContentDescription(context.getString(R.string.backend_url)), "https://api.example"),
        ScreenTextRequest(composeRule.onNodeWithContentDescription(context.getString(R.string.guest_origin)), "https://guest.example"),
    )

    private fun setupElements(context: Context): List<ScreenElementRequest> = listOf(
        ScreenElementRequest(button, "primary action", button = true),
        ScreenElementRequest(composeRule.onNodeWithContentDescription(context.getString(R.string.backend_url)), "backend input", input = true),
        ScreenElementRequest(composeRule.onNodeWithContentDescription(context.getString(R.string.guest_origin)), "guest input", input = true),
    )

    private fun captureWarning(context: Context, index: Int) {
        showScreen(context, HostingState.HttpWarning("http://192.168.1.20:8180", "http://192.168.1.20:8180"),
            R.string.use_http, R.string.http_warning_title)
        settleScale(1.1f, "warning ${context.resources.configuration.locales}")
        val cancelLabel = context.getString(R.string.cancel)
        captureScreen(collector.planned[index], listOf(bodyText(backendLabel), bodyText(context.getString(R.string.http_warning_body)),
            ScreenTextRequest(composeRule.onNodeWithText(cancelLabel, useUnmergedTree = true), cancelLabel)),
            listOf(ScreenElementRequest(button, "confirm action", button = true),
                ScreenElementRequest(composeRule.onNodeWithText(cancelLabel), "cancel action", button = true)))
    }

    private fun captureInvitation(context: Context, index: Int) {
        val url = "https://guest.example/" + "deterministic-long-invitation-path/".repeat(8) + "r/WXYZ"
        showScreen(context, HostingState.Invitation(GuestInvite("WXYZ", url), roomReplacementNotice = true),
            R.string.enter_room, R.string.join_this_room)
        settleScale(1.1f, "invitation ${context.resources.configuration.locales}")
        val description = context.getString(R.string.invitation_qr_description, url)
        val qr = composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
        captureScreen(collector.planned[index], listOf(bodyText(backendLabel),
            bodyText(context.getString(R.string.invitation_room_code, "WXYZ")),
            bodyText(context.getString(R.string.replacement_invitation_notice)), bodyText(url, ellipsized = true)),
            listOf(ScreenElementRequest(button, "enter action", button = true), ScreenElementRequest(qr, description)))
    }

    /** qmix#319: one bounded fixture, reused across acceptance classes rather than locale permutations. */
    private fun captureLiveScreens() {
        val english = localizedContext("en")
        val russian = localizedContext("ru")
        val fixture = liveCaptureFixture()
        val empty = fixture.copy(synchronization = RoomSyncState.Active(
            "ABCD", RoomState("ABCD", null, emptyList()), Freshness.FRESH, LiveConnection.CONNECTED),
            playback = LocalPlaybackState())
        showLiveScreen(english, empty, R.string.start)
        captureLiveCheckpoint("live-empty-en.png", english, empty, emptyList())
        showLiveScreen(english, fixture, R.string.next)
        listOf(19 to "live-long-row19-en.png", 99 to "live-long-final-en.png").forEach { (index, name) ->
            captureScrolledLiveRow(name, english, fixture, index)
        }
        val active = fixture.synchronization as RoomSyncState.Active
        val notices = fixture.copy(commandPending = true,
            synchronization = active.copy(freshness = Freshness.STALE, connection = LiveConnection.RECONNECTING),
            playback = LocalPlaybackState("server-current", LocalPlaybackStatus.ERROR,
                error = PlaybackError(PlaybackErrorKind.HTTP, "fixture detail must stay private", 503)))
        showLiveScreen(russian, notices, R.string.next)
        // The former focused queue row remains a real focus target across notice updates.
        focusLiveRow(19)
        captureLiveCheckpoint("live-notices-ru.png", russian, notices, listOf("playback-retry"), 19)
        showScreen(russian, fixture.copy(synchronization = RoomSyncState.Missing("ABCD"),
            replacementError = UserMessage.SERVER_TIMEOUT), R.string.new_room, R.string.room_unavailable_title)
        awaitScreenNode(button, "missing New room", focused = true)
        settleScale(1.1f, "missing New room")
        captureScreen(captureSpec("missing-room-ru.png"), listOf(bodyText(backendLabel),
            bodyText(russian.getString(R.string.sync_room_missing)),
            bodyText(russian.getString(UserMessage.SERVER_TIMEOUT.resourceId()))),
            listOf(ScreenElementRequest(button, "new room action", button = true)))
        composeRule.onNodeWithTag("queue-list").assertDoesNotExist()
        composeRule.onNodeWithTag("room-invite").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(russian.getString(R.string.invitation_qr_description,
            fixture.invite.guestUrl)).assertDoesNotExist()
    }

    private fun liveCaptureFixture(): HostingState.LiveRoom {
        val queue = (0 until 100).map { index ->
            QueuedTrack("gallery-$index", "https://music.example/$index",
                "Queue $index — \u97f3\u697d \u266b " + "Long Unicode title W ".repeat(40),
                "Artist $index — \u30a2\u30fc\u30c6\u30a3\u30b9\u30c8 " + "Wide artist W ".repeat(30),
                if (index == 99) 3661 else 65, "fixture")
        }
        val current = CurrentTrack("server-current", 0, "playing",
            "Server current — \u97f3\u697d \u266b " + "Long selected title W ".repeat(60),
            "Server artist — \u30a2\u30fc\u30c6\u30a3\u30b9\u30c8 " + "Wide current artist W ".repeat(40))
        return HostingState.LiveRoom(GuestInvite("ABCD", "https://guest.example/r/ABCD"),
            RoomSyncState.Active("ABCD", RoomState("ABCD", current, queue), Freshness.FRESH, LiveConnection.CONNECTED),
            playback = LocalPlaybackState("server-current", LocalPlaybackStatus.PLAYING, isPlaying = true,
                durationMs = 60_000, isSeekable = true))
    }

    private fun captureSpec(name: String): NativeCaptureSpec = collector.planned.single { it.name == name }

    private fun showLiveScreen(context: Context, state: HostingState.LiveRoom, action: Int) {
        showScreen(context, state, action, R.string.room_title, state.invite.code)
        awaitScreenNode(composeRule.onNodeWithTag("room-invite"), "live Invite composed")
        settleScale(1f, "live primary after screen update")
    }

    private fun captureScrolledLiveRow(name: String, context: Context, state: HostingState.LiveRoom, index: Int) {
        focusLiveRow(index)
        settleScale(1f, "live row $index primary unfocused")
        captureLiveCheckpoint(name, context, state,
            listOf("playback-play-pause", "playback-seek-back", "playback-seek-forward"), index)
    }

    private fun focusLiveRow(index: Int) {
        // Scroll via the production lazy-list contract before fetching a virtualized row or its Text nodes.
        composeRule.onNodeWithTag("queue-list").performScrollToIndex(index)
        val row = composeRule.onNodeWithTag("queue-track-gallery-$index")
        awaitScreenNode(row, "lazy row $index composed")
        row.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue("LIVE_ROW $index: focus rejected", it()) }
        awaitScreenNode(row, "lazy row $index focus", focused = true)
    }

    private fun awaitScreenNode(node: SemanticsNodeInteraction, step: String, focused: Boolean = false) {
        var last = "not observed"
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                val observed = try { node.fetchSemanticsNode() } catch (absent: AssertionError) {
                    last = "node not composed: ${absent.message}"
                    return@waitUntil false
                }
                val actualFocus = observed.config.getOrElse(SemanticsProperties.Focused) { false }
                last = "id=${observed.id} focused=$actualFocus size=${observed.size}"
                observed.size.width > 0 && observed.size.height > 0 && (!focused || actualFocus)
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("SCREEN_AWAIT $step: last=$last", timeout)
        }
        node.assertIsDisplayed()
    }

    private fun captureLiveCheckpoint(name: String, context: Context, state: HostingState.LiveRoom,
        playbackTags: List<String>, index: Int? = null) {
        val room = requireNotNull((state.synchronization as RoomSyncState.Active).room)
        val elements = liveElements(state, room, playbackTags, index)
        val texts = liveBodyTexts(context, state, room) + liveNoticeTexts(context, state) + actionTexts(elements)
        val rowTexts = index?.let { queueRowTexts(room.queue[it]) }.orEmpty()
        captureScreen(captureSpec(name), texts + rowTexts, elements)
    }

    private fun liveElements(state: HostingState.LiveRoom, room: RoomState, playbackTags: List<String>, index: Int?): List<ScreenElementRequest> {
        val actions = listOf(ScreenElementRequest(button, "primary action", button = true, enabled = state.isPrimaryActionEnabled),
            ScreenElementRequest(composeRule.onNodeWithTag("room-invite"), "invite action", button = true)) +
            playbackTags.map { ScreenElementRequest(composeRule.onNodeWithTag(it), it, button = true) }
        return actions + index?.let { listOf(
            ScreenElementRequest(composeRule.onNodeWithTag("queue-list"), "queue viewport", viewport = true),
            ScreenElementRequest(composeRule.onNodeWithTag("queue-track-gallery-$it"), "queue row $it", readOnly = true,
                mergedText = listOf(room.queue[it].title, room.queue[it].artist,
                    formatDuration(room.queue[it].durationSeconds, renderedContext.getString(R.string.duration_unknown)))),
        ) }.orEmpty()
    }

    private fun liveBodyTexts(context: Context, state: HostingState.LiveRoom, room: RoomState): List<ScreenTextRequest> {
        val texts = listOf(bodyText(backendLabel), bodyText(context.getString(R.string.local_playback,
            context.getString(localPlaybackStatusResource(state.playback.status)))),
            bodyText(context.getString(R.string.server_selected_track)), bodyText(context.getString(R.string.queue)))
        val selected = room.current?.let { listOf(
            bodyText(it.title, ellipsized = true),
            ScreenTextRequest(composeRule.onNodeWithText(it.artist, useUnmergedTree = true), it.artist,
                body = true, ellipsized = true, maxLines = 1),
        ) } ?: listOf(bodyText(context.getString(R.string.no_track_playing)))
        val empty = if (room.queue.isEmpty()) listOf(bodyText(context.getString(R.string.queue_empty))) else emptyList()
        return texts + selected + empty
    }

    private fun liveNoticeTexts(context: Context, state: HostingState.LiveRoom): List<ScreenTextRequest> = buildList {
        synchronizationMessageResource(state.synchronization)?.let { add(bodyText(context.getString(it))) }
        if (state.commandPending) add(bodyText(context.getString(R.string.command_pending)))
        state.playback.error?.let { error ->
            val detail = localPlaybackErrorText(error)
            val text = detail.argument?.let { context.getString(detail.resource, it) } ?: context.getString(detail.resource)
            add(bodyText(context.getString(R.string.playback_error, text)))
        }
    }

    private fun actionTexts(elements: List<ScreenElementRequest>): List<ScreenTextRequest> =
        elements.filter { it.button && it.node != button }.map { request ->
            val label = request.node.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            ScreenTextRequest(composeRule.onNodeWithText(label, useUnmergedTree = true), label, active = request.enabled)
        }

    private fun queueRowTexts(track: QueuedTrack): List<ScreenTextRequest> = listOf(
        queueTextRequest(track, track.title, ellipsized = true),
        queueTextRequest(track, track.artist, ellipsized = true),
        queueTextRequest(track, formatDuration(track.durationSeconds, renderedContext.getString(R.string.duration_unknown))),
    )

    private fun queueTextRequest(track: QueuedTrack, text: String, ellipsized: Boolean = false): ScreenTextRequest =
        ScreenTextRequest(composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("queue-track-${track.id}")),
            useUnmergedTree = true), text, ellipsized = ellipsized, maxLines = 1)

    private fun screenSnapshot(texts: List<ScreenTextRequest>, elements: List<ScreenElementRequest>): ScreenSnapshot {
        val textSnapshots = texts.map { textSnapshot(it.node.assertIsDisplayed(), it.text) }
        val nodes = elements.map { it.node.assertIsDisplayed().fetchSemanticsNode() }
        val native = checkpointSnapshot()
        val elementSnapshots = composeRule.runOnIdle {
            nodes.map { node -> screenElementSnapshot(node) }
        }
        val focusedActions = elements.zip(elementSnapshots).filter { (request, element) ->
            request.button && element.focused && element.node.id != native.node.id
        }.map { (request, element) ->
            checkpointSnapshot(request.node, element.node.config[SemanticsProperties.Text].single().text)
        }
        return ScreenSnapshot(native, textSnapshots, elementSnapshots, focusedActions)
    }

    private fun captureScreen(spec: NativeCaptureSpec, requests: List<ScreenTextRequest>, elements: List<ScreenElementRequest>) {
        composeRule.waitForIdle()
        val before = screenSnapshot(requests, elements)
        val windowBefore = presentationWindowDiagnostic(before)
        captureCheckpoint(spec, before.native) { bitmap, evidence ->
            // Save the exact asserted root even when the first geometry oracle fails.
            evidence.put("rootWindowDiagnostic", JSONObject()
                .put("rootUsedByOracle", before.native.root.json())
                .put("bitmapBoundsPx", JSONArray(listOf(0, 0, bitmap.width, bitmap.height)))
                .put("beforePng", windowBefore).put("afterPng", presentationWindowDiagnostic(before)))
            registerScreenEvidence(before, requests, evidence)
            collector.persist()
            verifyScreenState(spec, before.native)
            verifyScreenPixels(bitmap, before, requests, elements, evidence)
            verifyScreenStable(before, screenSnapshot(requests, elements))
        }
    }

    /** qmix#318: observed diagnostics only; IME pan/resize is not inferred from the PNG. */
    private fun presentationWindowDiagnostic(snapshot: ScreenSnapshot): JSONObject = composeRule.runOnUiThread {
        val view = snapshot.native.node.root as View
        val decor = view.rootView
        val insets = ViewCompat.getRootWindowInsets(view)
        val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())
        JSONObject().put("uptimeMillis", SystemClock.uptimeMillis())
            .put("observedRoot", nativeFrame(snapshot.native.node.layoutInfo.coordinates.findRootCoordinates()).json())
            .put("view", presentationViewDiagnostic(view)).put("decor", presentationViewDiagnostic(decor))
            .put("appWindowFocused", view.hasWindowFocus())
            .put("imeVisible", insets?.isVisible(WindowInsetsCompat.Type.ime()) ?: JSONObject.NULL)
            .put("imeInsetsPx", ime?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
            .put("softInputMode", (decor.layoutParams as? WindowManager.LayoutParams)?.softInputMode ?: JSONObject.NULL)
            .put("elementsAtSnapshot", JSONArray(snapshot.elements.map { it.json() }))
    }

    private fun presentationViewDiagnostic(view: View): JSONObject {
        val screen = IntArray(2)
        val window = IntArray(2)
        val visible = android.graphics.Rect()
        view.getLocationOnScreen(screen)
        view.getLocationInWindow(window)
        view.getWindowVisibleDisplayFrame(visible)
        return JSONObject().put("class", view.javaClass.name)
            .put("sizePx", JSONArray(listOf(view.width, view.height)))
            .put("locationOnScreenPx", JSONArray(screen.toList())).put("locationInWindowPx", JSONArray(window.toList()))
            .put("visibleDisplayFramePx", JSONArray(listOf(visible.left, visible.top, visible.right, visible.bottom)))
            .put("scrollPx", JSONArray(listOf(view.scrollX, view.scrollY)))
    }

    private fun verifyScreenState(spec: NativeCaptureSpec, snapshot: NativeCheckpoint) {
        assertEquals("SCREEN_STATE: enabled", spec.enabled, snapshot.enabled)
        assertEquals("SCREEN_STATE: focused primary", spec.focused, snapshot.focused)
        assertEquals("SCREEN_STATE: callbacks", spec.callbacks, snapshot.callbacks)
        assertEquals("SCREEN_STATE: keyDown", spec.keyDown, snapshot.keyDown)
        assertEquals("SCREEN_STATE: API", 36, Build.VERSION.SDK_INT)
        button.assertTextEquals(buttonLabel).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        verifyContentProvider(spec, snapshot.text.layout.layoutInput.style.color)
    }

    private fun verifyScreenPixels(bitmap: Bitmap, snapshot: ScreenSnapshot, requests: List<ScreenTextRequest>,
        elements: List<ScreenElementRequest>, evidence: JSONObject) {
        val native = snapshot.native
        val root = native.root.screen
        assertEquals("SCREEN_ROOT: width", 1920, bitmap.width)
        assertEquals("SCREEN_ROOT: height", 1080, bitmap.height)
        assertTrue("SCREEN_ROOT: complete root containment", contained(root, Rect(0f, 0f, 1920f, 1080f)))
        val inset = 48f * native.density
        val safe = safeRectangle(native)
        val boxes = mutableListOf<Pair<String, Rect>>()
        val textEvidence = JSONArray()
        verifyScreenTexts(bitmap, snapshot.texts, requests, root, safe, boxes, textEvidence)
        val elementEvidence = JSONArray()
        elements.zip(snapshot.elements).forEach { (request, element) ->
            verifyScreenElement(element, request, root, safe)
            if (!request.viewport) boxes.add(request.name to element.paintBounds())
            elementEvidence.put(element.json().put("name", request.name))
            verifyQueueViewport(element, request, boxes, evidence)
            verifyQueueFocus(bitmap, element, request, native.density, evidence)
            if (request.input && element.focused) {
                val baseline = requireNotNull(fieldFocusBaseline)
                assertEquals("FIELD_HISTORY: same semantic widget", baseline.node.id, element.node.id)
                assertSame("FIELD_HISTORY: same layout", baseline.node.layoutInfo, element.node.layoutInfo)
                assertSame("FIELD_HISTORY: same root", baseline.node.root, element.node.root)
                assertEquals("FIELD_HISTORY: unfocused source border 1dp", native.density, baseline.borderWidth, 0.001f)
                evidence.put("fieldFocus", verifyFieldFocus(bitmap, element, native.density)
                    .put("unfocusedSourceBorderWidthPx", baseline.borderWidth).put("sameWidgetId", element.node.id))
            }
        }
        verifyDisjoint(boxes)
        verifyFocusedActions(bitmap, snapshot.focusedActions, evidence)
        verifySafeFrame(native.outer, root, safe, "primary outline")
        verifySafeFrame(native.inner, root, safe, "primary scaled surface")
        val glyph = glyphEvidence(bitmap, native.text, native.enabled)
        val paint = nativePaintEvidence(bitmap, native, glyph.background)
        if (native.focused) {
            assertTrue("SCREEN_FOCUS: essential container/root adjacency >=3", paint.adjacentRatio >= 3.0)
            assertTrue("SCREEN_FOCUS: intervening essential edge paint", paint.adjacentGap <= 3)
        }
        evidence.put("safeRectanglePx", safe.array()).put("densityInsetPx", inset)
            .put("texts", textEvidence).put("elements", elementEvidence)
            .put("primaryGlyph", glyph.json()).put("primaryPaint", paint.json())
            .put("focusSubject", "primary action").put("observedFocusedElements",
                JSONArray(elements.zip(snapshot.elements).filter { it.second.focused }.map { it.first.name }))
            .put("effectiveResourceLocale", native.locale).put("deviceGlobalLocaleClaim", false)
    }

    /** Commit text identity/layout/geometry before pixel sampling so a failure names its current request. */
    private fun registerScreenEvidence(snapshot: ScreenSnapshot, requests: List<ScreenTextRequest>, evidence: JSONObject) {
        evidence.put("registeredTextCount", snapshot.texts.size)
            .put("registeredTexts", JSONArray(requests.zip(snapshot.texts).map { (request, text) ->
                JSONObject().put("text", request.text).put("maxLines", request.maxLines).put("ellipsisRequired", request.ellipsized)
                    .put("active", request.active).put("lineCount", text.layout.lineCount)
                    .put("frame", text.frame.json()).put("positionInRootPlusSize", text.semanticUnclipped.array())
                    .put("clippedSemanticBounds", text.clipped.array())
            }))
    }

    private fun verifyScreenTexts(bitmap: Bitmap, texts: List<NativeTextSnapshot>, requests: List<ScreenTextRequest>,
        root: Rect, safe: Rect, boxes: MutableList<Pair<String, Rect>>, evidence: JSONArray) {
        requests.zip(texts).forEach { (request, text) ->
            verifySafeFrame(text.frame, root, safe, request.text)
            assertTrue("SCREEN_TEXT: positionInRoot+size not contained", contained(text.semanticUnclipped, safe.translate(-root.topLeft)))
            assertTrue("SCREEN_TEXT: clipped semantics differ", rectDistance(text.semanticUnclipped, text.clipped) <= 0.5f)
            verifyScreenTextLayout(text, request.ellipsized, request.maxLines)
            evidence.put(glyphEvidence(bitmap, text, request.active, multiline = true, ellipsized = request.ellipsized,
                exactBackgroundChannels = true).json().put("text", text.expectedText).put("lineCount", text.layout.lineCount)
                .put("lastLineEllipsized", text.layout.isLineEllipsized(text.layout.lineCount - 1)).put("frame", text.frame.json())
                .put("positionInRootPlusSize", text.semanticUnclipped.array()).put("clippedSemanticBounds", text.clipped.array()))
            if (request.body) boxes.add(request.text to text.frame.screen)
        }
    }

    private fun verifyFocusedActions(bitmap: Bitmap, actions: List<NativeCheckpoint>, evidence: JSONObject) {
        val paints = JSONArray()
        actions.forEach { action ->
            val safe = safeRectangle(action)
            verifySafeFrame(action.outer, action.root.screen, safe, action.text.expectedText + " outline")
            verifySafeFrame(action.inner, action.root.screen, safe, action.text.expectedText + " scaled surface")
            val glyph = glyphEvidence(bitmap, action.text, true)
            val paint = nativePaintEvidence(bitmap, action, glyph.background)
            assertTrue("SCREEN_FOCUS: secondary action essential adjacency >=3", paint.adjacentRatio >= 3.0)
            assertTrue("SCREEN_FOCUS: secondary action intervening edge paint", paint.adjacentGap <= 3)
            paints.put(JSONObject().put("text", action.text.expectedText).put("id", action.node.id)
                .put("geometry", action.geometryJson()).put("glyph", glyph.json()).put("paint", paint.json()))
        }
        evidence.put("focusedActionPaints", paints)
    }

    private fun verifyScreenStable(before: ScreenSnapshot, after: ScreenSnapshot) {
        assertEquals("SCREEN_POSTCAPTURE: real identity/config/state", before.native.receipt(), after.native.receipt())
        assertSame("SCREEN_POSTCAPTURE: primary layout", before.native.node.layoutInfo, after.native.node.layoutInfo)
        assertSame("SCREEN_POSTCAPTURE: root", before.native.node.root, after.native.node.root)
        assertEquals("SCREEN_POSTCAPTURE: elements", before.elements.map { it.receipt() }, after.elements.map { it.receipt() })
        assertEquals("SCREEN_POSTCAPTURE: secondary focused actions", before.focusedActions.map { it.receipt() },
            after.focusedActions.map { it.receipt() })
        before.texts.zip(after.texts).forEach { (first, second) ->
            assertEquals("SCREEN_POSTCAPTURE: text/layout content", first.layout.layoutInput, second.layout.layoutInput)
            assertTrue("SCREEN_POSTCAPTURE: text moved", rectDistance(first.frame.screen, second.frame.screen) <= 0.5f)
        }
        assertTrue("SCREEN_POSTCAPTURE: primary border moved", rectDistance(before.native.outer.screen, after.native.outer.screen) <= 0.5f)
        assertTrue("SCREEN_POSTCAPTURE: primary surface moved", rectDistance(before.native.inner.screen, after.native.inner.screen) <= 0.5f)
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

private data class ScreenTextRequest(
    val node: SemanticsNodeInteraction, val text: String, val body: Boolean = false, val ellipsized: Boolean = false,
    val maxLines: Int = 2, val active: Boolean = true,
)

private data class ScreenElementRequest(
    val node: SemanticsNodeInteraction, val name: String, val button: Boolean = false, val input: Boolean = false,
    val enabled: Boolean = true, val readOnly: Boolean = false, val viewport: Boolean = false,
    val mergedText: List<String> = emptyList(),
)

private data class ScreenSnapshot(
    val native: NativeCheckpoint, val texts: List<NativeTextSnapshot>, val elements: List<ScreenElementSnapshot>,
    val focusedActions: List<NativeCheckpoint>,
)

private data class ScreenElementSnapshot(
    val node: SemanticsNode, val frame: NativeFrame, val semanticUnclipped: Rect, val semanticClipped: Rect,
    val borderFrame: NativeFrame?, val borderWidth: Float, val borderColor: Color?,
    val enabled: Boolean, val focused: Boolean, val editable: Boolean, val buttonRole: Boolean,
) {
    fun paintBounds(): Rect = borderFrame?.screen?.let { border ->
        unionBounds(frame.screen, border)
    } ?: frame.screen
    fun receipt(): List<Any?> = listOf(node.id, System.identityHashCode(node.layoutInfo), System.identityHashCode(node.root),
        frame.screen, borderFrame?.screen, borderWidth, borderColor, enabled, focused, editable, buttonRole)
    fun json(): JSONObject = JSONObject().put("id", node.id).put("frame", frame.json())
        .put("positionInRootPlusSize", semanticUnclipped.array()).put("clippedSemanticBounds", semanticClipped.array())
        .put("paintBounds", paintBounds().array()).put("sourceBorderWidthPx", borderWidth)
        .put("enabled", enabled).put("focused", focused).put("editable", editable).put("buttonRole", buttonRole)
        .put("clickAction", node.config.contains(SemanticsActions.OnClick))
        .put("selectionSemantics", node.config.contains(SemanticsProperties.Selected))
        .put("mergedText", JSONArray(node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }))
}

private fun screenElementSnapshot(node: SemanticsNode): ScreenElementSnapshot {
    val border = node.layoutInfo.getModifierInfo().singleOrNull { (it.modifier as? InspectableValue)?.nameFallback == "border" }
    val values = (border?.modifier as? InspectableValue)?.inspectableElements?.associate { it.name to it.value }
    return ScreenElementSnapshot(node, nativeFrame(node.layoutInfo.coordinates), Rect(node.positionInRoot, node.size.toSize()),
        node.boundsInRoot, border?.let { nativeFrame(it.coordinates) },
        (values?.get("width") as? Dp)?.value?.times(node.layoutInfo.density.density) ?: 0f,
        values?.get("color") as? Color, !node.config.contains(SemanticsProperties.Disabled),
        node.config.getOrElse(SemanticsProperties.Focused) { false },
        node.config.contains(SemanticsActions.SetText) && node.config.contains(SemanticsProperties.EditableText),
        node.config.contains(SemanticsProperties.Role) && node.config[SemanticsProperties.Role] == Role.Button)
}

private fun safeRectangle(native: NativeCheckpoint): Rect {
    val root = native.root.screen
    val inset = 48f * native.density
    return Rect(root.left + inset, root.top + inset, root.right - inset, root.bottom - inset)
}

private fun unionBounds(first: Rect, second: Rect): Rect = Rect(
    minOf(first.left, second.left), minOf(first.top, second.top), maxOf(first.right, second.right), maxOf(first.bottom, second.bottom),
)

private fun verifySafeFrame(frame: NativeFrame, root: Rect, safe: Rect, label: String) {
    verifyFrame(frame, root)
    assertTrue("SCREEN_SAFE: $label outside 48dp rectangle; ${frame.screen} safe=$safe", contained(frame.screen, safe))
}

private fun verifyScreenTextLayout(text: NativeTextSnapshot, ellipsized: Boolean, maxLines: Int = 2) {
    val layout = text.layout
    assertTrue("SCREEN_TEXT: native layout action rejected", text.actionAccepted)
    assertEquals("SCREEN_TEXT: full semantic/layout text", text.expectedText, layout.layoutInput.text.text)
    assertTrue("SCREEN_TEXT: no visible lines", layout.lineCount > 0)
    if (ellipsized) {
        assertEquals("SCREEN_OVERFLOW: deterministic bounded line count ${text.expectedText}", maxLines, layout.lineCount)
        assertTrue("SCREEN_OVERFLOW: deterministic long data must visibly ellipsize", layout.isLineEllipsized(layout.lineCount - 1))
    } else {
        assertTrue("SCREEN_TEXT: important copy lost", !layout.hasVisualOverflow)
        assertTrue("SCREEN_TEXT: important copy ellipsized", (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
    }
}

private fun verifyScreenElement(element: ScreenElementSnapshot, request: ScreenElementRequest, root: Rect, safe: Rect) {
    verifySafeFrame(element.frame, root, safe, request.name)
    element.borderFrame?.let { verifySafeFrame(it, root, safe, request.name + " outline") }
    val rootSafe = safe.translate(-root.topLeft)
    assertTrue("SCREEN_SEMANTICS: raw positionInRoot+size outside safe area ${request.name}", contained(element.semanticUnclipped, rootSafe))
    if (request.button) {
        assertTrue("SCREEN_ACTION: Button role missing ${request.name}", element.buttonRole)
        assertEquals("SCREEN_ACTION: disabled semantics ${request.name}", request.enabled, element.enabled)
        assertTrue("SCREEN_ACTION: readable name missing", element.node.config[SemanticsProperties.Text].any { it.text.isNotBlank() })
        assertTrue("SCREEN_ACTION: click action missing", element.node.config.contains(SemanticsActions.OnClick))
    }
    verifyReadOnlyElement(element, request)
    if (request.input) {
        assertTrue("SCREEN_INPUT: edit action/text missing", element.editable)
        assertTrue("SCREEN_INPUT: unexpectedly disabled", element.enabled)
        assertEquals("SCREEN_INPUT: source-bound non-color border width", if (element.focused) 4f else 1f,
            element.borderWidth / element.node.layoutInfo.density.density, 0.001f)
        assertTrue("SCREEN_INPUT: clipped", rectDistance(element.semanticUnclipped, element.semanticClipped) <= 0.5f)
    }
}

/** Framework-facing read-only names are distinct from button/selection/edit actions. */
private fun verifyReadOnlyElement(element: ScreenElementSnapshot, request: ScreenElementRequest) {
    if (!request.readOnly) return
    val config = element.node.config
    assertEquals("QUEUE_READ_ONLY: full merged title/artist/duration name", request.mergedText,
        config[SemanticsProperties.Text].map { it.text })
    assertTrue("QUEUE_FOCUS: scrolled target lost focus", element.focused)
    assertTrue("QUEUE_READ_ONLY: click action present", !config.contains(SemanticsActions.OnClick))
    assertTrue("QUEUE_READ_ONLY: long-click action present", !config.contains(SemanticsActions.OnLongClick))
    assertTrue("QUEUE_READ_ONLY: Button role present", !element.buttonRole)
    assertTrue("QUEUE_READ_ONLY: local selection semantics present", !config.contains(SemanticsProperties.Selected))
    assertTrue("QUEUE_READ_ONLY: editable", !element.editable)
    assertEquals("QUEUE_FOCUS: no non-color thickness cue", 4f,
        element.borderWidth / element.node.layoutInfo.density.density, 0.001f)
}

private fun verifyQueueFocus(bitmap: Bitmap, element: ScreenElementSnapshot, request: ScreenElementRequest,
    density: Float, evidence: JSONObject) {
    if (request.readOnly && element.focused) evidence.put("queueFocus", verifyFieldFocus(bitmap, element, density))
}

private fun verifyQueueViewport(element: ScreenElementSnapshot, request: ScreenElementRequest,
    boxes: List<Pair<String, Rect>>, evidence: JSONObject) {
    if (request.viewport) {
        val viewport = element.frame.screen
        evidence.put("queueViewportPx", viewport.array())
        assertTrue("QUEUE_VIEWPORT: no positive viewport", viewport.width > 0f && viewport.height > 0f)
        verifyDisjoint(boxes + listOf("queue viewport" to viewport))
    }
    if (request.readOnly) {
        val bounds = evidence.getJSONArray("queueViewportPx")
        val viewport = Rect(bounds.getDouble(0).toFloat(), bounds.getDouble(1).toFloat(),
            bounds.getDouble(2).toFloat(), bounds.getDouble(3).toFloat())
        assertTrue("QUEUE_VIEWPORT: focused row paint clipped", contained(element.paintBounds(), viewport))
    }
}

private fun verifyDisjoint(boxes: List<Pair<String, Rect>>) {
    boxes.forEachIndexed { index, first ->
        boxes.drop(index + 1).forEach { second ->
            val overlapWidth = minOf(first.second.right, second.second.right) - maxOf(first.second.left, second.second.left)
            val overlapHeight = minOf(first.second.bottom, second.second.bottom) - maxOf(first.second.top, second.second.top)
            assertTrue("SCREEN_OVERLAP: ${first.first} / ${second.first}", overlapWidth <= 0f || overlapHeight <= 0f)
        }
    }
}

private fun verifyFieldFocus(bitmap: Bitmap, element: ScreenElementSnapshot, density: Float): JSONObject {
    val border = requireNotNull(element.borderFrame) { "FIELD_FOCUS: actual border modifier missing" }
    val expected = requireNotNull(element.borderColor)
    assertEquals("FIELD_FOCUS: non-color 4dp thickness", 4f * density, element.borderWidth, 0.001f)
    val x = border.screen.center.x.roundToInt()
    val firstRow = ceil(border.screen.top).toInt()
    val sample = bitmap.color(x, (border.screen.top + element.borderWidth / 2f).roundToInt())
    val outside = bitmap.color(x, floor(border.screen.top).toInt() - 2)
    val coreRows = (firstRow until firstRow + floor(element.borderWidth).toInt()).count {
        colorDistance(bitmap.color(x, it), expected) <= 3f / 255f
    }
    assertTrue("FIELD_FOCUS: rendered border/source mismatch", colorDistance(sample, expected) <= 3f / 255f)
    assertTrue("FIELD_FOCUS: visible thickness missing", coreRows >= element.borderWidth - 2f)
    val ratio = contrast(sample, outside)
    assertTrue("FIELD_FOCUS: essential outline/exterior adjacency >=3; $ratio", ratio >= 3.0)
    return JSONObject().put("sourceWidthPx", element.borderWidth).put("paintCoreRows", coreRows)
        .put("nativeBorderArgb", sample.toArgb()).put("adjacentExteriorArgb", outside.toArgb()).put("unroundedContrast", ratio)
}

/** Bind Foundation's short, unscrolled URL glyphs to its source-declared 14dp inset. */
private fun inputGlyphFrame(node: SemanticsNode, layout: TextLayoutResult): NativeFrame {
    val modifiers = node.layoutInfo.getModifierInfo()
    val padding = modifiers.single { (it.modifier as? InspectableValue)?.nameFallback == "padding" }
    val appliedPadding = (padding.modifier as InspectableValue).valueOverride as Dp
    assertEquals("INPUT_REGISTRATION: real source padding", 14.dp, appliedPadding)
    val border = modifiers.single { (it.modifier as? InspectableValue)?.nameFallback == "border" }
    val outer = nativeFrame(border.coordinates)
    val inset = appliedPadding.value * node.layoutInfo.density.density
    assertEquals("INPUT_REGISTRATION: unscaled", 1f, outer.scaleX, 0.001f)
    assertTrue("INPUT_REGISTRATION: unexpected horizontal scrolling", layout.size.width <= outer.local.width - 2f * inset + 0.5f)
    assertEquals("INPUT_REGISTRATION: native text height/viewport", outer.local.height - 2f * inset, layout.size.height.toFloat(), 0.5f)
    val offset = Offset(inset, inset)
    val local = Rect(Offset.Zero, layout.size.toSize())
    return outer.copy(origin = outer.origin + offset, local = local,
        screen = Rect(outer.screen.topLeft + offset, local.size), root = Rect(outer.root.topLeft + offset, local.size),
        clippedRoot = Rect(outer.root.topLeft + offset, local.size))
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
    val semanticUnclipped: Rect,
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

private data class TransformedGlyphEvidence(
    val glyph: NativeGlyphEvidence, val matchingBackgroundEdges: Int, val topBackgroundEdges: Int, val bottomBackgroundEdges: Int,
) {
    val background: Color get() = glyph.background
    fun json(): JSONObject = glyph.json().put("matchingBackgroundEdgeSamples", matchingBackgroundEdges)
        .put("topBackgroundEdgeSamples", topBackgroundEdges).put("bottomBackgroundEdgeSamples", bottomBackgroundEdges)
        .put("totalBackgroundEdgeSamples", topBackgroundEdges + bottomBackgroundEdges)
}

private fun glyphEvidence(bitmap: Bitmap, text: NativeTextSnapshot, active: Boolean,
    multiline: Boolean = false, ellipsized: Boolean = false, exactBackgroundChannels: Boolean = false): TransformedGlyphEvidence {
    val layout = text.layout
    assertTrue("NATIVE_LAYOUT: semantics action rejected", text.actionAccepted)
    assertEquals("NATIVE_LAYOUT: wrong native Text", text.expectedText, layout.layoutInput.text.text)
    if (!multiline) assertEquals("NATIVE_LAYOUT: line count", 1, layout.lineCount)
    if (!ellipsized) assertTrue("NATIVE_LAYOUT: overflow", !layout.hasVisualOverflow)
    assertEquals("NATIVE_LAYOUT: local size", layout.size.toSize(), text.frame.local.size)
    val bounds = text.frame.screen
    val left = floor(bounds.left).toInt()
    val top = floor(bounds.top).toInt()
    val width = ceil(bounds.right).toInt() - left
    val height = ceil(bounds.bottom).toInt() - top
    val visibleOffsets = if (ellipsized) (0 until layout.lineCount).flatMap { line ->
        (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true)).toList()
    } else layout.layoutInput.text.text.indices.toList()
    val boxes = visibleOffsets.filterNot { layout.layoutInput.text.text[it].isWhitespace() }
        .map { layout.getBoundingBox(it) }
    val samples = pixelSamples(width, height) { x, y ->
        val local = text.frame.toLocal(Offset(left + x + 0.5f, top + y + 0.5f))
        if (text.frame.local.contains(local)) bitmap.color(left + x, top + y) else null
    }
    val background = Color(samples.groupingBy { it.toArgb() }.eachCount().maxBy { it.value }.key)
    // Use the first and last physical pixel-center rows inside the transformed Text frame.
    // As in the retained tracer, these edge rows must predominantly be background.
    val firstRow = ceil(bounds.top - 0.5f).toInt()
    val lastRow = ceil(bounds.bottom - 0.5f).toInt() - 1
    assertTrue("NATIVE_BACKGROUND: no transformed top/bottom rows", firstRow <= lastRow)
    fun edgeSamples(row: Int): List<Color> = pixelSamples(width, 1) { x, _ ->
        val screen = Offset(left + x + 0.5f, row + 0.5f)
        val local = text.frame.toLocal(screen)
        if (text.frame.local.contains(local)) bitmap.color(left + x, row) else null
    }
    val topEdges = edgeSamples(firstRow)
    val bottomEdges = edgeSamples(lastRow)
    assertTrue("NATIVE_BACKGROUND: missing transformed nonglyph edge samples", topEdges.isNotEmpty() && bottomEdges.isNotEmpty())
    val edges = topEdges + bottomEdges
    val matchingEdges = edges.count {
        if (exactBackgroundChannels) nativeBackgroundMatches(it, background) else colorDistance(it, background) <= 2f / 255f
    }
    assertTrue("NATIVE_BACKGROUND: nonuniform Text-local edges $matchingEdges/${edges.size}; text=${text.expectedText} " +
        "frame=$bounds rows=$firstRow/$lastRow backgroundArgb=${background.toArgb()}", matchingEdges >= edges.size * 0.9)
    val glyphs = pixelSamples(width, height) { x, y ->
        val local = text.frame.toLocal(Offset(left + x + 0.5f, top + y + 0.5f))
        if (boxes.any { it.contains(local) }) bitmap.color(left + x, top + y) else null
    }
    return TransformedGlyphEvidence(nativeGlyphEvidence(glyphs, background, layout.layoutInput.style.color, active),
        matchingEdges, topEdges.size, bottomEdges.size)
}

/** qmix#318: native sRGB8-bit samples retain the inclusive two-channel-level tolerance without Float subtraction error. */
private fun nativeBackgroundMatches(first: Color, second: Color): Boolean {
    val firstArgb = first.toArgb()
    val secondArgb = second.toArgb()
    return listOf(16, 8, 0).all { shift -> abs(((firstArgb ushr shift) and 255) - ((secondArgb ushr shift) and 255)) <= 2 }
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
        NativeCaptureSpec("setup-backend-focused.png", "setup_field_focused", true, false, 0, false),
        NativeCaptureSpec("http-warning-en.png", "http_warning", true, true, 0, false),
        NativeCaptureSpec("http-warning-ru.png", "http_warning", true, true, 0, false),
        NativeCaptureSpec("setup-error-ru.png", "setup_error", true, true, 0, false),
        NativeCaptureSpec("invitation-replacement-long-url-en.png", "invitation_replacement", true, true, 0, false),
        NativeCaptureSpec("invitation-replacement-long-url-ru.png", "invitation_replacement", true, true, 0, false),
        NativeCaptureSpec("live-empty-en.png", "live_empty", false, false, 0, false),
        NativeCaptureSpec("live-long-row19-en.png", "live_long_row19", true, false, 0, false),
        NativeCaptureSpec("live-long-final-en.png", "live_long_final", true, false, 0, false),
        NativeCaptureSpec("live-notices-ru.png", "live_notices", false, false, 0, false),
        NativeCaptureSpec("missing-room-ru.png", "missing_room", true, true, 0, false),
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
