@file:afield.CompletionCategory("Function")

package afield.stdlib

/**
 * The `selector => action` string rewrite DSL, compiled and applied end to end.
 * [compileFnRewrite] parses a spec into an [FnRewriteProgram] carrying line/column
 * [FnRewriteDiagnostic]s; [Functions.Fn.rewrite], [Functions.Fn.previewRewrite] (which also
 * reports match paths and which rules did nothing) and [Functions.Fn.rewriteChecked] (which
 * refuses an invalid program) run it over the graph copier. Selector matching lives here — op,
 * role, name, tag and depth tokens, `parent=`, `[key>=n]` predicates and slash-separated path
 * selectors with `*` and `**` wildcards — and so does action application: `op=`, `name=`,
 * `domain=`, `drop`, and the numeric `=`, `*=`, `/=`, `+=`, `-=` forms. `op=` targets are checked
 * against `OpRegistry`; an unknown op is reported as a warning, not an error.
 *
 * The file does not depend on the live-code compiler. A Kotlin right-hand side is
 * parsed out of its `@{...}` (or dollar-brace) wrapper and then handed to
 * [FnRewriteOptions.kotlinNumberContext]; when no caller supplied one, the action is simply not
 * applied. FunctionRewriteKotlin.kt is what plugs a box's Kotlin engine into that hook, and
 * FunctionRewritePattern.kt is the separate structural pattern-to-template surface.
 */

/**
 * Everything one matched node can tell a right-hand side about itself.
 *
 * Handed to a [FnRewriteNumberExpression] — an `@{...}` snippet or a [fnRewriteNumber] block — so the new
 * value can be computed from the old one and from where it sits: the current `value`, the node's op and
 * parameter name, its depth, and its occurrence path. Immutable, one instance per visited node.
 */
data class FnRewriteNumberContext(
    /**
     * The Kotlin source of the right-hand side, already unwrapped from its `@{...}` or dollar-brace
     * delimiters, and trimmed. An expression that carried no delimiters at all arrives verbatim.
     */
    val expression: String,
    /** The numeric value the action is about to change: the matched node's `value` metadata entry. */
    val current: Double,
    /** The matched occurrence itself — op, role, path, ancestry, domain and the node's scalar maps. */
    val graph: FnGraphCopyContext
) {
    /** [current] under the name a snippet actually writes, as in `*= @{value * 1.1}`. */
    val value: Double get() = current

    /**
     * [current] again, so a snippet can read like a lambda: `= @{it * 2}`. A plain property, not a
     * real lambda parameter — nothing is shadowing it, and `it` inside a nested lambda wins as usual.
     */
    val it: Double get() = current

    /** The op name of the matched node, from `OpRegistry`'s vocabulary. */
    val op: String get() = graph.op

    /** The role of the edge this node was reached by (`"frequency"`, `"amplitude"`, …), or null at the root. */
    val role: String? get() = graph.role

    /** Alias for [role], for rules that read better as "which parameter am I". */
    val parameter: String? get() = graph.role

    /** The immediate parent's op, or null at the root — "am I inside an fbm?" in one property. */
    val parentOp: String? get() = graph.parentOp

    /** How many edges below the root this occurrence sits; the root itself is `0`. */
    val depth: Int get() = graph.depth

    /** Child indices from the root down to this node — the same path a preview reports. */
    val path: List<Int> get() = graph.path

    /** The path elements from the root down to this node's parent, root first. Empty at the root. */
    val ancestors: List<FnGraphPathElement> get() = graph.ancestors

    /**
     * [ancestors] plus this node — the whole root-to-here path, which is what a path selector matches
     * against. Rebuilt on every read, so bind it to a local rather than indexing it repeatedly.
     */
    val occurrence: List<FnGraphPathElement> get() = graph.occurrence

    /** The matched function's own name, if it was given one. Null for the great majority of nodes. */
    val name: String? get() = graph.name

    /** The matched function's own domain, which can be narrower than the root's. */
    val domain: Functions.Domain get() = graph.domain

    /** This node's numeric metadata — its own parameters, keyed by name. A constant carries `value`. */
    val numbers: Map<String, Double> get() = graph.node.numbers

    /** This node's string metadata, for the ops that carry names or modes rather than numbers. */
    val strings: Map<String, String> get() = graph.node.strings

    /** This node's boolean metadata — flags such as a wrap or clamp mode. */
    val booleans: Map<String, Boolean> get() = graph.node.booleans

    /** This node's long metadata, typically seeds and counts. */
    val longs: Map<String, Long> get() = graph.node.longs

    /** One numeric metadata entry, or null if this node has no such key. */
    fun number(key: String): Double? = numbers[key]

    /** One string metadata entry, or null if this node has no such key. */
    fun string(key: String): String? = strings[key]

    /** One boolean metadata entry, or null if this node has no such key. */
    fun boolean(key: String): Boolean? = booleans[key]

    /** One long metadata entry, or null if this node has no such key. */
    fun long(key: String): Long? = longs[key]
}

