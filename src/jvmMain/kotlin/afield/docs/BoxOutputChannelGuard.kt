package afield.docs

import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.system.exitProcess

/**
 * Catches the figure that is written and then immediately erased.
 *
 * `display(channel, …)` **replaces** that channel's contents rather than appending to them, so two
 * figures emitted to one channel from the same box leave only the second. Nothing fails, nothing
 * warns, and the box still looks like it worked — the last figure renders, so the loss is only
 * visible if you knew to expect two. Five of the eight boxes in `typography_worked_example` were in
 * this state, as was [afield.four.synth.showFaustPattern], whose own KDoc promised "a player, a
 * waveform" while the waveform overwrote the player on every call.
 *
 * The rule: **two figures in one box means two channels.** This guard reports any box that emits to
 * one channel twice.
 *
 * ## What counts as emitting
 *
 * `display(…)` itself, plus every `fun Box.<name>(… channel: String = "…" …)` helper found in
 * `src/jvmMain/kotlin` — the table is read from the source rather than hardcoded, so a new `show*`
 * helper is covered the day it is written. A helper whose overloads disagree about the default is
 * skipped rather than guessed at (`logs`, `inspect`).
 *
 * ## Why only top-level calls
 *
 * Only calls at brace depth 0 of the box's code are counted. Replacing a channel repeatedly is the
 * *correct* way to animate — `Box.showImg`'s KDoc calls one `display` per frame "the path an
 * animation wants" — so an `animate { }` body, a `for` loop or any other nested emitter is
 * deliberate refresh, not loss.
 *
 * Two emitters at depth 0 are a loss **only when that channel is not animated at all**. The
 * establish-animate-settle shape writes the same channel three times on purpose: once before the
 * loop to put the first state on screen, once per iteration inside it, and once after it so the
 * settled state is not up to one iteration stale. Its two depth-0 writes are the ends of one
 * animation, not two figures, and the first one's pixels very much do reach a screen — in
 * `confluence_grid` there is a `Thread.sleep(300)` immediately after it for exactly that reason.
 * So a channel that also receives a *nested* emission in the same box is treated as animated and
 * its depth-0 writes are not reported. (This guard reported that demo as a loss until 2026-09-19;
 * the rule above is what it was missing, not the depth-0 premise itself.)
 *
 * The cost of that narrowness is a known blind spot: `for (…) { showImg(…) }` followed by a
 * top-level `showMeshGrid(…)` on the same channel is a real collision this will not report, because
 * depth 0 sees only one emitter — and the animated-channel rule above deliberately widens that same
 * quiet spot rather than guessing which loops terminate. Both are the same trade: this guard reports
 * a channel only when nothing in the box could have been refreshing it.
 *
 * ## Populations
 *
 * Three: the `.yaml` box code under `dev_docs/agents/transcripts`, the `_code_.kt` box code under
 * `dev_docs/agents/demos`, and the library helpers themselves — a collision inside a `fun Box.…` is
 * worse than one in a transcript, since it ships to every caller.
 *
 * (Globs are spelled out in prose here on purpose: a `/` followed by a `*` inside a KDoc comment
 * opens a nested block comment, and Kotlin's nest, so one would swallow the rest of this file.)
 *
 * Run with `./gradlew checkBoxOutputChannels` (also part of `check` and `docsGates`).
 */
object BoxOutputChannelGuard {
    private const val TRANSCRIPTS_REL = "dev_docs/agents/transcripts"
    private const val DEMOS_REL = "dev_docs/agents/demos"
    private const val LIBRARY_REL = "src/jvmMain/kotlin"
    private val CREATE_VERBS = setOf("create", "new-box", "box")

