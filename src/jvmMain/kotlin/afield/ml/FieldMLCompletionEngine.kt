package afield.ml

import afield.Completion
import afield.CompletionRequest
import afield.Metadata
import afield.SymbolContextRequest
import afield.SymbolContextResponse
import afield.ai.KotlinSourceIndex
import afield.ml.ast.ConstructorDecl
import afield.ml.ast.ETypeDecl
import afield.ml.ast.Expr
import afield.ml.ast.PConstructor
import afield.ml.ast.PList
import afield.ml.ast.PTuple
import afield.ml.ast.PVar
import afield.ml.ast.Pattern
import afield.ml.type.FieldMLHostTypes
import afield.ml.type.FieldMLTypePrelude
import afield.ml.type.Scheme
import afield.ml.type.TExpr
import afield.ml.type.TExprLambda
import afield.ml.type.TExprLet
import afield.ml.type.TExprRecord
import afield.ml.type.TExprTypeDecl
import afield.ml.type.TBool
import afield.ml.type.TFloat
import afield.ml.type.TFun
import afield.ml.type.THost
import afield.ml.type.TInt
import afield.ml.type.TRecord
import afield.ml.type.TString
import afield.ml.type.TVar
import afield.ml.type.Type
import afield.ml.type.TypePrinter
import afield.ml.type.walkTyped
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Completion and hover for a FieldML box: what the editor offers under the caret, and what the
 * card over a symbol says.
 *
 * The difficulty is that the text is half-written, so the compiler will usually refuse it. Both
 * entry points compile the box anyway and read the typed tree if one came back. When none did,
 * they fall back to compiling the top-level statements above the caret and keeping the longest
 * run of them that does compile, skipping the broken ones rather than stopping at the first,
 * because the broken one is normally the line being typed. Below that is a regex over `let`, `fun`
 * and lambda parameters, which finds the names in scope without their types.
 *
 * Which *kind* of thing can go under the caret is decided before any candidate is gathered, and
 * two of those contexts answer with nothing rather than falling through: inside the string
 * literal of a `host…` intrinsic, and after a record field's dot, a member is the only thing that
 * can legally follow, so an empty list is the right answer when no member matches. Falling
 * through to the general list there is how `img.wi` came to offer the `with` keyword.
 *
 * Everywhere else the list is five sources merged and deduplicated — snippets, keywords, the
 * prelude, the bindings in scope, and the constructors this box declared — sorted by a rank that
 * puts the closest thing first: a typed local binding at 0, a snippet at 10, a constructor at 16,
 * the graphics prelude at 18, the rest of the prelude at 24, the `host…` names at 34, and a
 * keyword last at 40. A `|>` to the left of the caret switches the prelude to a second table for
 * any name whose final argument accepts what the pipe is carrying, which is what floats `moveTo`,
 * `map` and the rest of `pipePreferred` to the top of a pipeline.
 *
 * `hostTap`'s completions are the one place this reads something other than the box: it lists and
 * describes Kotlin extension functions out of Field's own source, through `KotlinSourceIndex`.
 */

/** Completion and hover for FieldML, worked out from the box's own text on every keystroke. */
object FieldMLCompletionEngine {
    /**
     * The completion list for a caret position, and the span of text it would replace.
     *
     * Dispatches on context first: a `host…` string, a record field, or the general case. The
     * general case merges snippets, keywords, the prelude, the bindings in scope and this box's
     * constructors, and ranks them so that what was written nearby comes before what the language
     * merely offers.
     */
    fun complete(request: CompletionRequest): Completion {
        val code = request.allCode
        val cursor = request.position.coerceIn(0, code.length)

        hostTapTargetContext(code, cursor)?.let { context ->
            return completion(context.boundsStart, context.boundsEnd, hostTapTargetItems(context.prefix))
        }

        hostTapArgumentContext(code, cursor)?.let { context ->
            return completion(context.boundsStart, context.boundsEnd, hostTapArgumentItems(context))
        }

        // These two are where the search ends, not one option among several. Inside a member name —
        // after a dot, or inside a `hostGet "…"` — the only thing that can legally follow is a member,
        // so an empty answer is the right answer. Falling through to the general list is how `img.wi`
        // came to offer the `with` keyword.
        hostMemberContext(code, cursor)?.let { context ->
            return completion(context.boundsStart, context.boundsEnd, hostMemberItems(code, context))
        }

        recordFieldContext(code, cursor)?.let { context ->
            return completion(context.boundsStart, context.boundsEnd, recordFieldItems(code, context))
        }

        val bounds = identifierBounds(code, cursor)
        val prefix = code.substring(bounds.first, bounds.second)
        val pipeType = pipeInputType(code, bounds.first)
        val typedNames = typedVisibleNames(code, cursor)
        val lexicalNames = lexicalVisibleNames(code, cursor)
        val constructorItems = constructorItems(code, prefix)
        val items = buildList {
            addAll(snippetItems(prefix))
            addAll(keywordItems(prefix))
            addAll(preludeItems(prefix, pipeType))
            addAll(localItems(typedNames, lexicalNames, prefix))
            addAll(constructorItems)
        }
            .distinctBy { it.metadata.text to it.metadata.displayText }
            .sortedWith(compareBy<RankedMetadata> { it.rank }.thenBy { it.metadata.displayText })
            .map { it.metadata }

        return completion(bounds.first, bounds.second, items)
    }

    /**
     * The hover card for whatever is under the caret: a declaration line, a one-word detail
     * saying what kind of thing it is, and whatever documentation there is for it.
     *
     * The candidates are tried nearest-first — an indexed Kotlin function named in a `hostTap`
     * string, a record field, a local binding, a prelude name, a constructor this box declared,
     * then a keyword. A local binding and a record field get their inferred type printed and no
     * prose, because the type is the answer. An empty response means nothing under the caret was
     * recognised, which for a half-written line is common and not an error.
     */
    fun describeSymbolContext(request: SymbolContextRequest): SymbolContextResponse {
        hostTapTargetContext(request.allCode, request.position)?.let { context ->
            val exact = indexedKotlinFunctions().firstOrNull { it.qualifiedName == context.prefix }
            if (exact != null) {
                return SymbolContextResponse(
                    symbol = exact.qualifiedName,
                    html = symbolHtml(
                        declaration = exact.signature.ifBlank { exact.qualifiedName },
                        detail = "indexed Kotlin function",
                        documentation = exact.kdoc.orEmpty(),
                    ),
                )
            }
        }

        recordFieldContext(request.allCode, request.position)?.let { context ->
            val type = typedVisibleNames(request.allCode, context.receiverEnd)[context.receiver] as? TRecord
            val fieldType = type?.fields?.get(context.prefix)
            if (fieldType != null) {
                return SymbolContextResponse(
                    symbol = context.prefix,
                    html = symbolHtml(
                        declaration = "${context.receiver}.${context.prefix} : ${TypePrinter().print(fieldType)}",
                        detail = "record field",
                        documentation = "",
                    ),
                )
            }
        }

        val bounds = symbolBoundsAt(request.allCode, request.position) ?: return SymbolContextResponse()
        val symbol = bounds.text
        val locals = typedVisibleNames(request.allCode, request.position)
        locals[symbol]?.let { type ->
            return SymbolContextResponse(
                symbol = symbol,
                html = symbolHtml(
                    declaration = "$symbol : ${TypePrinter().print(type)}",
                    detail = "local FieldML binding",
                    documentation = "",
                ),
            )
        }

        FieldMLTypePrelude.schemes[symbol]?.let { scheme ->
            return SymbolContextResponse(
                symbol = symbol,
                html = symbolHtml(
                    declaration = "$symbol : ${TypePrinter().printSignature(scheme.body)}",
                    detail = if (symbol in graphicsPrelude) "FieldML graphics prelude" else "FieldML prelude",
                    documentation = docs[symbol].orEmpty(),
                ),
            )
        }

        constructorDecls(request.allCode).firstOrNull { it.name == symbol }?.let {
            return SymbolContextResponse(
                symbol = symbol,
                html = symbolHtml(
                    declaration = it.name,
                    detail = "FieldML constructor",
                    documentation = "Constructor from this box's algebraic data type declaration.",
                ),
            )
        }

        if (symbol in keywords) {
            return SymbolContextResponse(
                symbol = symbol,
                html = symbolHtml(
                    declaration = symbol,
                    detail = "FieldML keyword",
                    documentation = docs[symbol].orEmpty(),
                ),
            )
        }

        return SymbolContextResponse()
    }