/**
 * How a rewrite should be run: who evaluates a Kotlin right-hand side, and whether shared subgraphs
 * stay shared.
 *
 * The default is the compiler-free configuration, and that is a trap worth knowing: with no evaluator
 * installed, a rule whose action is a Kotlin expression is **not applied, and says nothing** — the rule still
 * matches, and a preview records it as `matched/no-op`. Pass an evaluator (or use `rewriteKotlin`,
 * which wires the live box's one in for you) when those actions are meant to run.
 */
data class FnRewriteOptions(
    /**
     * The older, flatter evaluator hook, taking the raw expression text. Consulted only when
     * [kotlinNumberContext] is absent *or* returns null, so it also works as a fallback under it.
     */
    val kotlinNumber: ((expression: String, current: Double, context: FnGraphCopyContext) -> Double?)? = null,
    /**
     * Whether a subgraph reached by two paths is copied once and shared, or expanded so each
     * occurrence can be edited on its own. False — expand — because that is what makes
     * path-sensitive selectors behave the way you would guess; see [Functions.Fn.deepCopy].
     */
    val preserveSharing: Boolean = false,
    /**
     * The preferred hook: given the matched node wrapped as an [FnRewriteNumberContext], return the
     * new number, or null to leave the value alone. Returning null falls through to [kotlinNumber].
     */
    val kotlinNumberContext: ((FnRewriteNumberContext) -> Double?)? = null
)

/**
 * Whether a diagnostic kills a rule or merely grumbles about it.
 *
 * `ERROR` means the rule was dropped at parse time and [Functions.Fn.rewriteChecked] will refuse the
 * whole spec; the surviving rules still run under plain [Functions.Fn.rewrite]. `WARNING` means the
 * rule was kept and will be applied — an `op=` target the `OpRegistry` does not know is the usual
 * case, and it ends up as a silent no-op at every node it matches.
 */
enum class FnRewriteSeverity { ERROR, WARNING }

/**
 * One complaint from the rewrite parser, addressed to a line and column of the spec so an editor or
 * an agent can point at it.
 *
 * Only the *action* side is checked this thoroughly. A selector is validated for balanced brackets
 * and nothing else, so a mistyped selector key produces no diagnostic at all and simply matches
 * nothing — [FnRewritePreview.unmatchedRules] is where that shows up instead.
 */
data class FnRewriteDiagnostic(
    /** Fatal for [FnRewriteProgram.valid], or advisory. See [FnRewriteSeverity]. */
    val severity: FnRewriteSeverity,
    /** One-based line within the spec. Rules separated by `;` on one line all report that line. */
    val line: Int,
    /**
     * One-based column. Mostly `1`, because most checks are about the statement as a whole; an empty
     * action is reported just past the arrow.
     */
    val column: Int,
    /** What is wrong, as a sentence. */
    val message: String,
    /** An optional nudge toward the fix — accepted arrow forms, valid domain syntax, viable ops. */
    val hint: String? = null
) {
    /** `severity at line:column: message (hint)` — the one-line form that goes into a report. */
    override fun toString(): String = buildString {
        append(severity.name.lowercase()).append(" at ").append(line).append(':').append(column).append(": ").append(message)
        hint?.let { append(" (").append(it).append(')') }
    }
}

/**
 * One parsed rule: the line it came from, the selector text on the left of the arrow, and the actions
 * on the right.
 *
 * Both sides are kept as **text** and interpreted per node at rewrite time; nothing here is a compiled
 * matcher. The actions are already split on top-level commas and pipes (both of which mean "then") and
 * are applied left to right to the same copied node, so `*= 2.0, += 1.0` adds one to the doubled value.
 */
data class FnRewriteRuleSpec(
    /** One-based line of the spec this rule was written on. */
    val line: Int,
    /** The raw selector text, trimmed — everything before the arrow. */
    val selector: String,
    /** The actions, in application order, each already trimmed and non-empty. */
    val actions: List<String>
)

/**
 * A compiled rewrite spec: the source you handed in, the rules that survived parsing, and every
 * diagnostic raised along the way.
 *
 * Produced by [compileFnRewrite], which needs no function graph at all — this is a pure check of the
 * language. Parsing is *partial by design*: a statement that raises an error is dropped and the rest
 * are kept, so `rules.size` can be smaller than the number of rules you wrote and a plain
 * [Functions.Fn.rewrite] will happily run the remainder. [Functions.Fn.rewriteChecked] is the entry
 * point that refuses to.
 */
data class FnRewriteProgram(
    /** The spec text exactly as it was passed in. */
    val source: String,
    /** The rules that parsed, in source order. This is the order they are tried at each node. */
    val rules: List<FnRewriteRuleSpec>,
    /** Every error and warning, in source order. */
    val diagnostics: List<FnRewriteDiagnostic>
) {
    /** True when nothing fatal was found. Warnings — an unknown `op=` target — do not make a program invalid. */
    val valid: Boolean get() = diagnostics.none { it.severity == FnRewriteSeverity.ERROR }

    /** The diagnostics as printable lines, or a one-line "no diagnostics" note with the rule count. */
    fun diagnosticText(): String = if (diagnostics.isEmpty()) "FnRewrite: ${rules.size} rule(s), no diagnostics"
    else diagnostics.joinToString("\n")
}

