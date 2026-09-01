package afield.macros

import afield.*
import afield.logger.lprintln
import afield.stdlib.*
import kotlinx.coroutines.withContext
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic


suspend fun standardExecutionPractice(
    ki: KotlinScriptEngine,
    s: String,
    onCompileError: (List<ScriptDiagnostic>) -> Unit = { lprintln("error,macros", "onCompileError default handler $it") },
    onEvalError: (List<ScriptDiagnostic>) -> Unit = { lprintln("error,macros", "onEvalError default handler $it") },
    onEvalException: (Throwable) -> Unit = { lprintln("error,macros", "onEvalException default handler $it"); throw it },
    implicits: List<Any> = emptyList(),
    runSetupBlocks: Boolean = true,
    onSetupCompileError: (String, List<ScriptDiagnostic>) -> Unit = { _, diagnostics -> onCompileError(diagnostics) },
    onSetupEvalError: (String, List<ScriptDiagnostic>) -> Unit = { _, diagnostics -> onEvalError(diagnostics) },
    onSetupEvalException: (String, Throwable) -> Unit = { _, throwable -> onEvalException(throwable) }
): List<ResultValue?> {

    var lines = s.lines()
    var breaks = lines.mapIndexedNotNull { index, line ->
        if (line.trim().startsWith("//%-")) {
            index
        } else if (line.trim().startsWith("/*%-*/")) {
            index
        } else null
    } + lines.size

    var at = 0
    var ll = 0
    var result = mutableListOf<ResultValue?>()
    breaks.forEach {
        val code = lines.subList(at, it).joinToString("\n")
        // Offload macro expansion (PSI parsing + AST rewriting) off the Framer
        val code2 = if (KotlinEngineConfig.offloadCompilation) {
            withContext(ki.compilationDispatcher) { ki.applyMacros(implicits[0] as Box, code) }
        } else {
            ki.applyMacros(implicits[0] as Box, code)
        }

        val code3 = code2.split("\n").mapIndexedNotNull { index, line ->
            ll++
            line.replace("__line__", "${ll - 1}")
        }.joinToString("\n")

        if (runSetupBlocks) {
            var setupFailed = false

            suspend fun runSetupBlock(
                name: String,
                execute: suspend (
                    (List<ScriptDiagnostic>) -> Unit,
                    (List<ScriptDiagnostic>) -> Unit,
                    (Throwable) -> Unit
                ) -> Unit
            ) {
                execute(
                    { diagnostics ->
                        setupFailed = true
                        diagnostics.forEach { lprintln("warning,macros", "$name compile: ${it.message} ${it.location}") }
                        onSetupCompileError(name, diagnostics)
                    },
                    { diagnostics ->
                        setupFailed = true
                        diagnostics.forEach { lprintln("warning,macros", "$name eval: ${it.message}") }
                        onSetupEvalError(name, diagnostics)
                    },
                    { throwable ->
                        setupFailed = true
                        lprintln("error,macros", "$name exception: ${throwable.message}")
                        onSetupEvalException(name, throwable)
                    }
                )
            }

            val box = implicits[0] as Box
            runSetupBlock("preamble") { c, e, x -> checkPreambleAndExecute(box, c, e, x) }
            if (setupFailed) return result
            runSetupBlock("introduction") { c, e, x -> checkIntroductionAndExecute(box, c, e, x) }
            if (setupFailed) return result
            runSetupBlock("attic") { c, e, x -> checkAtticAndExecute(box, c, e, x) }
            if (setupFailed) return result
        }
        val ret = ki.exec(code3, onCompileError, onEvalError, onEvalException, implicits)
        at = it
        result.add(ret)
        if (ret is ResultValue.Error) {
            return result
        }
    }
    return result
}

fun searchUpwards(f: HasHierarchy, name: String, found: (String?) -> Unit) {
    val a = f.getPropertyRaw(name)
    found(a as String?)
    val seen = mutableSetOf<HasHierarchy>()
    seen.add(f)

    var fringe = setOf(f)
    while (fringe.isNotEmpty()) {
        val nextFringe = fringe.flatMap { it.parents }.toSet() - seen
        seen.addAll(nextFringe)

        nextFringe.forEach {
            val a = it.getPropertyRaw(name)
            found(a as String?)
        }
        fringe = nextFringe
    }
}

/**
 * The text a box actually compiles against: its own preamble in full, plus — from every ancestor —
 * the `import` lines and the lines tagged with the trailing inherit marker (an empty block comment;
 * spelled out rather than written here, because a comment marker inside a KDoc nests). `prop`,
 * `@exported` and the FieldML host bridge all tag what they mean to publish; `moduleHere`'s
 * generated shader accessors deliberately do not, so they stay in the box whose shader declared
 * them. Documented for users in `public/docs/running_code.md` ("Property inheritance").
 *
 * The marker gate applies to every ancestor *uniformly*, including one whose `preamble` has never
 * been written. It did not used to: `isFirstBox` was cleared after the null check, so a box with no
 * preamble of its own never consumed the flag and the first ancestor that had a preamble was treated
 * as the starting box — its whole preamble, marker or no marker, was inherited, executed by
 * [checkPreambleAndExecute] and then written into the child permanently. Which of the two behaviours
 * a box got turned on whether anything had happened to read `Box.preamble` first (the `Delegation`
 * default writes `""` back on a bare read), so it was an ordering accident rather than a contract.
 */
