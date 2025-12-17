package afield.macros

import afield.Box
import afield.HasHierarchy
import afield.KotlinInterface
import afield.logger.lprintln
import afield.macroRegistration
import afield.macro_printHere
import afield.marco_printlnHere
import afield.runAll
import afield.stdlib.Delegation
import afield.stdlib.macro_computeModuleHere
import afield.stdlib.macro_meshModuleHere
import afield.stdlib.macro_moduleHere
import afield.stdlib.macro_prop
import afield.stdlib.macro_root_prop
import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic


suspend fun standardExecutionPractice(
    ki: KotlinInterface,
    s: String,
    onCompileError: (List<ScriptDiagnostic>) -> Unit = { lprintln(" onCompileError default handler $it") },
    onEvalError: (List<ScriptDiagnostic>) -> Unit = { lprintln(" onEvalError  default handler $it") },
    onEvalException: (Throwable) -> Unit = { lprintln(" onEvalException  default handler $it"); throw it },
    implicits: List<Any> = emptyList()
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

        checkPreambleAndExecute(implicits[0] as Box, onCompileError, onEvalError, onEvalException)
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
    searchUpwards(startingAt, "preamble") {
        if (it == null) return@searchUpwards
        it.split("\n").forEach {
            if (it.trim().startsWith("import")) total = it + "\n" + total
            else total = total + "\n" + it
        }
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
        if (_was == null) emptyMap<String, String>() else _was.split("\n").filter { it.trim().length > 0 }
            .prefixAwareMap()

    var now = gatherPreambleUpwards(inside)


    inside.setPropertyRaw("_preamble", now)

    val extra: MutableMap<String, String> = now.split("\n").filter { it.trim().length > 0 }.prefixAwareMap()
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
    val next = (extra + raw.split("\n").filter { it.trim().isNotEmpty() }.prefixAwareMap()).values.joinToString(separator = "\n")
    inside.setPropertyRaw("_preamble", next)
    inside.setPropertyRaw("preamble", next)
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

private fun String.prefixFor(): String {
    val regex = Regex("^\\s*/\\*(.*?)\\*/")
    val match = regex.find(this)
    return match?.groupValues?.get(1)?.trim() ?: this
}

object MacroRegistry {

    class MacroNotExpandedException(msg: String) : Exception(msg)
    class MacroExpansionProblem(msg: String) : Exception(msg)

    val callRewrite = mutableMapOf<String, (Box, String, List<String>) -> Insert?>()

    fun rewrite(box: Box, identifier: String, arguments: List<String>): Insert? {
        val a = callRewrite.get(identifier)?.invoke(box, identifier, arguments)
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

        runAll(macroRegistration)
    }
}

data class Insert(
    val start: String = "",
    val afterImports: String = "",
    val here: String = "",
    val end: String = "",
    val preamble: String = "",
    val preambleAfterImports: String = "",
    val target: Box? = null
)