/**
 * One rule firing at one occurrence: which rule, where it landed, and whether it actually changed
 * anything.
 *
 * Recorded per *match*, so a rule that matches five nodes contributes five of these. Every rule is
 * tried at every node and each matching one is applied in turn to the same copy, so [beforeOp] is the
 * op as it stood after any earlier rule already ran here — not necessarily the op in the original graph.
 */
data class FnRewriteApplication(
    /** Zero-based index into [FnRewriteProgram.rules]. A summary prints it one-based, as `#1`. */
    val rule: Int,
    /** The one-based spec line of the rule, for reporting back at the source. */
    val line: Int,
    /** The rule's selector text, copied here so an application reads on its own. */
    val selector: String,
    /** The rule's actions, in the order they were applied to this node. */
    val actions: List<String>,
    /** Child indices from the graph root to the matched node; empty means the root itself. */
    val path: List<Int>,
    /** The role of the edge the matched node hangs from, or null at the root. */
    val role: String?,
    /** The op before this rule's actions ran here. */
    val beforeOp: String,
    /** The op afterwards. Usually the same: a naming edit, a numeric edit or a refused transmutation all leave it. */
    val afterOp: String,
    /**
     * Whether the node's structural signature actually moved. False is the correct report for a
     * transmutation the `OpRegistry` refused, a numeric action on a non-numeric node, a Kotlin
     * right-hand side with no evaluator installed, and `*= 1.0`.
     */
    val changed: Boolean
)

/**
 * The result of a rewrite *with its receipts*: the rewritten function, the compiled program, and one
 * [FnRewriteApplication] per match.
 *
 * Not a dry run. By the time you hold one of these the rewrite has been done and [function] is the
 * result — [Functions.Fn.rewrite] is literally this call with the report thrown away. What "preview"
 * buys you is evidence before you commit: matched paths, no-ops, and above all
 * [unmatchedRules], since a selector that matches nothing is otherwise completely silent.
 */
data class FnRewritePreview(
    /** The rewritten function. Always a fresh graph; the receiver of the rewrite is untouched. */
    val function: Functions.Fn,
    /** The compiled spec, including the diagnostics that did not stop it. */
    val program: FnRewriteProgram,
    /** Every rule/occurrence pair that fired, in traversal order (bottom-up, so children first). */
    val applications: List<FnRewriteApplication>
) {
    /** How many distinct rules found at least one node. Compare with `program.rules.size`. */
    val matchedRules: Int get() = applications.map { it.rule }.distinct().size

    /** Total matches across all rules — one per rule per occurrence, so easily larger than the rule count. */
    val matchCount: Int get() = applications.size

    /** How many of those matches changed the graph. The gap between this and [matchCount] is the no-ops. */
    val changeCount: Int get() = applications.count { it.changed }

    /**
     * The rules that never matched anything — the one place a mistyped selector becomes visible, since
     * the parser does not check selectors. Treat these as hypotheses to reconsider, not as success.
     */
    val unmatchedRules: List<FnRewriteRuleSpec> get() {
        val matched = applications.mapTo(mutableSetOf()) { it.rule }
        return program.rules.filterIndexed { index, _ -> index !in matched }
    }

    /** Compact deterministic report suitable for logs, chat, and agent transcripts. */
    fun summary(): String = buildString {
        append("FnRewrite: ").append(program.rules.size).append(" rule(s), ")
            .append(matchCount).append(" match(es), ").append(changeCount).append(" change(s)")
        if (program.diagnostics.isNotEmpty()) {
            append(", ").append(program.diagnostics.count { it.severity == FnRewriteSeverity.ERROR }).append(" error(s), ")
                .append(program.diagnostics.count { it.severity == FnRewriteSeverity.WARNING }).append(" warning(s)")
        }
        unmatchedRules.takeIf { it.isNotEmpty() }?.let { rules ->
            append("\nunmatched: ").append(rules.joinToString { "line ${it.line} `${it.selector}`" })
        }
        program.diagnostics.forEach { append('\n').append(it) }
        applications.forEach { application ->
            append("\n#").append(application.rule + 1)
                .append(" path=").append(if (application.path.isEmpty()) "/" else application.path.joinToString("/", prefix = "/"))
                .append(" op=").append(application.beforeOp)
            application.role?.let { append(" role=").append(it) }
            append(if (application.changed) " changed" else " matched/no-op")
            if (application.afterOp != application.beforeOp) append(" -> ").append(application.afterOp)
        }
    }
}

/**
 * Parses a rewrite spec and reports on it, without needing a function graph or touching one.
 *
 * The language check on its own: statements split on newlines and semicolons, `#` and `//` comments
 * stripped, `=>` / `->` / `→` all accepted as the arrow, actions split on top-level commas and pipes.
 * Every action is validated (an unknown one is an error, an unknown `op=` target a warning); selectors
 * are only checked for balanced square brackets. Bad statements are dropped and the rest are kept, so read
 * [FnRewriteProgram.valid] before assuming the whole spec survived.
 */
