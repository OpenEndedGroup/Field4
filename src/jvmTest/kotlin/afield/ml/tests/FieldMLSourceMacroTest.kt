package afield.ml.tests

import afield.Box
import afield.SessionInformation
import afield.macros.MacroRegistry
import afield.ml.CompilationResult
import afield.ml.FieldMLCompiler
import afield.ml.FieldMLMacroLayer
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import afield.ml.core.fieldMLValueToHost
import afield.ml.type.TypePrinter
import afield.stdlib.Inheritance
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The declarations `prop<T>(name)` generates, written by hand — `var Box.<name> by Inheritance<T>()`
// (props.kt:71) is exactly this. They are here so that the *generated Kotlin* and FieldML's native
// `prop` can be pointed at the same box graph and compared, which is the only way to claim they are
// one property rather than two implementations that happen to agree today.
var Box.macroTestSpeed by Inheritance<Double>()
var Box.macroTestLabel by Inheritance<String>()
var Box.macroTestDepth by Inheritance<Int>()
var Box.macroTestFlag by Inheritance<Boolean>()

/**
 * Mechanism A of `dev_docs/FIELDML_HOST_BRIDGE.md`: can FieldML reach a Field **source macro** —
 * a rewrite over Kotlin text, applied before the Kotlin compiler runs?
 *
 * The claim this file makes, and the shape of the evidence for each part:
 *
 * - **The macro layer is enumerable, and this test knows the real set.** [theRegistryIsWhatThisTestThinksItIs]
 *   asserts its own premise off the live `MacroRegistry` maps rather than a grep, so a macro added or
 *   removed anywhere in the tree fails here instead of quietly shrinking the denominator.
 * - **Every registered macro is classified, in both directions.** [theLedgerAndTheRegistryAgreeBothWays].
 * - **Every call-shaped route is a real JVM method.** [everyCallShapedRouteIsARealBoxExtension] resolves
 *   each one by reflection, which proves `hostMacro` can route it without needing a Vulkan device.
 * - **A macro really expands, from FieldML, and the generated Kotlin comes back.**
 *   [expandingPropFromFieldMLProducesTheDeclarationTheKotlinMacroWouldHave].
 * - **A macro really *runs*, from FieldML, end to end.** [fieldMLCallsFieldsOwnPrintlnMacro] drives
 *   `println` — a source macro — through expansion and into `Box._printlnHere`, and reads the output
 *   back off the box.
 * - **The native forms are the same property as the generated delegate.**
 *   [propAgreesWithTheGeneratedKotlinDelegateOnTheSameGraph] compares them, and refuses to pass on an
 *   all-null graph or on a graph where every answer was available locally.
 */
class FieldMLSourceMacroTest {

    // ---- premise ---------------------------------------------------------------------------------

    /**
     * Asserted first, on purpose. Everything else here is a statement about "the source macros", and a
     * count is worthless if the thing counted moved. `MacroRegistry`'s entries come from two places —
     * its own `init` and `runAll(macroRegistration)`, which walks the classpath — so a test JVM that
     * failed to run the second half would see twelve macros instead of nineteen and every ratchet below
     * would slacken without failing.
     *
     * The named set is deliberately the *out of `macros.kt`* ones, because those are the ones that only
     * exist if `runAll` really ran.
     */
    @Test
    fun theRegistryIsWhatThisTestThinksItIs() {
        val registered = FieldMLMacroLayer.registeredMacroNames()

        val fromMacrosKt = setOf("moduleHere", "prop", "rootProp", "println", "sourceBlock")
        assertTrue(
            registered.containsAll(fromMacrosKt),
            "MacroRegistry's own init did not run: missing ${fromMacrosKt - registered}",
        )

        // These are registered by `@RunsAt(macroRegistration)` functions in four different files, so
        // their presence is the proof that the classpath scan happened in this JVM.
        val fromRunAll = setOf("gltfModelHere", "plyModelHere", "jsonParser", "graphLens", "ai")
        assertTrue(
            registered.containsAll(fromRunAll),
            "runAll(macroRegistration) did not populate the registry in this JVM: missing " +
                "${fromRunAll - registered}. Every count in this file is measuring the wrong set.",
        )

        assertTrue(
            registered.size >= 19,
            "expected at least the 19 source macros known on 2026-08-20, found ${registered.size}: " +
                registered.sorted().joinToString(", "),
        )
        assertTrue(
            "ai" in MacroRegistry.callRewriteWithContext,
            "`ai` is the only entry in callRewriteWithContext, and a survey that reads only " +
                "callRewrite would miss it",
        )
    }

