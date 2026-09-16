package com.qmix.tv

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])

class AndroidLoggingTest {
    @Test
    fun build_config_default_level_is_warn() {
        assertEquals(QMixLogLevel.WARN, QMixLogLevel.parse(BuildConfig.LOG_LEVEL))
    }

    @Test
    fun logcat_tag_property_can_raise_runtime_verbosity() {
        ShadowLog.setLoggable(QMIX_LOG_TAG, Log.DEBUG)

        assertEquals(QMixLogLevel.DEBUG, androidRuntimeLogLevel())
    }

    @Test
    fun default_warn_filters_routine_records() {
        val sink = RecordingLogSink()
        val logger = QMixLogger(
            sink = sink,
            defaultLevel = QMixLogLevel.WARN,
            runtimeLevel = { null },
        ).component(QMixLogComponent.APP_HOST_SESSION)

        logger.debug(QMixLogOperation.SESSION_STATE)
        logger.info(QMixLogOperation.SESSION_STATE)
        logger.warn(QMixLogOperation.CREATE_ROOM, QMixLogCause.NETWORK)
        logger.error(QMixLogOperation.OBSERVER_NOTIFICATION, QMixLogCause.CALLBACK_FAILURE)

        assertEquals(
            listOf(QMixLogLevel.WARN, QMixLogLevel.ERROR),
            sink.records.map(QMixLogRecord::level),
        )
    }

    @Test
    fun application_wires_host_session_to_process_logcat_logger() {
        ShadowLog.clear()
        val application = ApplicationProvider.getApplicationContext<QMixApplication>()

        application.hostSession.observe { throw IllegalStateException("token=do-not-log") }

        val entry = ShadowLog.getLogsForTag(QMIX_LOG_TAG).single()
        assertEquals(Log.ERROR, entry.type)
        assertEquals(
            "component=app/host-session operation=observer-notification cause=callback-failure",
            entry.msg,
        )
        assertEquals(false, entry.msg.contains("do-not-log"))
    }

    @Test
    fun android_sink_uses_stable_logcat_tag_and_component_attribution() {
        ShadowLog.clear()
        AndroidLogSink().emit(
            QMixLogRecord(
                QMixLogLevel.ERROR,
                QMixLogComponent.ROOM_API_CREATION,
                QMixLogOperation.CREATE_ROOM,
                QMixLogCause.NETWORK,
            ),
        )

        val entry = ShadowLog.getLogsForTag(QMIX_LOG_TAG).single()
        assertEquals(Log.ERROR, entry.type)
        assertEquals(
            "component=room-api/creation operation=create-room cause=network",
            entry.msg,
        )
        assertEquals(null, entry.throwable)
    }
}

internal class RecordingLogSink : QMixLogSink {
    val records = mutableListOf<QMixLogRecord>()

    override fun emit(record: QMixLogRecord) {
        records += record
    }
}
