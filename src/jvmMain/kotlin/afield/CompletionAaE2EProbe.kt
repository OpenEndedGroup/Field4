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
  # Every check below prints ✓ or ✗, and until now NOTHING asserted on them: all five could fail
  # and the probe still passed, because "AA E2E DONE" prints unconditionally. That is how `d.fl`
  # shipped answering with stdlib junk. A ✗ is never printed on the no-sidecar SKIP path, so this
  # stays green where no sidecar is running.
  stdoutNotContains:
    - "✗"
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

        /** The mirror of [check]: [banned] must NOT be offered. Some bugs are wrong answers, not missing ones. */
        fun absent(desc: String, s: String, cursor: Int, banned: String) {
            total++
            val m = meta(runBlocking { runCatching { ki.complete(s, cursor) }.getOrNull() })
            val leak = m.firstOrNull { it.text.trimEnd('(', ' ', '=') == banned }
            if (leak == null) { pass++; println("  ✓ [no-junk] $desc → $banned not offered (of ${m.size})") }
            else println("  ✗ [no-junk] $desc LEAKED ${leak.displayText} (of ${m.size})")
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

        // The box's OWN import. Every check above leans on the sidecar's hardcoded REPL default
        // imports, so nothing here (or in the differential corpus) ever exercised a box that writes
        // `import <some package>.*` for itself — and that entire path was broken: the import line
        // arrives inside the snippet, which the sidecar pastes into a synthetic wrapper function
        // where an import is a syntax error, so the receiver never resolved and every member and
        // extension of the box's own types went missing. `afield.morpho` is deliberately outside the
        // REPL intro, so the box's own import is what makes the type resolve at all, and
        // `MorphoCircuit.flines` mirrors the reported `SentenceDiagram.flines` through the same
        // stdlib-colliding `fl` prefix. Checked in, so unlike a src/personal package it runs anywhere.
        println("── the box's own imports ──")
        val own = "import afield.morpho.*\nval c: MorphoCircuit = TODO()\nc."
        check("own import → member at a prefix", own + "sign", own.length + 4, "signature", "AA+import")
        check("own import → extension at a prefix", own + "fl", own.length + 2, "flines", "AA+import")
        check("own import → extension at a bare dot", own, own.length, "flines", "AA+import")

        // The REPL's OWN imports (afield.Boxes.intro) — which the box never writes and a headless
        // sidecar cannot see. `afield.four.buffers` is in that intro and was missing from the sidecar's
        // hand-written copy of it, so this resolved under K1 and not under AA: the same defect one level
        // up. KotlinInterface2 now sends `introImports`, and this check is what holds it there.
        println("── the REPL's own imports (never written by the box) ──")
        val repl = "val s: Structure = TODO()\ns.proxy"
        check("intro import afield.four.buffers", repl, repl.length, "proxying", "AA+intro")

        // …and the seam that actually bit the human. An unresolved receiver must produce NOTHING, so
        // KI2 falls through to K1. When AA instead answered it with every prefix-matching extension in
        // its index (an error type is a subtype of everything, so they all "applied"), the non-empty
        // result told KI2 that AA had served the request and suppressed the fallback — the correct
        // answer was withheld, not merely diluted. Assert on the junk: the fallback's own answer for
        // an unresolvable receiver is empty too, so presence of the junk is the only observable.
        val bad = "val q: NoSuchTypeAnywhere = TODO()\nq.fl"
        absent("unresolved receiver offers no stdlib extension", bad, bad.length, "flatMap")

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
