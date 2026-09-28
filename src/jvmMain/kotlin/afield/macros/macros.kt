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
fun gatherPreambleUpwards(startingAt: Box): String = gatherPreambleUpwardsReport(startingAt).text

/**
 * Two inheritable declarations of one extension property (`Box._x`, `Once.speed`) whose texts differ —
 * typically two ancestors `@exported` the same name with different types. [kept] is the one compiled;
 * [discarded] were dropped from the gathered text rather than left to the REPL's shadowing order.
 */
data class PreambleConflict(
    val receiverAndName: String,
    val kept: String,
    val keptSupplier: String?,
    val discarded: List<Pair<String, String?>>,
)

/**
 * [gatherPreambleUpwards]'s text plus what it left out: inherited lines whose supplier is no longer
 * this box or one of its ancestors ([stale]) and conflicting declarations ([conflicts]).
 */
class GatheredPreamble(val text: String, val stale: List<String>, val conflicts: List<PreambleConflict>)

/**
 * The walk behind [gatherPreambleUpwards], reporting what it dropped.
 *
 * **Supplier tags** (since 2026-09-29). Every inherit-marked line that comes down from an ancestor is
 * tagged with the ancestor it came from — a block comment `from:<uid>` placed immediately *before* the
 * trailing inherit marker, not at the start of the line, where a leading block comment is the dedupe
 * key that `prefixFor` and `applyInsertToPreamble` use. `@exported` writes its own declarations tagged
 * `from:self` ([PREAMBLE_SELF_SUPPLIER]), rewritten to the exporter's uid on the way down, so a copied
 * box does not carry its original's uid. A tagged line is kept only while its supplier is this box or
 * an ancestor of it: after a disconnect the declaration stops being compiled, stops being persisted
 * into this box by [checkPreambleAndExecute], and stops propagating to this box's children. Lines
 * persisted before the tags existed carry none, and are kept exactly as before.
 *
 * **Conflicts.** Two inheritable declarations of the same `Receiver.name` with different text keep the
 * one from the nearest supplier — this box's own `from:self` lines first, then by the supplier's
 * generation, then untagged lines of this box (whose origin is unknown, and which are usually a copy
 * persisted from an ancestor before the tags). That matches the value `afield.ContextResolution`
 * picks; the old REPL shadowing order picked the *farthest*.
 */
fun gatherPreambleUpwardsReport(startingAt: Box): GatheredPreamble {
    // BFS over parents, in the order searchUpwards visits them
    val depthOf = LinkedHashMap<Box, Int>()
    depthOf[startingAt] = 0
    var fringe = listOf(startingAt)
    var depth = 0
    while (fringe.isNotEmpty()) {
        depth++
        val next = mutableListOf<Box>()
        for (b in fringe) for (p in b.parents) if (p !in depthOf) {
            depthOf[p] = depth
            next += p
        }
        fringe = next
    }
    val depthById = HashMap<String, Int>()
    depthOf.forEach { (b, d) -> depthById.putIfAbsent(b.id, d) }

    val stale = mutableListOf<String>()
    var total = ""
    val entries = mutableListOf<Pair<String, Int>>() // inheritable line to its supplier rank, in text order
    for ((box, d) in depthOf) {
        val preamble = box.getPropertyRaw("preamble") as String? ?: continue
        val isFirstBox = d == 0
        preamble.split("\n").forEach { line ->
            if (line.trim().startsWith("import")) {
                total = line + "\n" + total
                return@forEach
            }
            val marked = line.trimEnd().endsWith("/**/")
            if (!marked) {
                // for parent boxes, only imports and inherit-marked lines come down
                if (isFirstBox) total = total + "\n" + line
                return@forEach
            }
            val tag = preambleSupplierTag(line)
            val out: String
            val rank: Int
            when {
                tag == null -> {
                    out = if (isFirstBox) line else withPreambleSupplierTag(line, box.id)
                    rank = if (isFirstBox) Int.MAX_VALUE else d
                }
                tag == PREAMBLE_SELF_SUPPLIER -> {
                    out = if (isFirstBox) line else withPreambleSupplierTag(line, box.id)
                    rank = d
                }
                tag !in depthById -> {
                    stale += line
                    return@forEach
                }
                else -> {
                    out = line
                    rank = depthById.getValue(tag)
                }
            }
            entries += out to rank
            total = total + "\n" + out
        }
    }

    // conflicting declarations of one Receiver.name: keep the nearest, drop the rest
    val conflicts = mutableListOf<PreambleConflict>()
    val byName = LinkedHashMap<String, MutableList<Pair<String, Int>>>()
    entries.forEach { e -> declaredExtensionName(e.first)?.let { byName.getOrPut(it) { mutableListOf() } += e } }
    val losers = HashSet<String>()
    byName.forEach { (name, lines) ->
        if (lines.distinctBy { normalizeDeclaration(it.first) }.size < 2) return@forEach
        val winner = lines.minByOrNull { it.second }!!
        val winnerText = normalizeDeclaration(winner.first)
        val lost = lines.filter { normalizeDeclaration(it.first) != winnerText }
        lost.forEach { losers += it.first }
        conflicts += PreambleConflict(
            name,
            winner.first,
            preambleSupplierTag(winner.first),
            lost.map { it.first to preambleSupplierTag(it.first) },
        )
    }
    if (losers.isNotEmpty()) {
        total = total.split("\n").filterNot { it in losers }.joinToString("\n")
    }

    total = total.replace("var Mesh.dim by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector2f>()", "\n")

    return GatheredPreamble(total, stale, conflicts)
}

