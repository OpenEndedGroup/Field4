package afield.macros

import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.forEachDescendantOfType

/**
 * A structural source-to-source rewriter that runs as a preprocessor in front of `kotlinc`,
 * bootstrapped on the existing PSI-based macro pass (see [afield.KotlinScriptEngine.applyMacros]).
 *
 * Users author rules directly in a live box with the [rewrite]/[from]/[to]/[where]/[provide]/[ai]
 * DSL:
 * ```
 * rewrite { x: Expr, y: Expr ->
 *     from { max(x, y) }
 *     where { distinct(x, y) }              // optional structural guard
 *     to { if (x >= y) x else y }
 * }
 * max(p, q + 1)          // becomes: if (p >= (q + 1)) p else (q + 1)
 * ```
 *
 * The core insight: pattern and replacement bodies never need to *type-check* — only to parse. PSI
 * parsing runs no name resolution, so `from { max(x, y) }` parses into a valid [KtCallExpression]
 * even though nothing resolves. This whole layer therefore stays on stateless PSI: no K2 Analysis
 * API, no compiler sessions, safe to re-run per keystroke.
 *
 * Phasing (see [expand]):
 *  1. Parse the source once, **collect** every top-level `rewrite {}` into a [PatternRule], and
 *     blank the `rewrite {}` blocks out of the source (so `kotlinc` never sees the erased DSL, and
 *     so the pattern body `max(x, y)` is not itself rewritten — the "quote barrier").
 *  2. **Fixpoint**: parse the stripped text, find every outermost non-overlapping structural match
 *     whose guard holds, splice paren-guarded + hygienically-renamed replacements, and accumulate
 *     any `provide {}` (keyed preamble) and `ai(…)` (cached attic) side contributions; re-parse,
 *     repeat until stable or the iteration cap trips.
 *
 * The side contributions are returned as *data* in [ExpandResult] — [expand] performs no side
 * effects, which keeps it pure and testable; the caller ([afield.KotlinInterface2.applyMacros])
 * routes them through the existing preamble/attic machinery.
 *
 * Hygiene: names the `to {}` side *introduces* (locals, params, etc. — not carried in from a
 * metavariable) are gensym'd so they cannot capture or shadow the user code spliced in at a
 * metavar. Metavar captures are already hygienic (verbatim user code) and are never renamed.
 * `provide {}` declarations are deliberately *not* gensym'd — they define stable box-level names.
 *
 * Explicitly **deferred** (do not build here): variadic holes (`f($args...)`) and procedural
 * unquote (`to { emit(simplify(x)) }` running Kotlin at expansion time).
 */
object PatternRewrite {

    /** A collected rule: pattern PSI, replacement PSI, metavar declarations, guard, and side clauses. */
    data class PatternRule(
        val pattern: KtElement,
        val repl: KtElement,
        val metavars: Map<String, MetaKind>,
        val guard: Guard,
        /** `provide {}` body (keyed preamble contribution), or null. */
        val provide: KtElement?,
        /** `ai("…")` prompt template (cached attic request), or null. */
        val aiPrompt: KtStringTemplateExpression?,
        /** Rendered pattern text, for diagnostics. */
        val patternText: String,
    )

    /** A structural guard over captured metavariables (see [where] / [distinct] / [isLiteral] …). */
    sealed interface Guard {
        object Always : Guard
        data class Pred(val name: String, val args: List<String>) : Guard
        data class Not(val g: Guard) : Guard
        data class And(val l: Guard, val r: Guard) : Guard
        data class Or(val l: Guard, val r: Guard) : Guard
    }

    /** A cached AI generation request produced by an `ai("…")` clause when its rule fires. */
    data class AiRequest(
        /** Stable content-derived cache key (identifies the `@rw-ai:<key>` attic block). */
        val key: String,
        /** The concrete (metavar-substituted) prompt sent to the model. */
        val prompt: String,
        /** The rule's `to {}` text, for context about what symbols the generated code must define. */
        val toText: String,
    )

