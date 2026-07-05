package afield.examples

import afield.ai.core.OpenAiIdeCodexBridge

/*
FIELD_TEST
id: examples.ai4.rate-limit-retry-delay
runner: jvm-main
entrypoint: afield.examples.Ai4RateLimitRetrySmokeTest
tags: [smoke, ai4, rate-limit]
timeoutSec: 10
expect:
  exitCode: 0
  stdoutContains:
    - "Ai4RateLimitRetrySmokeTest ok"
*/
object Ai4RateLimitRetrySmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val pasted = """
            429: Rate limit reached for gpt-5.5 in organization org on tokens per min (TPM):
            Limit 500000, Used 449840, Requested 55534. Please try again in 644ms.
        """.trimIndent()
        require(OpenAiIdeCodexBridge.retryDelayMsForRateLimitMessage(pasted) == 966L) {
            "Expected 644ms * 1.5 to round up to 966ms"
        }
        require(OpenAiIdeCodexBridge.retryDelayMsForRateLimitMessage("Rate limit reached. Please try again in 1.2s.") == 1800L)
        require(OpenAiIdeCodexBridge.retryDelayMsForRateLimitMessage("429 retry-after: 2") == 3000L)
        require(OpenAiIdeCodexBridge.retryDelayMsForRateLimitMessage("ordinary tool failure") == null)

        println("Ai4RateLimitRetrySmokeTest ok")
    }
}
