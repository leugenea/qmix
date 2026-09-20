package com.qmix.tv

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalizedHostingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun english_locale_renders_setup_invitation_live_error_and_accessibility_copy() {
        exerciseLocale(
            language = "en",
            timeout = "The server timed out. Try again.",
            backendLabel = "Backend URL",
            retry = "Retry",
            join = "Join this room",
            roomCode = "Room code: ABCD",
            qrDescription = "QR code for https://guest.example/r/ABCD",
            enter = "Enter room",
            roomTitle = "Room ABCD",
            playbackError = "Playback error: Network connection interrupted.",
            queueEmpty = "Queue is empty",
        )
    }

    @Test
    fun russian_locale_renders_setup_invitation_live_error_and_accessibility_copy() {
        exerciseLocale(
            language = "ru",
            timeout = "\u0421\u0435\u0440\u0432\u0435\u0440 \u043d\u0435 \u043e\u0442\u0432\u0435\u0442\u0438\u043b \u0432\u043e\u0432\u0440\u0435\u043c\u044f. \u041f\u043e\u0432\u0442\u043e\u0440\u0438\u0442\u0435 \u043f\u043e\u043f\u044b\u0442\u043a\u0443.",
            backendLabel = "URL \u0441\u0435\u0440\u0432\u0435\u0440\u0430",
            retry = "\u041f\u043e\u0432\u0442\u043e\u0440\u0438\u0442\u044c",
            join = "\u041f\u0440\u0438\u0441\u043e\u0435\u0434\u0438\u043d\u044f\u0439\u0442\u0435\u0441\u044c \u043a \u043a\u043e\u043c\u043d\u0430\u0442\u0435",
            roomCode = "\u041a\u043e\u0434 \u043a\u043e\u043c\u043d\u0430\u0442\u044b: ABCD",
            qrDescription = "QR-\u043a\u043e\u0434 \u0434\u043b\u044f https://guest.example/r/ABCD",
            enter = "\u0412\u043e\u0439\u0442\u0438 \u0432 \u043a\u043e\u043c\u043d\u0430\u0442\u0443",
            roomTitle = "\u041a\u043e\u043c\u043d\u0430\u0442\u0430 ABCD",
            playbackError = "\u041e\u0448\u0438\u0431\u043a\u0430 \u0432\u043e\u0441\u043f\u0440\u043e\u0438\u0437\u0432\u0435\u0434\u0435\u043d\u0438\u044f: \u0421\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u0441 \u0441\u0435\u0442\u044c\u044e \u043f\u0440\u0435\u0440\u0432\u0430\u043d\u043e.",
            queueEmpty = "\u041e\u0447\u0435\u0440\u0435\u0434\u044c \u043f\u0443\u0441\u0442\u0430",
        )
    }

    @Test
    fun russian_resources_keep_fixed_representative_setup_http_and_accessibility_copy() {
        val russian = localizedContext("ru")

        assertEquals("\u0421\u043e\u0437\u0434\u0430\u0442\u044c \u043a\u043e\u043c\u043d\u0430\u0442\u0443", russian.getString(R.string.create_room))
        assertEquals("HTTP \u043d\u0435 \u0437\u0430\u0449\u0438\u0449\u0430\u0435\u0442 \u0434\u0430\u043d\u043d\u044b\u0435", russian.getString(R.string.http_warning_title))
        assertEquals(
            "\u041f\u0440\u0438 \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u043d\u0438\u0438 HTTP \u0443\u0441\u0442\u0440\u043e\u0439\u0441\u0442\u0432\u0430 \u0432 \u043b\u043e\u043a\u0430\u043b\u044c\u043d\u043e\u0439 \u0441\u0435\u0442\u0438 \u043c\u043e\u0433\u0443\u0442 \u043f\u0435\u0440\u0435\u0445\u0432\u0430\u0442\u0438\u0442\u044c \u0434\u0430\u043d\u043d\u044b\u0435 \u043a\u043e\u043c\u043d\u0430\u0442\u044b \u0438 \u0443\u0447\u0451\u0442\u043d\u044b\u0435 \u0434\u0430\u043d\u043d\u044b\u0435 \u0432\u0435\u0434\u0443\u0449\u0435\u0433\u043e. \u0418\u0441\u043f\u043e\u043b\u044c\u0437\u0443\u0439\u0442\u0435 HTTP \u0442\u043e\u043b\u044c\u043a\u043e \u0432 \u0434\u043e\u0432\u0435\u0440\u0435\u043d\u043d\u043e\u0439 \u043b\u043e\u043a\u0430\u043b\u044c\u043d\u043e\u0439 \u0441\u0435\u0442\u0438, \u0430 \u0434\u043b\u044f \u043f\u0443\u0431\u043b\u0438\u0447\u043d\u043e\u0433\u043e \u0438\u043b\u0438 \u0443\u0434\u0430\u043b\u0451\u043d\u043d\u043e\u0433\u043e \u0434\u043e\u0441\u0442\u0443\u043f\u0430 \u2014 HTTPS.",
            russian.getString(R.string.http_warning_body),
        )
        assertEquals("\u041a\u043e\u0434 \u043a\u043e\u043c\u043d\u0430\u0442\u044b: ABCD", russian.getString(R.string.invitation_room_code, "ABCD"))
        assertEquals(
            "QR-\u043a\u043e\u0434 \u0434\u043b\u044f https://guest.example/r/ABCD",
            russian.getString(R.string.invitation_qr_description, "https://guest.example/r/ABCD"),
        )
        assertEquals("\u041a\u043e\u043c\u043d\u0430\u0442\u0430 ABCD", russian.getString(R.string.room_title, "ABCD"))
        assertEquals("\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0438\u0442\u044c\u0441\u044f \u043a \u0441\u0435\u0440\u0432\u0435\u0440\u0443.", russian.getString(R.string.error_server_unreachable))
        assertEquals("\u0422\u0440\u0435\u043a, \u0432\u044b\u0431\u0440\u0430\u043d\u043d\u044b\u0439 \u0441\u0435\u0440\u0432\u0435\u0440\u043e\u043c", russian.getString(R.string.server_selected_track))
    }

    @Test
    fun every_domain_error_classification_resolves_in_english_and_russian() {
        val english = localizedContext("en")
        val russian = localizedContext("ru")

        UserMessage.entries.forEach { message ->
            val resource = message.resourceId()
            val englishText = english.getString(resource)
            val russianText = russian.getString(resource)
            assertTrue(englishText.isNotBlank())
            assertTrue(russianText.isNotBlank())
            assertNotEquals(englishText, russianText)
        }
        val http = localPlaybackErrorText(PlaybackError(PlaybackErrorKind.HTTP, "private", 503))
        assertEquals("Stream request failed (HTTP 503).", english.getString(http.resource, http.argument))
        assertEquals(
            "\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u0437\u0430\u043f\u0440\u043e\u0441\u0438\u0442\u044c \u0430\u0443\u0434\u0438\u043e\u043f\u043e\u0442\u043e\u043a (HTTP 503).",
            russian.getString(http.resource, http.argument),
        )
    }

    private fun exerciseLocale(
        language: String,
        timeout: String,
        backendLabel: String,
        retry: String,
        join: String,
        roomCode: String,
        qrDescription: String,
        enter: String,
        roomTitle: String,
        playbackError: String,
        queueEmpty: String,
    ) {
        val context = localizedContext(language)
        val state = mutableStateOf<HostingState>(
            HostingState.Error(
                UserMessage.SERVER_TIMEOUT,
                "https://api.example",
                "https://guest.example",
            ),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                HostingScreen(
                    state = state.value,
                    onSettingsChanged = { _, _ -> },
                    onCreate = {},
                    onEnterRoom = {},
                )
            }
        }

        composeRule.onNodeWithText(timeout).assertExists()
        composeRule.onNodeWithContentDescription(backendLabel).assertExists()
        composeRule.onNodeWithText(retry).assertIsFocused()

        composeRule.runOnIdle {
            state.value = HostingState.Invitation(GuestInvite("ABCD", "https://guest.example/r/ABCD"))
        }
        composeRule.onNodeWithText(join).assertExists()
        composeRule.onNodeWithText(roomCode).assertExists()
        composeRule.onNodeWithContentDescription(qrDescription).assertExists()
        composeRule.onNodeWithText(enter).assertIsFocused()

        composeRule.runOnIdle {
            state.value = HostingState.LiveRoom(
                invite = GuestInvite("ABCD", "https://guest.example/r/ABCD"),
                synchronization = RoomSyncState.Active(
                    roomCode = "ABCD",
                    room = RoomState("ABCD", null, emptyList()),
                    freshness = Freshness.FRESH,
                    connection = LiveConnection.CONNECTED,
                ),
                playback = LocalPlaybackState(
                    trackId = "current",
                    status = LocalPlaybackStatus.ERROR,
                    error = PlaybackError(PlaybackErrorKind.NETWORK, "must not render"),
                ),
            )
        }
        composeRule.onNodeWithText(roomTitle).assertExists()
        composeRule.onNodeWithText(playbackError).assertExists()
        composeRule.onNodeWithText(queueEmpty).assertExists()
    }

    private fun localizedContext(language: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(language)))
        return base.createConfigurationContext(configuration)
    }
}
