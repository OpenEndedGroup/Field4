package afield.ml

import afield.Box
import afield.macros.Insert
import afield.macros.MacroRegistry
import afield.ml.core.FieldMLHostBridge
import afield.ml.core.FieldMLValue

/**
 * How a Field **source macro** is reached from FieldML — mechanism A of `dev_docs/FIELDML_HOST_BRIDGE.md`.
 *
 * ## Why there is anything to do here at all
 *
 * A source macro is a rewrite over Kotlin *text*, applied before the Kotlin compiler runs
 * (`MacroRegistry.rewrite`, `macros.kt:331`). FieldML is an interpreter — lex, parse, infer, lower to
 * `CoreExpr`, evaluate — with no Kotlin compile anywhere in the path, so a macro that hands back
 * generated Kotlin has nothing to be compiled by. That is the whole of mechanism A.
 *
 * ## What makes it soluble anyway
 *
 * Every macro that rewrites a **call** rewrites it to an ordinary `Box` extension function. `moduleHere`
 * becomes `_moduleHere(…)` (`shaders.kt:4242`), `computeModuleHere` becomes `_computeModuleHere(…)`
 * (`shaders.kt:4577`), `println` becomes `_printlnHere(__line__+1, …)` (`outputs.kt:727`). `shaders.kt:1010`
 * even says so in prose about `_fieldStatsHere`: *"what the macro rewrites the call site to. Reach for it
 * directly only when you are driving a box from outside and do not want macro expansion."* FieldML **is**
 * driving a box from outside.
 *
 * So reaching such a macro needs two things, and a Kotlin compiler is neither of them:
 *
 * 1. the **expansion**, for its side effects on the box — `moduleHere` seeds `vertex`/`fragment`
 *    properties, compiles both stages and assigns them (`shaders.kt:4039`), and `fieldStatsHere` builds
 *    the whole `ShaderFieldStats` block while expanding (`shaders.kt:1105`);
 * 2. a route to the **rewritten callee**, which is a reflective static call with the box as the
 *    extension receiver — exactly what `FieldMLHostBridge.callStaticMethod` already does.
 *
 * What the Kotlin compile was ever *for* is the macro's **preamble**: generated typed accessors like
 * `val Mesh.pos by FromInput()`. FieldML types every host value `Host`, which is dynamic, so it reaches
 * the same members through `hostGet`/`hostCall` and does not need them. That is the trade being made,
 * and it is why this is a bridge answer rather than a language one.
 *
 * ## What is left, honestly
 *
 * Four macros generate Kotlin **declarations** rather than rewriting a call — `sourceBlock` (a lambda
 * whose body is Kotlin source), `jsonParser` (a generated class), `graphLens` (a `::` property
 * reference) and `ai` (generated Kotlin from a prompt). Those are the irreducible core of mechanism A:
 * reaching them needs a compiler, and [expand] gets no further than handing their generated text back as
 * a string. `prop`/`rootProp` also generate declarations, but their declaration is a *delegate* whose
 * semantics FieldML can implement natively — see `afield.stdlib.inheritedPropertyRaw` and the `prop`
 * family in [FieldMLRuntimePrelude].
 *
 * @see FieldMLMacroLayer.ledger for the classification, which `FieldMLSourceMacroTest` holds to the
 * live contents of `MacroRegistry` in both directions
 */
object FieldMLMacroLayer {

    /** How a given source macro is reachable from FieldML, if it is. */
    enum class Reach {
        /**
         * The macro rewrites its call site to a `Box` extension function, so `hostMacro` can expand it
         * and then call that function reflectively with the box as receiver.
         */
        CALL_SHAPED,

        /**
         * The macro generates a *declaration* whose semantics FieldML implements itself, natively, with
         * no expansion involved — `prop` is `afield.stdlib.inheritedPropertyRaw`.
         */
        NATIVE_FORM,

        /**
         * The macro generates Kotlin declarations, and there is no interpreting them. `hostMacroExpand`
         * still runs the expansion and hands back the generated source as text, which is inspectable
         * and occasionally useful, but it is not reaching the macro's effect.
         */
        NEEDS_KOTLIN,
    }