fun compileFnRewrite(spec: String): FnRewriteProgram = parseFnDslRewriteProgram(spec)

/**
 * Compiles [spec] and applies it, returning a new function and discarding everything the attempt
 * learned — the forgiving one-liner for sketching and performance.
 *
 * Quiet: rules that failed to parse are skipped, rules that matched nothing say nothing,
 * an impossible transmutation leaves the node alone, and a Kotlin right-hand side with no evaluator in
 * [options] is not applied. Always returns a fresh graph, even for a spec with no usable rules at all
 * (that case is a plain deep copy). When you need to know what actually happened, use
 * [previewRewrite]; when a malformed spec should be an error, [rewriteChecked].
 */
fun Functions.Fn.rewrite(
    spec: String,
    options: FnRewriteOptions = FnRewriteOptions()
): Functions.Fn = previewRewrite(spec, options).function

/**
 * Compiles and applies [spec], retaining diagnostics, match paths, and effective-change information.
 *
 * The rewrite really is performed — [FnRewritePreview.function] is the result, not an estimate of it —
 * so this costs exactly what [rewrite] costs and there is no second pass to commit. At each node every
 * rule is tried in source order and **each** matching rule is applied, cumulatively, so later rules see
 * what earlier ones did to that node; the traversal is bottom-up, so a rule can never revisit children
 * that have already been copied.
 */
fun Functions.Fn.previewRewrite(
    spec: String,
    options: FnRewriteOptions = FnRewriteOptions()
): FnRewritePreview {
    val program = compileFnRewrite(spec)
    if (program.rules.isEmpty()) {
        return FnRewritePreview(deepCopy(preserveSharing = options.preserveSharing), program, emptyList())
    }

    val applications = mutableListOf<FnRewriteApplication>()
    val rewritten = rewriteGraph(preserveSharing = options.preserveSharing) { context, copy ->
        var current = copy
        for ((ruleIndex, rule) in program.rules.withIndex()) {
            if (!rule.matches(context)) continue
            val before = current
            val beforeSignature = before.graphSignature()
            for (action in rule.actions) current = applyRewriteAction(current, context, action, options)
            applications += FnRewriteApplication(
                rule = ruleIndex,
                line = rule.line,
                selector = rule.selector,
                actions = rule.actions,
                path = context.path,
                role = context.role,
                beforeOp = before.graphNode().op,
                afterOp = current.graphNode().op,
                changed = current.graphSignature() != beforeSignature
            )
        }
        current
    }
    return FnRewritePreview(rewritten, program, applications)
}

/**
 * [Functions.Fn.rewrite], but a malformed spec throws instead of applying whatever rules parsed.
 *
 * Note the order: the rewrite is *run* first and its result discarded if the program had an error, because
 * the diagnostics come out of the same pass. So a `@{...}` right-hand side may well have been compiled and
 * evaluated before the throw — that is fine for pure expressions and worth knowing for anything else.
 * Throws `IllegalArgumentException` carrying [FnRewriteProgram.diagnosticText] when the program has any
 * error. Only errors: a warning — the usual one being an `op=` target the `OpRegistry` does not know —
 * passes through here and then does nothing at the nodes it matches, so check a preview too if
 * the transmutation matters.
 */
fun Functions.Fn.rewriteChecked(
    spec: String,
    options: FnRewriteOptions = FnRewriteOptions()
): Functions.Fn {
    val preview = previewRewrite(spec, options)
    require(preview.program.valid) {
        "Invalid FnRewrite program:\n${preview.program.diagnosticText()}"
    }
    return preview.function
}

/**
 * [rewrite] when the only thing you want to vary is sharing — no evaluator, no other options.
 *
 * With `preserveSharing = true` a subgraph reached by several paths is copied once and the single
 * replacement is reused at every one of them, which is usually *not* what a path-sensitive selector
 * wants. The overload exists so `f.rewrite(spec, true)` does not need an options object.
 */
fun Functions.Fn.rewrite(
    spec: String,
    preserveSharing: Boolean
): Functions.Fn = rewrite(spec, FnRewriteOptions(preserveSharing = preserveSharing))

