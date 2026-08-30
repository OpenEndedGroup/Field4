package afield

import afield.four.modules.Introspected
import afield.four.modules.PrimitiveType
import afield.four.modules.writeSuperMap
import afield.macros.gatherPreambleUpwards
import arrow.core.left
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
 * Every check asserts the BACKEND as well as the candidate, via [KotlinInterface2.lastCompletionBackend]:
 * a K1 fallback answer is a failure here however good the candidate list is. Candidate presence alone is
 * not the proof it looks like — the fallback's name-position list runs to thousands of names, and used to
 * satisfy the first "AA-only" check outright while the sidecar was still building its session.
 */
object CompletionAaE2EProbe {

    /**
     * How long to wait for the sidecar's Analysis-API session before calling it dead. A cold sidecar
     * indexes this JVM's whole classpath, so this is minutes-scale on the first request of its life and
     * near-instant once another client (a running Field, an earlier probe) has already paid for it. Kept
     * well inside the FIELD_TEST `timeoutSec` so a hung sidecar fails as a named ✗ rather than as a
     * runner timeout with no explanation.
     */
    private const val SESSION_WAIT_MS = 180_000L

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

        var pass = 0; var total = 0
        /**
         * Block until [ki] can actually be served by the AA sidecar, and assert it. Counted as a check so
         * an unreachable or still-building sidecar is named once, here, instead of being inferred from
         * twenty downstream ABSENTs.
         */
        fun warmed(label: String, ki: KotlinInterface2) {
            total++
            val t0 = System.nanoTime()
            val outcome = runBlocking { ki.warmCompletionBackend(SESSION_WAIT_MS) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (outcome.startsWith("served")) {
                pass++; println("  ✓ [AA-session] sidecar serves the $label box's shape in ${ms}ms — $outcome")
            } else {
                println("  ✗ [AA-session] sidecar never served the $label box's shape (${ms}ms) — $outcome")
            }
        }

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
        // …and then wait for the SIDECAR's own Analysis-API session, which it builds in the background.
        // Until it exists the sidecar answers success-with-no-candidates ("session building"), which
        // `complete` correctly treats as a fall-through to K1 — right for a keystroke, and fatal for a
        // probe whose whole subject is the AA chain. Without this wait the first checks measured whoever
        // happened to warm the sidecar first: against a freshly started one (2026-08-29) the very first
        // check passed off the fallback's 3996-name list at `backend=K1 (AA→K1: empty: session building)`.
        warmed("child", ki)
        println("inherited preamble (child): ${gatherPreambleUpwards(child).replace("\n", " ⏎ ")}")

        // Which engine `check`/`absent` ask. Every check below the graphics-preamble section needs a box
        // whose inherited preamble is a *different* one, and a preamble is fixed per box chain.
        var active = ki
        // A candidate's PRESENCE does not say which backend produced it, and every `expectFrom` tag below
        // claims AA. The K1 fallback answers a name position with thousands of names, so a check like the
        // "AA-only" named-argument one can be satisfied outright by the fallback and still print ✓ — which
        // is exactly what happened on 2026-08-29 (`backend=K1 (AA→K1: empty: session building)`, 3996
        // candidates, ✓). So the backend is asserted, not merely logged: a K1 answer is a ✗ even when the
        // wanted candidate is present, because such a check proves nothing about the chain it names.
        fun check(desc: String, s: String, cursor: Int, want: String, expectFrom: String) {
            total++
            val m = meta(runBlocking { runCatching { active.complete(s, cursor) }.getOrNull() })
            val backend = active.lastCompletionBackend
            val hit = m.firstOrNull { it.text.trimEnd('(', ' ', '=') == want || it.displayText.startsWith(want) }
            when {
                hit == null -> println("  ✗ [$expectFrom] $desc ABSENT (of ${m.size}, backend=$backend)")
                backend != KotlinEngineConfig.CompletionBackend.AA ->
                    println("  ✗ [$expectFrom] $desc → ${hit.displayText} but backend=$backend " +
                        "(${active.lastCompletionDetail}) — the fallback answered, so this proves nothing about AA")
                else -> { pass++; println("  ✓ [$expectFrom] $desc → ${hit.displayText}  ${hit.tail}  (of ${m.size})") }
            }
        }

        /** The mirror of [check]: [banned] must NOT be offered. Some bugs are wrong answers, not missing ones. */
        fun absent(desc: String, s: String, cursor: Int, banned: String) {
            total++
            val m = meta(runBlocking { runCatching { active.complete(s, cursor) }.getOrNull() })
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

        // The type EXPECTED at the caret. `d.scheme = ‸` (an enum-typed property) reported this: the
        // answer is three entry names, and completion offered several hundred in-scope names instead,
        // because nothing on either backend ever asked what type belonged there. AA can be asked
        // (`KaExpressionTypeProvider.expectedType`), which K1 cannot — so, like named arguments, a hit
        // here also proves the AA path served the request. `FabricateMaterial.alphaMode` mirrors the
        // reported `SentenceDiagram.scheme`: a `var` of a 3-entry enum, in a package outside the REPL
        // intro, so the box's own import is what makes the type resolve at all.
        println("── the type expected at the caret ──")
        val assign = "import afield.four.fabricated.*\nval m: FabricateMaterial = TODO()\nm.alphaMode = "
        // Asserted on the INSERTION text, not the display name: an entry is shown bare but has to be
        // inserted qualified or the box it lands in does not compile.
        check("assignment RHS → enum entry", assign, assign.length, "FabricateAlphaMode.BLEND", "AA-only")
        check("assignment RHS at a prefix", assign + "BL", assign.length + 2, "FabricateAlphaMode.BLEND", "AA-only")

        val named = "import afield.four.fabricated.*\nFabricateMaterial(alphaMode = "
        check("named-arg value → enum entry", named, named.length, "FabricateAlphaMode.BLEND", "AA-only")
        // The other half of a keyword argument: in the VALUE slot, another parameter's name is not
        // merely unranked, it is a syntax error. They were offered there until this work.
        absent("named-arg value slot offers no sibling param", named, named.length, "alphaCutoff")
        val boolArg = "import afield.four.fabricated.*\nFabricateMaterial(doubleSided = "
        check("named-arg value → Boolean literal", boolArg, boolArg.length, "true", "AA-only")
        // Positional arguments resolve through the same expected type, and the parameter NAMES must
        // still be there — the collapse that ranking applies keeps them (see execution.kt).
        val positional = "Vector3f("
        check("positional arg still offers param names", positional, positional.length, "x", "AA-only")

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

        // The same seam through a lambda's `it`, which is the shape people actually hit: binding `it`
        // needs the enclosing call to type-check, so ANY unresolved link in the chain leaves it unbound —
        // and `it` is also a root package on this classpath (fastutil's `it.unimi.dsi`), so AA's package
        // path answered a bare `it.` with the single row `unimi`. One row is a non-empty result, which
        // suppressed the K1 fallback that — holding the REPL's accumulated state — knew the receiver and
        // had its members ready. Typing one character emptied the package listing and let the fallback
        // back in, which is the whole of "it only completes if I guess a letter first".
        //
        // Asserted on AA's own verdict rather than through [absent], because K1 answers this same caret
        // with 83 rows that include `unimi` themselves: the fallback's answer is not empty here, so junk
        // in the merged result cannot tell the two backends apart. What must hold is that AA declines,
        // and `Outcome.served` is exactly that. A run with no reachable sidecar returns a non-served
        // outcome too, so this stays green wherever the rest of the AA checks are already SKIP/✗.
        total++
        val unbound = "(_noSuchLookAnywhere / \"x\").lenses<Float>().forEach {\n    it."
        val unboundOutcome = AaCompletionClient.complete(
            unbound, unbound.length, "", listOf(Box::class.qualifiedName!!)
        )
        if (!unboundOutcome.served) { pass++; println("  ✓ [AA-only] unbound lambda `it` declined by AA → K1 (${unboundOutcome.detail})") }
        else println("  ✗ [AA-only] unbound lambda `it` SERVED by AA, suppressing the fallback (${unboundOutcome.detail})")

        // Only a MEMBER the box could actually write. A type's member scope is the declaration's, not the
        // use site's, and nothing filtered it: `TriOsc`'s `private var integrator/phase/freqBuf` and
        // `Signal`'s `private var _cache*` were offered — four of the top rows on a bare dot were code
        // that cannot compile — as were `java.nio.Buffer`'s package-private `address`/`base()`. `freq` is
        // the control arm: a public `val` on the same receiver, which must stay.
        println("── visibility (a member the box can actually write) ──")
        val osc = "val o: TriOsc = TODO()\no."
        check("public member on a synth receiver", osc, osc.length, "freq", "AA")
        absent("private member of the receiver", osc, osc.length, "integrator")
        absent("private member of the receiver's supertype", osc, osc.length, "_cache")
        val internalFn = "vec4ToSvg"   // afield.graphics' `internal fun vec4ToSvgColor` — in intro, not reachable
        absent("internal top-level function of another module", internalFn, internalFn.length, "vec4ToSvgColor")

        // A preamble that declares a CLASS and an extension whose parameter is a LAMBDA WITH A RECEIVER —
        // what the graphics `/*supermap*/` macro writes for a Mesh box, and the form the local-stub path
        // cannot represent (a stub keeps a name and a type; it drops classes, and it drops the receiver of
        // a lambda parameter). Missing both, `m.set(3, 1) { tri.‸ }` had no `MappedStructure` receiver, so
        // `tri` resolved to the next thing in scope by that name — `afield.four.synth.tri`, a triangle
        // oscillator, via intro's `import afield.four.synth.*` — and the panel answered with `TriOsc`'s
        // members. Generated by the real macro, so a change to what it emits is covered here too.
        println("── a preamble that declares a class + a receiver-lambda extension ──")
        val gfxParent = Box("aa-e2e-gfx-parent")
        val gfxChild = Box("aa-e2e-gfx-child")
        gfxChild.parents.add(gfxParent)
        val inputs = listOf(Introspected.Input("pos", PrimitiveType.vec4.left(), 0))
        gfxParent.setPropertyRaw(
            "preamble",
            // The two halves `insertForShader` writes for a vertex input: the `FromInput` accessor and
            // the `MappedStructure`/`Mesh.set` pair. Both are extensions on `Mesh` that only a file-scope
            // preamble can express.
            "import java.nio.*\n" +
                inputs.joinToString("\n") { "/*graphics-input:${it.name}*/ val Mesh.${it.name} by FromInput()" } +
                "\n" + writeSuperMap(inputs),
            doResync = false,
        )
        val gfxKi = KotlinInterface2(implicitReceivers = listOf(KotlinType(Box::class)), filePrefix = gfxChild.id)
        gfxKi.associatedBox = gfxChild
        runBlocking { gfxKi.warmCompletion() }
        warmed("gfx child", gfxKi)
        active = gfxKi
        println("inherited preamble (gfx child): ${gatherPreambleUpwards(gfxChild).replace("\n", " ⏎ ")}")
        val lambda = "val m = Mesh()\nm.set(3, 1) {\n    tri."
        check("lambda receiver's IntBuffer member", lambda, lambda.length, "put", "AA+context")
        absent("the same-named oscillator's member", lambda, lambda.length, "freq")
        val lambdaPos = "val m = Mesh()\nm.set(3, 1) {\n    pos."
        check("the other buffer in the same lambda", lambdaPos, lambdaPos.length, "asReadOnlyBuffer", "AA+context")
        // The preamble's own extensions ON a receiver, which no local stub can stand in for either.
        val meshDot = "val m = Mesh()\nm.se"
        check("preamble extension fun on its receiver", meshDot, meshDot.length, "set", "AA+context")
        val meshPos = "val m = Mesh()\nm.po"
        check("preamble extension property on its receiver", meshPos, meshPos.length, "pos", "AA+context")

        // WHAT THE PANEL LEADS WITH. At an empty prefix every row fuzzy-scores alike and the popup's sort
        // is stable, so the order the sidecar sends is the order a person reads (see
        // `FieldSuggestPresentationTest`). A `Mesh.` panel therefore has to lead with what is about a Mesh
        // — its own members and the preamble's extensions written for it — and not with `also`/`apply`/
        // `let`, which are declared on `Any`/`T`, apply to every receiver in the language, and were sorting
        // into the middle of the list by name.
        val meshBare = "val m = Mesh()\nm."
        run {
            total++
            val m = meta(runBlocking { runCatching { active.complete(meshBare, meshBare.length) }.getOrNull() })
            fun rankOf(name: String) = m.indexOfFirst { it.text.trimEnd('(', ' ', '=') == name }
            val own = rankOf("pos")
            val universal = rankOf("also")
            if (own in 0 until universal) {
                pass++
                println("  ✓ [AA+rank] the receiver's own names lead → pos #$own before also #$universal (of ${m.size})")
            } else {
                println("  ✗ [AA+rank] pos #$own is NOT before also #$universal (of ${m.size})")
            }
        }

        // …and where each row is FILED. The category is the popup's section header, and it used to be
        // inferred from the *receiver* of the panel, so on a graphics receiver `also` and `equals` were
        // both announced as GRAPHICS — a header that described the thing being completed rather than the
        // row. Categorising on the declaration is what separates them; `classifyMetadata` is the same
        // function the server pipeline calls, asked here with the same metadata a browser would receive.
        run {
            val m = meta(runBlocking { runCatching { active.complete(meshBare, meshBare.length) }.getOrNull() })
            fun categoryOf(name: String): String {
                val row = m.firstOrNull { it.text.trimEnd('(', ' ', '=') == name } ?: return "<absent>"
                return KotlinCompletionSupport.classifyMetadata(
                    Metadata(icon = row.icon.orEmpty(), tail = row.tail.orEmpty(), text = row.text,
                        displayText = row.displayText, category = ""),
                    name, row.extraInfo,
                ).category
            }
            for ((name, want) in listOf("also" to "Kotlin", "let" to "Kotlin", "pos" to "Local", "set" to "Local")) {
                total++
                val got = categoryOf(name)
                if (got == want) { pass++; println("  ✓ [AA+section] $name is filed under $got") }
                else println("  ✗ [AA+section] $name filed under '$got', expected '$want'")
            }
        }
        active = ki

        // The defect classes the 2026-08-07 caret sweep named, asserted through the LIVE chain rather than
        // through the sweep's own harness. Each had a pre-fix failure mode that mattered here in particular,
        // because AA answered NON-EMPTY and wrong — which tells `AaCompletionClient` that AA served the
        // request and suppresses the K1 fallback that would have answered correctly. So each check below
        // really did fail through `KotlinInterface2.complete()` before its fix; this is the whole
        // production path bar the browser.
        println("── the 2026-08-07 sweep's defect classes, through the live chain ──")
        // A comment ending in a FULL STOP used to make the next line a dot position, so the request came
        // back "no qualified expr" and empty. Asserted with a named argument, which the K1 delegate does
        // not produce at all — so a hit here cannot be the fallback covering for AA.
        val afterProse = "// a comment that ends in a period.\nVector3f("
        check("name position after a prose comment", afterProse, afterProse.length, "x", "AA-only")
        // …and the other direction, which the same blanking could plausibly break: a REAL dot must still be
        // a dot when the text around it looks like prose or a literal. The sidecar module has no test source
        // set, so these edge cases live here, where they run against the live chain.
        for ((desc, snippet) in listOf(
            "a // inside a string" to "val a = \"http://x.y\".len",
            "a bare slash is not a comment" to "val a = 3 / 2\nval b = \"q\".len",
            "KDoc, period and all, before the code" to "/** kdoc with a period. */\nval s = \"q\".len",
            "a char literal holding a quote" to "val c = '\"'\nval s = \"q\".len",
            "an unterminated literal (mid-typing)" to "val s = \"unterminated\nval t = \"q\".len",
        )) check("a real dot survives: $desc", snippet, snippet.length, "length", "AA")
        val rawStringThenArg = "val t = \"\"\"a.b\"\"\"\nVector3f("
        check("a raw string's dot is not the caret's", rawStringThenArg, rawStringThenArg.length, "x", "AA-only")
        // `Math.` — a qualifier in one of Kotlin's DEFAULT imports, which the package-guessing resolution
        // could not see, so the value path answered with Object's members plus the `Any` extensions.
        val math = "val a = Math."
        check("java.lang static off a class qualifier", math, math.length, "toRadians", "AA")
        absent("no Any-extension junk on a class qualifier", math, math.length, "also")
        // The transport cap: a bare `s.` computes 299 candidates and used to ship the first 200 BY NAME, so
        // the panel had nothing past `orEmpty(` — no `trim`, no `toInt`, no `uppercase`, no `zip`.
        val str = "val s = \"x\"\ns."
        check("late-alphabet member survives the cap", str, str.length, "uppercase", "AA")
        check("…and the one after it", str, str.length, "zip", "AA")
        // A fully-qualified path answered with sub-packages only, so `kotlin.math.‸sin` came back with one
        // row and no `sin`; and the JDK's packages, being in modules rather than on the classpath, could
        // not be enumerated at all.
        val mathPkg = "val x = kotlin.math."
        check("package path offers the package's members", mathPkg, mathPkg.length, "sin", "AA")
        val javaPkg = "val r = java."
        check("JDK sub-packages (modules, not classpath)", javaPkg, javaPkg.length, "util", "AA")

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