    // ---- the ledger ------------------------------------------------------------------------------

    /**
     * A **bidirectional** ledger check, which is the only kind that catches a deletion.
     *
     * Missing: a macro the registry has and the ledger does not — a new macro nobody classified, which
     * would otherwise silently be "unreachable" without anyone saying so. Stale: a ledger row naming a
     * macro that is gone, which would otherwise inflate the reachable count forever. Set equality is
     * asserted last so the failure message can say which of the two it was.
     */
    @Test
    fun theLedgerAndTheRegistryAgreeBothWays() {
        val registered = FieldMLMacroLayer.registeredMacroNames()
        val classified = FieldMLMacroLayer.ledger.keys

        val unclassified = registered - classified
        assertTrue(
            unclassified.isEmpty(),
            "${unclassified.size} registered source macro(s) have no row in FieldMLMacroLayer.ledger: " +
                "${unclassified.sorted().joinToString(", ")}. Classify them — an unclassified macro is " +
                "an unmeasured one.",
        )

        val stale = classified - registered
        assertTrue(
            stale.isEmpty(),
            "${stale.size} ledger row(s) name a macro MacroRegistry no longer registers: " +
                stale.sorted().joinToString(", "),
        )

        assertEquals(registered, classified, "the ledger and the registry must be the same set")

        FieldMLMacroLayer.ledger.values.forEach {
            assertTrue(it.why.isNotBlank(), "${it.macro} has no reason recorded")
        }
    }

    /**
     * The claim `hostMacro` rests on: a call-shaped macro rewrites to an **ordinary `Box` extension
     * function**, and reflection can find it.
     *
     * This is the check that would have caught a wrong file-class guess in the ledger — a Kotlin
     * top-level function lives in a class whose name has nothing to do with the function's, so
     * `afield.OutputsKt` versus `afield.OutputKt` is exactly the mistake available here. It needs no
     * device, which is the point: it certifies the routing for `moduleHere` and `plyPointCloudHere`
     * too, which cannot be *run* in a headless JVM.
     */
    @Test
    fun everyCallShapedRouteIsARealBoxExtension() {
        val callShaped = FieldMLMacroLayer.ledger.values
            .filter { it.reach == FieldMLMacroLayer.Reach.CALL_SHAPED }

        val report = StringBuilder("call-shaped macro routes:\n")
        val broken = mutableListOf<String>()
        callShaped.forEach { route ->
            val owner = route.ownerClassName!!
            val name = route.runtimeName!!
            val klass = runCatching { Class.forName(owner) }.getOrNull()
            if (klass == null) {
                broken += "${route.macro}: no such class $owner"
                return@forEach
            }
            val matches = klass.methods.filter {
                it.name == name &&
                    it.parameterCount >= 1 &&
                    it.parameterTypes[0] == Box::class.java
            }
            report.appendLine(
                "  ${route.macro.padEnd(24)} -> $owner.$name  " +
                    "(${matches.size} overload(s), arities ${matches.map { it.parameterCount }.sorted()})",
            )
            if (matches.isEmpty()) {
                broken += "${route.macro}: $owner has no static '$name' whose first parameter is a Box"
            }
        }
        println(report)

        assertTrue(broken.isEmpty(), "unroutable call-shaped macro(s):\n${broken.joinToString("\n")}\n$report")
        // 13 on 2026-08-20. A floor rather than an equality because a *new* call-shaped macro should
        // not fail this test — it should fail theLedgerAndTheRegistryAgreeBothWays until it is
        // classified, and then raise this number.
        assertTrue(
            callShaped.size >= 13,
            "only ${callShaped.size} call-shaped routes, expected at least 13\n$report",
        )
    }