    /** The result of [expand]: rewritten text plus side contributions for the caller to route. */
    data class ExpandResult(
        val text: String,
        /** Keyed preamble segments, ready for `applyInsertToPreamble` (already `/*start*/…/*end*/`-wrapped). */
        val preambleBlocks: List<String>,
        /** Cached-AI requests, deduped by key. */
        val aiRequests: List<AiRequest>,
        /** Source text of each `rewrite {}` block declared *locally* in [expand]'s `source`. The caller
         *  persists these so descendant boxes can inherit them as ambient rules (see the `ambientRuleSources`
         *  parameter of [expand]). */
        val localRuleSources: List<String> = emptyList(),
    )

    private data class Match(
        val start: Int,
        val end: Int,
        val replacement: String,
        val rule: PatternRule,
        val preambleBlock: String? = null,
        val aiRequest: AiRequest? = null,
    )

    private data class Collected(
        val rules: List<PatternRule>,
        val deletions: List<IntRange>,
        /** Source text of each collected `rewrite {}` block (for ambient-rule persistence). */
        val ruleSources: List<String>,
    )

    /**
     * Expand every `rewrite {}` rule in [source]. [parse] turns text into a [KtFile] (the caller
     * supplies the PSI environment — the REPL's project in production, or a standalone parser in
     * tests). [onDiagnostic] receives a human-readable message on a guard-vocabulary error or if the
     * iteration cap is hit; when the cap is hit, expansion stops and the best-effort-expanded text so
     * far is returned (never hangs, never throws into the box).
     */
    fun expand(
        source: String,
        parse: (String) -> KtFile,
        maxIterations: Int = 64,
        evalUnquote: ((String) -> String?)? = null,
        ambientRuleSources: List<String> = emptyList(),
        onDiagnostic: (String) -> Unit = {},
    ): ExpandResult {
        val collected = collect(parse(source), onDiagnostic)

        // Ambient rules inherited from ancestor boxes: collected (so they apply here) but NOT blanked
        // from — their `rewrite {}` blocks live in the ancestors' source, not this one. Local rules
        // come first so a box can override an inherited rule (declaration-order, first-match-wins).
        val ambientRules = ambientRuleSources.flatMap { collect(parse(it), onDiagnostic).rules }
        val rules = collected.rules + ambientRules

        if (rules.isEmpty()) return ExpandResult(source, emptyList(), emptyList(), collected.ruleSources)

        var text = blankOut(source, collected.deletions)
        val gensym = GensymScope()
        val preambleBlocks = mutableListOf<String>()
        val aiRequests = mutableListOf<AiRequest>()

        fun result() = ExpandResult(
            text,
            preambleBlocks.distinct(),
            aiRequests.distinctBy { it.key },
            collected.ruleSources,
        )

        var iteration = 0
        while (true) {
            val matches = findMatches(parse(text), rules, gensym, evalUnquote)
            if (matches.isEmpty()) return result()
            if (iteration >= maxIterations) {
                val sample = matches.first()
                onDiagnostic(
                    "pattern-rewrite did not converge after $maxIterations iterations — a non-confluent " +
                            "rule set (e.g. a→b, b→a)? last firing rule `${sample.rule.patternText}` at " +
                            "offset ${sample.start}..${sample.end}. Stopping; rules applied so far are kept."
                )
                return result()
            }
            matches.forEach { m ->
                m.preambleBlock?.let { preambleBlocks += it }
                m.aiRequest?.let { aiRequests += it }
            }
            text = applyEdits(text, matches)
            iteration++
        }
    }

    // ---- collection (phase 1) -------------------------------------------------------------------

    private fun collect(file: KtFile, onDiagnostic: (String) -> Unit): Collected {
        val rules = mutableListOf<PatternRule>()
        val deletions = mutableListOf<IntRange>()
        val ruleSources = mutableListOf<String>()

        file.collectDescendantsOfType<KtCallExpression>()
            .filter { it.calleeName() == "rewrite" }
            .filter { call -> !call.isNestedInRewrite() }               // quote barrier: outermost only
            .forEach { call ->
                val rule = call.toPatternRule(onDiagnostic)
                if (rule != null) rules += rule
                ruleSources += call.text
                // Blank the whole `rewrite {}` call regardless (a malformed one is still erased DSL
                // that must not reach the compiler).
                deletions += call.textRange.startOffset until call.textRange.endOffset
            }

        return Collected(rules, deletions, ruleSources)
    }

    private fun KtCallExpression.toPatternRule(onDiagnostic: (String) -> Unit): PatternRule? {
        val lambda = lambdaArguments.firstOrNull()?.getLambdaExpression() ?: return null
        val metavars = lambda.valueParameters.mapNotNull { p: KtParameter ->
            val name = p.name ?: return@mapNotNull null
            name to metaKindOf(p.typeReference?.text)
        }.toMap()

        val body = lambda.bodyExpression ?: return null
        val fromBody = body.callBody("from") ?: return null
        val toBody = body.callBody("to") ?: return null

        val patternEl = fromBody.singleMeaningfulStatement() ?: return null
        val replEl = toBody.singleMeaningfulStatement() ?: return null

        val guard = (body.callBody("where")?.singleMeaningfulStatement() as? KtExpression)
            ?.let { parseGuard(it, metavars.keys, onDiagnostic) }
            ?: Guard.Always

        // `provide { … }` keeps its whole block (may declare several bindings).
        val provide = body.callBody("provide")

        // `ai("…")` takes a string prompt argument, not a lambda.
        val aiPrompt = body.statements.filterIsInstance<KtCallExpression>()
            .firstOrNull { it.calleeName() == "ai" }
            ?.valueArguments?.firstOrNull()?.getArgumentExpression() as? KtStringTemplateExpression

        return PatternRule(patternEl, replEl, metavars, guard, provide, aiPrompt, patternEl.text)
    }

    /** The single statement inside a `name { … }` call's trailing lambda, or null if absent/multi. */
    private fun KtBlockExpression.callBody(name: String): KtBlockExpression? =
        statements
            .filterIsInstance<KtCallExpression>()
            .firstOrNull { it.calleeName() == name }
            ?.lambdaArguments?.firstOrNull()?.getLambdaExpression()?.bodyExpression

    private fun KtBlockExpression.singleMeaningfulStatement(): KtElement? =
        statements.singleOrNull()

    private fun KtCallExpression.calleeName(): String? =
        (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

    private fun KtCallExpression.isNestedInRewrite(): Boolean {
        var p = parent
        while (p != null) {
            if (p is KtCallExpression && p.calleeName() == "rewrite") return true
            p = p.parent
        }
        return false
    }

    private fun metaKindOf(typeText: String?): MetaKind = when (typeText?.substringAfterLast('.')?.trim()) {
        "Ident" -> Ident
        "Stmt" -> Stmt
        "TypeRef" -> TypeRef
        "Vararg" -> Vararg
        else -> Expr
    }

    /** Captured bindings: scalar metavars, plus variadic metavars that bind a run of arguments. */
    private class Bindings {
        val scalar = HashMap<String, PsiElement>()
        val variadic = HashMap<String, List<KtValueArgument>>()
    }

    // ---- guard parsing + evaluation -------------------------------------------------------------

    private val guardPredicates = setOf("distinct", "identical", "isLiteral", "isIdentifier", "isCall", "isCallTo")

    private fun parseGuard(expr: KtExpression, metavars: Set<String>, onDiagnostic: (String) -> Unit): Guard {
        return when (val e = expr.stripParens()) {
            is KtBinaryExpression -> when (e.operationReference.text) {
                "&&" -> Guard.And(
                    parseGuard(e.left ?: return fail(e, onDiagnostic), metavars, onDiagnostic),
                    parseGuard(e.right ?: return fail(e, onDiagnostic), metavars, onDiagnostic),
                )
                "||" -> Guard.Or(
                    parseGuard(e.left ?: return fail(e, onDiagnostic), metavars, onDiagnostic),
                    parseGuard(e.right ?: return fail(e, onDiagnostic), metavars, onDiagnostic),
                )
                else -> fail(e, onDiagnostic)
            }

            is KtPrefixExpression ->
                if (e.operationReference.text == "!")
                    Guard.Not(parseGuard(e.baseExpression ?: return fail(e, onDiagnostic), metavars, onDiagnostic))
                else fail(e, onDiagnostic)

            is KtCallExpression -> {
                val name = e.calleeName()
                if (name == null || name !in guardPredicates) {
                    onDiagnostic("unknown guard predicate `$name` — known: $guardPredicates. Rule will never fire.")
                    return Guard.Not(Guard.Always)
                }
                val args = e.valueArguments.mapNotNull { it.getArgumentExpression()?.text }
                // Only bare-identifier args are expected to be metavariables; string-literal args
                // (e.g. the "name" of isCallTo) are legitimately not.
                args.filter { it.matches(Regex("[A-Za-z_]\\w*")) && it !in metavars }.forEach {
                    onDiagnostic("guard `$name` references `$it`, which is not a declared metavariable")
                }
                Guard.Pred(name, args)
            }

            else -> fail(e, onDiagnostic)
        }
    }

    private fun fail(e: KtElement, onDiagnostic: (String) -> Unit): Guard {
        onDiagnostic("unrecognized guard `${e.text}` — use distinct/identical/isLiteral/isIdentifier/isCall with && || !")
        return Guard.Not(Guard.Always)   // fail-closed: the rule never fires
    }

    private fun evalGuard(g: Guard, binds: Map<String, PsiElement>): Boolean = when (g) {
        Guard.Always -> true
        is Guard.Not -> !evalGuard(g.g, binds)
        is Guard.And -> evalGuard(g.l, binds) && evalGuard(g.r, binds)
        is Guard.Or -> evalGuard(g.l, binds) || evalGuard(g.r, binds)
        is Guard.Pred -> evalPredicate(g.name, g.args, binds)
    }

    private fun evalPredicate(name: String, args: List<String>, binds: Map<String, PsiElement>): Boolean {
        fun cap(i: Int): PsiElement? = args.getOrNull(i)?.let { binds[it] }
        return when (name) {
            "distinct" -> {
                val caps = args.mapNotNull { binds[it] }
                caps.indices.all { i -> (i + 1 until caps.size).all { j -> !structurallyEqual(caps[i], caps[j]) } }
            }
            "identical" -> {
                val a = cap(0); val b = cap(1)
                a != null && b != null && structurallyEqual(a, b)
            }
            "isLiteral" -> cap(0).let { it is KtConstantExpression || it is KtStringTemplateExpression }
            "isIdentifier" -> cap(0) is KtNameReferenceExpression
            "isCall" -> cap(0)?.unwrapParens() is KtCallExpression
            "isCallTo" -> {
                val target = cap(0)?.unwrapParens() as? KtCallExpression
                val wanted = args.getOrNull(1)?.trim()?.removeSurrounding("\"")
                target != null && wanted != null && target.calleeName() == wanted
            }
            else -> false
        }
    }

    // ---- matching (phase 2) ---------------------------------------------------------------------

    private fun findMatches(
        file: KtFile,
        rules: List<PatternRule>,
        gensym: GensymScope,
        evalUnquote: ((String) -> String?)?,
    ): List<Match> {
        val out = mutableListOf<Match>()

        fun visit(element: PsiElement) {
            // Never start a match at a parenthesized wrapper: recurse into it so the inner
            // expression is matched with its own (tighter) range, preserving the user's parens in
            // the surrounding context. Matching modulo parens still happens inside `structuralMatch`.
            if (element is KtElement && element !is KtParenthesizedExpression) {
                for (rule in rules) {
                    val binds = Bindings()
                    if (Matcher(rule.metavars).match(rule.pattern, element, binds) && evalGuard(rule.guard, binds.scalar)) {
                        out += buildMatch(element, rule, binds, gensym, evalUnquote)
                        return   // outermost-wins: do not descend into a matched subtree this round
                    }
                }
            }
            var child = element.firstChild
            while (child != null) {
                visit(child)
                child = child.nextSibling
            }
        }

        visit(file)
        return out
    }

    private fun buildMatch(
        element: KtElement,
        rule: PatternRule,
        binds: Bindings,
        gensym: GensymScope,
        evalUnquote: ((String) -> String?)?,
    ): Match {
        val replacement = substitute(rule.repl, binds, rule.metavars, gensym, hygiene = true, evalUnquote = evalUnquote)

        val preambleBlock = rule.provide?.let {
            keyedPreambleBlock(substitute(it, binds, rule.metavars, gensym, hygiene = false))
        }

        val aiRequest = rule.aiPrompt?.let { template ->
            val prompt = substitutePrompt(template, binds.scalar)
            AiRequest(stableKey(prompt), prompt, rule.repl.text)
        }

        return Match(
            element.textRange.startOffset,
            element.textRange.endOffset,
            replacement,
            rule,
            preambleBlock,
            aiRequest,
        )
    }

    /**
     * Structural comparison of a pattern node against a target node. Compares by element type, skips
     * trivia, captures at metavars, and enforces non-linearity (a repeated metavar must bind
     * structurally equal subtrees). Matching is modulo redundant parentheses.
     */
    private class Matcher(val metavars: Map<String, MetaKind>) {

        fun match(p: PsiElement, t: PsiElement, binds: Bindings): Boolean {
            val pp = p.unwrapParens()
            val tt = t.unwrapParens()

            val holeName = (pp as? KtNameReferenceExpression)?.getReferencedName()
            if (holeName != null && holeName in metavars) {
                val prior = binds.scalar[holeName]
                return if (prior == null) {
                    if (!kindAllows(metavars.getValue(holeName), tt)) false
                    else { binds.scalar[holeName] = tt; true }
                } else {
                    structurallyEqual(prior, tt)
                }
            }

            // Variadic holes live in argument lists: `f(args)` / `f(x, rest)`. When the pattern's
            // arg list contains a Vararg hole, match by segment (fixed prefix + one variadic run +
            // fixed suffix) instead of the fixed-arity element zip below.
            if (pp is KtValueArgumentList && tt is KtValueArgumentList &&
                pp.arguments.any { variadicHoleName(it) != null }
            ) {
                return matchArgList(pp.arguments, tt.arguments, binds)
            }

            if (pp.node?.elementType != tt.node?.elementType) return false

            val pc = pp.meaningfulChildren()
            val tc = tt.meaningfulChildren()
            if (pc.isEmpty() && tc.isEmpty()) return pp.text == tt.text   // leaf: literal / operator / keyword
            return pc.size == tc.size && pc.zip(tc).all { (a, b) -> match(a, b, binds) }
        }

        /** The Vararg-metavar name a pattern argument is a hole for, or null. */
        private fun variadicHoleName(arg: KtValueArgument): String? {
            val name = (arg.getArgumentExpression()?.unwrapParens() as? KtNameReferenceExpression)?.getReferencedName()
            return if (name != null && metavars[name] == Vararg) name else null
        }

        /** Segment-match a pattern arg list with (at most one) variadic hole against a target arg list. */
        private fun matchArgList(pArgs: List<KtValueArgument>, tArgs: List<KtValueArgument>, binds: Bindings): Boolean {
            val vIdx = pArgs.indexOfFirst { variadicHoleName(it) != null }
            if (pArgs.drop(vIdx + 1).any { variadicHoleName(it) != null }) return false   // v1: one variadic only
            val prefix = pArgs.subList(0, vIdx)
            val suffix = pArgs.subList(vIdx + 1, pArgs.size)
            if (tArgs.size < prefix.size + suffix.size) return false

            for (i in prefix.indices) if (!match(prefix[i], tArgs[i], binds)) return false
            for (i in suffix.indices) if (!match(suffix[i], tArgs[tArgs.size - suffix.size + i], binds)) return false

            val name = variadicHoleName(pArgs[vIdx])!!
            val mid = tArgs.subList(prefix.size, tArgs.size - suffix.size).toList()
            val prior = binds.variadic[name]
            return if (prior != null) {
                prior.size == mid.size && mid.indices.all { structurallyEqual(prior[it], mid[it]) }
            } else {
                binds.variadic[name] = mid; true
            }
        }

        private fun kindAllows(kind: MetaKind, node: PsiElement): Boolean = when (kind) {
            Expr -> node is KtExpression
            Ident -> node is KtNameReferenceExpression
            Stmt -> node is KtExpression || node is KtDeclaration
            TypeRef -> node is KtTypeReference
            Vararg -> false   // a variadic hole only matches inside an argument list (see matchArgList)
        }
    }

    private fun structurallyEqual(a: PsiElement, b: PsiElement): Boolean =
        Matcher(emptyMap()).match(a, b, Bindings())

    // ---- substitution + hygiene (phase 2) -------------------------------------------------------

    /** Deterministic per-expansion fresh-name generator (see hygiene note on [PatternRewrite]). */
    private class GensymScope {
        private var counter = 0
        fun fresh(name: String): String = "${name}__hyg${counter++}"
    }

    /**
     * Render the replacement [repl] as text, splicing each bound metavar's captured text in at its
     * reference offset and (when [hygiene]) gensym-renaming any names the replacement introduces.
     * All edits are applied right-to-left so earlier offsets stay valid. Non-atomic expression
     * captures are paren-guarded so text splicing cannot silently change precedence (`x` binds
     * `a + b`, `to = x * 2` ⇒ `(a + b) * 2`, never `a + b * 2`).
     */
    private fun substitute(
        repl: PsiElement,
        binds: Bindings,
        metavars: Map<String, MetaKind>,
        gensym: GensymScope,
        hygiene: Boolean,
        evalUnquote: ((String) -> String?)? = null,
    ): String {
        val base = repl.textRange.startOffset
        val edits = mutableListOf<Triple<Int, Int, String>>()   // start, end, text (relative to `base`)

        // Procedural unquote: `unquote { … }` regions run Kotlin at expansion time. Metavar captures
        // are bound as `String` vals (their source text); the block's result is spliced. The regions
        // are a substitution barrier — the ordinary metavar/gensym splices below skip inside them,
        // because the unquote handles its own metavars via the eval snippet.
        val unquoteCalls = repl.collectDescendantsOfType<KtCallExpression>()
            .filter { (it.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == "unquote" }
        val unquoteRanges = unquoteCalls.map { it.textRange.startOffset until it.textRange.endOffset }
        fun insideUnquote(offset: Int) = unquoteRanges.any { offset in it }
        unquoteCalls.forEach { call ->
            val body = call.lambdaArguments.firstOrNull()?.getLambdaExpression()?.bodyExpression?.text
                ?: return@forEach
            val spliced = when {
                // Editor preview: we don't run user code, so leave a well-typed placeholder. `TODO()`
                // has type Nothing, so it fits any expected type — the analyzer stays quiet instead
                // of choking on the erased generic `unquote {}`.
                evalUnquote == null -> "TODO()"
                // Execute: run the block; on failure emit a clear compiling stand-in (Nothing type)
                // rather than the raw `unquote {}` (which the compiler can't make sense of).
                else -> evalUnquote(buildUnquoteSnippet(binds.scalar, body))
                    ?: "error(\"rewrite unquote {} failed to evaluate — see log\")"
            }
            edits += Triple(call.textRange.startOffset - base, call.textRange.endOffset - base, spliced)
        }

        // Declaration name identifiers. A metavar in a *declaration-name* position (e.g. the `n` of
        // `var Box.n by …` in a `provide {}`) is substituted with its capture. Otherwise, under
        // hygiene, the introduced name is gensym'd so it cannot shadow/capture a spliced metavar —
        // a fresh name unique to this expansion.
        val renames = HashMap<String, String>()
        repl.forEachDescendantOfType<KtNamedDeclaration> { decl ->
            // Skip anonymous nodes like the KtFunctionLiteral of a lambda (a KtNamedDeclaration with
            // no nameIdentifier) — processing it would burn a fresh-name counter value for nothing.
            val id = decl.nameIdentifier ?: return@forEachDescendantOfType
            if (insideUnquote(id.textRange.startOffset)) return@forEachDescendantOfType
            val name = decl.name ?: return@forEachDescendantOfType
            val text = when {
                binds.scalar.containsKey(name) -> renderCapture(binds.scalar.getValue(name))   // metavar-named decl
                hygiene -> renames.getOrPut(name) { gensym.fresh(name) }                       // hygienic rename
                else -> return@forEachDescendantOfType                                          // box-level name, kept
            }
            edits += Triple(id.textRange.startOffset - base, id.textRange.endOffset - base, text)
        }

        repl.forEachDescendantOfType<KtNameReferenceExpression> { ref ->
            if (insideUnquote(ref.textRange.startOffset)) return@forEachDescendantOfType
            val name = ref.getReferencedName()
            val text = when {
                binds.scalar.containsKey(name) -> renderCapture(binds.scalar.getValue(name))          // scalar hole
                binds.variadic.containsKey(name) -> binds.variadic.getValue(name).joinToString(", ") { it.text }  // variadic run
                name in renames -> renames.getValue(name)                                             // gensym'd local ref
                else -> return@forEachDescendantOfType                                                // free reference, untouched
            }
            edits += Triple(ref.textRange.startOffset - base, ref.textRange.endOffset - base, text)
        }

        var out = repl.text
        edits.sortedByDescending { it.first }.forEach { (start, end, text) -> out = out.replaceRange(start, end, text) }
        return out
    }

    /** Substitute captured metavariables into an `ai("…")` prompt, yielding the concrete prompt text. */
    private fun substitutePrompt(template: KtStringTemplateExpression, binds: Map<String, PsiElement>): String =
        template.entries.joinToString("") { entry ->
            when (entry) {
                is KtLiteralStringTemplateEntry -> entry.text
                is KtEscapeStringTemplateEntry -> entry.unescapedValue
                is KtSimpleNameStringTemplateEntry -> {
                    val name = (entry.expression as? KtNameReferenceExpression)?.getReferencedName()
                    if (name != null && binds.containsKey(name)) binds.getValue(name).text else entry.text
                }
                is KtBlockStringTemplateEntry -> {
                    val name = (entry.expression as? KtNameReferenceExpression)?.getReferencedName()
                    if (name != null && binds.containsKey(name)) binds.getValue(name).text else entry.text
                }
                else -> entry.text
            }
        }

    private fun renderCapture(capture: PsiElement): String {
        val text = capture.text
        return if (capture is KtExpression && !capture.isAtomic()) "($text)" else text
    }

    /** An expression that can be substituted without parens without changing precedence. */
    private fun KtExpression.isAtomic(): Boolean = when (this) {
        is KtNameReferenceExpression,
        is KtConstantExpression,
        is KtCallExpression,
        is KtQualifiedExpression,     // a.b / a?.b
        is KtArrayAccessExpression,   // a[i]
        is KtParenthesizedExpression, // already grouped
        is KtStringTemplateExpression,
        is KtThisExpression,
        is KtSuperExpression -> true
        else -> false
    }

    // ---- keyed contributions (stable ids) -------------------------------------------------------

    /** A stable, content-derived key (safe inside a `/*…*/` comment — hex only). */
    fun stableKey(content: String): String = "rw-" + Integer.toHexString(content.hashCode())

    // ---- procedural unquote (eval at expansion time) --------------------------------------------

    /**
     * Build the Kotlin snippet an `unquote { … }` block evaluates to. Each scalar metavar is bound
     * as a `String` val holding its captured *source text*, so the block can manipulate captures as
     * strings (`unquote { x.reversed() }`, `unquote { simplify(x) }`, …); the block's result is
     * `toString()`-ed and spliced.
     */
    fun buildUnquoteSnippet(scalarBinds: Map<String, PsiElement>, blockBody: String): String {
        val decls = scalarBinds.entries.joinToString("\n") { (name, el) ->
            "    val $name: String = ${kotlinStringLiteral(el.text)}"
        }
        return "run {\n$decls\n    $blockBody\n}.toString()"
    }

    private fun kotlinStringLiteral(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    /**
     * Wrap a preamble contribution as a single deduplicating segment: a `/*start*/…/*end*/` block
     * carrying a leading `/*prefix*/` key. This is the "stack mark" idempotency mechanism — the
     * key (not the text) is the identity, so `applyInsertToPreamble`/`checkPreambleAndExecute`
     * replace-in-place and only re-execute when the content under the key changes.
     */
    private fun keyedPreambleBlock(text: String): String =
        "/*start*/\n/*${stableKey(text)}*/\n${text.trim()}\n/*end*/"

    // ---- cached-AI attic helpers (pure; caller does the model call + box I/O) --------------------

    private fun aiBlockHeader(key: String): String = "// @rw-ai:$key"
    private fun aiBlockFooter(key: String): String = "// @rw-ai-end:$key"

    /** True iff [attic] already contains a cached block for [key] (a cache hit — skip generation). */
    fun atticHasAiBlock(attic: String, key: String): Boolean = attic.contains(aiBlockHeader(key))

    /** Append a generated block for [key] to [attic], returning the new attic text. */
    fun appendAiBlock(attic: String, key: String, prompt: String, code: String): String {
        val block = buildString {
            append(aiBlockHeader(key)).append(" | prompt: ").append(prompt.replace("\n", " ")).append('\n')
            append(code.trim()).append('\n')
            append(aiBlockFooter(key))
        }
        return if (attic.isBlank()) block else "${attic.trimEnd()}\n\n$block"
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun PsiElement.unwrapParens(): PsiElement {
        var e = this
        while (e is KtParenthesizedExpression) e = e.expression ?: return e
        return e
    }

    private fun KtExpression.stripParens(): KtExpression {
        var e: KtExpression = this
        while (e is KtParenthesizedExpression) e = e.expression ?: return e
        return e
    }

    /** Children in AST order (leaves included: operators, keywords) with whitespace/comments dropped. */
    private fun PsiElement.meaningfulChildren(): List<PsiElement> {
        val out = mutableListOf<PsiElement>()
        var child = firstChild
        while (child != null) {
            if (child !is PsiWhiteSpace && child !is PsiComment) out += child
            child = child.nextSibling
        }
        return out
    }

    /** Replace each range with as many newlines as it spanned, keeping downstream line numbers stable. */
    private fun blankOut(source: String, ranges: List<IntRange>): String {
        var out = source
        ranges.sortedByDescending { it.first }.forEach { range ->
            val slice = out.substring(range.first, range.last + 1)
            val newlines = "\n".repeat(slice.count { it == '\n' })
            out = out.replaceRange(range.first, range.last + 1, newlines)
        }
        return out
    }

    private fun applyEdits(text: String, matches: List<Match>): String {
        var out = text
        matches.sortedByDescending { it.start }.forEach { out = out.replaceRange(it.start, it.end, it.replacement) }
        return out
    }
}
