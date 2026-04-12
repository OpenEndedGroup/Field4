package afield.macros

import afield.*
import afield.logger.lprintln
import afield.stdlib.*
import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic


suspend fun standardExecutionPractice(
    ki: KotlinScriptEngine,
    s: String,
    onCompileError: (List<ScriptDiagnostic>) -> Unit = { lprintln("error,macros", "onCompileError default handler $it") },
    onEvalError: (List<ScriptDiagnostic>) -> Unit = { lprintln("error,macros", "onEvalError default handler $it") },
    onEvalException: (Throwable) -> Unit = { lprintln("error,macros", "onEvalException default handler $it"); throw it },
    implicits: List<Any> = emptyList(),
    runSetupBlocks: Boolean = true
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
        val code2 = ki.applyMacros(implicits[0] as Box, code)

        val code3 = code2.split("\n").mapIndexedNotNull { index, line ->
            ll++
            line.replace("__line__", "${ll - 1}")
        }.joinToString("\n")

        if (runSetupBlocks) {
            // Use isolated error handlers for setup blocks (preamble/introduction/attic)
            // so that setup errors don't suppress the main code's return value display.
            // Setup errors are logged but don't propagate to the caller's hasError flag.
            val setupCompileError: (List<ScriptDiagnostic>) -> Unit = { diagnostics ->
                diagnostics.forEach { lprintln("warning,macros", "setup compile: ${it.message} ${it.location}") }
            }
            val setupEvalError: (List<ScriptDiagnostic>) -> Unit = { diagnostics ->
                diagnostics.forEach { lprintln("warning,macros", "setup eval: ${it.message}") }
            }
            val setupEvalException: (Throwable) -> Unit = { throwable ->
                lprintln("error,macros", "setup exception: ${throwable.message}")
            }
            checkPreambleAndExecute(implicits[0] as Box, setupCompileError, setupEvalError, setupEvalException)
            checkIntroductionAndExecute(implicits[0] as Box, setupCompileError, setupEvalError, setupEvalException)
            checkAtticAndExecute(implicits[0] as Box, setupCompileError, setupEvalError, setupEvalException)
        }
        val ret = ki.exec(code3, onCompileError, onEvalError, onEvalException, implicits)
        at = it
        result.add(ret)
        if (ret is ResultValue.Error) {
            return result
        }
        ret
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

fun gatherPreambleUpwards(startingAt: Box): String {
    var total = ""
    var isFirstBox = true
    searchUpwards(startingAt, "preamble") {
        if (it == null) return@searchUpwards
        it.split("\n").forEach { line ->
            // For the first box (startingAt), include all lines
            if (isFirstBox) {
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
        isFirstBox = false
    }


    total = total.replace("var Mesh.dim by Mesh.BoundFromStructure<org.joml.Vector2f>()", "\n")

    return total
}

fun checkPreambleAndExecute(
    inside: Box,
    onCompileError: (List<ScriptDiagnostic>) -> Unit,
    onEvalError: (List<ScriptDiagnostic>) -> Unit,
    onEvalException: (Throwable) -> Unit
) {
    val _was = inside.getPropertyRaw("_preamble", null) as String?
    val was: Map<String, String> =
        if (_was == null) emptyMap<String, String>() else _was.preambleSegments()
            .prefixAwareMap()

    var now = gatherPreambleUpwards(inside)


    inside.setPropertyRaw("_preamble", now)

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
    if (extraLines.trim().isNotEmpty()) runBlocking {
        println(" preamble, extra lines are ${extraLines}, executing")
        (inside as Box).ki.exec(
            extraLines, onCompileError, onEvalError, onEvalException, implicits = listOf(inside as Box)
        )
        println(" finished ")
    }

    // remove duplicate lines in the order that they arrive

    val raw = inside.getPropertyRaw("preamble", "") as String
    val next = (extra + raw.preambleSegments().prefixAwareMap()).values.joinToString(separator = "\n")
    inside.setPropertyRaw("_preamble", next)
    inside.setPropertyRaw("preamble", next)
}

fun checkIntroductionAndExecute(
    inside: Box,
    onCompileError: (List<ScriptDiagnostic>) -> Unit,
    onEvalError: (List<ScriptDiagnostic>) -> Unit,
    onEvalException: (Throwable) -> Unit
) {
    val current = inside.getPropertyRaw("introduction", "") as String
    val was = inside.getPropertyRaw("_introduction", null) as String?

    // Execute all of introduction if and only if any of it has changed
    if (current.trim().isNotEmpty() && current != was) {
        runBlocking {
            println(" introduction has changed, executing all of it")
            inside.ki.exec(
                current, onCompileError, onEvalError, onEvalException, implicits = listOf(inside)
            )
            println(" finished introduction ")
        }
        inside.setPropertyRaw("_introduction", current)
    }
}

fun checkAtticAndExecute(
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
        lprintln("info,attic", "attic changed for '${inside.name}' — executing all of it (${currentTrimmed.lines().size} lines)")
        runBlocking {
            inside.ki.exec(
                current, onCompileError, onEvalError, onEvalException, implicits = listOf(inside)
            )
        }
        inside.setPropertyRaw("_attic", current)
        lprintln("info,attic", "attic execution complete for '${inside.name}', _attic cache updated")
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

        callRewrite["moduleHere"] = { box, id, args ->
            box.macro_moduleHere(args)
        }
        callRewrite["meshModuleHere"] = { box, id, args ->
            box.macro_meshModuleHere(args)
        }
        callRewrite["computeModuleHere"] = { box, id, args ->
            box.macro_computeModuleHere(args)
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
    val end: String = "",
    val preamble: String = "",
    val preambleAfterImports: String = "",
    val target: Box? = null
)