    /**
     * The headline measurement, and the one that moves row A off "UNCHANGED".
     *
     * Reachable means one of two things, both verified elsewhere in this file rather than asserted
     * here: a `CALL_SHAPED` route whose method reflection can find, or a `NATIVE_FORM` whose FieldML
     * prelude name reaches the same effect. The `NEEDS_KOTLIN` set is pinned **exactly**, not floored:
     * those four are the irreducible core of mechanism A — macros that generate Kotlin *declarations* —
     * and if one of them ever moves out, that is the interesting event, not a number going up.
     */
    @Test
    fun theReachableFractionOfTheMacroSurfaceIsFloored() {
        val byReach = FieldMLMacroLayer.ledger.values.groupBy { it.reach }
        val total = FieldMLMacroLayer.ledger.size
        val reachable = FieldMLMacroLayer.ledger.values.count { it.reach != FieldMLMacroLayer.Reach.NEEDS_KOTLIN }

        val report = buildString {
            appendLine("source macros reachable from FieldML: $reachable of $total (%.1f%%)".format(100.0 * reachable / total))
            FieldMLMacroLayer.Reach.entries.forEach { reach ->
                val rows = byReach[reach].orEmpty()
                appendLine("  $reach: ${rows.size}")
                rows.sortedBy { it.macro }.forEach { appendLine("    ${it.macro} — ${it.why}") }
            }
        }
        println(report)

        // 15 of 19 = 78.9% on 2026-08-20. The floor is one macro below the measurement, which is the
        // smallest step this number can take: 14/19 is 73.7% and fails.
        assertTrue(
            reachable >= 15,
            "only $reachable of $total source macros are reachable, expected at least 15\n$report",
        )
        assertTrue(
            reachable.toDouble() / total >= 0.75,
            "the reachable fraction fell below 75%\n$report",
        )
        assertEquals(
            setOf("sourceBlock", "jsonParser", "graphLens", "ai"),
            byReach[FieldMLMacroLayer.Reach.NEEDS_KOTLIN].orEmpty().map { it.macro }.toSet(),
            "the macros that need a Kotlin compiler are the ones that generate Kotlin declarations, " +
                "and that set is the claim\n$report",
        )
    }

    // ---- expansion, from FieldML ------------------------------------------------------------------

    /**
     * A FieldML box expanding a source macro, with the generated Kotlin coming back as text.
     *
     * `prop<Double>(speed)` is the case worth doing this on: it generates a declaration, so it is one
     * of the two macros `hostMacro` cannot invoke, and yet the expansion is real — the assertion is
     * against the *exact* text `macro_prop` emits (`props.kt:71`), so a change to the generated delegate
     * shows up here.
     */
    @Test
    fun expandingPropFromFieldMLProducesTheDeclarationTheKotlinMacroWouldHave() {
        val expansion = evaluate(
            """
            let expansion = hostMacroExpand "prop" ("<Double>", "(speed)") in
            expansion.declarations
            """,
        )
        assertTrue(
            "var Box.speed by Inheritance<Double>()" in (expansion as String),
            "expected the generated Inheritance delegate, got:\n$expansion",
        )
        assertTrue(
            "val Once.speed by Onceing<Double>()" in expansion,
            "expected the generated Onceing delegate too, got:\n$expansion",
        )

        // And the other half of the record: the call site becomes the bare name, which is why there is
        // nothing for hostMacro to call.
        assertEquals("speed", evaluate("""(hostMacroExpand "prop" ("<Double>", "(speed)")).here"""))
        assertEquals("", evaluate("""(hostMacroExpand "prop" ("<Double>", "(speed)")).call"""))

        // The record is typed, which is the one place in the host surface where a real shape is known.
        assertEquals(
            "{call: String, arguments: String, here: String, preamble: String, declarations: String, start: String, end: String}",
            TypePrinter().printSignature(compileOk("""hostMacroExpand "prop" ("<Double>", "(speed)")""").typed!!.type),
        )
    }

    /**
     * `rootProp` differs from `prop` only in *where* the declaration lands, and the expansion says so:
     * the call site becomes `root.<name>` rather than the bare name.
     *
     * Note this needs a session: `macro_root_prop` reads `Box.root` while *expanding* (`props.kt:96`),
     * which is a live document's business and not a bare `Box`'s. The refusal when it is missing is
     * checked too, because "a macro threw while expanding" with no macro named is the failure a reader
     * cannot act on.
     */
    @Test
    fun expandingRootPropShowsTheRootTargeting() {
        val box = Box("fieldml-macro-rootprop")
        box.inside = SessionInformation(filename = "macro-rootprop", root = Box("fieldml-macro-doc-root"))
        assertEquals("root.tempo", evaluate("""(hostMacroExpand "rootProp" ("<Int>", "(tempo)")).here""", box))

        val withoutSession = runCatching {
            evaluate("""(hostMacroExpand "rootProp" ("<Int>", "(tempo)")).here""", Box("no-session"))
        }.exceptionOrNull()
        assertNotNull(withoutSession, "expanding rootProp off a document should fail")
        assertTrue(
            "rootProp" in withoutSession.message.orEmpty(),
            "an expansion failure must name the macro, got: ${withoutSession.message}",
        )
    }