fun gatherPreambleUpwards(startingAt: Box): String {
    var total = ""
    var isFirstBox = true
    searchUpwards(startingAt, "preamble") {
        val wasFirstBox = isFirstBox
        // Consume the flag whether or not this box has a preamble at all: an unset preamble is still
        // this box's turn in the walk, and must not hand "first box" status to an ancestor.
        isFirstBox = false
        if (it == null) return@searchUpwards
        it.split("\n").forEach { line ->
            // For the first box (startingAt), include all lines
            if (wasFirstBox) {
                if (line.trim().startsWith("import")) total = line + "\n" + total
                else total = total + "\n" + line
            } else {
                // For parent boxes, only include import statements and lines ending with /**/
                if (line.trim().startsWith("import")) {
                    total = line + "\n" + total
                } else if (line.trimEnd().endsWith("/**/")) {
                    total = total + "\n" + line
                }
            }
        }
    }


    total = total.replace("var Mesh.dim by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector2f>()", "\n")

    return total
}

suspend fun checkPreambleAndExecute(
    inside: Box,
    onCompileError: (List<ScriptDiagnostic>) -> Unit,
    onEvalError: (List<ScriptDiagnostic>) -> Unit,
    onEvalException: (Throwable) -> Unit
) {
    val _was = (inside.getPropertyRaw("_preamble", null) as String?)?.let(::normalizePreambleText)
    val was: Map<String, String> =
        if (_was == null) emptyMap<String, String>() else _was.preambleSegments()
            .prefixAwareMap()

    var now = normalizePreambleText(gatherPreambleUpwards(inside))


    val extra: MutableMap<String, String> = now.preambleSegments().prefixAwareMap()
    was.forEach {
        if (extra.containsKey(it.key)) {
            if (extra[it.key] != it.value) {
                // actually execute the extra if it's changed
            } else {
                extra.remove(it.key)
            }
        }
    }

    val extraLines = extra.values.joinToString(separator = "\n")
    if (extraLines.trim().isNotEmpty()) {
        var failed = false
        println(" preamble, extra lines are ${extraLines}, executing")
        inside.ki.withSourceProperty("preamble") {
            inside.ki.exec(
                extraLines,
                { failed = true; onCompileError(it) },
                { failed = true; onEvalError(it) },
                { failed = true; onEvalException(it) },
                implicits = listOf(inside)
            )
        }
        println(" finished ")
        if (failed) return
    }

    // remove duplicate lines in the order that they arrive

    val raw = normalizePreambleText(inside.getPropertyRaw("preamble", "") as String)
    val next = normalizePreambleText((extra + raw.preambleSegments().prefixAwareMap()).values.joinToString(separator = "\n"))
    inside.setPropertyRaw("_preamble", next)
    inside.setPropertyRaw("preamble", next)
}

suspend fun checkIntroductionAndExecute(
    inside: Box,
    onCompileError: (List<ScriptDiagnostic>) -> Unit,
    onEvalError: (List<ScriptDiagnostic>) -> Unit,
    onEvalException: (Throwable) -> Unit
) {
    val current = inside.getPropertyRaw("introduction", "") as String
    val was = inside.getPropertyRaw("_introduction", null) as String?

    // Execute all of introduction if and only if any of it has changed
    if (current.trim().isNotEmpty() && current != was) {
        var failed = false
        println(" introduction has changed, executing all of it")
        inside.ki.withSourceProperty("introduction") {
            inside.ki.exec(
                current,
                { failed = true; onCompileError(it) },
                { failed = true; onEvalError(it) },
                { failed = true; onEvalException(it) },
                implicits = listOf(inside)
            )
        }
        println(" finished introduction ")
        if (!failed) inside.setPropertyRaw("_introduction", current)
    }
}

