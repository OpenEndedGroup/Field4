package afield.examples

import afield.AaCompletionClient
import afield.Box
import afield.Completion
import afield.CompletionRequest
import afield.CompletionResult
import afield.graphics.RemoteArKotlinJs
import afield.graphics.VisionMetalKotlinJs
import afield.remotejs.KotlinJsExecution
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * KotlinJS autocomplete: reuse the K2 Analysis-API completion, scoped to the Kotlin
 * stdlib only (AaCompletionClient.kotlinStdlibRoots). What this asserts (the honest, measured
 * behaviour):
 *
 *   1. A box's OWN declared types complete cleanly (`p.` on a snippet-local data class → x/y/len/…).
 *      This is the high-value case — graphics boxes with custom vector/particle types.
 *   2. The scope is genuinely stdlib-only: an afield symbol (`FLine`) does NOT complete.
 *
 * The profile assertions also prove that file-scope synthetic declarations participate in analysis:
 * nested namespace classifiers, constructed external types, their members, and callback parameter
 * types all complete without adding Field's JVM classes to the KotlinJS classpath.
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

        // --- Stage 2: Kotlin extension functions surface on prefixed dot-completion ---
        // (bare `xs.` shows members; typing a letter enumerates stdlib extensions via the index.)
        val snippetC = "fun main() { val xs = listOf(1,2,3)\n xs.m\n}"
        val candsC = completeWithRetry(snippetC, snippetC.indexOf("xs.m") + 4, roots) ?: emptyList()
        require(candsC.any { it.startsWith("map") }) {
            "expected stdlib extension `map` on List after 'xs.m', got: ${candsC.take(20)}"
        }
        val snippetD = "fun main() { val s = \"hi\"\n s.up\n}"
        val candsD = completeWithRetry(snippetD, snippetD.indexOf("s.up") + 4, roots) ?: emptyList()
        require(candsD.any { it.startsWith("uppercase") }) {
            "expected stdlib extension `uppercase` on String after 's.up', got: ${candsD.take(20)}"
        }
        println("stdlib extensions surface: List 'xs.m' → ${candsC.filter { it.startsWith("map") }.take(4)}; " +
            "String 's.up' → ${candsD.filter { it.startsWith("upper") }}")

        // --- Stage 2b: JVM-only stdlib members are filtered in kotlinjs mode ONLY ---
        // A kotlinjs box compiles Kotlin→JS and runs in a head with no JVM, so JDK-augmented stdlib
        // members (stream/parallelStream/spliterator/toArray on collections; chars/codePoints/getChars
        // on String) must NOT be offered — they don't exist in the Kotlin/JS stdlib. But in normal
        // Kotlin completion (jsTarget=false) they must still be present. Both directions are asserted.
        val listDenied = setOf("stream", "parallelStream", "spliterator", "toArray")
        val stringDenied = setOf("chars", "codePoints", "getChars")

        val listSnippet = "fun main() { val xs = listOf(1,2,3)\n xs.\n}"
        val listCursor = listSnippet.indexOf("xs.\n") + 3
        val listJs = completeWithRetry(listSnippet, listCursor, roots, jsTarget = true) ?: emptyList()
        val listJvm = completeWithRetry(listSnippet, listCursor, roots, jsTarget = false) ?: emptyList()
        // (a) kotlinjs mode: denied members gone, but legit stdlib members/extensions survive.
        require(listJs.none { it.trimEnd('(') in listDenied }) {
            "kotlinjs List completion still leaks JVM-only members: ${listJs.filter { it.trimEnd('(') in listDenied }}"
        }
        require(listJs.any { it == "size" } && listJs.any { it.startsWith("iterator") }) {
            "kotlinjs List completion over-filtered legit members (size/iterator missing): ${listJs.take(20)}"
        }
        // (b) normal Kotlin mode: the same members are STILL present (filter is kotlinjs-scoped).
        require(listJvm.any { it.trimEnd('(') == "stream" } && listJvm.any { it.trimEnd('(') == "spliterator" }) {
            "normal-mode List completion should still offer JVM members stream/spliterator, got: ${listJvm.take(30)}"
        }
        println("List 'xs.'  kotlinjs → JVM-only gone ${listDenied.filter { d -> listJs.none { it.trimEnd('(') == d } }}; " +
            "normal → still present ${listDenied.filter { d -> listJvm.any { it.trimEnd('(') == d } }}")

        val strSnippet = "fun main() { val s = \"hi\"\n s.\n}"
        val strCursor = strSnippet.indexOf("s.\n") + 2
        val strJs = completeWithRetry(strSnippet, strCursor, roots, jsTarget = true) ?: emptyList()
        val strJvm = completeWithRetry(strSnippet, strCursor, roots, jsTarget = false) ?: emptyList()
        require(strJs.none { it.trimEnd('(') in stringDenied }) {
            "kotlinjs String completion still leaks JVM-only members: ${strJs.filter { it.trimEnd('(') in stringDenied }}"
        }
        require(strJs.any { it == "length" }) {
            "kotlinjs String completion over-filtered legit members (length missing): ${strJs.take(20)}"
        }
        require(strJvm.any { it.trimEnd('(') == "chars" }) {
            "normal-mode String completion should still offer JVM member chars, got: ${strJvm.take(30)}"
        }
        println("String 's.'  kotlinjs → JVM-only gone ${stringDenied.filter { d -> strJs.none { it.trimEnd('(') == d } }}; " +
            "normal → still present ${stringDenied.filter { d -> strJvm.any { it.trimEnd('(') == d } }}")

        // --- Head declarations: the selected profile contributes a JVM-analysis-safe file model. ---
        val visionNames = "fun main() {\n  onFr\n}"
        val visionNameCands = completeWithRetry(
            visionNames,
            visionNames.indexOf("onFr") + 4,
            roots,
            VisionMetalKotlinJs.completionDeclarations,
        ) ?: emptyList()
        require(visionNameCands.any { it.startsWith("onFrame") }) {
            "expected the visionmetal onFrame façade, got: ${visionNameCands.take(20)}"
        }

        val visionFrame = "fun main() {\n  onFrame { frame -> frame.pred\n  }\n}"
        val visionFrameCands = completeWithRetry(
            visionFrame,
            visionFrame.indexOf("frame.pred") + "frame.pred".length,
            roots,
            VisionMetalKotlinJs.completionDeclarations,
        ) ?: emptyList()
        require(visionFrameCands.any { it == "predictedDisplayTime" }) {
            "expected shared HeadFrame members in callback completion, got: ${visionFrameCands.take(20)}"
        }

        val remoteNamespace = "fun main() {\n  THREE.Ve\n}"
        val remoteNamespaceCands = completeWithRetry(
            remoteNamespace,
            remoteNamespace.indexOf("THREE.Ve") + "THREE.Ve".length,
            roots,
            RemoteArKotlinJs.completionDeclarations,
        ) ?: emptyList()
        require(remoteNamespaceCands.contains("Vector2") && remoteNamespaceCands.contains("Vector3")) {
            "expected nested THREE vector classifiers, got: ${remoteNamespaceCands.take(20)}"
        }

        val remoteVector = "fun main() {\n  val v = THREE.Vector3()\n  v.cl\n}"
        val remoteVectorCands = completeWithRetry(
            remoteVector,
            remoteVector.indexOf("v.cl") + 4,
            roots,
            RemoteArKotlinJs.completionDeclarations,
        ) ?: emptyList()
        require(remoteVectorCands.any { it.startsWith("clone") }) {
            "expected THREE.Vector3 members, got: ${remoteVectorCands.take(20)}"
        }

        val remoteLine = "fun main() {\n  FLine().cu\n}"
        val remoteLineCands = completeWithRetry(
            remoteLine,
            remoteLine.indexOf("FLine().cu") + "FLine().cu".length,
            roots,
            RemoteArKotlinJs.completionDeclarations,
        ) ?: emptyList()
        require(remoteLineCands.any { it.startsWith("cubicTo") }) {
            "expected FLine members from remoteAR declarations, got: ${remoteLineCands.take(20)}"
        }

        // Exact BoxExecution integration: marker selection, profile declaration lookup, async AA call,
        // and Completion payload serialization all participate.
        val selectedCode = "// {{ remoteAR }}\nval selected = THREE.Vector3()\nselected.cl"
        val selectedCompletion = completeThroughExecution(selectedCode, selectedCode.length)
        require(selectedCompletion.metadata.any { it.text.startsWith("clone") }) {
            "KotlinJsExecution did not apply the remoteAR profile: ${selectedCompletion.metadata.take(20)}"
        }
        println("head declarations surface: visionmetal frame + remoteAR THREE/FLine ✓")

        println("KotlinJsCompletionSpikeTest ok")
    }

    /** Returns candidate texts, or null if the sidecar never answered (unreachable → SKIP). */
    private fun completeWithRetry(
        snippet: String,
        cursor: Int,
        roots: List<String>,
        declarations: String = "",
        jsTarget: Boolean = true,
    ): List<String>? {
        var unreachable = true
        repeat(8) {
            val outcome = AaCompletionClient.complete(
                snippet = snippet, cursor = cursor, preamble = "", implicitReceivers = emptyList(),
                declarations = declarations,
                classpathRootsOverride = roots,
                jsTarget = jsTarget,
            )
            if (!outcome.detail.startsWith("no-sidecar") && !outcome.detail.startsWith("error") &&
                !outcome.detail.startsWith("http")
            ) unreachable = false
            (outcome.result as? CompletionResult.Success)?.let { return it.metadata.map { m -> m.text } }
            Thread.sleep(400)
        }
        return if (unreachable) null else emptyList()
    }

    private fun completeThroughExecution(code: String, cursor: Int): Completion {
        val box = Box("kotlinjs-profile-completion").also {
            it.setPropertyRaw("code", code, doResync = false)
        }
        val latch = CountDownLatch(1)
        var result: Completion? = null
        KotlinJsExecution().complete(
            box,
            CompletionRequest(
                uid = box.id,
                line = 1,
                allCode = code,
                column = cursor,
                position = cursor,
            ),
        ) { message ->
            result = Json.decodeFromJsonElement<Completion>(message.payload ?: error("completion reply had no payload"))
            latch.countDown()
        }
        require(latch.await(20, TimeUnit.SECONDS)) { "KotlinJsExecution completion timed out" }
        return result ?: error("KotlinJsExecution returned no completion")
    }
}
