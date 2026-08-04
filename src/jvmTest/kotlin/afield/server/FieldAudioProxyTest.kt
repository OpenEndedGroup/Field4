package afield.server

import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldAudioProxyTest {
    @Test
    fun allowsOnlyBrowserCandidateMediaAndJudgements() {
        assertTrue(
            isAllowedFieldAudioProxyRequest(
                HttpMethod.Get,
                "/v1/representations/rep_abc-123/content",
            )
        )
        assertTrue(
            isAllowedFieldAudioProxyRequest(
                HttpMethod.Get,
                "/v1/representations/rep_abc-123/waveform",
            )
        )
        assertTrue(
            isAllowedFieldAudioProxyRequest(
                HttpMethod.Put,
                "/v1/candidate-sets/cset_abc/candidates/7",
            )
        )
        assertTrue(isAllowedFieldAudioProxyRequest(HttpMethod.Get, "/monitor"))
        assertTrue(isAllowedFieldAudioProxyRequest(HttpMethod.Get, "/v1/jobs"))
        assertTrue(
            isAllowedFieldAudioProxyRequest(
                HttpMethod.Post,
                "/v1/jobs/job_abc-123/cancel",
            )
        )

        assertFalse(isAllowedFieldAudioProxyRequest(HttpMethod.Post, "/v1/operations"))
        assertFalse(isAllowedFieldAudioProxyRequest(HttpMethod.Get, "/v1/jobs/job_abc-123"))
        assertFalse(isAllowedFieldAudioProxyRequest(HttpMethod.Get, "/v1/assets/sha256:secret"))
        assertFalse(
            isAllowedFieldAudioProxyRequest(
                HttpMethod.Get,
                "/v1/representations/../../metadata.sqlite/content",
            )
        )
        assertFalse(
            isAllowedFieldAudioProxyRequest(
                HttpMethod.Get,
                "/v1/representations/../../metadata.sqlite/waveform",
            )
        )
    }
}
