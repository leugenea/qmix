package com.qmix.tv

import androidx.media3.common.PlaybackException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackErrorMappingTest {
    @Test
    fun parsing_and_decoder_errors_are_decode_failures() {
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        ).forEach { errorCode ->
            val error = IllegalStateException("player error")

            val failure = toBackendFailure(errorCode, "cannot decode", error)

            assertEquals(BackendFailure.Decode("cannot decode", error), failure)
        }
    }

    @Test
    fun documented_io_errors_are_network_failures() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        ).forEach { errorCode ->
            val error = IllegalStateException("player error")

            val failure = toBackendFailure(errorCode, "cannot read", error)

            assertEquals(BackendFailure.Network("cannot read", error), failure)
        }
    }

    @Test
    fun out_of_range_error_preserves_range_semantics_without_http_code() {
        val error = IllegalStateException("player error")

        val failure = toBackendFailure(
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            "position rejected",
            error,
        )

        assertEquals(BackendFailure.Range("position rejected", cause = error), failure)
        assertNull((failure as BackendFailure.Range).responseCode)
    }

    @Test
    fun uncategorized_io_cause_is_still_a_network_failure() {
        val error = IllegalStateException("wrapper", IOException("socket reset"))

        val failure = toBackendFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, "stream failed", error)

        assertEquals(BackendFailure.Network("stream failed", error), failure)
        assertSame(error, failure.cause)
    }

    @Test
    fun unknown_error_uses_stable_fallback_message_when_media3_has_none() {
        val error = IllegalStateException("player error")

        val failure = toBackendFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, null, error)

        assertEquals(
            BackendFailure.Unknown("Media3 playback error ${PlaybackException.ERROR_CODE_UNSPECIFIED}", error),
            failure,
        )
        assertSame(error, failure.cause)
    }
}