    /**
     * One row of the classification: which mechanism reaches this macro, and where.
     *
     * @param runtimeName the `Box` extension the expansion rewrites the call to, for [Reach.CALL_SHAPED].
     * Advisory — [invoke] reads the name out of the *actual* expansion, so a macro that changes its
     * rewrite target keeps working and the ledger row is what goes stale. `FieldMLSourceMacroTest`
     * checks the two agree wherever it can expand headlessly.
     * @param ownerClassName the JVM class holding [runtimeName]. This is the part reflection cannot
     * guess: a Kotlin top-level function lives in a file class whose name is not derivable from the
     * function's.
     * @param nativeForm the FieldML prelude name that reaches the same effect, for [Reach.NATIVE_FORM].
     */
    data class Route(
        val macro: String,
        val reach: Reach,
        val why: String,
        val runtimeName: String? = null,
        val ownerClassName: String? = null,
        val nativeForm: String? = null,
    )

    private fun shaders(macro: String, runtimeName: String, why: String) =
        Route(macro, Reach.CALL_SHAPED, why, runtimeName, "afield.stdlib.ShadersKt")

    /**
     * Every name `MacroRegistry` registers, and how FieldML gets at it.
     *
     * Deliberately here and not in `macros.kt`: this is FieldML's *reading* of the macro layer, and a
     * reading that can be wrong should live next to the thing that can be wrong about it. The guard
     * that keeps it honest is a bidirectional one — a macro missing a row and a row naming a macro that
     * is gone are both failures, and so is a `CALL_SHAPED` row whose method reflection cannot find.
     */
    val ledger: Map<String, Route> = listOf(
        shaders("moduleHere", "_moduleHere", "graphics module; expansion seeds and compiles both shader stages"),
        shaders("meshModuleHere", "_meshModuleHere", "mesh-shader module, same shape as moduleHere"),
        shaders("computeModuleHere", "_computeModuleHere", "compute module, same shape as moduleHere"),
        shaders("shadertoyHere", "_shadertoyHere", "moduleHere with the shadertoy preamble templates seeded first"),
        shaders("fieldStatsHere", "_fieldStatsHere", "expansion builds the ShaderFieldStats block itself; the call re-reads it"),
        Route(
            "print", Reach.CALL_SHAPED,
            "rewrites to _printHere(line, vararg), which needs the line number the Kotlin pass supplied",
            "_printHere", "afield.OutputsKt",
        ),
        Route(
            "println", Reach.CALL_SHAPED,
            "rewrites to _printlnHere(line, vararg)",
            "_printlnHere", "afield.OutputsKt",
        ),
        Route("_print", Reach.CALL_SHAPED, "the underscore spelling of print", "_printHere", "afield.OutputsKt"),
        Route("_println", Reach.CALL_SHAPED, "the underscore spelling of println", "_printlnHere", "afield.OutputsKt"),
        Route(
            "gltfModelHere", Reach.CALL_SHAPED,
            "moduleHere with glTF shaders, plus a loader; the call needs a renderer from box context",
            "_gltfModelHere", "afield.four.modules.SimpleGLTFSupportKt",
        ),
        Route(
            "gltfModelWithTextureHere", Reach.CALL_SHAPED,
            "as gltfModelHere, with a texture resolver — note the runtime name is not the macro name",
            "_gltfModelHereWithTexture", "afield.four.modules.SimpleGLTFSupportKt",
        ),
        Route(
            "plyModelHere", Reach.CALL_SHAPED,
            "moduleHere with PLY shaders, plus a loader",
            "_plyModelHere", "afield.four.modules.SimplePLYSupportKt",
        ),
        Route(
            "plyPointCloudHere", Reach.CALL_SHAPED,
            "as plyModelHere, point-cloud shaders",
            "_plyPointCloudHere", "afield.four.modules.SimplePLYSupportKt",
        ),
        shaders(
            "gaussianSplatModuleHere", "_moduleHere",
            "macro_moduleHereWithDefaultShaders with the splat shader pair, which leaves resolvesToCall " +
                "at its default — so the rewrite target is _moduleHere, not a name of its own",
        ),
        shaders(
            "gaussianSplatMultiviewModuleHere", "_moduleHere",
            "as gaussianSplatModuleHere, seeded with the multiview vertex shader that selects a " +
                "model-view/projection pair with gl_ViewIndex",
        ),
        Route(
            "prop", Reach.NATIVE_FORM,
            "generates `var Box.<name> by Inheritance<T>()` (props.kt:71); the delegate is " +
                "afield.stdlib.inheritedPropertyRaw, which FieldML calls directly",
            nativeForm = "prop / propOr / setProp / once",
        ),
        Route(
            "rootProp", Reach.NATIVE_FORM,
            "the same declaration, targeting the document root (props.kt:88)",
            nativeForm = "rootProp / setRootProp",
        ),
        Route(
            "sourceBlock", Reach.NEEDS_KOTLIN,
            "rewrites to SourceBackedBlock(…) { <the original Kotlin body> } (source_block_macro.kt:11) — " +
                "the payload is Kotlin source in a lambda, and there is nothing to compile it",
        ),
        Route(
            "jsonParser", Reach.NEEDS_KOTLIN,
            "here is empty; the whole expansion is a generated Kotlin data class in the preamble " +
                "(jsonparsing.kt:90). hostMacroExpand hands the generated source back as text",
        ),
        Route(
            "graphLens", Reach.NEEDS_KOTLIN,
            "rewrites to boundLens(receiver.a.b::c, \"a/b/c\") (PathMacro.kt:91) — a Kotlin callable " +
                "reference synthesised from a path, which is a compile-time construct",
        ),
        Route(
            "ai", Reach.NEEDS_KOTLIN,
            "asks a model for Kotlin and inserts it (ai_macro.kt:490); generated Kotlin, by definition",
        ),
    ).associateBy { it.macro }