/** The supplier a declaration `@exported` writes into its own box names: itself, whoever that is. */
const val PREAMBLE_SELF_SUPPLIER = "self"

private val supplierTagRegex = Regex("""/\*from:([^*/\s]+)\*/\s*/\*\*/\s*$""")

/** The uid in the `from:` tag just before a line's trailing inherit marker, or null. */
fun preambleSupplierTag(line: String): String? = supplierTagRegex.find(line)?.groupValues?.get(1)

/** [line] with its supplier tag removed; the inherit marker stays. */
fun stripPreambleSupplierTag(line: String): String =
    supplierTagRegex.find(line)?.let { line.substring(0, it.range.first).trimEnd() + " /**/" } ?: line

/** [line], an inherit-marked line, tagged as supplied by [uid] (replacing any tag it had). */
fun withPreambleSupplierTag(line: String, uid: String): String {
    val bare = stripPreambleSupplierTag(line).trimEnd()
    if (!bare.endsWith("/**/")) return line
    return bare.removeSuffix("/**/").trimEnd() + " /*from:" + uid + "*/ /**/"
}

private val extensionDeclarationRegex =
    Regex("""^\s*(?:@\S+\s+)*(?:val|var)\s+([A-Za-z_][\w.]*)\.([A-Za-z_]\w*)\b""")

private fun declaredExtensionName(line: String): String? =
    extensionDeclarationRegex.find(line)?.let { it.groupValues[1] + "." + it.groupValues[2] }

private fun normalizeDeclaration(line: String): String =
    stripPreambleSupplierTag(line).replace(Regex("\\s+"), " ").trim()

/**
 * [preamble] without the inherit-marked lines whose supplier tag names a box that is neither [box] nor
 * one of its ancestors — what [checkPreambleAndExecute] persists, so a disconnected supplier's
 * declarations leave the box instead of living in it for good.
 */
fun dropStaleSuppliedLines(preamble: String, box: Box): String {
    if ("/*from:" !in preamble) return preamble
    val live = HashSet<String>()
    live += PREAMBLE_SELF_SUPPLIER
    live += box.id
    val seen = HashSet<Box>()
    var fringe = listOf(box)
    while (fringe.isNotEmpty()) {
        val next = mutableListOf<Box>()
        for (b in fringe) for (p in b.parents) if (seen.add(p)) {
            live += p.id
            next += p
        }
        fringe = next
    }
    return preamble.lines().filterNot { line ->
        val tag = preambleSupplierTag(line)
        tag != null && tag !in live
    }.joinToString("\n")
}

private val reportedPreambleConflicts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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

    val gathered = gatherPreambleUpwardsReport(inside)
    var now = normalizePreambleText(gathered.text)

    // A declaration kept over a conflicting one is always re-executed: an earlier run may have
    // executed the loser after it, and in the REPL the last declaration is the one in force.
    val forced = gathered.conflicts.map { it.kept.trimEnd() }.toSet()
    gathered.conflicts.forEach { conflict ->
        val key = inside.id + "|" + conflict.receiverAndName + "|" + conflict.kept + "|" + conflict.discarded
        if (reportedPreambleConflicts.add(key)) {
            val from = conflict.keptSupplier?.let { " (from $it)" } ?: ""
            val others = conflict.discarded.joinToString("; ") { (line, supplier) ->
                stripPreambleSupplierTag(line).removeSuffix("/**/").trim() + (supplier?.let { " from $it" } ?: "")
            }
            val message = "preamble: '${conflict.receiverAndName}' is declared differently by more than one supplier; " +
                "using ${stripPreambleSupplierTag(conflict.kept).removeSuffix("/**/").trim()}$from, not $others"
            lprintln("warning,macros", "${inside.name.ifBlank { inside.id }}: $message")
            inside.println("warning: $message")
        }
    }

    val extra: MutableMap<String, String> = now.preambleSegments().prefixAwareMap()
    was.forEach {
        if (extra.containsKey(it.key)) {
            if (extra[it.key] != it.value || extra[it.key]!!.trimEnd() in forced) {
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

    val raw = normalizePreambleText(dropStaleSuppliedLines(inside.getPropertyRaw("preamble", "") as String, inside))
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
    // a supplier tag is provenance, not identity: one declaration from two suppliers is one line
    return match?.groupValues?.get(1)?.trim() ?: this.lines().joinToString("\n") { stripPreambleSupplierTag(it) }
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