private fun parseFnDslRewriteProgram(spec: String): FnRewriteProgram {
    val rules = mutableListOf<FnRewriteRuleSpec>()
    val diagnostics = mutableListOf<FnRewriteDiagnostic>()
    var searchFrom = 0

    for (rawStatement in splitRewriteText(spec, setOf('\n', ';'))) {
        val offset = spec.indexOf(rawStatement, searchFrom).let { if (it < 0) searchFrom else it }
        searchFrom = (offset + rawStatement.length).coerceAtMost(spec.length)
        val line = spec.take(offset).count { it == '\n' } + 1
        val statement = stripFnRewriteComment(rawStatement).trim()
        if (statement.isEmpty() || statement.equals("#fnrewrite", ignoreCase = true)) continue

        val arrow = findRewriteArrow(statement)
        if (arrow == null) {
            diagnostics += FnRewriteDiagnostic(
                FnRewriteSeverity.ERROR, line, 1,
                "Expected `selector => action`.",
                "`->` and `→` are also accepted"
            )
            continue
        }
        val selector = statement.substring(0, arrow.first).trim()
        val rhs = statement.substring(arrow.first + arrow.second).trim()
        if (selector.isEmpty()) {
            diagnostics += FnRewriteDiagnostic(FnRewriteSeverity.ERROR, line, 1, "Selector is empty.", "Use `any` to match every node")
            continue
        }
        if (rhs.isEmpty()) {
            diagnostics += FnRewriteDiagnostic(FnRewriteSeverity.ERROR, line, arrow.first + arrow.second + 1, "Action is empty.")
            continue
        }
        if (!balancedSelectorBrackets(selector)) {
            diagnostics += FnRewriteDiagnostic(FnRewriteSeverity.ERROR, line, 1, "Unbalanced `[...]` selector predicate.")
            continue
        }

        val actions = splitRewriteText(rhs, setOf('|', ','))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (actions.isEmpty()) {
            diagnostics += FnRewriteDiagnostic(FnRewriteSeverity.ERROR, line, arrow.first + arrow.second + 1, "Action is empty.")
            continue
        }
        actions.forEach { action -> validateRewriteAction(action, line, diagnostics) }
        rules += FnRewriteRuleSpec(line, selector, actions)
    }
    return FnRewriteProgram(spec, rules, diagnostics)
}

private fun findRewriteArrow(statement: String): Pair<Int, Int>? {
    val arrows = listOf("=>", "->", "→")
    var best: Pair<Int, Int>? = null
    for (arrow in arrows) {
        val index = statement.indexOf(arrow)
        if (index >= 0 && (best == null || index < best.first)) best = index to arrow.length
    }
    return best
}

private fun stripFnRewriteComment(text: String): String {
    var bracketDepth = 0
    var braceDepth = 0
    var inSingle = false
    var inDouble = false
    var escaped = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (inSingle || inDouble) {
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (inSingle && c == '\'') inSingle = false
            else if (inDouble && c == '"') inDouble = false
            i++
            continue
        }
        when (c) {
            '\'' -> inSingle = true
            '"' -> inDouble = true
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '{' -> braceDepth++
            '}' -> if (braceDepth > 0) braceDepth--
            '#' -> if (bracketDepth == 0 && braceDepth == 0) return text.substring(0, i)
            '/' -> if (text.getOrNull(i + 1) == '/' && bracketDepth == 0 && braceDepth == 0) return text.substring(0, i)
        }
        i++
    }
    return text
}

private fun balancedSelectorBrackets(selector: String): Boolean {
    var depth = 0
    for (c in selector) {
        if (c == '[') depth++
        if (c == ']' && --depth < 0) return false
    }
    return depth == 0
}

private fun validateRewriteAction(
    action: String,
    line: Int,
    diagnostics: MutableList<FnRewriteDiagnostic>
) {
    val lower = action.lowercase()
    if (lower in setOf("drop", "prune", "zero")) return
    if (lower.startsWith("op=") || lower.startsWith("replace=")) {
        val target = action.substringAfter('=').trim().trim('"', '\'')
        if (target.isEmpty()) diagnostics += FnRewriteDiagnostic(FnRewriteSeverity.ERROR, line, 1, "Replacement operation is empty.")
        else if (!OpRegistry.knows(target)) {
            diagnostics += FnRewriteDiagnostic(
                FnRewriteSeverity.WARNING, line, 1,
                "`op=$target` is not a known operation.",
                OpRegistry.transmutableHint()
            )
        }
        return
    }
    if (lower.startsWith("name=")) {
        if (action.substringAfter('=').trim().isEmpty()) diagnostics += FnRewriteDiagnostic(FnRewriteSeverity.ERROR, line, 1, "Name is empty.")
        return
    }
    if (lower.startsWith("domain=")) {
        val value = action.substringAfter('=').trim()
        if (!value.equals("all", true) && !value.equals("default", true) && parseDomain(value) == null) {
            diagnostics += FnRewriteDiagnostic(FnRewriteSeverity.ERROR, line, 1, "Invalid domain `$value`.", "Use `all`, `default`, or `a..b`")
        }
        return
    }
    if (Regex("""^\s*(?:value\s*)?(\*=|/=|\+=|-=|=)\s*.+$""").matches(action)) return
    diagnostics += FnRewriteDiagnostic(
        FnRewriteSeverity.ERROR, line, 1,
        "Unknown action `$action`.",
        "Try `op=triangle`, `*= 0.5`, `name=lead`, `domain=all`, or `drop`"
    )
}

/**
 * Split rewrite text on top-level separators only.
 *
 * Kotlin expressions are allowed on the RHS (`@{...}`), and those snippets
 * routinely contain commas, pipes, braces, strings, or semicolons. This small
 * scanner is not a Kotlin parser; it simply keeps obvious nested/comment/string
 * regions intact so the forgiving DSL does not sabotage the expressive part.
 */
