@file:Suppress("MemberVisibilityCanBePrivate", "unused")
@file:afield.CompletionCategory("Visualization")

package afield.petri

import afield.four.synth.PatPattern
import afield.four.synth.PatQ
import afield.four.synth.cat
import afield.four.synth.degrade
import afield.four.synth.fastBy
import afield.four.synth.pat
import afield.four.synth.palindrome
import afield.four.synth.rev
import afield.four.synth.slowcat
import afield.four.synth.stack
import afield.four.synth.stretch

/*
 The `#petri` text notation: a net you can retype while it is playing.

 The Kotlin DSL in `PetriNets.kt` is the complete surface — anything you can express, you can
 express there, including arbitrary lambdas. This file is the *terse* surface, and terseness is
 the point: live coding is editing, and editing a net is only pleasant if the whole net fits in
 view and one arc is one line.

     val net = petri("""
         #petri
         !grid 1/16

         kick : 1
         kick -[ hit  @1/16 : bd ]-> hats
         hats -[ back @1/16 : sd ]-> kick
     """)

 One line, one arc
 -----------------

     pre -[ name @dur key=value : emit => combinator ]-> post

 Both sides are `+`-joined lists of `weight*place`, and either may be empty — `-[ spawn ]-> p`
 is a source and `p -[ sink ]->` is a drain. A place is created the first time it is mentioned.
 A transition **may be named on more than one line**, which is how a transition with several
 inputs stays readable:

     verse  -[ build ]-> chorus
     energy -[ build ]-> chorus

 Prefixes on an input mark the two arcs that are not ordinary:

     ~armed  -[ fill ]-> hats        // inhibitor: only while `armed` is empty
     &clock  -[ tick ]-> bar         // read/test: needs a token, consumes none
     2*hats  -[ roll ]-> fill        // weight: needs two

 Place declarations
 ------------------

     kick   : 1              // one black token
     dst    : 0 ^4           // empty, and bounded at four
     verse  : "bd ~ sd ~"    // ONE token whose colour is that pattern — a section
     label  : 'lead'         // one token coloured with the literal string
     notes  : #60, #64, #67  // three numeric tokens

 The double-quote form is the interesting one, and the asymmetry is deliberate: a quoted string
 is a *pattern*, because a place holding a rhythm is what makes a net a song structure rather
 than a drum machine.

 Directives
 ----------

     !timing step | event | timed
     !grid 1/16
     !conflict order | priority | round-robin | random
     !seed 7

 Everything else
 ---------------

 `//` starts a comment. A leading `#petri` line is the editor's marker and is ignored here.
 Colour transforms after `=>` come from [PetriCombinators], a fixed vocabulary rather than
 compiled Kotlin — the notation stays fast to parse, and the cheatsheet stays finite and
 guardable. When you want a real lambda, that is what the Kotlin DSL is for.

 Errors are collected, not thrown at the first one: [petriParse] hands back everything it found so
 an editor can underline all of it at once. [petri] is the throwing convenience on top.
*/

// ── diagnostics ────────────────────────────────────────────────────────────────────────────

/** Where something is in the source. Offsets are into the whole string; [line] and [column] are 1-based. */
data class PetriSpan(val start: Int, val end: Int, val line: Int, val column: Int, val text: String) {
    override fun toString(): String = "$line:$column '$text'"
}

enum class PetriSeverity { ERROR, WARNING }

/** One complaint about a source, with the span to underline. */
data class PetriDiagnostic(val message: String, val span: PetriSpan, val severity: PetriSeverity = PetriSeverity.ERROR) {
    override fun toString(): String = "${severity.name.lowercase()} at ${span.line}:${span.column}: $message"
}

/**
 * What [petriParse] found: the net if it could build one, and everything it wants to say.
 *
 * The span maps are what an editor hovers and jumps with, and what the visualizer uses to trace a
 * firing back to the line that declared it.
 */