    /**
     * The macros that need a Kotlin compiler are not *invisible* — their generated source comes back as
     * text, which is worth having and is as far as an interpreter can go.
     *
     * `sourceBlock` is the clearest statement of the bound. It *is* call-shaped, and yet it is
     * unreachable, because the callee is a **constructor** taking a lambda whose body is the original
     * Kotlin (`source_block_macro.kt:11`). So `call` is empty — [FieldMLMacroLayer.calleeOf]
     * deliberately declines a dotted callee — and the reason is visible in `here` rather than asserted
     * in a comment.
     */
    @Test
    fun aMacroThatOnlyGeneratesKotlinStillHandsItsSourceBack() {
        val box = Box("fieldml-macro-sourceblock")
        val here = evaluate("""(hostMacroExpand "sourceBlock" "{ 2 + 2 }").here""", box) as String

        assertTrue("SourceBackedBlock" in here, "expected the generated block construction, got:\n$here")
        assertTrue("""source = "2 + 2"""" in here, "the block's own source should be captured, got:\n$here")
        assertTrue(box.id in here, "the block should be keyed to the expanding box, got:\n$here")
        assertEquals(
            "",
            evaluate("""(hostMacroExpand "sourceBlock" "{ 2 + 2 }").call""", box),
            "a dotted callee is a constructor, not a Box extension, so hostMacro must not claim it",
        )
    }

    /**
     * The bound, marked where it is: asking `hostMacro` to *invoke* a declaration macro fails with a
     * message that names the mechanism and the way round it, rather than with a reflection error.
     */
    @Test
    fun invokingADeclarationMacroIsRefusedWithTheMechanismNamed() {
        val failure = runCatching {
            evaluate("""hostMacro "prop" ("<Double>", "(speed)") ()""")
        }.exceptionOrNull()

        assertNotNull(failure, "hostMacro should refuse a macro that does not rewrite to a call")
        val message = failure.message.orEmpty()
        assertTrue("does not rewrite its call site to a call" in message, "unhelpful refusal: $message")
        assertTrue("NATIVE_FORM" in message, "the refusal should name the mechanism: $message")
        assertTrue("hostMacroExpand" in message, "the refusal should name the way round it: $message")
    }

    // ---- a macro that actually runs ---------------------------------------------------------------

    /**
     * A source macro **run** from FieldML, end to end, headlessly.
     *
     * `println` is Field's own output macro: `macro_printlnHere` rewrites `println(x)` to
     * `_printlnHere(__line__+1, x)` (`outputs.kt:727`), and `Box._printlnHere` pushes the payload
     * through `DefaultOutput.proxy` (`outputs.kt:714`). So this is the whole mechanism in one call —
     * expansion, callee extraction, and a reflective `Box`-extension call with a `vararg` tail — with
     * nothing about it stubbed and no device needed.
     *
     * The assertion is that the text **arrived at the box's output**, with the line number the caller
     * supplied, rather than that a call was made.
     */
    @Test
    fun fieldMLCallsFieldsOwnPrintlnMacro() {
        val box = Box("fieldml-macro-println")
        val seen = mutableListOf<Triple<Any?, Boolean, Number>>()
        box.output.proxy = { payload, newline, line -> seen += Triple(payload, newline, line) }

        evaluate("""hostMacro "println" "(x)" (7, "from a FieldML box")""", box)

        assertEquals(1, seen.size, "expected exactly one line of output, got $seen")
        assertEquals("from a FieldML box", seen[0].first)
        assertEquals(true, seen[0].second, "println should be the newline form")
        assertEquals(7, seen[0].third.toInt(), "the line number the caller passed should arrive")

        // The non-newline sibling goes through the same route to a different function, which is the
        // cheapest possible check that the callee really is read out of the expansion rather than
        // guessed from the macro name.
        seen.clear()
        evaluate("""hostMacro "print" "(x)" (2, "no newline")""", box)
        assertEquals(1, seen.size)
        assertEquals(false, seen[0].second, "print should not be the newline form")

        // And the expansion agrees with the ledger about which function that was.
        assertEquals("_printlnHere", evaluate("""(hostMacroExpand "println" "(x)").call""", box))
        assertEquals("_printHere", evaluate("""(hostMacroExpand "print" "(x)").call""", box))
        assertEquals(
            "_printlnHere",
            FieldMLMacroLayer.ledger.getValue("println").runtimeName,
            "the ledger's advisory runtime name has gone stale against the real expansion",
        )
    }

    /**
     * The named form, which is what composes mechanism A with B and C.
     *
     * A macro's runtime function is usually all-defaults — `_fieldStatsHere` declares a default for
     * every one of its seven parameters — so the positional form makes you write all seven to change
     * one. A record of arguments is a named call, and a named call can leave a default unwritten.
     * `_printlnHere(line, vararg a)` is the one such function that runs headlessly: naming only `line`
     * omits the `vararg` entirely, which Kotlin treats as optional.
     */
    @Test
    fun aMacrosRuntimeFunctionCanBeCalledByName() {
        val box = Box("fieldml-macro-named")
        val seen = mutableListOf<Triple<Any?, Boolean, Number>>()
        box.output.proxy = { payload, newline, line -> seen += Triple(payload, newline, line) }

        evaluate("""hostMacro "println" "(x)" {line = 11}""", box)

        assertEquals(1, seen.size, "expected one line of output, got $seen")
        assertEquals(11, seen[0].third.toInt(), "the named `line` should arrive")
        assertEquals("", seen[0].first, "an omitted vararg is an empty payload (outputs.kt:693)")
    }

    // ---- the native forms -------------------------------------------------------------------------

    /**
     * FieldML's `prop` against the Kotlin declaration `prop<T>(name)` generates, on one box graph.
     *
     * This is the whole argument for the native form: not "FieldML can read a property" — the reach
     * test already showed that with `getPropertyRaw` — but that it reads *the same slot with the same
     * walk*, so a Kotlin box and a FieldML box in one document share a parameter. Since
     * `2026-08-20` they share the implementation too (`afield.stdlib.inheritedPropertyRaw`), which is
     * what makes this an agreement test rather than a coincidence test.
     *
     * Two guards against a vacuous pass, both floored:
     *
     * - at least four of the comparisons must be **inherited** — the reading box does not have the slot
     *   locally — so a local-read-only implementation cannot pass;
     * - the comparisons must produce at least three **distinct** values, so an all-null graph cannot
     *   pass at zero disagreements.
     */
    @Test
    fun propAgreesWithTheGeneratedKotlinDelegateOnTheSameGraph() {
        // grandparent -> parent -> child, plus a sibling branch, so the breadth-first order matters.
        val grandparent = Box("macro-prop-grandparent")
        val parent = Box("macro-prop-parent")
        val aunt = Box("macro-prop-aunt")
        val child = Box("macro-prop-child")
        parent.parents.add(grandparent)
        aunt.parents.add(grandparent)
        child.parents.add(parent)

        grandparent.macroTestSpeed = 0.25
        grandparent.macroTestLabel = "from the grandparent"
        grandparent.macroTestDepth = 1
        parent.macroTestSpeed = 0.75 // shadows the grandparent for parent and child
        aunt.macroTestFlag = true

        data class Case(val box: Box, val slot: String, val throughKotlin: () -> Any?)
        val cases = listOf(
            Case(grandparent, "macroTestSpeed") { grandparent.macroTestSpeed },
            Case(parent, "macroTestSpeed") { parent.macroTestSpeed },
            Case(child, "macroTestSpeed") { child.macroTestSpeed },
            Case(child, "macroTestLabel") { child.macroTestLabel },
            Case(parent, "macroTestLabel") { parent.macroTestLabel },
            Case(child, "macroTestDepth") { child.macroTestDepth },
            Case(aunt, "macroTestDepth") { aunt.macroTestDepth },
            Case(aunt, "macroTestFlag") { aunt.macroTestFlag },
        )

        val disagreed = mutableListOf<String>()
        var inherited = 0
        val values = mutableSetOf<Any?>()
        cases.forEach { case ->
            val fromKotlin = case.throughKotlin()
            val fromFieldML = evaluate("""prop "${case.slot}"""", case.box)
            values += fromKotlin
            if (case.box.getPropertyRaw(case.slot) == null) inherited++
            if (asFieldMLWidth(fromKotlin) != fromFieldML) {
                disagreed += "${case.box.id}.${case.slot}: Kotlin $fromKotlin " +
                    "(${fromKotlin?.javaClass?.simpleName}), FieldML $fromFieldML " +
                    "(${fromFieldML?.javaClass?.simpleName})"
            }
        }

        assertTrue(disagreed.isEmpty(), "the generated delegate and FieldML's prop disagreed:\n${disagreed.joinToString("\n")}")
        assertTrue(
            inherited >= 4,
            "only $inherited of ${cases.size} comparisons were inherited rather than local — a " +
                "local-read-only prop would have passed this test",
        )
        assertTrue(
            values.size >= 3,
            "the comparisons only produced ${values.size} distinct value(s) ($values) — a blank graph " +
                "agrees with a blank graph at zero disagreements",
        )

        // The counterfactual, spelled out: reading a slot no ancestor has must fail, and fail naming
        // the slot, in both implementations.
        val kotlinFailure = runCatching { child.macroTestFlag }.exceptionOrNull()
        val fieldMLFailure = runCatching { evaluate("""prop "macroTestFlag"""", child) }.exceptionOrNull()
        assertNotNull(kotlinFailure, "the Kotlin delegate should refuse a slot nothing up the graph has")
        assertNotNull(fieldMLFailure, "FieldML's prop should refuse a slot nothing up the graph has")
        assertTrue("macroTestFlag" in kotlinFailure.message.orEmpty())
        assertTrue("macroTestFlag" in fieldMLFailure.message.orEmpty())

        // `propOr` is the form that does not fail, and it must not paper over a value that *is* there.
        assertEquals(9.0, evaluate("""propOr "macroTestNothing" 9.0""", child))
        assertEquals(0.75, evaluate("""propOr "macroTestSpeed" 9.0""", child))

        // And the one place the two readers genuinely differ, pinned rather than normalised away in
        // silence: FieldML has *one* integer type, so a `prop<Int>` slot reads back as a JVM Long
        // (Evaluator.kt:313). This is why [asFieldMLWidth] exists above, and why `setProp` narrows on
        // the way in — see [aFieldMLWriteKeepsTheWidthAKotlinBoxDeclared].
        assertEquals(1, grandparent.macroTestDepth, "the Kotlin delegate gives the declared Int")
        assertEquals(1L, evaluate("""prop "macroTestDepth"""", grandparent), "FieldML gives its own Int, a Long")
    }

    /**
     * Which JVM width a FieldML value has when the Kotlin side reads it back, and what `setProp` does
     * about it. Written as a test because two of the three facts here are surprising and one of them was
     * the opposite of what this test first claimed.
     *
     * A FieldML `5` is a JVM `Long` (`Evaluator.kt:313`) and its `0.5` a `Double`, but a Kotlin box that
     * wrote `prop<Int>(depth)` reads the slot through `Inheritance<Int>`. So:
     *
     * 1. The delegate **does not** throw on a `Long` in an `Int` slot, which is what this test was
     *    written expecting. Kotlin compiles `Any as Int` to `Number.intValue()`, not to a checkcast on
     *    `Integer`, so a `Long` 8 reads back as 8. Non-numeric mismatches still throw — a `String` in a
     *    `Boolean` slot is a `ClassCastException` — so the forgiveness is exactly the numeric tower.
     * 2. It truncates instead. `1L shl 40` in an `Int` slot reads back as **0**, silently, and neither
     *    the narrowing nor anything else here protects against that; the value was already too big when
     *    it was written.
     * 3. What *is* observably wrong without narrowing is the **raw** property: its class changes to
     *    `java.lang.Long` and it stops being equal to the `Int` a Kotlin box wrote. Everything in Field
     *    that reads `getPropertyRaw` directly or type-switches on the result sees a different value —
     *    `hostToFieldMLValue` does, `Structure.writeToBuffer` does (and prints `Warning: unknown type`
     *    rather than failing), the document writer does, and `setPropertyRaw`'s own resync check does.
     *
     * So `setProp` keeps the width that is already in the slot. The counterfactual below is on (3),
     * because that is the one that is demonstrably different rather than the one that reads well.
     */
    @Test
    fun aFieldMLWriteKeepsTheWidthAKotlinBoxDeclared() {
        val box = Box("macro-width")
        box.macroTestDepth = 3 // a Kotlin box declared `prop<Int>` and seeded the slot

        evaluate("""setProp "macroTestDepth" 8""", box)
        assertEquals(8, box.macroTestDepth, "the Kotlin reader must still work after a FieldML write")
        assertEquals(
            "java.lang.Integer",
            box.getPropertyRaw("macroTestDepth")!!.javaClass.name,
            "the slot should still hold an Int",
        )
        assertEquals(8, box.getPropertyRaw("macroTestDepth"), "and the raw property should equal the Int a Kotlin box wrote")

        // The counterfactual: the same value stored the way it would be without the narrowing.
        box.setPropertyRaw("macroTestDepth", 8L, false)
        assertEquals(
            "java.lang.Long",
            box.getPropertyRaw("macroTestDepth")!!.javaClass.name,
            "if this is already Integer the narrowing above proved nothing",
        )
        assertTrue(
            box.getPropertyRaw("macroTestDepth") != 8,
            "a raw Long must not compare equal to the Int a Kotlin box wrote, or there is nothing to fix",
        )
        // And the part that is *not* fixed, pinned so nobody claims it is: the delegate reads a Long
        // through Number.intValue(), so it succeeds — and truncates once the value leaves Int range.
        assertEquals(8, box.macroTestDepth, "Kotlin unboxes Any to Int via Number.intValue(), so this succeeds")
        box.setPropertyRaw("macroTestDepth", 1L shl 40, false)
        assertEquals(0, box.macroTestDepth, "an out-of-range Long truncates silently; nothing here prevents that")

        // A non-numeric mismatch is where the JVM does refuse, which is why the narrowing is only ever
        // between widths of one FieldML type.
        box.setPropertyRaw("macroTestFlag", "yes", false)
        assertTrue(
            runCatching { box.macroTestFlag }.exceptionOrNull() is ClassCastException,
            "a String in a Boolean slot should still be a ClassCastException",
        )

        // An empty slot has no width to preserve, and then FieldML's own widths are the right answer.
        val fresh = Box("macro-width-fresh")
        evaluate("""setProp "macroTestDepth" 8""", fresh)
        assertEquals(
            "java.lang.Long",
            fresh.getPropertyRaw("macroTestDepth")!!.javaClass.name,
            "with nothing to match, a FieldML Int is a Long",
        )
    }

    /**
     * FieldML has one integer type and one float type, so the JVM widths on the two sides of a shared
     * slot differ by design. Comparing through this is what keeps
     * [propAgreesWithTheGeneratedKotlinDelegateOnTheSameGraph] about the *slot* rather than about the
     * numeric tower — and it is narrow on purpose, so it cannot hide a real disagreement.
     */
    private fun asFieldMLWidth(value: Any?): Any? =
        when (value) {
            is Int -> value.toLong()
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is Float -> value.toDouble()
            else -> value
        }

    /**
     * The write half, both directions, because a shared slot that is only readable is not shared.
     *
     * FieldML writing and Kotlin reading is the case a live document hits: a FieldML parameter box that
     * a Kotlin box downstream consumes.
     */
    @Test
    fun setPropAndTheKotlinDelegateWriteTheSameSlot() {
        val documentRoot = Box("macro-setprop-root")
        val parent = Box("macro-setprop-parent")
        val child = Box("macro-setprop-child")
        child.parents.add(parent)
        // `rootProp` reads `Box.root`, which is `inside.root` (Boxes.kt:277) — a session-scoped
        // lateinit, so a bare Box has none. Giving these three a session is what lets the root half of
        // the family be tested here rather than only in a live Field.
        val session = SessionInformation(filename = "macro-setprop", root = documentRoot)
        listOf(documentRoot, parent, child).forEach { it.inside = session }

        // FieldML writes, Kotlin reads — including through the inheritance walk.
        assertEquals(0.5, evaluate("""setProp "macroTestSpeed" 0.5""", parent))
        assertEquals(0.5, parent.macroTestSpeed)
        assertEquals(0.5, child.macroTestSpeed, "a FieldML write on the parent must be inherited")

        // Kotlin writes, FieldML reads.
        parent.macroTestLabel = "written in Kotlin"
        assertEquals("written in Kotlin", evaluate("""prop "macroTestLabel"""", child))

        // A FieldML write on the *child* shadows locally rather than changing the parent, which is what
        // Inheritance.setValue does (stdlib.kt:307).
        evaluate("""setProp "macroTestSpeed" 2.0""", child)
        assertEquals(2.0, child.macroTestSpeed)
        assertEquals(0.5, parent.macroTestSpeed, "a local write must not reach up the graph")

        // `rootProp` puts the slot on the document root, so it is one value wherever it is read — the
        // child writes and the *parent* sees it, which a `prop` write could never do.
        assertEquals(120L, evaluate("""setRootProp "macroTestTempo" 120""", child))
        assertEquals(120L, evaluate("""rootProp "macroTestTempo"""", parent))
        assertEquals(120L, documentRoot.getPropertyRaw("macroTestTempo"))
        assertEquals(null, child.getPropertyRaw("macroTestTempo"), "setRootProp must not also write locally")
    }

    /**
     * `once` is `Onceing.getValue` (`scratch.kt:26`): compute on the first read, store, and never
     * compute again. The assertion is on the number of times the FieldML closure **ran**, not on the
     * value it produced — a cache that recomputes and gets the same answer would pass the latter.
     */
    @Test
    fun onceComputesOnceAndThenReadsTheStoredValue() {
        val box = Box("macro-once")
        val runs = AtomicInteger()
        box.setPropertyRaw("macroOnceCounter", runs, false)

        val source = """
            let counter = thisBox |> hostCall "getPropertyRaw" ("macroOnceCounter", hostNull) in
            once "macroOnceSlot" (fun ignored -> 40 + (counter |> hostCall0 "incrementAndGet" |> hostAsInt))
        """

        assertEquals(41L, evaluate(source, box))
        assertEquals(1, runs.get(), "the computation should have run once")
        assertEquals(41L, box.getPropertyRaw("macroOnceSlot"), "the computed value should have been stored")

        assertEquals(41L, evaluate(source, box), "the stored value should win on the second read")
        assertEquals(1, runs.get(), "the computation must not run again")
    }

    // ---- the live artifact -----------------------------------------------------------------------

    /**
     * The demo's two captures, held to the claim they are made to support: **the `prop` is live**.
     *
     * `spiral.png` and `spiral-edited.png` are the same FieldML box in the same real Field, the only
     * difference being the number its parent box wrote into the shared slot (`swirl` 0.85 / `turns` 4
     * versus 0.35 / 9). Both were taken through `field_backdoor.py --port 9601` on 2026-08-20, and the
     * FLine the box returned went from 187×176 bounds to 91×89 between them.
     *
     * The test is here rather than in prose because prose cannot notice a re-capture that pasted the
     * same image twice. Three guards, in the order that matters:
     *
     * - the two images have to be the **same size**, or a difference measurement is meaningless;
     * - each has to have **ink** in it — a blank capture agrees with a blank capture at zero
     *   differences, which is the way this measurement fails without failing;
     * - and then they have to **differ**, floored at a number rather than at "not identical".
     */
    @Test
    fun theDemoCapturesShowTheParameterActuallyChangingTheDrawing() {
        val folder = java.nio.file.Path.of("public/docs/assets/fieldml_macro_demo")
        val before = javax.imageio.ImageIO.read(folder.resolve("spiral.png").toFile())
        val after = javax.imageio.ImageIO.read(folder.resolve("spiral-edited.png").toFile())

        assertNotNull(before, "spiral.png is missing or unreadable")
        assertNotNull(after, "spiral-edited.png is missing or unreadable")
        assertEquals(before.width to before.height, after.width to after.height, "the two captures must be the same size")

        fun ink(image: java.awt.image.BufferedImage): Double {
            var lit = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val brightest = maxOf((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)
                if (brightest > 40) lit++
            }
            return 100.0 * lit / (image.width * image.height)
        }

        var differing = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val a = before.getRGB(x, y)
            val b = after.getRGB(x, y)
            val delta = maxOf(
                Math.abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
                Math.abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)),
                Math.abs((a and 0xff) - (b and 0xff)),
            )
            if (delta > 16) differing++
        }
        val differingPercent = 100.0 * differing / (before.width * before.height)
        val inkBefore = ink(before)
        val inkAfter = ink(after)
        println(
            "fieldml_macro_demo captures: %.2f%% of pixels differ; ink %.2f%% -> %.2f%%"
                .format(differingPercent, inkBefore, inkAfter),
        )

        assertTrue(inkBefore >= 1.5, "spiral.png has only %.2f%% ink — a blank capture proves nothing".format(inkBefore))
        assertTrue(inkAfter >= 1.5, "spiral-edited.png has only %.2f%% ink".format(inkAfter))
        // Measured 7.13% on 2026-08-20. Floored under it rather than at "they are not identical",
        // which two re-captures of the same drawing would also satisfy.
        assertTrue(
            differingPercent >= 5.0,
            "the two captures differ in only %.2f%% of pixels — the parameter is not driving the drawing"
                .format(differingPercent),
        )
    }

    // ---- harness ---------------------------------------------------------------------------------

    private fun evaluate(source: String, box: Box = Box("fieldml-source-macro")): Any? {
        val result = compileOk(source)
        return fieldMLValueToHost(Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(result.core!!))
    }

    private fun compileOk(source: String): CompilationResult {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(result.successful, result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" })
        return result
    }
}
