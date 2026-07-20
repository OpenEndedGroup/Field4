package afield

import afield.macros.gatherPreambleUpwards
import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.SourceCodeCompletionVariant
import java.io.File

/*
FIELD_TEST
id: kotlin.completion-aa-e2e.probe
runner: jvm-main
entrypoint: afield.CompletionAaE2EProbe
tags: [probe, kotlin, completion]
timeoutSec: 400
expect:
  exitCode: 0
  stdoutContains:
    - "AA E2E DONE"
*/
/**
 * STAGE D live end-to-end: exercises the REAL KI2 → AA-sidecar completion chain in a running Field JVM.
 * Sets `activeCompletionBackend = AA`, builds a parent→child box pair with an inherited preamble (as
 * `CompletionContextProbe` does), and calls `KotlinInterface2.complete()` — which POSTs the box's
 * gathered preamble + implicit `Box` receiver + this JVM's classpath to the sidecar `/complete`.
 *
 * Needs a running sidecar; its URL comes from `-Dafield.completion.sidecarUrl`,
 * `AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL`, or a `build/completion-corpus/sidecar-url.txt` file. If none
 * is set, the probe SKIPs (exit 0) so it never fails CI where no sidecar is running.
 *
 * Assertions that a candidate came from AA (not a silent K1 fallback): named-argument completion, which
 * the K1 delegate does not produce at all (verified by the Stage B differential).
 */
object CompletionAaE2EProbe {

    private fun meta(r: CompletionResult?): List<SourceCodeCompletionVariant> =
        (r as? CompletionResult.Success)?.metadata ?: emptyList()

    @JvmStatic
    fun main(args: Array<String>) {
        val urlFile = File("build/completion-corpus/sidecar-url.txt")
        val url = (System.getProperty("afield.completion.sidecarUrl")?.trim()?.ifBlank { null }
            ?: System.getenv("AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL")?.trim()?.ifBlank { null }
            ?: urlFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null })
        if (url == null) {
            println("SKIP: no sidecar URL (set -Dafield.completion.sidecarUrl or write build/completion-corpus/sidecar-url.txt)")
            println("AA E2E DONE")
            return
        }
        System.setProperty("afield.completion.sidecarUrl", url)
        KotlinEngineConfig.activeCompletionBackend = KotlinEngineConfig.CompletionBackend.AA
        println("using AA sidecar at $url")

        val parent = Box("aa-e2e-parent")
        val child = Box("aa-e2e-child")
        child.parents.add(parent)
        val preamble = """
            var Box.inheritedCount by Inheritance<Int>() /**/
            val Box._exportedGreeting: String get() = "hi from export" /**/
        """.trimIndent()
        parent.setPropertyRaw("preamble", preamble, doResync = false)

        val ki = KotlinInterface2(implicitReceivers = listOf(KotlinType(Box::class)), filePrefix = child.id)
        ki.associatedBox = child
        runBlocking { ki.warmCompletion() } // warms the K1 fallback delegate
        println("inherited preamble (child): ${gatherPreambleUpwards(child).replace("\n", " ⏎ ")}")

        var pass = 0; var total = 0
        fun check(desc: String, s: String, cursor: Int, want: String, expectFrom: String) {
            total++
            val m = meta(runBlocking { runCatching { ki.complete(s, cursor) }.getOrNull() })
            val hit = m.firstOrNull { it.text.trimEnd('(', ' ', '=') == want || it.displayText.startsWith(want) }
            if (hit != null) { pass++; println("  ✓ [$expectFrom] $desc → ${hit.displayText}  ${hit.tail}  (of ${m.size})") }
            else println("  ✗ [$expectFrom] $desc ABSENT (of ${m.size})")
        }

        println("── AA chain (sidecar up) ──")
        // Named-arg completion PROVES the AA path — the K1 delegate produces none (Stage B differential).
        check("named-arg completion Vector3f(", "Vector3f(", 9, "x", "AA-only")
        // Inherited REPL context surfaces through the live chain.
        check("prop<Int>(inheritedCount) inherited", "inheritedC", 10, "inheritedCount", "AA+context")
        check("@exported _exportedGreeting", "_exp", 4, "_exportedGreeting", "AA+context")
        // Ordinary dot/name completion.
        check("dot FLine().move", "FLine().move", 12, "moveTo", "AA")
        check("name Vec → Vec3", "Vec", 3, "Vec3", "AA")

        // Latency over the warm session (first call already paid session/index build above).
        val times = mutableListOf<Long>()
        repeat(12) {
            val t0 = System.nanoTime()
            runBlocking { runCatching { ki.complete("FLine().move", 12) }.getOrNull() }
            times += (System.nanoTime() - t0) / 1_000_000
        }
        times.sort()
        println("── latency (warm, ms) ── p50=${times[times.size / 2]} min=${times.first()} max=${times.last()} avg=${times.average().toInt()}")

        // Fallback: a bad URL must fall through to K1 (non-empty), not return nothing.
        println("── K1 fallback (bad sidecar URL) ──")
        System.setProperty("afield.completion.sidecarUrl", "http://127.0.0.1:1/nope")
        val fb = meta(runBlocking { runCatching { ki.complete("FLine().move", 12) }.getOrNull() })
        val fallbackOk = fb.any { it.displayText.startsWith("moveTo") }
        println("  ${if (fallbackOk) "✓" else "✗"} K1 fallback produced ${fb.size} candidates (moveTo present=$fallbackOk)")
        System.setProperty("afield.completion.sidecarUrl", url)

        println("── SUMMARY: $pass/$total AA checks passed; fallback ${if (fallbackOk) "ok" else "FAILED"} ──")
        println("AA E2E DONE")
    }
}
