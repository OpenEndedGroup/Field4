package afield.stdlib.noir.audioai

import afield.userFacingThrowable
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FieldAudioErrorTest {
    @Test
    fun decodesStructuredHttpError() {
        val exception = decodeFieldAudioError(
            422,
            """{"error":{"code":"INVALID_TIME_RANGE","message":"range exceeds asset duration","retryable":false,"details":{"asset_duration_seconds":182.3}}}""",
            FieldAudioClient.json,
        )

        assertEquals("INVALID_TIME_RANGE: range exceeds asset duration", exception.message)
        assertEquals("INVALID_TIME_RANGE", exception.error?.code)
        assertFalse(exception.error?.retryable ?: true)
        assertEquals(422, exception.statusCode)
    }

    @Test
    fun preservesUnstructuredHttpError() {
        val exception = decodeFieldAudioError(500, "plain worker failure", FieldAudioClient.json)
        assertEquals("Field Audio HTTP 500: plain worker failure", exception.message)
        assertEquals(null, exception.error)
    }

    @Test
    fun editorPresentationUnwrapsFutureWithoutLosingTypedError() {
        val fieldError = decodeFieldAudioError(
            422,
            """{"error":{"code":"INVALID_TIME_RANGE","message":"bad range"}}""",
            FieldAudioClient.json,
        )
        val visible = userFacingThrowable(ExecutionException(fieldError))

        assertIs<FieldAudioException>(visible)
        assertEquals("INVALID_TIME_RANGE: bad range", visible.message)
    }
}