suspend fun checkAtticAndExecute(
    inside: Box,
    onCompileError: (List<ScriptDiagnostic>) -> Unit,
    onEvalError: (List<ScriptDiagnostic>) -> Unit,
    onEvalException: (Throwable) -> Unit
) {
    val current = inside.getPropertyRaw("attic", "") as String
    val was = inside.getPropertyRaw("_attic", null) as String?

    val currentTrimmed = current.trim()
    val isEmpty = currentTrimmed.isEmpty()
    val isSame = current == was

    lprintln("info,attic", "checkAtticAndExecute for '${inside.name}' (${inside.id}): " +
            "empty=$isEmpty, same=$isSame, currentLen=${currentTrimmed.length}, wasLen=${was?.trim()?.length ?: -1}")

    if (!isEmpty && !isSame) {
        var failed = false
        lprintln("info,attic", "attic changed for '${inside.name}' — executing all of it (${currentTrimmed.lines().size} lines)")
        inside.ki.withSourceProperty("attic") {
            inside.ki.exec(
                current,
                { failed = true; onCompileError(it) },
                { failed = true; onEvalError(it) },
                { failed = true; onEvalException(it) },
                implicits = listOf(inside)
            )
        }
        if (!failed) {
            inside.setPropertyRaw("_attic", current)
            lprintln("info,attic", "attic execution complete for '${inside.name}', _attic cache updated")
        }
    } else if (isEmpty) {
        lprintln("info,attic", "attic is empty for '${inside.name}' — skipping")
    } else {
        lprintln("info,attic", "attic unchanged for '${inside.name}' — skipping (already executed)")
    }
}


// this is not an associateby - if we have prefixes, we want the _first_ one to count, not ones from parent boxes
private fun List<String>.prefixAwareMap(): MutableMap<String, String> {
    var m = mutableMapOf<String, String>()
    this.forEach {
        val p = it.prefixFor()
        var v = it
        if (m.containsKey(p)) {
        } else m[p] = v
    }
    return m
}

private const val preambleBlockStartMarker = "/*start*/"
private const val preambleBlockEndMarker = "/*end*/"

private fun String.preambleSegments(): List<String> {
    val segments = mutableListOf<String>()
    val currentBlock = mutableListOf<String>()
    var inBlock = false

    fun flushBlock() {
        if (currentBlock.isNotEmpty()) {
            segments += currentBlock.joinToString(separator = "\n")
            currentBlock.clear()
        }
        inBlock = false
    }

    this.lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        if (line.isBlank()) return@forEach

        if (inBlock) {
            currentBlock += line
            if (line.contains(preambleBlockEndMarker)) flushBlock()
            return@forEach
        }

        if (line.contains(preambleBlockStartMarker)) {
            inBlock = true
            currentBlock += line
            if (line.contains(preambleBlockEndMarker)) flushBlock()
        } else {
            segments += line
        }
    }

    flushBlock()
    return segments
}

private fun String.prefixFor(): String {
    val regex = Regex("^\\s*/\\*(.*?)\\*/")
    val match = this.lines()
        .asSequence()
        .map { it.replace(preambleBlockStartMarker, "").replace(preambleBlockEndMarker, "").trim() }
        .mapNotNull { regex.find(it) }
        .firstOrNull()
    return match?.groupValues?.get(1)?.trim() ?: this
}

object MacroRegistry {

    class MacroNotExpandedException(msg: String) : Exception(msg)
    class MacroExpansionProblem(msg: String) : Exception(msg)

    val callRewrite = mutableMapOf<String, (Box, String, List<String>) -> Insert?>()
    val callRewriteWithContext = mutableMapOf<String, (Box, String, List<String>, ContainingFunction?) -> Insert?>()

    fun rewrite(box: Box, identifier: String, arguments: List<String>, containingFunction: ContainingFunction? = null): Insert? {
        val a = callRewriteWithContext.get(identifier)?.invoke(box, identifier, arguments, containingFunction)
            ?: callRewrite.get(identifier)?.invoke(box, identifier, arguments)
        return a
    }

    init {

        registerLygiaIncludeRoot()
        callRewrite["moduleHere"] = { box, id, args ->
            box.macro_moduleHere(args)
        }
        callRewrite["meshModuleHere"] = { box, id, args ->
            box.macro_meshModuleHere(args)
        }
        callRewrite["computeModuleHere"] = { box, id, args ->
            box.macro_computeModuleHere(args)
        }
        callRewrite["fieldStatsHere"] = { box, id, args ->
            box.macro_fieldStatsHere(args)
        }
        callRewrite["shadertoyHere"] = { box, id, args ->
            box.macro_shadertoyHere(args)
        }
        callRewrite["prop"] = { box, id, args ->
            box.macro_prop(args)
        }
        callRewrite["rootProp"] = { box, id, args ->
            box.macro_root_prop(args)
        }
        callRewrite["_print"] = { box, id, args ->
            box.macro_printHere(args)
        }
        callRewrite["_println"] = { box, id, args ->
            box.marco_printlnHere(args)
        }
        callRewrite["print"] = { box, id, args ->
            box.macro_printHere(args)
        }
        callRewrite["println"] = { box, id, args ->
            box.marco_printlnHere(args)
        }
        callRewrite["sourceBlock"] = { box, id, args ->
            box.macro_sourceBlock(args)
        }

        runAll(macroRegistration)
    }
}

data class ContainingFunction(
    val name: String,
    val parameterList: String,
    val returnType: String
)

data class Insert(
    val start: String = "",
    val afterImports: String = "",
    val here: String = "",
    val standaloneHere: String? = null,
    val end: String = "",
    val preamble: String = "",
    val preambleAfterImports: String = "",
    val target: Box? = null
)