private fun splitRewriteText(text: String, separators: Set<Char>): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var parenDepth = 0
    var bracketDepth = 0
    var braceDepth = 0
    var inLineComment = false
    var inSingleQuote = false
    var inDoubleQuote = false
    var inTripleQuote = false
    var escaped = false

    fun emit() {
        parts += current.toString()
        current.clear()
    }

    var i = 0
    while (i < text.length) {
        val c = text[i]
        val next = text.getOrNull(i + 1)
        val next2 = text.getOrNull(i + 2)

        if (inLineComment) {
            if (c == '\n') {
                inLineComment = false
                if (c in separators && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) emit()
                else current.append(c)
            } else {
                current.append(c)
            }
            i++
            continue
        }
        if (inTripleQuote) {
            current.append(c)
            if (c == '"' && next == '"' && next2 == '"') {
                current.append("\"\"")
                inTripleQuote = false
                i += 3
            } else {
                i++
            }
            continue
        }
        if (inSingleQuote || inDoubleQuote) {
            current.append(c)
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (inSingleQuote && c == '\'') {
                inSingleQuote = false
            } else if (inDoubleQuote && c == '"') {
                inDoubleQuote = false
            }
            i++
            continue
        }

        if (c == '/' && next == '/') {
            inLineComment = true
            current.append("//")
            i += 2
            continue
        }
        if (c == '"' && next == '"' && next2 == '"') {
            inTripleQuote = true
            current.append("\"\"\"")
            i += 3
            continue
        }
        when (c) {
            '\'' -> inSingleQuote = true
            '"' -> inDoubleQuote = true
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '{' -> braceDepth++
            '}' -> if (braceDepth > 0) braceDepth--
        }

        if (
            c in separators &&
            parenDepth == 0 &&
            bracketDepth == 0 &&
            braceDepth == 0
        ) {
            emit()
        } else {
            current.append(c)
        }
        i++
    }

    emit()
    return parts
}

private fun FnRewriteRuleSpec.matches(context: FnGraphCopyContext): Boolean {
    val selector = selector.trim()
    if (selector == "*" || selector.equals("any", ignoreCase = true)) return true
    return splitSelectorTerms(selector)
        .filter { it.isNotEmpty() }
        .all { token ->
            if (looksLikePathSelector(token)) pathSelectorMatches(token, context)
            else tokenMatches(token, context)
        }
}

private fun tokenMatches(token: String, context: FnGraphCopyContext): Boolean {
    if ("[" in token && pathSegmentMatches(token, context.occurrence.last(), context.depth)) return true

    val lower = token.lowercase()
    if ("." in lower) {
        val parts = lower.split(".").filter { it.isNotEmpty() }
        if (parts.size == 2) {
            val left = parts[0]
            val right = parts[1]
            val parentMatches = context.parentOp?.equals(left, ignoreCase = true) == true ||
                context.op.equals(left, ignoreCase = true) ||
                context.node.tags.any { it.equals(left, ignoreCase = true) }
            val roleMatches = context.role?.equals(right, ignoreCase = true) == true ||
                context.op.equals(right, ignoreCase = true)
            return parentMatches && roleMatches
        }
    }
    if (lower == context.op.lowercase()) return true
    if (context.role?.equals(lower, ignoreCase = true) == true) return true
    if (lower == "number") return "number" in context.node.tags
    if (lower == "const") return context.op.equals("const", ignoreCase = true)
    if (lower == "param") return context.op.equals("param", ignoreCase = true)
    if (lower == "opaque") return "opaque" in context.node.tags

    val parts = token.split("=", limit = 2)
    if (parts.size != 2) return false
    val key = parts[0].trim().lowercase()
    val value = parts[1].trim().trim('"', '\'')
    return when (key) {
        "op" -> context.op.equals(value, ignoreCase = true)
        "name" -> context.name?.equals(value, ignoreCase = true) == true
        "role", "edge", "param", "parameter" -> context.role?.equals(value, ignoreCase = true) == true
        "parent", "under" -> context.parentOp?.equals(value, ignoreCase = true) == true
        "tag" -> context.node.tags.any { it.equals(value, ignoreCase = true) }
        "depth" -> context.depth == value.toIntOrNull()
        else -> false
    }
}

private fun splitSelectorTerms(selector: String): List<String> {
    val terms = mutableListOf<String>()
    val current = StringBuilder()
    var bracketDepth = 0
    var parenDepth = 0
    var braceDepth = 0
    var inSingleQuote = false
    var inDoubleQuote = false
    var escaped = false

    fun emit() {
        val text = current.toString().trim()
        if (text.isNotEmpty()) terms += text
        current.clear()
    }

    for (c in selector) {
        if (inSingleQuote || inDoubleQuote) {
            current.append(c)
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (inSingleQuote && c == '\'') inSingleQuote = false
            else if (inDoubleQuote && c == '"') inDoubleQuote = false
            continue
        }

        when (c) {
            '\'' -> inSingleQuote = true
            '"' -> inDoubleQuote = true
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '{' -> braceDepth++
            '}' -> if (braceDepth > 0) braceDepth--
        }

        if ((c == '&' || c.isWhitespace()) && bracketDepth == 0 && parenDepth == 0 && braceDepth == 0) {
            emit()
        } else {
            current.append(c)
        }
    }

    emit()
    return terms
}