    private val CALL_AT = Regex("""([A-Za-z_]\w*)\s*\(""")
    private val BOX_FUN = Regex("""\bfun\s+Box\.(\w+)\s*\(""")
    private val CHANNEL_DEFAULT = Regex("""channel:\s*String\s*=\s*"([^"]*)"""")
    private val CHANNEL_LITERAL_ARG = Regex("""channel\s*=\s*"([^"]*)"""")
    private val CHANNEL_NAMED_ARG = Regex("""\bchannel\s*=""")
    private val DISPLAY_LITERAL_ARG = Regex("""^\(\s*"([^"]*)"""")

    /** A channel a call resolves to, or null when the call names one this guard cannot read. */
    private data class Emission(val helper: String, val channel: String, val line: Int)

    /**
     * One box's emitters, split by the only distinction that matters here.
     *
     * [refreshed] holds every channel written from inside a brace — a loop body, an `animate { }`,
     * a lambda. A channel in it is being animated, so its [topLevel] writes bracket the animation
     * rather than losing a figure to it.
     */
    private data class Scan(val topLevel: List<Emission>, val refreshed: Set<String>)

    data class AuditResult(val boxes: Int, val emissions: Int, val problems: List<String>)

    @JvmStatic
    fun main(args: Array<String>) {
        val repo = repoRootFrom(File(System.getProperty("user.dir")))
        val result = audit(repo)
        if (result.problems.isNotEmpty()) {
            System.err.println("BoxOutputChannelGuard FAILED with ${result.problems.size} problem(s):")
            result.problems.forEach { System.err.println("  - $it") }
            System.err.println(
                "\ndisplay(channel, …) REPLACES a channel rather than appending, so the earlier figure is " +
                    "never seen. Give each figure its own channel and displayOpen() it so the card arrives " +
                    "expanded. A channel written repeatedly inside a loop or an animate { } block is a " +
                    "refresh and is not reported."
            )
            exitProcess(1)
        }
        println(
            "BoxOutputChannelGuard ok — ${result.boxes} box(es) of code, ${result.emissions} top-level " +
                "channel emission(s), no channel written twice"
        )
    }

    fun audit(repo: File): AuditResult {
        val problems = mutableListOf<String>()
        val helpers = helperChannelDefaults(File(repo, LIBRARY_REL))
        if (helpers.isEmpty()) {
            return AuditResult(0, 0, listOf("found no `fun Box.…(channel: String = \"…\")` helpers under $LIBRARY_REL"))
        }

        var boxes = 0
        var emissions = 0
        fun check(source: String, code: String, forwardedChannelName: String? = null) {
            boxes++
            val found = topLevelEmissions(code, helpers, forwardedChannelName)
            emissions += found.topLevel.size
            found.topLevel.groupBy { it.channel }
                .filterValues { it.size > 1 }
                .filterKeys { it !in found.refreshed }
                .toSortedMap()
                .forEach { (channel, clash) ->
                    val where = clash.joinToString(", ") { "${it.helper}() at line ${it.line}" }
                    problems.add(
                        "$source: $where all write channel '$channel'; only the last one is ever seen — " +
                            "give each figure its own channel"
                    )
                }
        }

        for ((source, code) in transcriptBoxCode(File(repo, TRANSCRIPTS_REL), problems)) check(source, code)
        for ((source, code) in demoBoxCode(File(repo, DEMOS_REL))) check(source, code)
        for ((source, code, param) in libraryHelperBodies(File(repo, LIBRARY_REL))) check(source, code, param)

        return AuditResult(boxes, emissions, problems.sorted())
    }

    // ---- the helper table, read from the library source ------------------------------------------

    /**
     * `helper name -> default channel`, for every `fun Box.…` that takes a `channel: String = "…"`.
     *
     * A name whose overloads disagree maps to null and is then ignored: `Box.logs` defaults to
     * "debug" in one overload and "internal logging" in another, and picking either would invent a
     * collision or hide one.
     */
    fun helperChannelDefaults(librarySource: File): Map<String, String?> {
        val seen = mutableMapOf<String, MutableSet<String?>>()
        for (file in kotlinFiles(librarySource)) {
            val text = file.readText()
            for (match in BOX_FUN.findAll(text)) {
                val params = balanced(text, match.range.last) ?: continue
                if (!params.contains("channel:")) continue
                val default = CHANNEL_DEFAULT.find(params)?.groupValues?.get(1)
                seen.getOrPut(match.groupValues[1]) { mutableSetOf() }.add(default)
            }
        }
        return seen.mapValues { (_, defaults) -> defaults.singleOrNull() }
    }

    // ---- the scanner ------------------------------------------------------------------------------

    /**
     * Every emitting call at brace depth 0 of [code], paired with the channel it resolves to.
     *
     * [forwardedChannelName] is the name of an in-scope channel parameter, set only when scanning a
     * library helper: inside `fun Box.showFaustPattern(… channel: String …)`, both `display(channel, …)`
     * and `showSignals(channel = channel, …)` target one channel whose value is unknown here, and
     * they still collide with each other. Box code has no such parameter, so it is null there and a
     * non-literal channel is simply skipped.
     */
    private fun topLevelEmissions(
        code: String,
        helpers: Map<String, String?>,
        forwardedChannelName: String?,
    ): Scan {
        val masked = maskCommentsAndStrings(code)
        val result = mutableListOf<Emission>()
        val refreshed = mutableSetOf<String>()
        var depth = 0
        var i = 0
        while (i < masked.length) {
            when (masked[i]) {
                '{' -> { depth++; i++; continue }
                '}' -> { depth--; i++; continue }
            }
            // A receiver-qualified call is somebody else's member: `report().lines()` is not Box.lines.
            val qualified = i > 0 && (masked[i - 1] == '.' || masked[i - 1] == '?')
            val call = CALL_AT.matchAt(masked, i)
            if (call == null) { i++; continue }
            val name = call.groupValues[1]
            // matchAt reports an absolute range, so this is already the index of the '('.
            val openParen = call.range.last
            val argsEnd = balancedEnd(masked, openParen)
            if (argsEnd == null) { i++; continue }
            if (!qualified && (name == "display" || name in helpers)) {
                val args = code.substring(openParen, (argsEnd + 1).coerceAtMost(code.length))
                val channel = resolveChannel(name, args, helpers, forwardedChannelName)
                if (channel != null) {
                    if (depth == 0) {
                        result.add(Emission(name, channel, code.take(i).count { it == '\n' } + 1))
                    } else {
                        refreshed.add(channel)
                    }
                }
            }
            // Step just inside the parens so nested calls are still visited.
            i = openParen + 1
        }
        return Scan(result, refreshed)
    }

    private fun resolveChannel(
        name: String,
        args: String,
        helpers: Map<String, String?>,
        forwardedChannelName: String?,
    ): String? {
        CHANNEL_LITERAL_ARG.find(args)?.let { return it.groupValues[1] }
        if (forwardedChannelName != null) {
            if (Regex("""channel\s*=\s*$forwardedChannelName\b""").containsMatchIn(args)) return "<the channel parameter>"
            if (name == "display" && Regex("""^\(\s*$forwardedChannelName\s*[,)]""").containsMatchIn(args)) {
                return "<the channel parameter>"
            }
        }
        // Any other spelling of `channel =` is a value this guard cannot read; say nothing about it.
        if (CHANNEL_NAMED_ARG.containsMatchIn(args)) return null
        if (name == "display") return DISPLAY_LITERAL_ARG.find(args)?.groupValues?.get(1)
        return helpers[name]
    }

    /**
     * Blanks out line comments and string literals so that braces, parens and the word `channel`
     * inside them do not count. Interpolations go with the string, which is right: a `${'$'}{…}` holding a
     * `showImg` call is not a top-level figure.
     */
    private fun maskCommentsAndStrings(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0
        while (i < code.length) {
            val c = code[i]
            if (c == '/' && i + 1 < code.length && code[i + 1] == '/') {
                while (i < code.length && code[i] != '\n') { out.append(' '); i++ }
                continue
            }
            if (c == '"') {
                val triple = code.startsWith("\"\"\"", i)
                val quote = if (triple) "\"\"\"" else "\""
                repeat(quote.length) { out.append(' ') }
                i += quote.length
                while (i < code.length && !code.startsWith(quote, i)) {
                    if (!triple && code[i] == '\\' && i + 1 < code.length) { out.append(' '); i++ }
                    out.append(if (code[i] == '\n') '\n' else ' ')
                    i++
                }
                if (i < code.length) { repeat(quote.length) { out.append(' ') }; i += quote.length }
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** The substring from the bracket at or after [from] through its match, or null if unbalanced. */
    private fun balanced(text: String, from: Int): String? {
        val open = text.indexOfAny(charArrayOf('(', '[', '{'), from)
        if (open < 0) return null
        val end = balancedEnd(text, open) ?: return null
        return text.substring(open, end + 1)
    }

    private fun balancedEnd(text: String, open: Int): Int? {
        if (open < 0 || open >= text.length) return null
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(', '[' -> depth++
                ')', ']' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    // ---- populations ------------------------------------------------------------------------------

    private fun transcriptBoxCode(dir: File, problems: MutableList<String>): List<Pair<String, String>> {
        if (!dir.isDirectory) {
            problems.add("no transcript corpus at ${dir.absolutePath}")
            return emptyList()
        }
        val result = mutableListOf<Pair<String, String>>()
        dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("yaml", "yml") }
            .sortedBy { it.name }
            .forEach { file ->
                val loaded = runCatching { Yaml().load<Any?>(file.readText()) }.getOrNull() ?: return@forEach
                val operations = when (loaded) {
                    is List<*> -> loaded
                    is Map<*, *> -> (loaded["operations"] as? List<*>) ?: (loaded["ops"] as? List<*>) ?: emptyList<Any?>()
                    else -> emptyList<Any?>()
                }
                for (rawOperation in operations) {
                    val operation = rawOperation as? Map<*, *> ?: continue
                    val verb = operation.keys.firstOrNull()?.toString()?.lowercase() ?: continue
                    if (verb !in CREATE_VERBS) continue
                    val payload = operation.values.firstOrNull() as? Map<*, *> ?: continue
                    val box = (payload["box"] ?: payload["uid"] ?: payload["id"])?.toString() ?: continue
                    val code = (payload["properties"] as? Map<*, *>)?.get("code")?.toString() ?: continue
                    result.add("${file.name}#$box" to code)
                }
            }
        return result
    }

    private fun demoBoxCode(dir: File): List<Pair<String, String>> {
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("_code_.kt") }
            .sortedBy { it.invariantSeparatorsPath }
            .map { "${it.parentFile.name}/${it.name}" to it.readText() }
            .toList()
    }

    /** Each `fun Box.…` body, with the name of its channel parameter when it has one. */
    private fun libraryHelperBodies(librarySource: File): List<Triple<String, String, String?>> {
        val result = mutableListOf<Triple<String, String, String?>>()
        for (file in kotlinFiles(librarySource)) {
            val text = file.readText()
            val relative = file.invariantSeparatorsPath.substringAfter("src/jvmMain/kotlin/")
            for (match in BOX_FUN.findAll(text)) {
                val paramsEnd = balancedEnd(text, match.range.last) ?: continue
                val params = text.substring(match.range.last, paramsEnd + 1)
                val hasChannelParam = Regex("""\bchannel:\s*String""").containsMatchIn(params)
                // Expression bodies delegate; only a braced body emits here.
                val brace = text.indexOf('{', paramsEnd)
                if (brace < 0 || text.substring(paramsEnd + 1, brace).contains(";")) continue
                if (text.substring(paramsEnd + 1, brace).length > 200) continue
                val bodyEnd = braceEnd(text, brace) ?: continue
                result.add(
                    Triple(
                        "$relative Box.${match.groupValues[1]}",
                        text.substring(brace + 1, bodyEnd),
                        if (hasChannelParam) "channel" else null,
                    )
                )
            }
        }
        return result
    }

    private fun braceEnd(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    private fun kotlinFiles(root: File): List<File> =
        if (!root.isDirectory) emptyList()
        else root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun repoRootFrom(start: File): File {
        var candidate: File? = start.absoluteFile
        while (candidate != null) {
            if (File(candidate, "public/docs").isDirectory && File(candidate, "src/jvmMain/kotlin").isDirectory) {
                return candidate
            }
            candidate = candidate.parentFile
        }
        return start.absoluteFile
    }
}
