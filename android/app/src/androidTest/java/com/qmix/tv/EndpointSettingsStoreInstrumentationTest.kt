package com.qmix.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EndpointSettingsStoreInstrumentationTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = context.getSharedPreferences(
            EndpointSettingsStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        assertTrue(preferences.edit().clear().commit())
    }

    @After
    fun tearDown() {
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun failed_endpoint_commit_restores_prior_values_for_same_and_fresh_stores() {
        val previous = EndpointSettings(
            "https://previous.example/base",
            "https://guest.previous.example",
        )
        val replacement = EndpointSettings(
            "https://replacement.example",
            "https://guest.replacement.example",
        )
        EndpointSettingsStore(context).apply {
            save(previous)
            acknowledgeHttpWarning()
        }
        var commitCalls = 0
        val failingStore = EndpointSettingsStore(
            context = context,
            endpointSaveCommit = { editor ->
                commitCalls++
                editor.commit()
                false
            },
            acknowledgementCommit = SharedPreferences.Editor::commit,
        )

        expectPersistenceFailure("Could not persist endpoint settings") {
            failingStore.save(replacement)
        }

        assertEquals(2, commitCalls)
        assertEquals(previous, failingStore.load())
        assertEquals(previous, EndpointSettingsStore(context).load())
        assertTrue(EndpointSettingsStore(context).isHttpWarningAcknowledged())
    }

    @Test
    fun failed_endpoint_commit_removes_new_values_and_preserves_acknowledgement() {
        assertTrue(
            preferences.edit().putString("backend_url", "https://partial.example").commit(),
        )
        EndpointSettingsStore(context).acknowledgeHttpWarning()
        var commitCalls = 0
        val failingStore = EndpointSettingsStore(
            context = context,
            endpointSaveCommit = { editor ->
                commitCalls++
                editor.commit()
                false
            },
            acknowledgementCommit = SharedPreferences.Editor::commit,
        )

        expectPersistenceFailure("Could not persist endpoint settings") {
            failingStore.save(
                EndpointSettings("https://replacement.example", "https://guest.example"),
            )
        }

        assertEquals(2, commitCalls)
        assertEquals(EndpointSettings.EMPTY, failingStore.load())
        assertEquals(EndpointSettings.EMPTY, EndpointSettingsStore(context).load())
        assertFalse(preferences.all.containsKey("backend_url"))
        assertFalse(preferences.all.containsKey("guest_origin"))
        assertTrue(EndpointSettingsStore(context).isHttpWarningAcknowledged())
    }

    @Test
    fun malformed_stored_values_load_as_empty_on_device() {
        assertTrue(
            preferences.edit()
                .putString("backend_url", "https://user:secret@api.example")
                .putString("guest_origin", "https://guest.example")
                .commit(),
        )

        assertEquals(EndpointSettings.EMPTY, EndpointSettingsStore(context).load())
    }

    @Test
    fun invalid_endpoint_save_is_rejected_without_mutating_preferences() {
        val store = EndpointSettingsStore(context)

        try {
            store.save(EndpointSettings("not a url", "https://guest.example"))
            fail("Expected invalid endpoint rejection")
        } catch (_: IllegalArgumentException) {
            // Expected: validation fails before opening a preference transaction.
        }

        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun failed_acknowledgement_commit_rolls_back_live_state_for_same_and_fresh_stores() {
        val failingStore = EndpointSettingsStore(context) { editor ->
            editor.commit()
            false
        }
        val backend = "http://192.168.1.20:8180"
        val guestOrigin = "https://guest.example"
        val controller = HostSessionController(
            OkHttpClient(),
            settingsPersistence = failingStore,
        )
        controller.updateSettings(backend, guestOrigin)
        assertTrue(controller.createRoom())
        assertEquals(HostingState.HttpWarning(backend, guestOrigin), controller.state)

        assertFalse(controller.confirmHttpWarning())

        assertEquals(
            HostingState.Error(UserMessage.PERSISTENCE_ERROR, backend, guestOrigin),
            controller.state,
        )
        assertFalse(failingStore.isHttpWarningAcknowledged())
        assertFalse(EndpointSettingsStore(context).isHttpWarningAcknowledged())
        assertEquals(false, preferences.all["http_warning_acknowledged"])
    }

    private fun expectPersistenceFailure(expectedMessage: String, action: () -> Unit) {
        try {
            action()
            fail("Expected persistence failure")
        } catch (error: IllegalStateException) {
            assertEquals(expectedMessage, error.message)
        }
    }
}