class PetriParseResult(
    val net: PetriNet?,
    val diagnostics: List<PetriDiagnostic>,
    val placeSpans: Map<String, PetriSpan> = emptyMap(),
    val transitionSpans: Map<String, PetriSpan> = emptyMap(),
) {
    val errors: List<PetriDiagnostic> get() = diagnostics.filter { it.severity == PetriSeverity.ERROR }
    val warnings: List<PetriDiagnostic> get() = diagnostics.filter { it.severity == PetriSeverity.WARNING }
    val ok: Boolean get() = net != null && errors.isEmpty()

    override fun toString(): String =
        "PetriParseResult(${if (ok) "ok" else "${errors.size} errors"}, ${warnings.size} warnings)"
}

// ── the combinator vocabulary ──────────────────────────────────────────────────────────────

/**
 * The named colour transforms the notation's `=>` can reach.
 *
 * Each takes the tokens a firing consumed and returns the colour of the token it produces. The set
 * is fixed on purpose — see the file header — and every name in it is required by the docs coverage
 * guard to have a worked example, so this map and `petri_cheatsheet.md` cannot drift apart.
 */
object PetriCombinators {

    private fun patterns(binding: PetriBinding): List<PatPattern<Any?>> =
        binding.consumed.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            it.colour as? PatPattern<Any?>
        }

    private fun firstPattern(binding: PetriBinding): PatPattern<Any?>? = patterns(binding).firstOrNull()

    private val table: Map<String, (PetriBinding) -> Any?> = linkedMapOf(
        // The default, spelled out so it can be written explicitly.
        "carry" to { b -> b.colour() },
        "black" to { _ -> null },
        "swap" to { b -> b.consumed.getOrNull(1)?.colour ?: b.colour() },

        // Pattern algebra over the consumed tokens.
        "stack" to { b -> patterns(b).takeIf { it.isNotEmpty() }?.let { stack(*it.toTypedArray()) } ?: b.colour() },
        "cat" to { b -> patterns(b).takeIf { it.isNotEmpty() }?.let { slowcat(*it.toTypedArray()) } ?: b.colour() },
        "fastcat" to { b -> patterns(b).takeIf { it.isNotEmpty() }?.let { cat(*it.toTypedArray()) } ?: b.colour() },
        "rev" to { b -> firstPattern(b)?.rev() ?: b.colour() },
        "palindrome" to { b -> firstPattern(b)?.palindrome() ?: b.colour() },
        "degrade" to { b -> firstPattern(b)?.degrade() ?: b.colour() },
        "fast2" to { b -> firstPattern(b)?.fastBy(PatQ.of(2)) ?: b.colour() },
        "slow2" to { b -> firstPattern(b)?.stretch(PatQ.of(2)) ?: b.colour() },

        // Numeric colours.
        "sum" to { b -> b.consumed.sumOf { (it.colour as? Number)?.toDouble() ?: 0.0 } },
        "inc" to { b -> ((b.colour() as? Number)?.toDouble() ?: 0.0) + 1.0 },
        "count" to { b -> b.consumed.size.toDouble() },
    )

    /** Every combinator name, in the order the cheatsheet lists them. */
    val names: List<String> get() = table.keys.toList()

    /** The transform called [name], or null if there is no such word. */
    operator fun get(name: String): ((PetriBinding) -> Any?)? = table[name]

    /** A one-line description of each word, for editor hovers and the cheatsheet. */
    val docs: Map<String, String> = linkedMapOf(
        "carry" to "the first consumed colour, unchanged — the default",
        "black" to "no colour: produce a plain black token",
        "swap" to "the second consumed colour, falling back to the first",
        "stack" to "all consumed patterns layered on top of each other",
        "cat" to "all consumed patterns, one per cycle",
        "fastcat" to "all consumed patterns squeezed side by side into one cycle",
        "rev" to "the first consumed pattern, reversed",
        "palindrome" to "the first consumed pattern, then its reverse",
        "degrade" to "the first consumed pattern with half its events dropped",
        "fast2" to "the first consumed pattern at double speed",
        "slow2" to "the first consumed pattern at half speed",
        "sum" to "the numeric consumed colours added together",
        "inc" to "the first numeric consumed colour plus one",
        "count" to "how many tokens were consumed",
    )
}

// ── parsing ────────────────────────────────────────────────────────────────────────────────

