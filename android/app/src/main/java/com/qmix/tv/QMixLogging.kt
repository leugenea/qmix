package com.qmix.tv

import android.util.Log

const val QMIX_LOG_TAG = "QMix"

internal fun androidRuntimeLogLevel(): QMixLogLevel? =
    QMixLogLevel.DEBUG.takeIf { Log.isLoggable(QMIX_LOG_TAG, Log.DEBUG) }

enum class QMixLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR;

    companion object {
        fun parse(value: String): QMixLogLevel = entries.first { it.name == value }
    }
}

enum class QMixLogComponent(val label: String) {
    APP_HOST_SESSION("app/host-session"),
    ROOM_API_CREATION("room-api/creation"),
    ROOM_SYNC_SSE_RECONNECT("room-sync/sse/reconnect"),
    PLAYBACK_LIFECYCLE("playback/lifecycle"),
}

enum class QMixLogOperation(val label: String) {
    SESSION_STATE("session-state"),
    CREATE_ROOM("create-room"),
    OBSERVER_NOTIFICATION("observer-notification"),
    SSE_CONNECTION("sse-connection"),
    RECONNECT("reconnect"),
    PLAYBACK_FAILURE("playback-failure"),
}

enum class QMixLogCause(val label: String) {
    NETWORK("network"),
    HTTP_STATUS("http-status"),
    UNKNOWN("unknown"),
    CALLBACK_FAILURE("callback-failure"),
}

data class QMixLogRecord(
    val level: QMixLogLevel,
    val component: QMixLogComponent,
    val operation: QMixLogOperation,
    val cause: QMixLogCause? = null,
)

fun interface QMixLogSink {
    fun emit(record: QMixLogRecord)
}

class AndroidLogSink : QMixLogSink {
    override fun emit(record: QMixLogRecord) {
        val message = buildString {
            append("component=")
            append(record.component.label)
            append(" operation=")
            append(record.operation.label)
            record.cause?.let {
                append(" cause=")
                append(it.label)
            }
        }
        when (record.level) {
            QMixLogLevel.DEBUG -> Log.d(QMIX_LOG_TAG, message)
            QMixLogLevel.INFO -> Log.i(QMIX_LOG_TAG, message)
            QMixLogLevel.WARN -> Log.w(QMIX_LOG_TAG, message)
            QMixLogLevel.ERROR -> Log.e(QMIX_LOG_TAG, message)
        }
    }
}

class QMixLogger(
    private val sink: QMixLogSink,
    private val defaultLevel: QMixLogLevel,
    private val runtimeLevel: () -> QMixLogLevel?,
) {
    fun component(component: QMixLogComponent): QMixComponentLogger =
        QMixComponentLogger(component) { record ->
            val minimum = runtimeLevel() ?: defaultLevel
            if (record.level.ordinal >= minimum.ordinal) sink.emit(record)
        }
}

object QMixLogging {
    val process: QMixLogger by lazy {
        QMixLogger(
            sink = AndroidLogSink(),
            defaultLevel = QMixLogLevel.parse(BuildConfig.LOG_LEVEL),
            runtimeLevel = ::androidRuntimeLogLevel,
        )
    }
}

class QMixComponentLogger internal constructor(
    private val component: QMixLogComponent,
    private val emit: (QMixLogRecord) -> Unit,
) {
    companion object {
        fun noOp(component: QMixLogComponent): QMixComponentLogger = QMixComponentLogger(component) {}
    }

    fun debug(operation: QMixLogOperation, cause: QMixLogCause? = null) = log(QMixLogLevel.DEBUG, operation, cause)
    fun info(operation: QMixLogOperation, cause: QMixLogCause? = null) = log(QMixLogLevel.INFO, operation, cause)
    fun warn(operation: QMixLogOperation, cause: QMixLogCause? = null) = log(QMixLogLevel.WARN, operation, cause)
    fun error(operation: QMixLogOperation, cause: QMixLogCause? = null) = log(QMixLogLevel.ERROR, operation, cause)

    private fun log(level: QMixLogLevel, operation: QMixLogOperation, cause: QMixLogCause?) {
        emit(QMixLogRecord(level, component, operation, cause))
    }
}