    /**
     * The names `MacroRegistry` really has, read off the live maps rather than grepped.
     *
     * Both maps, because `ai` is the only entry in `callRewriteWithContext` and a survey that missed it
     * would undercount. Touching either map runs `MacroRegistry`'s `init`, which is what performs
     * `runAll(macroRegistration)` and so populates the entries declared outside `macros.kt`.
     */
    fun registeredMacroNames(): Set<String> =
        MacroRegistry.callRewrite.keys + MacroRegistry.callRewriteWithContext.keys

    /**
     * Run a source macro's expansion against [box] and hand back the rewrite.
     *
     * The **side effects are the point** for most macros: `moduleHere` seeds and compiles this box's
     * shader stages, `fieldStatsHere` allocates the stats block, `shadertoyHere` installs the template
     * preambles. Everything a Kotlin box would get from expanding the macro, this box gets too — the
     * only thing left behind is the generated Kotlin, which FieldML has no compiler for and, being
     * dynamic at the host boundary, no need of.
     *
     * @param argumentTexts the macro's arguments **as source text**, which is what a macro consumes: a
     * type-argument group and/or a parenthesised or braced argument list, exactly as the Kotlin pass
     * would have handed them over
     * @throws IllegalStateException naming the registered set, when [macro] is not one of them
     */
    fun expand(box: Box, macro: String, argumentTexts: List<String>): Insert {
        val registered = registeredMacroNames()
        if (macro !in registered) {
            error("'$macro' is not a registered source macro. Registered: ${registered.sorted().joinToString(", ")}")
        }
        // A macro's expansion is arbitrary code with arbitrary side effects, and when it throws, the
        // throw is from deep inside the macro layer with nothing in it that names the call site. Naming
        // the macro and the argument text is the difference between a diagnosable failure and a
        // stack trace. `rootProp` is the case that showed this: it reads `Box.root` while *expanding*
        // (props.kt:96), so on a box with no session it fails with a bare
        // `UninitializedPropertyAccessException`.
        val expanded = try {
            MacroRegistry.rewrite(box, macro, argumentTexts)
        } catch (t: Throwable) {
            throw IllegalStateException(
                "The source macro '$macro' failed while expanding ${argumentTexts.joinToString(" ")} on " +
                    "'${box.name.ifBlank { box.id }}': ${t.message ?: t.javaClass.simpleName}",
                t,
            )
        }
        return expanded ?: error("The source macro '$macro' expanded to nothing")
    }