    /**
     * Always a reply, even an empty one. The editor's popup is waiting on this message and has no way
     * to tell "nothing matches" from "still thinking", so staying silent leaves it spinning until the
     * RPC times out fifteen seconds later.
     */
    private fun completion(boundsStart: Int, boundsEnd: Int, items: List<Metadata>): Completion =
        Completion(boundsStart = boundsStart, boundsEnd = boundsEnd, metadata = items)

    private fun identifierBounds(code: String, cursor: Int): Pair<Int, Int> {
        var start = cursor
        while (start > 0 && isIdentifierPart(code[start - 1])) start--
        var end = cursor
        while (end < code.length && isIdentifierPart(code[end])) end++
        return start to end
    }

    private fun symbolBoundsAt(code: String, cursor: Int): SymbolBounds? {
        if (code.isEmpty()) return null
        val safe = cursor.coerceIn(0, code.length)
        val probe = when {
            safe < code.length && isIdentifierPart(code[safe]) -> safe
            safe > 0 && isIdentifierPart(code[safe - 1]) -> safe - 1
            else -> return null
        }
        var start = probe
        while (start > 0 && isIdentifierPart(code[start - 1])) start--
        var end = probe + 1
        while (end < code.length && isIdentifierPart(code[end])) end++
        if (end <= start) return null
        return SymbolBounds(start, end, code.substring(start, end))
    }

