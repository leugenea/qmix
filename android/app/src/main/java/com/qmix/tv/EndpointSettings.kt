package com.qmix.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class EndpointSettings(
    val backendUrl: String,
    val guestOrigin: String,
) {
    val usesHttp: Boolean
        get() = backendUrl.startsWith("http://") || guestOrigin.startsWith("http://")

    companion object {
        val EMPTY = EndpointSettings("", "")

        fun validate(backendUrl: String, guestOrigin: String): EndpointSettings? {
            val backend = parseEndpoint(backendUrl) ?: return null
            val guest = parseEndpoint(guestOrigin) ?: return null
            if (guest.encodedPath != "/") return null
            return EndpointSettings(backend.canonicalBase(), guest.canonicalBase())
        }

        private fun parseEndpoint(raw: String): HttpUrl? {
            val parsed = raw.trim().toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "http" && parsed.scheme != "https") return null
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
            if (parsed.query != null || parsed.fragment != null) return null
            return parsed
        }

        private fun HttpUrl.canonicalBase(): String = toString().removeSuffix("/")
    }
}

interface EndpointSettingsPersistence {
    fun load(): EndpointSettings
    fun save(settings: EndpointSettings)
    fun isHttpWarningAcknowledged(): Boolean
    fun acknowledgeHttpWarning()
}

internal class InitialEndpointSettingsPersistence(
    initial: EndpointSettings,
) : EndpointSettingsPersistence {
    private var settings = initial

    override fun load(): EndpointSettings = settings

    override fun save(settings: EndpointSettings) {
        this.settings = settings
    }

    override fun isHttpWarningAcknowledged(): Boolean = true

    override fun acknowledgeHttpWarning() = Unit
}

class EndpointSettingsStore private constructor(
    private val preferences: SharedPreferences,
    private val endpointSaveCommit: (SharedPreferences.Editor) -> Boolean,
    private val acknowledgementCommit: (SharedPreferences.Editor) -> Boolean,
) : EndpointSettingsPersistence {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        SharedPreferences.Editor::commit,
        SharedPreferences.Editor::commit,
    )

    internal constructor(
        context: Context,
        acknowledgementCommit: (SharedPreferences.Editor) -> Boolean,
    ) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        SharedPreferences.Editor::commit,
        acknowledgementCommit,
    )

    internal constructor(
        context: Context,
        endpointSaveCommit: (SharedPreferences.Editor) -> Boolean,
        acknowledgementCommit: (SharedPreferences.Editor) -> Boolean,
    ) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        endpointSaveCommit,
        acknowledgementCommit,
    )

    override fun load(): EndpointSettings = synchronized(preferences) {
        loadLocked()
    }

    @SuppressLint(
        "UseKtx",
        "ApplySharedPref",
    ) // Both commits must synchronously update process state and attempt disk persistence.
    override fun save(settings: EndpointSettings) {
        val validated = requireNotNull(EndpointSettings.validate(settings.backendUrl, settings.guestOrigin))
        synchronized(preferences) {
            val previous = loadLocked()
            val committed = endpointSaveCommit(
                preferences.edit()
                    .putString(KEY_BACKEND_URL, validated.backendUrl)
                    .putString(KEY_GUEST_ORIGIN, validated.guestOrigin),
            )
            if (!committed) {
                val rollback = preferences.edit()
                if (previous == EndpointSettings.EMPTY) {
                    rollback.remove(KEY_BACKEND_URL).remove(KEY_GUEST_ORIGIN)
                } else {
                    rollback
                        .putString(KEY_BACKEND_URL, previous.backendUrl)
                        .putString(KEY_GUEST_ORIGIN, previous.guestOrigin)
                }
                // The endpoint commit seam also exercises rollback failures: commit() updates the
                // live map synchronously even when it reports that disk persistence failed.
                endpointSaveCommit(rollback)
                error("Could not persist endpoint settings")
            }
        }
    }

    override fun isHttpWarningAcknowledged(): Boolean = synchronized(preferences) {
        isHttpWarningAcknowledgedLocked()
    }

    @SuppressLint(
        "UseKtx",
        "ApplySharedPref",
    ) // Both commits must synchronously update process state and attempt disk persistence.
    override fun acknowledgeHttpWarning() {
        synchronized(preferences) {
            // Another store may have durably acknowledged while this caller was waiting for the lock.
            if (isHttpWarningAcknowledgedLocked()) return
            val committed = acknowledgementCommit(
                preferences.edit().putBoolean(KEY_HTTP_WARNING_ACKNOWLEDGED, true),
            )
            if (!committed) {
                // commit() publishes to SharedPreferences' live map before disk completion. A second
                // commit restores fail-closed process state even when that rollback also reports a
                // disk failure; false was the value observed before this serialized attempt.
                preferences.edit().putBoolean(KEY_HTTP_WARNING_ACKNOWLEDGED, false).commit()
                error("Could not persist HTTP warning acknowledgement")
            }
        }
    }

    private fun loadLocked(): EndpointSettings {
        val values = preferences.all
        val backend = values[KEY_BACKEND_URL] as? String ?: return EndpointSettings.EMPTY
        val guest = values[KEY_GUEST_ORIGIN] as? String ?: return EndpointSettings.EMPTY
        return EndpointSettings.validate(backend, guest) ?: EndpointSettings.EMPTY
    }

    private fun isHttpWarningAcknowledgedLocked(): Boolean =
        preferences.all[KEY_HTTP_WARNING_ACKNOWLEDGED] as? Boolean ?: false

    companion object {
        const val PREFERENCES_NAME = "qmix_endpoint_settings"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_GUEST_ORIGIN = "guest_origin"
        private const val KEY_HTTP_WARNING_ACKNOWLEDGED = "http_warning_acknowledged"
    }
}