private fun looksLikePathSelector(selector: String): Boolean =
    '/' in selector || selector == "**"

private fun pathSelectorMatches(selector: String, context: FnGraphCopyContext): Boolean {
    val trimmed = selector.trim()
    val anchored = trimmed.startsWith("/")
    val body = trimmed.trim('/')
    val segments = splitPathSegments(body).filter { it.isNotBlank() }
    if (segments.isEmpty()) return false

    val occurrence = context.occurrence

    fun match(pathIndex: Int, segmentIndex: Int): Boolean {
        if (segmentIndex == segments.size) return pathIndex == occurrence.size
        val segment = segments[segmentIndex]

        if (segment == "**") {
            if (segmentIndex == segments.lastIndex) return true
            for (nextIndex in pathIndex..occurrence.size) {
                if (match(nextIndex, segmentIndex + 1)) return true
            }
            return false
        }

        if (pathIndex >= occurrence.size) return false
        return pathSegmentMatches(segment, occurrence[pathIndex], pathIndex) &&
            match(pathIndex + 1, segmentIndex + 1)
    }

    if (anchored) return match(0, 0)
    return occurrence.indices.any { start -> match(start, 0) }
}

private fun splitPathSegments(selector: String): List<String> {
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    var bracketDepth = 0
    var inSingleQuote = false
    var inDoubleQuote = false
    var escaped = false

    fun emit() {
        segments += current.toString().trim()
        current.clear()
    }

    for (c in selector) {
        if (inSingleQuote || inDoubleQuote) {
            current.append(c)
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (inSingleQuote && c == '\'') inSingleQuote = false
            else if (inDoubleQuote && c == '"') inDoubleQuote = false
            continue
        }

        when (c) {
            '\'' -> inSingleQuote = true
            '"' -> inDoubleQuote = true
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
        }

        if (c == '/' && bracketDepth == 0) emit()
        else current.append(c)
    }

    emit()
    return segments
}

private fun pathSegmentMatches(segment: String, element: FnGraphPathElement, depth: Int): Boolean {
    val trimmed = segment.trim()
    if (trimmed == "*" || trimmed.equals("any", ignoreCase = true)) return true
    if (trimmed == "**") return true

    val predicateRanges = Regex("""\[[^\]]*]""").findAll(trimmed).toList()
    val base = trimmed.substringBefore("[").trim()
    val baseMatches = base.isEmpty() ||
        base == "*" ||
        elementTokenMatches(base, element, depth)
    if (!baseMatches) return false

    return predicateRanges.all { match ->
        val predicate = match.value.removePrefix("[").removeSuffix("]").trim()
        predicate.isNotEmpty() && elementPredicateMatches(predicate, element, depth)
    }
}

private fun elementTokenMatches(token: String, element: FnGraphPathElement, depth: Int): Boolean {
    val lower = token.lowercase()
    if (lower == element.op.lowercase()) return true
    if (element.role?.equals(lower, ignoreCase = true) == true) return true
    if (lower == "number") return "number" in element.node.tags
    if (lower == "const") return element.op.equals("const", ignoreCase = true)
    if (lower == "param") return element.op.equals("param", ignoreCase = true)
    if (lower == "opaque") return "opaque" in element.node.tags
    if (element.node.tags.any { it.equals(lower, ignoreCase = true) }) return true

    val parts = token.split("=", limit = 2)
    if (parts.size == 2) return elementPredicateMatches(token, element, depth)
    return false
}

private fun elementPredicateMatches(predicate: String, element: FnGraphPathElement, depth: Int): Boolean {
    val op = listOf(">=", "<=", "!=", "=", ">", "<").firstOrNull { it in predicate } ?: return elementTokenMatches(predicate, element, depth)
    val parts = predicate.split(op, limit = 2)
    if (parts.size != 2) return false
    val key = parts[0].trim().lowercase()
    val expected = parts[1].trim().trim('"', '\'')

    if (key == "tag") return element.node.tags.any { it.equals(expected, ignoreCase = true) }

    val actual = elementValue(key, element, depth) ?: return false
    val expectedNumber = expected.toDoubleOrNull()
    val actualNumber = when (actual) {
        is Number -> actual.toDouble()
        is Boolean -> if (actual) 1.0 else 0.0
        is String -> actual.toDoubleOrNull()
        else -> null
    }

    return if (actualNumber != null && expectedNumber != null) {
        when (op) {
            ">=" -> actualNumber >= expectedNumber
            "<=" -> actualNumber <= expectedNumber
            "!=" -> actualNumber != expectedNumber
            "=" -> actualNumber == expectedNumber
            ">" -> actualNumber > expectedNumber
            "<" -> actualNumber < expectedNumber
            else -> false
        }
    } else {
        val actualText = actual.toString()
        when (op) {
            "!=" -> !actualText.equals(expected, ignoreCase = true)
            "=" -> actualText.equals(expected, ignoreCase = true)
            else -> false
        }
    }
}