/** Builds a net from the `#petri` notation, throwing on the first error. See [petriParse]. */
fun petri(source: String): PetriNet {
    val result = petriParse(source)
    result.net?.takeIf { result.errors.isEmpty() }?.let { return it }
    val first = result.errors.firstOrNull()
    throw IllegalArgumentException(
        "petri notation: ${first?.message ?: "could not build a net"}" +
            (first?.let { " (line ${it.span.line}, column ${it.span.column})" } ?: "") +
            if (result.errors.size > 1) " — and ${result.errors.size - 1} more" else ""
    )
}

/**
 * Parses the `#petri` notation, collecting every complaint rather than stopping at the first.
 *
 * Returns a net whenever one can be built at all; a source with errors in it may still yield a
 * partial net, which is what a live-coding editor wants — the last good thing keeps playing while
 * you are halfway through typing the next.
 */
fun petriParse(source: String): PetriParseResult = PetriParser(source).parse()

private class PetriParser(private val source: String) {

    private val diagnostics = mutableListOf<PetriDiagnostic>()
    private val placeSpans = LinkedHashMap<String, PetriSpan>()
    private val transitionSpans = LinkedHashMap<String, PetriSpan>()

    private val declared = LinkedHashMap<String, Int?>()          // place -> capacity
    private val marking = LinkedHashMap<String, MutableList<PetriToken>>()
    private val specs = LinkedHashMap<String, TransitionSpec>()

    private var timing = PetriTiming.STEP
    private var grid = PatQ.of(1, 16)
    private var conflict = PetriConflict.PRIORITY
    private var seed = 0

    private class TransitionSpec(val name: String, val span: PetriSpan) {
        val inputs = mutableListOf<PetriInput>()
        val outputs = mutableListOf<PetriOutput>()
        var duration: PatQ = PatQ.ZERO
        var delay: PatQ = PatQ.ZERO
        var priority: Int = 0
        var weight: Double = 1.0
        var emit: Any? = null
        var combinator: ((PetriBinding) -> Any?)? = null
        var modifiersSeen = false
    }

    fun parse(): PetriParseResult {
        var offset = 0
        for ((index, raw) in source.lines().withIndex()) {
            val line = raw.substringBefore("//")
            parseLine(line, index + 1, offset)
            offset += raw.length + 1
        }
        val net = buildNet()
        return PetriParseResult(net, diagnostics, placeSpans, transitionSpans)
    }

    // ── lines ──────────────────────────────────────────────────────────────────────────────

