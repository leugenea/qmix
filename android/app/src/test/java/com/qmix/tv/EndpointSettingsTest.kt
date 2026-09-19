package com.qmix.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EndpointSettingsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(EndpointSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun no_saved_value_migrates_to_blank_setup() {
        val store = EndpointSettingsStore(context)

        assertEquals(EndpointSettings.EMPTY, store.load())
        assertFalse(store.isHttpWarningAcknowledged())
    }

    @Test
    fun successful_values_survive_a_new_store_instance() {
        val expected = EndpointSettings(
            backendUrl = "https://api.example/base",
            guestOrigin = "https://guest.example",
        )
        EndpointSettingsStore(context).save(expected)

        assertEquals(expected, EndpointSettingsStore(context).load())
    }

    @Test
    fun failed_endpoint_commit_restores_prior_valid_values_for_same_and_fresh_stores() {
        val previous = EndpointSettings("https://previous.example/base", "https://guest.previous.example")
        val replacement = EndpointSettings("https://replacement.example", "https://guest.replacement.example")
        EndpointSettingsStore(context).apply {
            save(previous)
            acknowledgeHttpWarning()
        }
        var endpointCommitCalls = 0
        val failingStore = EndpointSettingsStore(
            context = context,
            endpointSaveCommit = { editor ->
                endpointCommitCalls++
                editor.commit()
                false
            },
            acknowledgementCommit = SharedPreferences.Editor::commit,
        )

        assertThrows(IllegalStateException::class.java) {
            failingStore.save(replacement)
        }

        assertEquals(2, endpointCommitCalls)
        assertEquals(previous, failingStore.load())
        assertEquals(previous, EndpointSettingsStore(context).load())
        assertEquals(
            HostingState.Setup(previous.backendUrl, previous.guestOrigin),
            HostSessionController(
                okhttp3.OkHttpClient(),
                settingsPersistence = EndpointSettingsStore(context),
            ).state,
        )
        assertTrue(EndpointSettingsStore(context).isHttpWarningAcknowledged())
    }

    @Test
    fun failed_endpoint_commit_removes_malformed_prior_values_without_touching_acknowledgement() {
        val preferences = context.getSharedPreferences(EndpointSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putString("backend_url", "https://user:secret@api.example")
            .putInt("guest_origin", 7)
            .putBoolean("http_warning_acknowledged", true)
            .commit()
        val failingStore = EndpointSettingsStore(
            context = context,
            endpointSaveCommit = { editor ->
                editor.commit()
                false
            },
            acknowledgementCommit = SharedPreferences.Editor::commit,
        )

        assertThrows(IllegalStateException::class.java) {
            failingStore.save(EndpointSettings("https://replacement.example", "https://guest.example"))
        }

        assertEquals(EndpointSettings.EMPTY, failingStore.load())
        assertEquals(EndpointSettings.EMPTY, EndpointSettingsStore(context).load())
        assertFalse(preferences.all.containsKey("backend_url"))
        assertFalse(preferences.all.containsKey("guest_origin"))
        assertTrue(EndpointSettingsStore(context).isHttpWarningAcknowledged())
    }

    @Test
    fun endpoint_save_transactions_are_serialized_across_store_instances() {
        val previous = EndpointSettings("https://previous.example", "https://guest.previous.example")
        val successful = EndpointSettings("https://successful.example", "https://guest.successful.example")
        EndpointSettingsStore(context).save(previous)
        val failedCommitPublished = CountDownLatch(1)
        val allowFailure = CountDownLatch(1)
        val failingStore = EndpointSettingsStore(
            context = context,
            endpointSaveCommit = { editor ->
                editor.commit()
                failedCommitPublished.countDown()
                check(allowFailure.await(5, TimeUnit.SECONDS))
                false
            },
            acknowledgementCommit = SharedPreferences.Editor::commit,
        )
        val successfulSaveStarted = CountDownLatch(1)
        val successfulCommitEntered = CountDownLatch(1)
        val successfulStore = EndpointSettingsStore(
            context = context,
            endpointSaveCommit = { editor ->
                successfulCommitEntered.countDown()
                editor.commit()
            },
            acknowledgementCommit = SharedPreferences.Editor::commit,
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val failed = executor.submit<Unit> {
                assertThrows(IllegalStateException::class.java) {
                    failingStore.save(EndpointSettings("https://failed.example", "https://guest.failed.example"))
                }
            }
            assertTrue(failedCommitPublished.await(5, TimeUnit.SECONDS))
            val succeeded = executor.submit<Unit> {
                successfulSaveStarted.countDown()
                successfulStore.save(successful)
            }
            assertTrue(successfulSaveStarted.await(5, TimeUnit.SECONDS))
            assertFalse(successfulCommitEntered.await(200, TimeUnit.MILLISECONDS))
            allowFailure.countDown()

            failed.get(5, TimeUnit.SECONDS)
            succeeded.get(5, TimeUnit.SECONDS)
            assertEquals(0L, successfulCommitEntered.count)
            assertEquals(successful, EndpointSettingsStore(context).load())
        } finally {
            allowFailure.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun warning_acknowledgement_is_durable_and_stores_no_endpoint_secret() {
        val store = EndpointSettingsStore(context)
        store.acknowledgeHttpWarning()

        val replacement = EndpointSettingsStore(context)
        assertTrue(replacement.isHttpWarningAcknowledged())
        assertFalse(
            context.getSharedPreferences(EndpointSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .all.toString().contains("token", ignoreCase = true),
        )
    }

    @Test
    fun failed_acknowledgement_commit_rolls_back_live_state_for_same_and_fresh_stores() {
        context.getSharedPreferences(EndpointSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putString("http_warning_acknowledged", "true").commit()
        val failingStore = EndpointSettingsStore(context) { editor ->
            editor.commit()
            false
        }

        assertThrows(IllegalStateException::class.java) {
            failingStore.acknowledgeHttpWarning()
        }

        assertFalse(failingStore.isHttpWarningAcknowledged())
        assertFalse(EndpointSettingsStore(context).isHttpWarningAcknowledged())

        val controller = HostSessionController(
            okhttp3.OkHttpClient(),
            settingsPersistence = EndpointSettingsStore(context),
        )
        controller.updateSettings("http://192.168.1.20:8180", "https://guest.example")
        assertTrue(controller.createRoom())
        assertTrue(controller.state is HostingState.HttpWarning)
    }

    @Test
    fun acknowledgement_already_made_by_shared_store_is_not_overwritten() {
        EndpointSettingsStore(context).acknowledgeHttpWarning()
        var commitCalls = 0
        val staleCaller = EndpointSettingsStore(context) { editor ->
            commitCalls++
            editor.commit()
        }

        staleCaller.acknowledgeHttpWarning()

        assertEquals(0, commitCalls)
        assertTrue(EndpointSettingsStore(context).isHttpWarningAcknowledged())
    }

    @Test
    fun invalid_partial_or_wrong_typed_endpoint_values_restore_as_blank() {
        val preferences = context.getSharedPreferences(EndpointSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putString("backend_url", "https://user:secret@api.example")
            .putString("guest_origin", "https://guest.example")
            .commit()

        assertEquals(EndpointSettings.EMPTY, EndpointSettingsStore(context).load())

        preferences.edit().clear().putString("backend_url", "https://api.example").commit()
        assertEquals(EndpointSettings.EMPTY, EndpointSettingsStore(context).load())

        val wrongTypedValues = listOf<Any>(true, 7, 8L, 1.5f, setOf("https://wrong.example"))
        listOf("backend_url", "guest_origin").forEach { wrongTypedKey ->
            wrongTypedValues.forEach { wrongValue ->
                val editor = preferences.edit().clear()
                    .putString("backend_url", "https://api.example")
                    .putString("guest_origin", "https://guest.example")
                putPreference(editor, wrongTypedKey, wrongValue).commit()

                assertEquals(
                    "$wrongTypedKey with ${wrongValue::class.java.simpleName}",
                    EndpointSettings.EMPTY,
                    EndpointSettingsStore(context).load(),
                )
            }
        }
    }

    @Test
    fun wrong_typed_warning_acknowledgement_is_treated_as_false() {
        val preferences = context.getSharedPreferences(EndpointSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val wrongTypedValues = listOf<Any>("true", 1, 1L, 1.0f, setOf("true"))

        wrongTypedValues.forEach { wrongValue ->
            val editor = preferences.edit().clear()
            putPreference(editor, "http_warning_acknowledged", wrongValue).commit()

            assertFalse(
                "acknowledgement with ${wrongValue::class.java.simpleName}",
                EndpointSettingsStore(context).isHttpWarningAcknowledged(),
            )
        }
    }

    @Test
    fun validation_canonicalizes_http_and_https_without_credentials_or_url_secrets() {
        assertEquals(
            EndpointSettings("http://192.168.1.20:8180", "https://guest.example"),
            EndpointSettings.validate(" HTTP://192.168.1.20:8180/ ", "https://GUEST.example/"),
        )
        assertNull(EndpointSettings.validate("ftp://api.example", "https://guest.example"))
        assertNull(EndpointSettings.validate("https://user:secret@api.example", "https://guest.example"))
        assertNull(EndpointSettings.validate("https://api.example?host_token=secret", "https://guest.example"))
        assertNull(EndpointSettings.validate("https://api.example", "https://guest.example/path"))
        assertNull(EndpointSettings.validate("https://api.example", "https://guest.example/#fragment"))
    }

    @Test
    fun cleartext_detection_covers_either_configured_endpoint() {
        assertTrue(EndpointSettings.validate("http://lan:8180", "https://guest.example")!!.usesHttp)
        assertTrue(EndpointSettings.validate("https://api.example", "http://lan:8180")!!.usesHttp)
        assertFalse(EndpointSettings.validate("https://api.example", "https://guest.example")!!.usesHttp)
    }

    private fun putPreference(
        editor: SharedPreferences.Editor,
        key: String,
        value: Any,
    ): SharedPreferences.Editor = when (value) {
        is String -> editor.putString(key, value)
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
        is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
        else -> error("Unsupported preference fixture type")
    }
}