    /**
     * Expand [macro] and then **call** what it rewrote the call site to, with [arguments] as the
     * argument values.
     *
     * The callee is read out of the expansion rather than out of [ledger], so this follows a macro that
     * changes its rewrite target. The ledger supplies only the owning class, which is the part
     * reflection genuinely cannot recover: a Kotlin top-level function lives in a file class whose name
     * has nothing to do with the function's.
     *
     * The macro's own argument *text* is discarded at this point on purpose. A macro's arguments do two
     * different jobs — they configure the expansion (shader block, sample count) and they are passed
     * through to the rewritten call — and only the first can be text in an interpreter. So the caller
     * writes the text once for the expansion and the values once for the call.
     */
    fun invoke(box: Box, macro: String, argumentTexts: List<String>, arguments: List<Any?>): FieldMLValue =
        route(box, macro, argumentTexts) { callee, owner ->
            FieldMLHostBridge.callStaticMethod(callee, owner, listOf(box) + arguments)
        }

    /**
     * [invoke], by **name** — which is also the only shape that can leave a Kotlin default unwritten
     * (`FieldMLHostBridge.callStaticMethodNamed`, and decision 6 of the design notes).
     *
     * This matters more for macros than for ordinary calls, because a macro's runtime function is
     * usually all-defaults: `_fieldStatsHere` takes seven parameters and declares a default for every
     * one of them, so the positional form makes you write all seven to change one. The box goes in as
     * the **extension receiver**, bound by `KParameter.Kind` rather than by name, since Kotlin gives an
     * extension receiver no name a `callBy` map would accept.
     */
    fun invokeNamed(box: Box, macro: String, argumentTexts: List<String>, arguments: Map<String, Any?>): FieldMLValue =
        route(box, macro, argumentTexts) { callee, owner ->
            FieldMLHostBridge.callStaticMethodNamed(callee, owner, extensionReceiver = box, arguments = arguments)
        }

    private fun route(
        box: Box,
        macro: String,
        argumentTexts: List<String>,
        call: (callee: String, owner: Class<*>) -> FieldMLValue,
    ): FieldMLValue {
        val insert = expand(box, macro, argumentTexts)
        val route = ledger[macro]
        val callee = calleeOf(insert.here)
            ?: error(
                "The source macro '$macro' does not rewrite its call site to a call — it expanded to " +
                    "'${insert.here}'${route?.let { ", which is ${it.reach}: ${it.why}" } ?: ""}. " +
                    "Use hostMacroExpand to run the expansion and read the generated source.",
            )
        val owner = route?.ownerClassName
            ?: error(
                "FieldML does not know which class holds '$callee', the function '$macro' rewrites to. " +
                    "Add a row to FieldMLMacroLayer.ledger, or call it yourself with " +
                    "hostExt (hostClass \"…\") \"$callee\" (…) thisBox.",
            )
        val ownerClass = runCatching { Class.forName(owner) }.getOrNull()
            ?: error("FieldMLMacroLayer.ledger names the class '$owner' for '$macro', and it does not exist")
        return call(callee, ownerClass)
    }

    /**
     * The leading identifier of a rewritten call site, or null when the rewrite is not a call.
     *
     * `_moduleHere{ … }` gives `_moduleHere`; `_printHere(__line__+1, x)` gives `_printHere`. A bare
     * name (`prop`'s rewrite is the property name itself) gives null, and so does a *dotted* callee —
     * `sourceBlock` rewrites to `afield.stdlib.SourceBackedBlock(…)`, a constructor rather than a `Box`
     * extension, and calling it would need the Kotlin lambda that follows it.
     */
    fun calleeOf(here: String): String? =
        Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*[({]""").find(here)?.groupValues?.get(1)

    /** The argument text of a rewritten call site — everything after the callee. Empty when there is none. */
    fun argumentTextOf(here: String): String {
        val callee = calleeOf(here) ?: return ""
        return here.trimStart().removePrefix(callee).trim()
    }
}