    private fun parseLine(line: String, lineNumber: Int, lineOffset: Int) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.equals("#petri", ignoreCase = true) || trimmed.startsWith("#petri ")) return
        when {
            trimmed.startsWith("!") -> parseDirective(trimmed, line, lineNumber, lineOffset)
            ARC_RE.containsMatchIn(line) -> parseArc(line, lineNumber, lineOffset)
            line.contains(':') -> parsePlaceDeclaration(line, lineNumber, lineOffset)
            else -> error("expected a place declaration ('name : 1'), an arc ('a -[ t ]-> b') or a '!' directive", span(line, lineNumber, lineOffset))
        }
    }

    private fun parseDirective(trimmed: String, line: String, lineNumber: Int, lineOffset: Int) {
        val parts = trimmed.removePrefix("!").trim().split(Regex("\\s+"), limit = 2)
        val key = parts.firstOrNull().orEmpty().lowercase()
        val value = parts.getOrNull(1)?.trim().orEmpty()
        val where = span(line, lineNumber, lineOffset)
        when (key) {
            "timing" -> when (value.lowercase()) {
                "step" -> timing = PetriTiming.STEP
                "event" -> timing = PetriTiming.EVENT
                "timed" -> timing = PetriTiming.TIMED
                else -> error("!timing must be step, event or timed — not '$value'", where)
            }
            "grid" -> parseRational(value, where)?.let {
                if (it > PatQ.ZERO) grid = it else error("!grid must be greater than zero", where)
            }
            "conflict" -> when (value.lowercase()) {
                "order" -> conflict = PetriConflict.ORDER
                "priority" -> conflict = PetriConflict.PRIORITY
                "round-robin", "roundrobin" -> conflict = PetriConflict.ROUND_ROBIN
                "random" -> conflict = PetriConflict.RANDOM
                else -> error("!conflict must be order, priority, round-robin or random — not '$value'", where)
            }
            "seed" -> value.toIntOrNull()?.let { seed = it }
                ?: error("!seed needs a whole number, not '$value'", where)
            else -> error("unknown directive '!$key' — try timing, grid, conflict or seed", where)
        }
    }

    /** `name : <items> [^capacity]` */
    private fun parsePlaceDeclaration(line: String, lineNumber: Int, lineOffset: Int) {
        val colon = line.indexOf(':')
        val nameText = line.substring(0, colon).trim()
        val where = span(line, lineNumber, lineOffset)
        if (!nameText.matches(NAME_RE)) {
            error("'$nameText' is not a usable place name (letters, digits, . _ - and no spaces)", where)
            return
        }
        val tokens = mutableListOf<PetriToken>()
        var capacity: Int? = null

        for (item in splitTop(line.substring(colon + 1), ',')) {
            val text = item.trim()
            if (text.isEmpty()) continue
            if (text.startsWith("^")) {
                capacity = text.drop(1).trim().toIntOrNull()
                    ?.also { if (it <= 0) error("a capacity must be greater than zero", where) }
                    ?: run { error("'$text' is not a capacity — write ^4", where); null }
                continue
            }
            // `0 ^4` puts the count and the capacity in the same comma-separated item. Only an
            // unquoted item may be split on whitespace: a pattern is full of spaces, and cutting
            // `"bd ~ sd ~"` at the first one leaves an unterminated string.
            var head = text
            if (!text.startsWith("\"") && !text.startsWith("'")) {
                val parts = text.split(Regex("\\s+"), limit = 2)
                head = parts[0]
                val tail = parts.getOrNull(1)?.trim()
                if (tail != null) {
                    if (tail.startsWith("^")) {
                        capacity = tail.drop(1).trim().toIntOrNull()
                            ?: run { error("'$tail' is not a capacity — write ^4", where); null }
                    } else {
                        error("did not understand '$tail' — separate several tokens with commas", where)
                    }
                }
            }
            tokens += placeItem(head, where) ?: continue
        }

        declarePlace(nameText, capacity, where)
        marking.getOrPut(nameText) { mutableListOf() } += tokens
    }

    private fun placeItem(text: String, where: PetriSpan): List<PetriToken>? = when {
        text.isEmpty() -> emptyList()
        text.startsWith("\"") ->
            unquote(text, '"', where)?.let { listOf(PetriToken(pat(it))) }
        text.startsWith("'") ->
            unquote(text, '\'', where)?.let { listOf(PetriToken(it)) }
        text.startsWith("#") ->
            text.drop(1).toDoubleOrNull()?.let { listOf(PetriToken(it)) }
                ?: run { error("'$text' is not a number — write #60", where); null }
        else -> text.toIntOrNull()?.let { count ->
            if (count < 0) {
                error("a place cannot start with $count tokens", where); null
            } else List(count) { blackToken() }
        } ?: run {
            error("'$text' is not a token count, a \"pattern\", a 'string' or a #number", where); null
        }
    }

    /** `pre -[ name modifiers ]-> post` */
    private fun parseArc(line: String, lineNumber: Int, lineOffset: Int) {
        val match = ARC_RE.find(line)!!
        val where = span(line, lineNumber, lineOffset)
        val pre = line.substring(0, match.range.first)
        val post = line.substring(match.range.last + 1)
        val inner = match.groupValues[1].trim()

        if (inner.isEmpty()) {
            error("a transition needs a name — write -[ hit ]->", where)
            return
        }

        val head = inner.takeWhile { !it.isWhitespace() }
        if (!head.matches(NAME_RE)) {
            error("'$head' is not a usable transition name (letters, digits, . _ - and no spaces)", where)
            return
        }
        val bracketColumn = match.range.first + 1
        val nameSpan = PetriSpan(
            lineOffset + bracketColumn, lineOffset + bracketColumn + head.length, lineNumber, bracketColumn + 1, head
        )
        val spec = specs.getOrPut(head) { TransitionSpec(head, nameSpan) }
        transitionSpans.putIfAbsent(head, nameSpan)

        parseModifiers(inner.drop(head.length), spec, where)

        for (term in splitTop(pre, '+')) {
            val text = term.trim()
            if (text.isEmpty()) continue
            parseInput(text, where)?.let { spec.inputs += it }
        }
        for (term in splitTop(post, '+')) {
            val text = term.trim()
            if (text.isEmpty()) continue
            parseOutput(text, where)?.let { spec.outputs += it }
        }
    }

    /** `@dur`, `key=value`, `: emit`, `=> combinator` — in any order, after the name. */
    private fun parseModifiers(rest: String, spec: TransitionSpec, where: PetriSpan) {
        var text = rest.trim()
        if (text.isEmpty()) return
        if (spec.modifiersSeen) {
            warn("transition '${spec.name}' already had its settings on an earlier line; these are ignored", where)
            return
        }

        // `: emit` and `=> combinator` run to the end of the bracket, so peel them off first.
        val arrowAt = text.indexOf("=>")
        if (arrowAt >= 0) {
            val word = text.substring(arrowAt + 2).trim().substringBefore(':').trim()
            val combinator = PetriCombinators[word]
            if (combinator == null) {
                error(
                    "'$word' is not a combinator — try ${PetriCombinators.names.joinToString(", ")}",
                    where,
                )
            } else {
                spec.combinator = combinator
            }
            val emitPart = text.substring(arrowAt + 2).substringAfter(':', "").trim()
            text = text.substring(0, arrowAt).trim()
            if (emitPart.isNotEmpty()) spec.emit = parseEmit(emitPart, where)
        }
        val colonAt = text.indexOf(':')
        if (colonAt >= 0) {
            spec.emit = parseEmit(text.substring(colonAt + 1).trim(), where)
            text = text.substring(0, colonAt).trim()
        }

        for (token in text.split(Regex("\\s+")).filter { it.isNotBlank() }) {
            when {
                token.startsWith("@") -> parseRational(token.drop(1), where)?.let { spec.duration = it }
                token.contains('=') -> {
                    val key = token.substringBefore('=').lowercase()
                    val value = token.substringAfter('=')
                    when (key) {
                        "delay" -> parseRational(value, where)?.let { spec.delay = it }
                        "pri", "priority" -> value.toIntOrNull()?.let { spec.priority = it }
                            ?: error("pri= needs a whole number, not '$value'", where)
                        "w", "weight" -> value.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { spec.weight = it }
                            ?: error("w= needs a number greater than zero, not '$value'", where)
                        else -> error("unknown setting '$key=' — try delay=, pri= or w=", where)
                    }
                }
                else -> error("did not understand '$token' inside -[ … ]-", where)
            }
        }
        spec.modifiersSeen = true
    }

    private fun parseEmit(text: String, where: PetriSpan): Any? = when {
        text.isEmpty() || text == "~" -> null
        text.startsWith("\"") || text.startsWith("'") -> unquote(text, text[0], where)
        text.startsWith("#") -> text.drop(1).toDoubleOrNull()
            ?: run { error("'$text' is not a number — write #60", where); null }
        else -> text
    }

    /** `[~&][N*]place` */
    private fun parseInput(text: String, where: PetriSpan): PetriInput? {
        val kind = when (text.first()) {
            '~' -> PetriArcKind.INHIBIT
            '&' -> PetriArcKind.READ
            else -> PetriArcKind.NORMAL
        }
        val body = if (kind == PetriArcKind.NORMAL) text else text.drop(1).trim()
        val (weight, name) = splitWeight(body, where) ?: return null
        declarePlace(name, null, where)
        return PetriInput(name, weight, kind)
    }

    /** `[N*]place` */
    private fun parseOutput(text: String, where: PetriSpan): PetriOutput? {
        if (text.first() == '~' || text.first() == '&') {
            error("'${text.first()}' only makes sense on the left of a transition", where)
            return null
        }
        val (weight, name) = splitWeight(text, where) ?: return null
        declarePlace(name, null, where)
        return PetriOutput(name, weight)
    }

    private fun splitWeight(text: String, where: PetriSpan): Pair<Int, String>? {
        val star = text.indexOf('*')
        val weight = if (star < 0) 1 else text.substring(0, star).trim().toIntOrNull()
            ?: run { error("'${text.substringBefore('*')}' is not an arc weight — write 2*hats", where); return null }
        if (weight < 1) {
            error("an arc weight must be at least 1", where)
            return null
        }
        val name = text.substring(star + 1).trim()
        if (!name.matches(NAME_RE)) {
            error("'$name' is not a usable place name (letters, digits, . _ - and no spaces)", where)
            return null
        }
        return weight to name
    }

    private fun declarePlace(name: String, capacity: Int?, where: PetriSpan) {
        val existing = declared[name]
        declared[name] = capacity ?: existing
        placeSpans.putIfAbsent(name, where)
    }

    // ── assembly ───────────────────────────────────────────────────────────────────────────

    private fun buildNet(): PetriNet? {
        if (declared.isEmpty() && specs.isEmpty()) return null

        val transitions = specs.values.map { spec ->
            val combinator = spec.combinator
            val outputs =
                if (combinator == null) spec.outputs
                else spec.outputs.map { arc ->
                    PetriOutput(arc.place, arc.weight) { binding ->
                        List(arc.weight) { PetriToken(combinator(binding), binding.at) }
                    }
                }
            if (spec.inputs.isEmpty() && spec.outputs.isEmpty()) {
                warn("transition '${spec.name}' has no arcs, so it can never do anything", spec.span)
            }
            PetriTransition(
                name = spec.name,
                inputs = spec.inputs.toList(),
                outputs = outputs,
                duration = spec.duration,
                delay = spec.delay,
                priority = spec.priority,
                weight = spec.weight,
                emit = spec.emit,
            )
        }

        return try {
            PetriNet(
                places = declared.map { (name, capacity) -> PetriPlace(name, capacity) },
                transitions = transitions,
                initial = PetriMarking.of(marking),
                timing = timing,
                grid = grid,
                conflict = conflict,
                seed = seed,
            )
        } catch (ex: IllegalArgumentException) {
            error(ex.message ?: "could not build the net", PetriSpan(0, 0, 1, 1, ""))
            null
        }
    }

    // ── small helpers ──────────────────────────────────────────────────────────────────────

    private fun span(line: String, lineNumber: Int, lineOffset: Int): PetriSpan {
        val lead = line.length - line.trimStart().length
        val text = line.trim()
        return PetriSpan(lineOffset + lead, lineOffset + lead + text.length, lineNumber, lead + 1, text)
    }

    private fun error(message: String, span: PetriSpan) {
        diagnostics += PetriDiagnostic(message, span, PetriSeverity.ERROR)
    }

    private fun warn(message: String, span: PetriSpan) {
        diagnostics += PetriDiagnostic(message, span, PetriSeverity.WARNING)
    }

    private fun unquote(text: String, quote: Char, where: PetriSpan): String? {
        if (text.length < 2 || text.last() != quote) {
            error("unterminated $quote$quote string", where)
            return null
        }
        return text.substring(1, text.length - 1)
    }

    private fun parseRational(text: String, where: PetriSpan): PatQ? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            error("expected a number of cycles, like 1/16 or 4", where)
            return null
        }
        val slash = trimmed.indexOf('/')
        val numerator = (if (slash < 0) trimmed else trimmed.substring(0, slash)).trim().toLongOrNull()
        val denominator = if (slash < 0) 1L else trimmed.substring(slash + 1).trim().toLongOrNull()
        if (numerator == null || denominator == null || denominator == 0L) {
            error("'$trimmed' is not a number of cycles — write 4 or 1/16", where)
            return null
        }
        return PatQ.of(numerator, denominator)
    }

    /** Splits on [separator] but never inside quotes — so `'a,b'` stays one item. */
    private fun splitTop(text: String, separator: Char): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (character in text) {
            when {
                quote != null -> {
                    current.append(character)
                    if (character == quote) quote = null
                }
                character == '"' || character == '\'' -> {
                    quote = character
                    current.append(character)
                }
                character == separator -> {
                    out += current.toString()
                    current.clear()
                }
                else -> current.append(character)
            }
        }
        out += current.toString()
        return out
    }

    private companion object {
        val ARC_RE = Regex("""-\[(.*?)]->""")
        val NAME_RE = Regex("""[A-Za-z_][A-Za-z0-9_.\-]*""")
    }
}