private fun elementValue(key: String, element: FnGraphPathElement, depth: Int): Any? =
    when (key) {
        "op" -> element.op
        "role", "edge", "param", "parameter" -> element.role
        "name" -> element.name
        "depth" -> depth
        "index" -> element.index
        "value" -> element.node.numbers["value"]
        else -> element.node.numbers[key]
            ?: element.node.strings[key]
            ?: element.node.booleans[key]
            ?: element.node.longs[key]
    }

private fun applyRewriteAction(
    copy: Functions.Fn,
    context: FnGraphCopyContext,
    action: String,
    options: FnRewriteOptions
): Functions.Fn {
    val trimmed = action.trim()
    val lower = trimmed.lowercase()

    if (lower == "drop" || lower == "prune" || lower == "zero") {
        return Functions.zero(copy.domain)
    }

    if (lower.startsWith("op=") || lower.startsWith("replace=")) {
        val target = trimmed.substringAfter('=').trim().trim('"', '\'')
        return copy.rebuildAsOp(target) ?: copy
    }

    if (lower.startsWith("name=")) {
        return copy.withName(trimmed.substringAfter('=').trim().trim('"', '\''))
    }

    if (lower.startsWith("domain=")) {
        val value = trimmed.substringAfter('=').trim()
        parseDomain(value)?.let { return copy.withDomain(it) }
        if (value.equals("all", ignoreCase = true)) return copy.withDomain(Functions.Domain.ALL)
        if (value.equals("default", ignoreCase = true)) return copy.withDomain(Functions.Domain.DEFAULT)
        return copy
    }

    val currentValue = rewriteNumericValue(copy) ?: context.node.numbers["value"]
    if (currentValue != null) {
        val changed = applyNumberAction(trimmed, currentValue, context, options)
        if (changed != null && changed != currentValue) return rewriteNumericFn(changed, copy, context)
    }

    return copy
}

private fun rewriteNumericFn(value: Double, copy: Functions.Fn, context: FnGraphCopyContext): Functions.Fn {
    if ("parameter" in context.node.tags || context.node.op == "param") return parameterLiteral(value)
    return Functions.c(value, copy.domain).withName(copy.name)
}

private fun parameterLiteral(value: Number): Functions.Fn {
    val v = value.toDouble()
    return Functions.Fn(
        Functions.Domain.ALL,
        "param",
        FnGraphNode(
            op = "param",
            numbers = mapOf("value" to v),
            tags = setOf("number", "parameter"),
            rebuild = { parameterLiteral(v) }
        )
    ) { v }
}

private fun rewriteNumericValue(fn: Functions.Fn): Double? {
    val node = fn.graphNode()
    node.numbers["value"]?.let { return it }
    if (node.op != "name" && node.op != "over") return null
    return node.child("source")?.let(::rewriteNumericValue)
}

private fun applyNumberAction(
    action: String,
    current: Double,
    context: FnGraphCopyContext,
    options: FnRewriteOptions
): Double? {
    val match = Regex("""^\s*(?:value\s*)?(\*=|/=|\+=|-=|=)\s*(.+?)\s*$""").matchEntire(action)
        ?: return null
    val op = match.groupValues[1]
    val rhs = match.groupValues[2]
    val value = parseNumberExpression(rhs, current, context, options) ?: return null

    return when (op) {
        "*=" -> current * value
        "/=" -> if (value == 0.0) current else current / value
        "+=" -> current + value
        "-=" -> current - value
        "=" -> value
        else -> null
    }
}

private fun parseNumberExpression(
    expression: String,
    current: Double,
    context: FnGraphCopyContext,
    options: FnRewriteOptions
): Double? {
    val trimmed = expression.trim()
    if (trimmed.equals("it", ignoreCase = true) || trimmed.equals("value", ignoreCase = true)) return current
    trimmed.toDoubleOrNull()?.let { return it }
    val kotlinExpr = when {
        trimmed.startsWith("@{") && trimmed.endsWith("}") -> trimmed.removeSurrounding("@{", "}").trim()
        trimmed.startsWith("\${") && trimmed.endsWith("}") -> trimmed.removeSurrounding("\${", "}").trim()
        else -> trimmed
    }
    val numberContext = FnRewriteNumberContext(kotlinExpr, current, context)
    return options.kotlinNumberContext?.invoke(numberContext)
        ?: options.kotlinNumber?.invoke(kotlinExpr, current, context)
}

private fun parseDomain(value: String): Functions.Domain? {
    val trimmed = value.trim().removePrefix("(").removeSuffix(")")
    val parts = trimmed.split("..", limit = 2)
    if (parts.size != 2) return null
    val a = parts[0].trim().toDoubleOrNull() ?: return null
    val b = parts[1].trim().toDoubleOrNull() ?: return null
    return Functions.domain(a..b)
}
