package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackErrorContractTest {
    @Test
    fun playback_error_defaults_optional_transport_details_to_absent() {
        val error = PlaybackError(PlaybackErrorKind.UNKNOWN, "player stopped unexpectedly")

        assertEquals(PlaybackErrorKind.UNKNOWN, error.kind)
        assertEquals("player stopped unexpectedly", error.message)
        assertNull(error.httpResponseCode)
        assertNull(error.cause)
    }

    @Test
    fun unknown_backend_failure_retains_its_diagnostic_cause() {
        val cause = IllegalArgumentException("unsupported state")

        val failure = BackendFailure.Unknown("unclassified Media3 error", cause)

        assertEquals("unclassified Media3 error", failure.message)
        assertSame(cause, failure.cause)
    }

    @Test
    fun unknown_backend_failure_defaults_cause_to_absent() {
        val failure = BackendFailure.Unknown("unclassified Media3 error")

        assertEquals("unclassified Media3 error", failure.message)
        assertNull(failure.cause)
    }
}