package afield.ai

import afield.AiChatModelIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiChatModelIdentityTest {
    @Test fun pendingRequestIsNotPresentedAsAConfirmedResponse() {
        val requested = AiChatModelIdentity("openai", "gpt-6-astra")
        assertEquals("gpt-6-astra · OpenAI (requested)", requested.label())
        val returned = requested.copy(responseModel = "gpt-6-astra-2026-09-01")
        assertEquals("gpt-6-astra-2026-09-01 · OpenAI", returned.label())
        assertTrue(returned.description().contains("Requested: gpt-6-astra."))
    }

    @Test fun providerAndModelStayWithTheirOwnTranscriptEntry() {
        for (identity in listOf(
            AiChatModelIdentity("openai", "gpt-6-astra", "gpt-6-astra"),
            AiChatModelIdentity("openrouter", "vendor/alias", "vendor/returned-model"),
            AiChatModelIdentity("anthropic", "claude-opus-4-8", "claude-opus-4-8")
        )) {
            val (decoded, text) = AiChatModelIdentity.splitFromContent(identity.marker() + "A reply.\nSecond line.")
            assertEquals(identity, decoded)
            assertEquals("A reply.\nSecond line.", text)
        }
    }

    @Test fun olderOrMalformedTranscriptsDoNotAcquireAnInventedIdentity() {
        for (text in listOf("An older reply.", "<<model:not JSON>>\nText")) {
            val (model, body) = AiChatModelIdentity.splitFromContent(text)
            assertNull(model)
            assertEquals(text, body)
        }
    }
}