    private fun isIdentifierPart(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '\''

    private fun keywordItems(prefix: String): List<RankedMetadata> =
        keywords
            .filter { it.startsWith(prefix) }
            .map {
                RankedMetadata(
                    rank = 40,
                    metadata = Metadata(
                        icon = "keyword",
                        tail = "keyword",
                        text = it,
                        displayText = it,
                        documentation = docs[it] ?: "FieldML keyword.",
                        category = "snippets",
                    ),
                )
            }

    private fun preludeItems(prefix: String, pipeType: Type?): List<RankedMetadata> {
        val printer = TypePrinter()
        return FieldMLTypePrelude.schemes.entries
            .filter { (name, _) -> name.startsWith(prefix) }
            .map { (name, scheme) ->
                val pipeCompatible = pipeType != null && acceptsPipeInput(scheme.body, pipeType)
                RankedMetadata(
                    rank = if (pipeCompatible) pipeRank(name) else preludeRank(name),
                    metadata = Metadata(
                        icon = if (name.firstOrNull()?.isUpperCase() == true) "class" else "function",
                        tail = printer.printSignature(scheme.body),
                        text = name,
                        displayText = name,
                        documentation = docs[name] ?: preludeDocumentation(name, scheme),
                        category = if (name in graphicsPrelude) "graphics" else "functions",
                    ),
                )
            }
    }

    private fun preludeDocumentation(name: String, scheme: Scheme): String =
        buildString {
            append("```fieldml\n")
            append(name)
            append(" : ")
            append(TypePrinter().printSignature(scheme.body))
            append("\n```")
        }

    private fun preludeRank(name: String): Int =
        when {
            name in graphicsPrelude -> 18
            name.startsWith("host") -> 34
            else -> 24
        }

    private fun pipeRank(name: String): Int =
        when {
            name in pipePreferred -> 4 + pipePreferred.indexOf(name)
            name in graphicsPrelude -> 14
            else -> 32
        }

    private fun acceptsPipeInput(type: Type, input: Type): Boolean {
        var cursor = type
        var lastArgument: Type? = null
        while (cursor is TFun) {
            lastArgument = cursor.from
            cursor = cursor.to
        }
        return lastArgument?.let { compatibleTypes(it, input) } == true
    }

    private fun snippetItems(prefix: String): List<RankedMetadata> =
        snippets
            .filter { it.label.startsWith(prefix) }
            .mapIndexed { index, snippet ->
                RankedMetadata(
                    rank = 10 + index,
                    metadata = Metadata(
                        icon = "snippet",
                        tail = "snippet",
                        text = snippet.insertText,
                        displayText = snippet.label,
                        documentation = snippet.documentation,
                        category = "snippets",
                    ),
                )
            }

    private fun localItems(
        typedNames: Map<String, Type>,
        lexicalNames: Set<String>,
        prefix: String,
    ): List<RankedMetadata> {
        val printer = TypePrinter()
        val typed = typedNames
            .filterKeys { it.startsWith(prefix) }
            .map { (name, type) ->
                RankedMetadata(
                    rank = 0,
                    metadata = Metadata(
                        icon = "variable",
                        tail = printer.print(type),
                        text = name,
                        displayText = name,
                        documentation = "Local FieldML binding.\n\n```fieldml\n$name : ${printer.print(type)}\n```",
                        category = "local",
                    ),
                )
            }
        val typedKeys = typedNames.keys
        val lexical = lexicalNames
            .filter { it.startsWith(prefix) && it !in typedKeys }
            .map {
                RankedMetadata(
                    rank = 2,
                    metadata = Metadata(
                        icon = "variable",
                        tail = "local",
                        text = it,
                        displayText = it,
                        documentation = "Local FieldML binding.",
                        category = "local",
                    ),
                )
            }
        return typed + lexical
    }

    private fun constructorItems(code: String, prefix: String): List<RankedMetadata> =
        constructorDecls(code)
            .filter { it.name.startsWith(prefix) }
            .map {
                RankedMetadata(
                    rank = 16,
                    metadata = Metadata(
                        icon = "class",
                        tail = "constructor",
                        text = it.name,
                        displayText = it.name,
                        documentation = "Constructor from this FieldML box.",
                        category = "types",
                    ),
                )
            }

    private fun lexicalVisibleNames(code: String, cursor: Int): Set<String> {
        val before = code.take(cursor)
        val names = linkedSetOf<String>()
        Regex("""\blet\s+([a-z_][A-Za-z0-9_']*)""").findAll(before).forEach { names += it.groupValues[1] }
        Regex("""\bfun\s+([a-z_][A-Za-z0-9_']*)""").findAll(before).forEach { names += it.groupValues[1] }
        Regex("""\b([a-z_][A-Za-z0-9_']*)\s*->""").findAll(before).forEach { names += it.groupValues[1] }
        return names
    }

    private fun typedVisibleNames(code: String, cursor: Int): Map<String, Type> {
        // Callback parameters come last: inside `fun p -> …`, `p` shadows whatever is above it.
        val fallback = typedVisibleNameFallback(code, cursor) + callbackParameterTypes(code, cursor)
        val typed = FieldMLCompiler.compile(code).typed ?: return fallback
        val out = linkedMapOf<String, Type>()
        fun visit(expr: TExpr) {
            when (expr) {
                is TExprLet -> {
                    if (expr.range.start.index <= cursor) {
                        out[expr.name] = expr.value.type
                        expr.parameters.forEach { (name, type) -> out.putIfAbsent(name, type) }
                    }
                    visit(expr.value)
                    visit(expr.body)
                }
                is TExprTypeDecl -> visit(expr.body)
                else -> Unit
            }
        }
        visit(typed)
        return fallback + out
    }

    /**
     * Types for the bindings above the caret when the box as a whole does not compile — which, while
     * someone is typing, is nearly always. The document is rebuilt as the *scope prelude*: the
     * top-level `let`/`type` statements that precede the caret, in order, dropping any that will not
     * compile. That is a real program, so the ordinary inferencer types it, and a binding that leans
     * on an earlier one (`let img = hostNew … Img`) sees the earlier one — which is the whole point,
     * and is what compiling each binding on its own could never do.
     */
    private fun typedVisibleNameFallback(code: String, cursor: Int): Map<String, Type> {
        val typed = compiledScopePrelude(code, cursor)?.typed ?: return emptyMap()
        val out = linkedMapOf<String, Type>()
        fun visit(expr: TExpr) {
            when (expr) {
                is TExprLet -> {
                    out[expr.name] = expr.value.type
                    expr.parameters.forEach { (name, type) -> out.putIfAbsent(name, type) }
                    visit(expr.value)
                    visit(expr.body)
                }
                is TExprTypeDecl -> visit(expr.body)
                else -> Unit
            }
        }
        visit(typed)
        return out
    }

    private fun pipeInputType(code: String, cursor: Int): Type? {
        val pipe = maskedForScanning(code).lastIndexOf("|>", startIndex = (cursor - 1).coerceAtLeast(0))
        if (pipe < 0) return null
        return typeOfExpressionEndingAt(code, pipe)
    }

    // ---- scope reconstruction ---------------------------------------------------------------
    //
    // Everything below exists because completion runs on source that is, by definition, half-written.
    // Asking the compiler about a truncated prefix of the box answers "syntax error" and nothing else,
    // so instead the caret's surroundings are rebuilt into the smallest program that still says what
    // the receiver's type is.

    /**
     * The type of the expression that ends at [end] — the receiver of a pipe, typically — evaluated
     * in the scope the caret actually sits in.
     *
     * Two things have to be true at once for this to work inside a record literal or an argument
     * list: the expression has to be cut back to just itself (`width = img |> …` is not a program,
     * `img` is), and the bindings above it have to come along (`img` means nothing without its `let`).
     */
    private fun typeOfExpressionEndingAt(code: String, end: Int): Type? {
        val segment = expressionSegmentEndingAt(code, end)
        if (segment.isBlank()) return null
        // The segment walk stops at the enclosing `->`, so a receiver that is a callback parameter
        // would otherwise be a free name. Binding them here is what makes `fun p -> p |> hostCall "…`
        // complete against `Vec3` rather than against nothing.
        val prelude = listOf(compilingScopePreludeSource(code, end), callbackParameterBindings(code, end))
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val source = if (prelude.isBlank()) segment else "$prelude\n$segment"
        return FieldMLCompiler.compile(source).takeIf { it.errorFree }?.typed?.type
    }

    /**
     * The innermost expression ending at [end], found by walking back to whatever opened it: an
     * unmatched bracket, a `=`/`,`/`;`, an arrow or an `in`/`then`/`else`, or the start of a top-level
     * statement.
     *
     * A newline only ends the walk when the line that follows it starts in column zero, which is the
     * same rule the parser uses to end a top-level binding. That is what lets a pipe stay in one piece
     * across lines while `img |> …` on its own line does not swallow the `let` above it.
     */
    private fun expressionSegmentEndingAt(code: String, end: Int): String {
        val mask = maskedForScanning(code)
        var depth = 0
        var index = (end - 1).coerceAtMost(mask.length - 1)
        var start = 0
        while (index >= 0) {
            when (val ch = mask[index]) {
                ')', ']', '}' -> depth++
                '(', '[', '{' -> {
                    if (depth == 0) {
                        start = index + 1
                        break
                    }
                    depth--
                }
                '=', ',', ';' -> {
                    // `==`, `<=`, `>=`, `!=` are operators inside one expression, not separators.
                    val binary = ch == '=' &&
                        (index > 0 && mask[index - 1] in "=<>!" || index + 1 < mask.length && mask[index + 1] == '=')
                    if (depth == 0 && !binary) {
                        start = index + 1
                        break
                    }
                }
                '>' -> if (depth == 0 && index > 0 && mask[index - 1] == '-') {
                    start = index + 1
                    break
                }
                '\n' -> if (depth == 0 && startsTopLevelStatement(mask, index + 1)) {
                    start = index + 1
                    break
                }
                else -> {
                    val keyword = keywordEndingAt(mask, index)
                    if (depth == 0 && keyword != null) {
                        start = index + 1
                        break
                    }
                }
            }
            index--
        }
        return code.substring(start.coerceAtMost(end), end).trim()
    }

    /** True when the line beginning at [lineStart] has content in column zero. */
    private fun startsTopLevelStatement(mask: String, lineStart: Int): Boolean =
        lineStart < mask.length && !mask[lineStart].isWhitespace()

    private fun keywordEndingAt(mask: String, index: Int): String? =
        expressionOpeningKeywords.firstOrNull { keyword ->
            val start = index - keyword.length + 1
            start > 0 &&
                mask.regionMatches(start, keyword, 0, keyword.length) &&
                !isIdentifierPart(mask[start - 1]) &&
                (index + 1 >= mask.length || !isIdentifierPart(mask[index + 1]))
        }

    private val expressionOpeningKeywords = listOf("in", "then", "else")

    private val statementOpener = Regex("""^(let|type)\b""")

    private fun compilingScopePreludeSource(code: String, cursor: Int): String =
        compiledScopePrelude(code, cursor)?.source.orEmpty()

    /**
     * The longest run of top-level statements above the caret that compiles. Statements that do not
     * are skipped rather than ending the walk, because the one being typed is usually broken and the
     * ones after it usually are not.
     *
     * Memoised on the statement list: this runs on every keystroke, and the statements above the caret
     * are exactly what does not change while a line is being typed.
     */
    private fun compiledScopePrelude(code: String, cursor: Int): CompilationResult? {
        val statements = topLevelStatements(code, cursor)
        if (statements.isEmpty()) return null
        scopePreludeCache[statements]?.let { return it.value }
        var kept = emptyList<String>()
        var result: CompilationResult? = null
        statements.forEach { statement ->
            val candidate = kept + statement
            val compiled = FieldMLCompiler.compile(candidate.joinToString("\n"))
            if (compiled.errorFree) {
                kept = candidate
                result = compiled
            }
        }
        synchronized(scopePreludeCache) {
            if (scopePreludeCache.size > 32) scopePreludeCache.clear()
            scopePreludeCache[statements] = CachedPrelude(result)
        }
        return result
    }

    /**
     * The `let` and `type` statements before the caret, each with its indented continuation lines, so
     * that a binding whose value runs over several lines survives intact.
     */
    private fun topLevelStatements(code: String, cursor: Int): List<String> {
        val before = maskedForScanning(code).take(cursor.coerceIn(0, code.length))
        // Split on '\n' rather than lines(): masking preserves length, and that only stays true — so
        // that `raw` lines up with `code` — if a '\r' counts as one of the characters consumed.
        val lines = before.split('\n')
        val statements = mutableListOf<String>()
        var current: MutableList<String>? = null
        var offset = 0
        lines.forEach { masked ->
            val raw = code.substring(offset.coerceAtMost(code.length), (offset + masked.length).coerceAtMost(code.length))
            offset += masked.length + 1
            val opensStatement = masked.isNotEmpty() && !masked[0].isWhitespace() &&
                statementOpener.containsMatchIn(masked)
            val open = current
            when {
                opensStatement -> {
                    open?.let { statements += it.joinToString("\n") }
                    current = mutableListOf(raw)
                }
                open == null -> Unit
                masked.isBlank() || masked[0].isWhitespace() -> open.add(raw)
                else -> {
                    // A column-zero line that is not a binding ends the one above it and is the box's
                    // result expression, which contributes no names.
                    statements += open.joinToString("\n")
                    current = null
                }
            }
        }
        current?.let { statements += it.joinToString("\n") }
        return statements
    }

    /**
     * [code] with string literals and comments blanked out, so the backward scans above cannot be
     * fooled by an `=` or a bracket inside `hostCall "at" (…)` or a sentence in a comment. Indices are
     * preserved, and newlines are kept so line structure still reads.
     */
    private fun maskedForScanning(code: String): String {
        maskCache[code]?.let { return it }
        val out = StringBuilder(code.length)
        var index = 0
        while (index < code.length) {
            val ch = code[index]
            when {
                ch == '"' -> {
                    out.append(' ')
                    index++
                    while (index < code.length && code[index] != '"') {
                        if (code[index] == '\\' && index + 1 < code.length) {
                            out.append(' ')
                            index++
                        }
                        out.append(if (code[index] == '\n') '\n' else ' ')
                        index++
                    }
                    if (index < code.length) {
                        out.append(' ')
                        index++
                    }
                }
                ch == '/' && index + 1 < code.length && code[index + 1] == '/' -> {
                    while (index < code.length && code[index] != '\n') {
                        out.append(' ')
                        index++
                    }
                }
                ch == '(' && index + 1 < code.length && code[index + 1] == '*' -> {
                    var depth = 0
                    while (index < code.length) {
                        if (code.startsWith("(*", index)) depth++
                        if (code.startsWith("*)", index)) depth--
                        out.append(if (code[index] == '\n') '\n' else ' ')
                        index++
                        if (depth == 0) break
                    }
                }
                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        val masked = out.toString()
        synchronized(maskCache) {
            if (maskCache.size > 16) maskCache.clear()
            maskCache[code] = masked
        }
        return masked
    }

    // ---- callback parameters -------------------------------------------------------------------
    //
    // A lambda handed to a JVM method has typed parameters now — `Inferencer.preInferHostSpine` reads
    // them off the method's signature. Completion cannot simply read them back, because the source it
    // is given is half-written and does not parse, so instead it *asks*: rebuild the enclosing host
    // call with the lambda closed off at its arrow, compile that, and take the parameter types out of
    // the result.
    //
    // Asking rather than re-deriving is the point. The mapping from `hostCall2 "m" a f t` to a JVM
    // parameter index lives in the inferencer, and a second copy of it here would be a second copy to
    // keep in step. This costs one small compile per keystroke, memoised on the rebuilt program.

    /** The types of the parameters of the lambda the caret sits inside, or empty when it does not. */
    private fun callbackParameterTypes(code: String, cursor: Int): Map<String, Type> =
        enclosingCallbackParameters(code, cursor)
            .filterValues { it is THost || it in scalarWitnesses.keys }

    /**
     * The same thing as `let` statements, for the paths that rebuild a *program* rather than consult a
     * map. A host value is reconstructed with `hostAs`, which is exactly what a FieldML author would
     * have had to write here before this existed.
     */
    private fun callbackParameterBindings(code: String, cursor: Int): String =
        enclosingCallbackParameters(code, cursor)
            .mapNotNull { (name, type) -> witnessFor(type)?.let { "let $name = $it" } }
            .joinToString("\n")

    private val scalarWitnesses: Map<Type, String> = mapOf(
        TInt to "0",
        TFloat to "0.0",
        TBool to "true",
        TString to "\"\"",
    )

    /** An expression of [type], or null when there is no way to write one — in which case: no binding. */
    private fun witnessFor(type: Type): String? {
        scalarWitnesses[type]?.let { return it }
        val host = type as? THost ?: return null
        if (FieldMLHostTypes.jvmClassOf(host) == null) return null
        return """hostAs "${host.name}" hostInput"""
    }

    /**
     * The parameters of the innermost `fun … ->` the caret is inside, with the types the JVM signature
     * behind it gives them.
     *
     * The rebuild is deliberately literal: everything from the opening bracket up to the lambda's
     * arrow is kept verbatim — which is what carries the tuple spread in
     * `hostCall "generate" (4, 4, fun x y -> …` — and the body is replaced by the first parameter so
     * that the result parses no matter how half-finished the real body is.
     */
    private fun enclosingCallbackParameters(code: String, cursor: Int): Map<String, Type> {
        val lambda = enclosingLambda(code, cursor) ?: return emptyMap()
        val call = expressionSegmentEndingAt(code, lambda.open)
        if (call.isBlank()) return emptyMap()
        val head = code.substring(lambda.open + 1, lambda.arrow)
        val probe = "$call($head-> ${lambda.parameters.first()})"
        val prelude = compilingScopePreludeSource(code, lambda.open)
        val source = if (prelude.isBlank()) probe else "$prelude\n$probe"
        callbackProbeCache[source]?.let { return it.value }

        val typed = FieldMLCompiler.compile(source).takeIf { it.errorFree }?.typed
        var found = emptyMap<String, Type>()
        typed?.walkTyped { node ->
            if (node is TExprLambda && node.parameters.map { it.first } == lambda.parameters) {
                found = node.parameters.toMap()
            }
        }
        synchronized(callbackProbeCache) {
            if (callbackProbeCache.size > 32) callbackProbeCache.clear()
            callbackProbeCache[source] = CachedParameters(found)
        }
        return found
    }

    private class CachedParameters(val value: Map<String, Type>)

    private val callbackProbeCache = java.util.Collections.synchronizedMap(
        linkedMapOf<String, CachedParameters>(),
    )

    private class EnclosingLambda(val open: Int, val arrow: Int, val parameters: List<String>)

    /**
     * The innermost unclosed `(` before the caret that has a `fun … ->` written inside it, plus that
     * lambda's parameters. Null when the caret is not inside a lambda argument at all, which is the
     * common case and has to cost nothing.
     *
     * Outward, not just innermost: in `hostCall "generate" (4, 4, fun x y -> toFloat (x` the caret is
     * inside the `(x`, and the bracket that carries the lambda is two levels out.
     */
    private fun enclosingLambda(code: String, cursor: Int): EnclosingLambda? {
        val mask = maskedForScanning(code)
        var depth = 0
        var index = (cursor - 1).coerceAtMost(mask.length - 1)
        while (index >= 0) {
            when (mask[index]) {
                ')', ']', '}' -> depth++
                '(' -> if (depth == 0) lambdaOpenedAt(mask, index, cursor)?.let { return it } else depth--
                '[', '{' -> if (depth > 0) depth--
            }
            index--
        }
        return null
    }

    private fun lambdaOpenedAt(mask: String, open: Int, cursor: Int): EnclosingLambda? {
        val header = lambdaHeader.findAll(mask.substring(open + 1, cursor)).lastOrNull() ?: return null
        val parameters = header.groupValues[1].trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (parameters.isEmpty()) return null
        // `header.range.last` indexes the arrow's `>` within the substring that starts at `open + 1`,
        // so the `-` before it is at `open + last`. The rebuild wants everything up to there.
        return EnclosingLambda(open, open + header.range.last, parameters)
    }

    private val lambdaHeader = Regex("""\bfun\s+((?:[a-z_][A-Za-z0-9_']*\s+)+)->""")

    private val CompilationResult.errorFree: Boolean
        get() = typed != null && diagnostics.none { it.severity == afield.ml.diag.Severity.Error }

    private class CachedPrelude(val value: CompilationResult?)

    private val scopePreludeCache = java.util.Collections.synchronizedMap(
        linkedMapOf<List<String>, CachedPrelude>(),
    )
    private val maskCache = java.util.Collections.synchronizedMap(linkedMapOf<String, String>())

    private fun recordFieldContext(code: String, cursor: Int): RecordFieldContext? {
        val dot = code.lastIndexOf('.', startIndex = (cursor - 1).coerceAtLeast(0))
        if (dot < 0) return null
        if (code.substring(dot + 1, cursor).any { !isIdentifierPart(it) }) return null
        var nameEnd = dot
        while (nameEnd > 0 && code[nameEnd - 1].isWhitespace()) nameEnd--
        var nameStart = nameEnd
        while (nameStart > 0 && isIdentifierPart(code[nameStart - 1])) nameStart--
        val receiver = code.substring(nameStart, nameEnd)
        if (receiver.isBlank()) return null
        val bounds = identifierBounds(code, cursor)
        return RecordFieldContext(
            receiver = receiver,
            prefix = code.substring(bounds.first, bounds.second),
            receiverEnd = nameEnd,
            boundsStart = bounds.first,
            boundsEnd = bounds.second,
        )
    }

    private fun recordFieldItems(code: String, context: RecordFieldContext): List<Metadata> {
        // The whole box, with the caret's position as the visibility cut — not the prefix up to the
        // receiver. A prefix that stops mid-record never parses, and the receiver's `let` is then just
        // as invisible as everything else.
        val receiverType = typedVisibleNames(code, context.receiverEnd)[context.receiver]
            ?: typeOfExpressionEndingAt(code, context.receiverEnd)
        // Dot access on a host value reads a JVM member, so a receiver whose class is known completes
        // from reflection. This is the payoff of typing host values at all: `img.` offers what `Img`
        // has, which is something FieldML could not do at any point before tonight.
        if (receiverType is THost) return hostMemberMetadata(receiverType, context.prefix, context.receiver)
        val type = receiverType as? TRecord ?: return emptyList()
        return type.fields.entries
            .filter { (field, _) -> field.startsWith(context.prefix) }
            .map { (field, type) ->
                Metadata(
                    icon = "property",
                    tail = TypePrinter().print(type),
                    text = field,
                    displayText = field,
                    documentation = "Record field.\n\n```fieldml\n${context.receiver}.$field : ${TypePrinter().print(type)}\n```",
                    category = "values",
                )
            }
    }

    private fun constructorDecls(code: String): List<ConstructorDecl> {
        val expr = FieldMLCompiler.compile(code).surface ?: return regexConstructorNames(code).map {
            ConstructorDecl(it, emptyList(), afield.ml.diag.SourceRange(afield.ml.diag.SourcePos(0, 1, 1), afield.ml.diag.SourcePos(0, 1, 1)))
        }
        val out = mutableListOf<ConstructorDecl>()
        fun visit(current: Expr) {
            if (current is ETypeDecl) {
                out += current.declaration.constructors
                visit(current.body)
            }
        }
        visit(expr)
        return out
    }

    private fun regexConstructorNames(code: String): List<String> =
        Regex("""\btype\s+\w+(?:\s+\w+)*\s*=\s*([^\n]+)""")
            .findAll(code)
            .flatMap { match ->
                match.groupValues[1]
                    .split("|")
                    .asSequence()
                    .mapNotNull { it.trim().split(Regex("""\s+""")).firstOrNull() }
                    .filter { it.firstOrNull()?.isUpperCase() == true }
            }
            .toList()

    /**
     * The caret sitting inside the string literal of a `host…` intrinsic — the one place where what a
     * FieldML author needs to know is *the JVM's* vocabulary rather than FieldML's.
     *
     * The receiver comes from the pipe on the left, which is why this only fires for the piped form
     * (`img |> hostGet "wi`). In `hostGet "wi" img` the receiver is written after the caret and
     * genuinely is not available yet; offering everything in that case would be worse than offering
     * nothing. `hostExt` is the exception: its owner class is written *before* the name.
     */
    private fun hostMemberContext(code: String, cursor: Int): HostStringContext? {
        val quote = code.lastIndexOf('"', startIndex = (cursor - 1).coerceAtLeast(0))
        if (quote < 0) return null
        val nextQuote = code.indexOf('"', quote + 1)
        if (nextQuote in 0 until cursor) return null
        val match = Regex("""\b(host[A-Za-z0-9]*)\s+(?:([A-Za-z_][A-Za-z0-9_']*)\s+)?$""")
            .find(code.substring(0, quote).takeLast(64)) ?: return null
        val intrinsic = match.groupValues[1]
        if (intrinsic !in hostIntrinsicsWithNamedMembers) return null
        return HostStringContext(
            intrinsic = intrinsic,
            owner = match.groupValues[2].takeIf { it.isNotBlank() },
            prefix = code.substring(quote + 1, cursor),
            boundsStart = quote + 1,
            boundsEnd = cursor,
            quoteIndex = quote,
        )
    }

    private val hostIntrinsicsWithNamedMembers = setOf(
        "hostGet",
        "hostSet",
        "hostCall",
        "hostCall0",
        "hostCall1",
        "hostCall2",
        "hostExt",
        "hostClass",
        "hostAs",
    )

    private fun hostMemberItems(code: String, context: HostStringContext): List<Metadata> {
        if (context.intrinsic == "hostClass" || context.intrinsic == "hostAs") {
            return hostClassNameItems(context.prefix)
        }
        val receiver = when (context.intrinsic) {
            // `hostExt Owner "name"` calls a static of Owner, and Owner is a name in scope.
            "hostExt" -> context.owner?.let { typedVisibleNames(code, context.quoteIndex)[it] }
            else -> pipeInputType(code, context.quoteIndex)
        } ?: return emptyList()
        return when (context.intrinsic) {
            "hostGet", "hostSet" -> hostMemberMetadata(receiver, context.prefix, receiverLabel(receiver))
            else -> hostMethodMetadata(receiver, context.prefix, receiverLabel(receiver))
        }
    }

    private fun receiverLabel(type: Type): String = TypePrinter().print(type)

    private fun hostMemberMetadata(receiver: Type, prefix: String, label: String): List<Metadata> =
        FieldMLHostTypes.readableMembers(receiver)
            .filter { it.name.startsWith(prefix) }
            .take(200)
            .map { member ->
                val rendered = TypePrinter().printSignature(member.type)
                Metadata(
                    icon = "property",
                    tail = rendered,
                    text = member.name,
                    displayText = member.name,
                    documentation = "JVM member of `${member.owner}`.\n\n```fieldml\n$label.${member.name} : $rendered\n```",
                    category = "values",
                )
            }

    private fun hostMethodMetadata(receiver: Type, prefix: String, label: String): List<Metadata> =
        FieldMLHostTypes.callableMethods(receiver)
            .filter { it.name.startsWith(prefix) }
            .take(200)
            .map { method ->
                val rendered = TypePrinter().printSignature(method.resultType)
                val parameters = method.parameters.joinToString(", ")
                Metadata(
                    icon = "method",
                    tail = "($parameters) -> $rendered",
                    text = method.name,
                    displayText = method.name,
                    documentation = "JVM method of `${method.owner}`.\n\n```kotlin\n${method.owner}.${method.name}" +
                        "($parameters): $rendered\n```",
                    category = "functions",
                )
            }

    /**
     * Class names for `hostClass` and `hostAs`: the short aliases FieldML already understands, then
     * the classes in Field's own source index. The index stores a simple name and a file, so the
     * package has to be read back out of the file — done once per process, because it is hundreds of
     * files and this runs on a keystroke.
     */
    private fun hostClassNameItems(prefix: String): List<Metadata> =
        (FieldMLHostTypes.curatedTypeNames + indexedKotlinClassNames())
            .asSequence()
            .filter { it.startsWith(prefix) }
            .distinct()
            .take(120)
            .map { name ->
                Metadata(
                    icon = "class",
                    tail = "JVM class",
                    text = name,
                    displayText = name,
                    documentation = "```fieldml\nhostClass \"$name\"\n```",
                    category = "values",
                )
            }
            .toList()

    private val indexedKotlinClassNames: () -> List<String> = run {
        var cached: List<String>? = null
        {
            cached ?: buildIndexedKotlinClassNames().also { cached = it }
        }
    }

    private fun buildIndexedKotlinClassNames(): List<String> {
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() ?: return emptyList()
        val packages = mutableMapOf<String, String>()
        return index.definitions
            .asSequence()
            .filter { it.kind == "class" || it.kind == "object" || it.kind == "interface" }
            .mapNotNull { definition ->
                val pkg = packages.getOrPut(definition.filePath) {
                    val file = root.resolve(definition.filePath).normalize()
                    val text = runCatching { Files.readString(file) }.getOrNull() ?: return@getOrPut ""
                    Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""").find(text)?.groupValues?.get(1).orEmpty()
                }
                val simple = definition.name.substringAfterLast('.')
                if (pkg.isBlank()) simple else "$pkg.$simple"
            }
            .distinct()
            .sorted()
            .toList()
    }

    private fun hostTapTargetContext(code: String, cursor: Int): StringContext? {
        val quote = code.lastIndexOf('"', startIndex = (cursor - 1).coerceAtLeast(0))
        if (quote < 0) return null
        val beforeQuote = code.substring(0, quote)
        if (!Regex("""hostTap\s*$""").containsMatchIn(beforeQuote.takeLast(32))) return null
        val nextQuote = code.indexOf('"', quote + 1)
        if (nextQuote >= 0 && nextQuote < cursor) return null
        val prefix = code.substring(quote + 1, cursor)
        return StringContext(prefix, quote + 1, cursor)
    }

    private fun hostTapTargetItems(prefix: String): List<Metadata> =
        indexedKotlinFunctions()
            .filter { it.qualifiedName.startsWith(prefix) }
            .take(120)
            .map {
                Metadata(
                    icon = "function",
                    tail = it.filePath.substringAfterLast('/'),
                    text = it.qualifiedName,
                    displayText = it.qualifiedName,
                    documentation = buildString {
                        append("Indexed Kotlin function.\n\n```kotlin\n")
                        append(it.signature.ifBlank { it.qualifiedName })
                        append("\n```")
                        if (!it.kdoc.isNullOrBlank()) append("\n\n").append(it.kdoc.trim())
                    },
                    category = it.category ?: "functions",
                    documentationSourceName = it.qualifiedName,
                    documentationSourcePath = it.filePath,
                    documentationSourceLine = it.line,
                )
            }

    private fun hostTapArgumentContext(code: String, cursor: Int): HostTapArgumentContext? {
        val open = code.lastIndexOf('{', startIndex = (cursor - 1).coerceAtLeast(0))
        if (open < 0) return null
        val close = code.indexOf('}', open + 1)
        if (close >= 0 && close < cursor) return null
        val before = code.substring(0, open)
        val match = Regex("""hostTap\s+"([^"]+)"""").findAll(before).lastOrNull() ?: return null
        if (match.range.last < before.length - 80) return null
        val bounds = identifierBounds(code, cursor)
        val present = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*=""")
            .findAll(code.substring(open + 1, cursor))
            .map { it.groupValues[1] }
            .toSet()
        return HostTapArgumentContext(
            target = match.groupValues[1],
            prefix = code.substring(bounds.first, bounds.second),
            present = present,
            boundsStart = bounds.first,
            boundsEnd = bounds.second,
        )
    }

    private fun hostTapArgumentItems(context: HostTapArgumentContext): List<Metadata> {
        val function = indexedKotlinFunctions()
            .firstOrNull { it.qualifiedName == context.target }
            ?: return emptyList()
        val inputParameter = function.parameters.firstOrNull { !it.hasDefault }
        return function.parameters
            .asSequence()
            .filter { it.name != inputParameter?.name }
            .filter { it.name !in context.present }
            .filter { it.name.startsWith(context.prefix) }
            .map {
                Metadata(
                    icon = "property",
                    tail = it.type + if (it.hasDefault) " = ${it.defaultSource}" else "",
                    text = "${it.name} = ",
                    displayText = it.name,
                    documentation = "Named argument for `${function.qualifiedName}`.\n\n```kotlin\n${it.name}: ${it.type}${if (it.hasDefault) " = ${it.defaultSource}" else ""}\n```",
                    category = "values",
                    documentationSourceName = function.qualifiedName,
                    documentationSourcePath = function.filePath,
                    documentationSourceLine = function.line,
                )
            }
            .toList()
    }

    private fun indexedKotlinFunctions(): List<IndexedFunction> {
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
        val fromIndex = index?.definitions
            ?.asSequence()
            ?.filter { it.kind == "fun" }
            ?.mapNotNull { def ->
                val file = root.resolve(def.filePath).normalize()
                val text = runCatching { Files.readString(file) }.getOrNull() ?: return@mapNotNull null
                val pkg = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
                    .find(text)
                    ?.groupValues
                    ?.getOrNull(1)
                    .orEmpty()
                val simple = def.name.substringAfterLast('.')
                val signature = findFunctionSignature(text, simple) ?: ""
                IndexedFunction(
                    qualifiedName = listOf(pkg, simple).filter { it.isNotBlank() }.joinToString("."),
                    simpleName = simple,
                    filePath = def.filePath,
                    line = def.line,
                    kdoc = def.kdoc,
                    category = def.completionCategory,
                    signature = signature,
                    parameters = parseKotlinParameters(signature),
                )
            }
            ?.toList()
            .orEmpty()
        val candidates = fromIndex.ifEmpty { scanKotlinSourceFunctions(root) }
        return candidates
            .distinctBy { it.qualifiedName to it.filePath to it.line }
            .sortedBy { it.qualifiedName }
            .toList()
    }

    private fun scanKotlinSourceFunctions(root: Path): List<IndexedFunction> {
        val roots = listOf(root.resolve("src/jvmMain/kotlin"), root.resolve("src/personal/kotlin")).filter { Files.exists(it) }
        return roots.flatMap { sourceRoot ->
            Files.walk(sourceRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .flatMap { file -> parseFunctionsFromFile(root, file).stream() }
                    .toList()
            }
        }
    }

    private fun parseFunctionsFromFile(root: Path, file: Path): List<IndexedFunction> {
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return emptyList()
        val rel = runCatching { root.relativize(file).toString().replace("\\", "/") }.getOrDefault(file.toString())
        val pkg = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val regex = Regex("""(?m)(?:^|\n)\s*(?:public\s+|internal\s+|private\s+)?fun\s+(?:[A-Za-z_][A-Za-z0-9_.<>?]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        return regex.findAll(text).mapNotNull { match ->
            val name = match.groupValues[1]
            val signature = findFunctionSignature(text, name) ?: return@mapNotNull null
            IndexedFunction(
                qualifiedName = listOf(pkg, name).filter { it.isNotBlank() }.joinToString("."),
                simpleName = name,
                filePath = rel,
                line = text.substring(0, match.range.first).count { it == '\n' } + 1,
                kdoc = null,
                category = null,
                signature = signature,
                parameters = parseKotlinParameters(signature),
            )
        }.toList()
    }

    /**
     * Finds the source text of `fun <name>(...)`.
     *
     * [name] comes from `KotlinSourceIndex`, which happily reports backtick-quoted declarations, so
     * it must be **escaped** before being spliced into a pattern. Unescaped, a legal Kotlin name
     * containing a regex metacharacter — `` fun `|+|`(…) ``, or a test named
     * `` fun `a (b) works`() `` — throws `PatternSyntaxException` and takes the whole completion
     * index down with it.
     */
    private fun findFunctionSignature(text: String, name: String): String? {
        val quoted = Regex.escape(name)
        val regex = Regex("""(?m)(?:^|\n)\s*(?:public\s+|internal\s+|private\s+)?fun\s+(?:[A-Za-z_][A-Za-z0-9_.<>?]*\.)?$quoted\s*\(""")
        val match = regex.find(text) ?: return null
        val start = match.value.indexOf("fun ").let { if (it >= 0) match.range.first + it else match.range.first }
        val open = text.indexOf('(', match.range.last - 1)
        val close = findMatchingParen(text, open) ?: return text.substring(start, match.range.last.coerceAtMost(text.length)).trim()
        val after = text.indexOfAny(charArrayOf('\n', '{', '='), close + 1).let { if (it < 0) text.length else it }
        return text.substring(start, after).trim()
    }

    private fun parseKotlinParameters(signature: String): List<KotlinParameter> {
        val open = signature.indexOf('(')
        val close = if (open >= 0) findMatchingParen(signature, open) else null
        if (open < 0 || close == null) return emptyList()
        return splitTopLevel(signature.substring(open + 1, close), ',').mapNotNull { raw ->
            val cleaned = raw.trim()
                .replace(Regex("""^@[A-Za-z_][A-Za-z0-9_.]*(\([^)]*\))?\s*"""), "")
                .removePrefix("vararg ")
                .trim()
            val colon = cleaned.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val name = cleaned.substring(0, colon).trim()
            val afterColon = cleaned.substring(colon + 1).trim()
            val equals = topLevelIndexOf(afterColon, '=')
            val type = (if (equals >= 0) afterColon.substring(0, equals) else afterColon).trim()
            val defaultSource = if (equals >= 0) afterColon.substring(equals + 1).trim() else null
            KotlinParameter(name, type, defaultSource != null, defaultSource)
        }
    }

    private fun findMatchingParen(text: String, openIndex: Int): Int? {
        if (openIndex !in text.indices || text[openIndex] != '(') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openIndex until text.length) {
            val ch = text[i]
            if (inString) {
                escaped = ch == '\\' && !escaped
                if (ch == '"' && !escaped) inString = false
                if (ch != '\\') escaped = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun splitTopLevel(text: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var paren = 0
        var angle = 0
        var bracket = 0
        var inString = false
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '"') inString = !inString
            if (!inString) {
                when (ch) {
                    '(' -> paren++
                    ')' -> if (paren > 0) paren--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    '[' -> bracket++
                    ']' -> if (bracket > 0) bracket--
                    delimiter -> if (paren == 0 && angle == 0 && bracket == 0) {
                        parts += text.substring(start, i)
                        start = i + 1
                    }
                }
            }
        }
        parts += text.substring(start)
        return parts
    }

    private fun topLevelIndexOf(text: String, target: Char): Int {
        var paren = 0
        var angle = 0
        var inString = false
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '"') inString = !inString
            if (!inString) {
                when (ch) {
                    '(' -> paren++
                    ')' -> if (paren > 0) paren--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    target -> if (paren == 0 && angle == 0) return i
                }
            }
        }
        return -1
    }

    private fun symbolHtml(
        declaration: String,
        detail: String,
        documentation: String,
    ): String {
        val doc = documentation.trim()
        return buildString {
            append("<div style='display:inline-block; max-width: 620px;'>")
            append("<div style='color:#ddd; overflow-wrap:anywhere;'><code>")
            append(escapeHtml(declaration))
            append("</code></div>")
            if (detail.isNotBlank()) {
                append("<div style='color:#777;'>")
                append(escapeHtml(detail))
                append("</div>")
            }
            if (doc.isNotBlank()) {
                append("<div style='margin-top:4px; color:#aaa; max-width:620px;'>")
                append(escapeHtml(doc))
                append("</div>")
            }
            append("</div>")
        }
    }

    private fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private data class RankedMetadata(val rank: Int, val metadata: Metadata) {
        val text: String get() = metadata.text
    }

    private data class SymbolBounds(val start: Int, val end: Int, val text: String)
    private data class Snippet(val label: String, val insertText: String, val documentation: String)
    private data class StringContext(val prefix: String, val boundsStart: Int, val boundsEnd: Int)

    private data class HostStringContext(
        val intrinsic: String,
        val owner: String?,
        val prefix: String,
        val boundsStart: Int,
        val boundsEnd: Int,
        /** Where the literal opens, which is also where the receiver's pipe expression ends. */
        val quoteIndex: Int,
    )
    private data class RecordFieldContext(
        val receiver: String,
        val prefix: String,
        /** Where the receiver ends, which is also the scope cut for typing it. */
        val receiverEnd: Int,
        val boundsStart: Int,
        val boundsEnd: Int,
    )
    private data class HostTapArgumentContext(
        val target: String,
        val prefix: String,
        val present: Set<String>,
        val boundsStart: Int,
        val boundsEnd: Int,
    )

    private data class IndexedFunction(
        val qualifiedName: String,
        val simpleName: String,
        val filePath: String,
        val line: Int,
        val kdoc: String?,
        val category: String?,
        val signature: String,
        val parameters: List<KotlinParameter>,
    )

    private data class KotlinParameter(
        val name: String,
        val type: String,
        val hasDefault: Boolean,
        val defaultSource: String?,
    )

    private val keywords = listOf("let", "type", "match", "with", "if", "then", "else", "fun", "true", "false", "in")

    private val graphicsPrelude = setOf(
        "fline",
        "vec2",
        "vec3",
        "vec4",
        "moveTo",
        "moveTo3",
        "lineTo",
        "lineTo3",
        "moveToV",
        "lineToV",
        "cubicTo",
        "close",
        "thickness",
        "color",
        "filled",
        "stroked",
        "pointed",
        "pointSize",
        "draw",
        "drawSized",
        "animate",
        "animateFor",
        "dot",
        "cross",
        "mix",
        "normalize",
        "vlength",
        "distance",
        "polar",
    )

    private val pipePreferred = listOf(
        "moveTo",
        "lineTo",
        "cubicTo",
        "color",
        "thickness",
        "filled",
        "stroked",
        "pointed",
        "pointSize",
        "close",
        "draw",
        "map",
        "filter",
        "fold",
    )

    private val snippets = listOf(
        Snippet("let", "let name = value\n\nname", "Top-level immutable binding."),
        Snippet("fun", "fun x -> x", "Anonymous function."),
        Snippet("match", "match value with\n| _ -> value", "Pattern match expression."),
        Snippet("type", "type Option a = None | Some a\n\nNone", "Algebraic data type declaration."),
        Snippet("hostTap", "hostTap \"afield.graphics.lines\" { channel = \"main\" }", "Pipe-friendly JVM interop wrapper."),
        Snippet("fline", "fline\n  |> moveTo 0.0 0.0\n  |> lineTo 120.0 40.0", "Create and edit an FLine."),
    )

    private val docs = mapOf(
        "let" to "Introduce an immutable binding.",
        "type" to "Declare an algebraic data type.",
        "match" to "Pattern match over a value.",
        "with" to "Start match cases.",
        "fun" to "Anonymous function.",
        "if" to "Conditional expression.",
        "display" to "display \"channel\" value sends a value to a Field output panel and returns it.",
        "print" to "Print a value and return it, useful as a pipe tap.",
        "println" to "Print a value plus newline and return it, useful as a pipe tap.",
        "tap" to "Apply a function for its effect and return the original value.",
        "fline" to "Create an FLine host value.",
        "moveTo" to "moveTo x y line adds a move instruction and returns the FLine.",
        "lineTo" to "lineTo x y line adds a line instruction and returns the FLine.",
        "cubicTo" to "cubicTo x1 y1 x2 y2 x3 y3 line adds a cubic curve segment and returns the FLine.",
        "color" to "color vec4 line sets FLine color and returns the FLine.",
        "thickness" to "thickness amount line sets stroke thickness and returns the FLine.",
        "hostTap" to "Build a pipe-friendly JVM call from indexed Kotlin source metadata and named argument overrides.",
        "hostStaticTap" to "Build a pipe-friendly static JVM call that substitutes hostInput and returns the piped value.",
        "hostInput" to "Placeholder for the piped value in hostStaticTap argument templates.",
        "thisBox" to "Current Field Box as a host value.",

        // Helpers that transform one subject take it last, so they read as `value |> helper args`.
        "mod" to "mod divisor value is the remainder, so `7 |> mod 4` is 3.",
        "idiv" to "idiv divisor value is integer division, so `7 |> idiv 2` is 3.",
        "fmod" to "fmod divisor value is the Float remainder, so `7.5 |> fmod 2.0` is 1.5.",
        "pow" to "pow exponent value raises value, so `3.0 |> pow 2.0` is 9.0.",
        "lerp" to "lerp from to amount interpolates, so `0.5 |> lerp 0.0 10.0` is 5.0.",
        "clamp" to "clamp low high value keeps value inside a range.",
        "toFloat" to "Convert an Int to a Float. FieldML never mixes them silently.",
        "toInt" to "Truncate a Float to an Int.",
        "not" to "Boolean negation. Combine conditions with && and ||.",
        "range" to "range start end lists the Ints from start up to but not including end.",
        "linspace" to "linspace start end count lists count Floats including both ends.",
        "tabulate" to "tabulate f count builds a list by calling f with 0, 1, ... count - 1.",
        "map" to "map f list transforms every element.",
        "mapIndexed" to "mapIndexed f list transforms every element, passing its index first.",
        "filter" to "filter predicate list keeps the elements the predicate accepts.",
        "fold" to "fold f initial list reduces a list left to right, f accumulator element.",
        "append" to "append suffix list puts the piped list first, so `[1] |> append [2]` is [1, 2].",
        "zip" to "zip other list pairs elements, taking the first component from the piped list.",
        "sortBy" to "sortBy key list orders by a Float key.",
        "nth" to "nth index list reads one element and fails when the index is outside the list.",
        "dot" to "Dot product of two vectors of the same width.",
        "cross" to "Cross product of two Vec3 values.",
        "vlength" to "Length of a vector. `length` is the list one.",
        "distance" to "Distance between two vectors of the same width.",
        "normalize" to "Scale a vector to length 1, leaving a zero vector alone.",
        "mix" to "mix a b amount interpolates between two vectors.",
        "polar" to "polar angle radius builds a Vec2 from an angle in radians.",
        "draw" to "draw \"channel\" line draws an FLine into a Field output channel and returns the line.",
        "drawSized" to "drawSized \"channel\" w h line draws into an output channel at a fixed size.",
        "animate" to
            "animate (fun t -> ...) returns a per-frame animation. Field steps it once per frame with the " +
            "seconds since launch, until the box is stopped. Draw inside the function.",
        "animateFor" to "animateFor seconds (fun t -> ...) is animate with a stopping time.",
    )
    private fun compatibleTypes(left: Type, right: Type): Boolean =
        left == right ||
            left is TVar ||
            right is TVar ||
            left is THost && right is THost && left.name == right.name
}
