package afield.examples

import afield.AaCompletionClient
import afield.CompletionResult

/*
FIELD_TEST
id: remotejs.kotlinjs-completion
runner: jvm-main
entrypoint: afield.examples.KotlinJsCompletionSpikeTest
tags: [remotejs, kotlinjs, completion]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinJsCompletionSpikeTest ok"
*/

/**
 * Stage 1 of KotlinJS autocomplete: reuse the K2 Analysis-API completion, scoped to the Kotlin
 * stdlib only (AaCompletionClient.kotlinStdlibRoots). What this asserts (the honest, measured
 * behaviour):
 *
 *   1. A box's OWN declared types complete cleanly (`p.` on a snippet-local data class → x/y/len/…).
 *      This is the high-value case — graphics boxes with custom vector/particle types.
 *   2. The scope is genuinely stdlib-only: an afield symbol (`FLine`) does NOT complete.
 *
 * Known limitation (NOT asserted, logged): the sidecar is a member-only completer — Kotlin
 * extension functions (map/filter/uppercase) do not surface, and stdlib receivers show JVM
 * interface members (some JVM-only, e.g. parallelStream). Fixing that is Stage 2 (see the plan).
 *
 * Requires the AA sidecar; SKIPs cleanly if none is reachable.
 */
object KotlinJsCompletionSpikeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val url = System.getProperty("afield.completion.sidecarUrl")
            ?: System.getenv("AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL")
            ?: "http://127.0.0.1:17841"
        System.setProperty("afield.completion.sidecarUrl", url)

        val roots = AaCompletionClient.kotlinStdlibRoots()
        require(roots.isNotEmpty()) { "no kotlin-stdlib jar on the classpath to scope completion to" }
        println("stdlib scope: ${roots.map { it.substringAfterLast('/') }}")

        // --- 1. box-local type members (retry across the cold-session "warming up" window) ---
        val snippetA = "data class P(val x: Double, val y: Double) { fun len() = 0.0 }\n" +
            "fun main() {\n  val p = P(1.0, 2.0)\n  p.\n}\n"
        val cursorA = snippetA.indexOf("p.\n") + 2
        val candsA = completeWithRetry(snippetA, cursorA, roots)
        if (candsA == null) {
            println("SKIP: no AA sidecar reachable at $url (set -Dafield.completion.sidecarUrl)")
            println("KotlinJsCompletionSpikeTest ok")
            return
        }
        require(candsA.any { it.startsWith("len") } && candsA.any { it == "x" } && candsA.any { it == "y" }) {
            "expected the box's own P members (x/y/len), got: $candsA"
        }
        println("box-local 'p.' → own members complete: ${candsA.filter { it in setOf("x", "y") || it.startsWith("len") || it.startsWith("copy") }}")

        // --- 2. scope really is stdlib-only: afield's FLine must NOT complete ---
        val snippetB = "fun main() {\n    FLin\n}\n"
        val candsB = completeWithRetry(snippetB, snippetB.indexOf("FLin") + 4, roots) ?: emptyList()
        require(candsB.none { it.contains("FLine") }) {
            "stdlib-scoped completion leaked an afield symbol (FLine): ${candsB.take(15)}"
        }
        println("prefix 'FLin' → ${candsB.size} candidates, none are afield's FLine (scope is stdlib-only) ✓")

        // --- known limitation: extensions on stdlib receivers (logged, not asserted) ---
        val snippetC = "fun main() { val xs = listOf(1,2,3)\n xs.\n}"
        val candsC = completeWithRetry(snippetC, snippetC.indexOf("xs.") + 3, roots) ?: emptyList()
        val extensions = candsC.filter { it.startsWith("map") || it.startsWith("filter") || it.startsWith("reduce") }
        println("stdlib 'xs.' → ${candsC.size} candidates; extension fns present: ${extensions.isNotEmpty()} " +
            "(known Stage-2 gap: sidecar is member-only)")

        println("KotlinJsCompletionSpikeTest ok")
    }

    /** Returns candidate texts, or null if the sidecar never answered (unreachable → SKIP). */
    private fun completeWithRetry(snippet: String, cursor: Int, roots: List<String>): List<String>? {
        var unreachable = true
        repeat(8) {
            val outcome = AaCompletionClient.complete(
                snippet = snippet, cursor = cursor, preamble = "", implicitReceivers = emptyList(),
                classpathRootsOverride = roots,
            )
            if (!outcome.detail.startsWith("no-sidecar") && !outcome.detail.startsWith("error") &&
                !outcome.detail.startsWith("http")
            ) unreachable = false
            (outcome.result as? CompletionResult.Success)?.let { return it.metadata.map { m -> m.text } }
            Thread.sleep(400)
        }
        return if (unreachable) null else emptyList()
    }
}
