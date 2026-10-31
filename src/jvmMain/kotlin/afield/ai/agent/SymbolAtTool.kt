package afield.ai.agent

import afield.ai.tools.WorkspaceSymbolMatch
import afield.ai.tools.searchWorkspaceSymbols
import afield.app.protocol.SymbolContextRequest
import afield.document.graph.Box
import afield.runtime.completion.KotlinSourceIndex
import afield.runtime.kotlin.CompletionResult
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a name means at one place in a box's code, as the compiler sees it there: the editor's hover,
 * every completion candidate the compiler offers for the name (all overloads, with signatures and
 * return types), the live value if a run left one in this box or an ancestor, where the box itself
 * declares it, and the indexed Field declarations of that name with their KDoc and source.
 *
 * The answer depends on position: `curves` at the top of a box can be a library function while
 * `curves` inside a class the box declares is that class's member. Built 2026-10-28 after a local
 * model spent rounds searching the workspace for what one hover would have told it.
 */
@JsonClassDescription("Ask what a name means at a place in a box's code, the way the editor hover does, but with everything Field knows: the hover text, every candidate the compiler offers there (overloads with signatures and types), the live value from the box's last run (or its parents'), the lines where this box declares it, and indexed Field declarations of that name with KDoc and source. The answer depends on position: give `line`, or `near` (text on the line where it is used), or neither for its first use. Use it before guessing an API or searching the workspace for a name you can see in code. When the name is not found at that place (or `near` is missing from the code), it fails the position lookup, says so, and answers with what Field knows about the name regardless. e.g. box = `Writer`, symbol = `curves`, near = `world.curves(`.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.READ_DOCUMENT])
data class SymbolAtRequest(
    @param:JsonPropertyDescription("The box, by name or uid.")
    val box: String,
    @param:JsonPropertyDescription("The identifier to resolve, e.g. `color` or `prop1`; for `a.b.c` give the last part, `c`.")
    val symbol: String,
    @param:JsonPropertyDescription("Optional text that occurs where the name is used, e.g. `line.color =`; the name is resolved inside or right after it. Empty = no constraint.")
    val near: String = "",
    @param:JsonPropertyDescription("Optional 1-based line of the box's code to resolve the name on. 0 = not given.")
    val line: Int = 0,
    @param:JsonPropertyDescription("Which occurrence to use when the name (or `near`) appears more than once. 1 = the first.")
    val occurrence: Int = 1,
    @param:JsonPropertyDescription("Optional code to resolve in instead of the box's saved code, e.g. a draft you have not written yet. Empty = the box's code.")
    val code: String = "",
)

data class SymbolCandidate(val text: String, val signature: String, val type: String, val deprecated: String = "")

data class SymbolRuntimeValue(val box: String, val type: String, val value: String, val declaredAt: String)

data class SymbolAtResult(
    val success: Boolean,
    val message: String,
    val box: String = "",
    val symbol: String = "",
    val line: Int = 0,
    val column: Int = 0,
    val lineText: String = "",
    /** The editor hover for this position, as text. */
    val hover: String = "",
    /** Every completion candidate the compiler offers for this name at this position. */
    val candidates: List<SymbolCandidate> = emptyList(),
    /** Live values left by the last run, in this box first and then its ancestors. */
    val runtimeValues: List<SymbolRuntimeValue> = emptyList(),
    /** `N: text` for each line of this box's code that declares the name. */
    val declaredInBox: List<String> = emptyList(),
    /** Indexed Field declarations with this name (KDoc, source line, body), matched by name. */
    val fieldDeclarations: List<WorkspaceSymbolMatch> = emptyList(),
)

internal object SymbolAt {
    data class Place(val offset: Int, val line: Int, val column: Int)

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun wordAt(code: String, symbol: String, from: Int, until: Int = code.length): Int? {
        var i = code.indexOf(symbol, from)
        while (i in 0 until until) {
            val before = i == 0 || !isIdent(code[i - 1])
            val after = i + symbol.length >= code.length || !isIdent(code[i + symbol.length])
            if (before && after) return i
            i = code.indexOf(symbol, i + 1)
        }
        return null
    }

    /** Where [symbol] is used in [code]: on [line], inside or after the [occurrence]th [near], or its [occurrence]th use. */
    fun locate(code: String, symbol: String, near: String = "", line: Int = 0, occurrence: Int = 1): Place? {
        if (symbol.isBlank()) return null
        val n = occurrence.coerceAtLeast(1)
        val offset = when {
            line > 0 -> {
                val starts = listOf(0) + code.indices.filter { code[it] == '\n' }.map { it + 1 }
                val start = starts.getOrNull(line - 1) ?: return null
                val end = code.indexOf('\n', start).let { if (it < 0) code.length else it }
                var at = wordAt(code, symbol, start, end)
                repeat(n - 1) { at = at?.let { wordAt(code, symbol, it + 1, end) } }
                at
            }
            near.isNotBlank() -> {
                var at = code.indexOf(near)
                repeat(n - 1) { at = if (at < 0) -1 else code.indexOf(near, at + 1) }
                if (at < 0) null else wordAt(code, symbol, at)
            }
            else -> {
                var at = wordAt(code, symbol, 0)
                repeat(n - 1) { at = at?.let { wordAt(code, symbol, it + 1) } }
                at
            }
        } ?: return null
        val lineNo = 1 + code.substring(0, offset).count { it == '\n' }
        return Place(offset, lineNo, offset - code.lastIndexOf('\n', offset - 1))
    }

    /** The hover's HTML as plain text. */
    fun htmlToText(html: String): String = html
        .replace(Regex("<br\\s*/?>|</(div|p|li|tr|h\\d)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .lines().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n")

    /** Lines of [code] that declare [symbol]: `fun`, `val`/`var`, `class`/`object`/`interface`, parameters excluded. */
    fun declarations(code: String, symbol: String): List<String> {
        val decl = Regex("""\b(fun|val|var|class|object|interface|typealias)\s+(<[^>]*>\s*)?([\w.]+\.)?${Regex.escape(symbol)}\b""")
        return code.lines().mapIndexedNotNull { i, text -> if (decl.containsMatchIn(text)) "${i + 1}: ${text.trim()}" else null }
    }

    /** The start of the identifier [offset] is a member of (`f` in `f.color`), or null when it is not after a `.`. */
    fun receiverStart(code: String, offset: Int): Int? {
        var i = offset - 1
        while (i >= 0 && code[i] == ' ') i--
        if (i < 0 || code[i] != '.') return null
        i--
        while (i >= 0 && code[i] == ' ') i--
        if (i < 0 || !isIdent(code[i])) return null
        while (i > 0 && isIdent(code[i - 1])) i--
        return i
    }

    /** `FLine` from a hover such as `f : FLine` or `val f: afield.geometry.path.FLine?`. */
    fun typeFromHover(hover: String): String? = hover.lineSequence().firstOrNull()
        ?.substringAfter(':', "")?.trim()?.substringBefore('<')?.substringBefore('=')?.trim()?.removeSuffix("?")
        ?.substringAfterLast('.')?.takeIf { it.isNotEmpty() && it.all(::isIdent) }

    private fun candidateName(text: String) = text.substringBefore('<').substringBefore('(').substringAfterLast('.').trim()

    private fun ancestors(box: Box): List<Box> {
        val seen = LinkedHashSet<Box>()
        var fringe = box.parents.toList()
        while (fringe.isNotEmpty() && seen.size < 64) {
            fringe = fringe.filter { seen.add(it) }.flatMap { it.parents }
        }
        return seen.toList()
    }

    fun describe(box: Box, req: SymbolAtRequest): SymbolAtResult {
        val symbol = req.symbol.trim().substringAfterLast('.')
        val code = req.code.ifBlank { box.getPropertyRaw("code") as? String ?: "" }
        val place = locate(code, symbol, req.near, req.line, req.occurrence)
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
        val byName = runCatching { searchWorkspaceSymbols(index, symbol, limit = 6).matches }.getOrDefault(emptyList())
            .filter { candidateName(it.symbol) == symbol }
        val runtime = (listOf(box) + ancestors(box)).mapNotNull { b ->
            b.ki.context.vars[symbol]?.let { v ->
                SymbolRuntimeValue(b.name.ifBlank { b.id }, runCatching { v.type }.getOrDefault("?"),
                    runCatching { v.value.toString() }.getOrDefault("?").take(1200), v.line.toString())
            }
        }
        val declared = declarations(code, symbol)
        if (place == null) {
            val where = when { req.line > 0 -> "on line ${req.line}"; req.near.isNotBlank() -> "after `${req.near}`"; else -> "" }
            return SymbolAtResult(false, "`$symbol` does not occur as a name $where in '${box.name}'s code, so nothing was resolved at a " +
                "position; what Field knows about the name regardless is below.", box.name, symbol,
                runtimeValues = runtime, declaredInBox = declared, fieldDeclarations = byName)
        }
        fun hoverAt(offset: Int, line: Int, column: Int) = runCatching {
            val response = runBlocking {
                withTimeoutOrNull(20_000) {
                    box.ke.describeSymbolContext(box, SymbolContextRequest(box.id, line, code, column, offset, forceRefresh = false))
                }
            }
            htmlToText(response?.html.orEmpty())
        }.getOrDefault("")
        val hover = hoverAt(place.offset + 1, place.line, place.column)
        // `f.color`: the index names extension and member declarations `FLine.color`, so a bare-name
        // lookup can miss the one that resolves here. Ask the hover for the receiver's type and look
        // that up first (found 2026-10-28: six `color`s came back, none of them `FLine.color`).
        val receiverType = receiverStart(code, place.offset)?.let { start ->
            val receiverLine = 1 + code.substring(0, start).count { it == '\n' }
            typeFromHover(hoverAt(start + 1, receiverLine, start - code.lastIndexOf('\n', start - 1)))
        }
        val qualified = receiverType?.let { type ->
            runCatching { searchWorkspaceSymbols(index, "$type.$symbol", limit = 3).matches }.getOrDefault(emptyList())
                .filter { it.symbol == "$type.$symbol" }
        }.orEmpty()
        val fieldDeclarations = (qualified + byName).distinctBy { it.locationHint }.take(8)
        val candidates = runCatching {
            val end = place.offset + symbol.length
            when (val completion = runBlocking { withTimeoutOrNull(20_000) { box.ki.complete(code, end) } }) {
                is CompletionResult.Success -> completion.metadata.filter { candidateName(it.text) == symbol }
                    .distinctBy { it.displayText + it.tail }
                    .map { SymbolCandidate(it.text, it.displayText, it.tail, it.deprecationLevel?.name.orEmpty()) }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        val lineText = code.lines().getOrElse(place.line - 1) { "" }.trim()
        val found = listOfNotNull(
            "hover".takeIf { hover.isNotBlank() },
            "${candidates.size} compiler candidate(s)".takeIf { candidates.isNotEmpty() },
            "a live value".takeIf { runtime.isNotEmpty() },
            "${declared.size} declaration(s) in this box".takeIf { declared.isNotEmpty() },
            "${fieldDeclarations.size} indexed Field declaration(s) named `$symbol`".takeIf { fieldDeclarations.isNotEmpty() },
        )
        val message = "`$symbol` at line ${place.line}, column ${place.column} of '${box.name}': " +
            (if (found.isEmpty()) "the compiler resolved nothing here and Field has no declaration of that name; check the spelling and imports."
            else found.joinToString(", ") + ". The candidates are what resolves at this position; " +
                (qualified.firstOrNull()?.let { "the first indexed declaration, `${it.symbol}`, matches the receiver's type `$receiverType`; the rest match by name only." }
                    ?: "indexed declarations match by name only."))
        return SymbolAtResult(true, message, box.name, symbol, place.line, place.column, lineText, hover, candidates, runtime, declared, fieldDeclarations)
    }
}
