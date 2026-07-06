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
import afield.ml.type.FieldMLTypePrelude
import afield.ml.type.Scheme
import afield.ml.type.TExpr
import afield.ml.type.TExprLet
import afield.ml.type.TExprRecord
import afield.ml.type.TExprTypeDecl
import afield.ml.type.TFun
import afield.ml.type.THost
import afield.ml.type.TRecord
import afield.ml.type.TVar
import afield.ml.type.Type
import afield.ml.type.TypePrinter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object FieldMLCompletionEngine {
    fun complete(request: CompletionRequest): Completion? {
        val code = request.allCode
        val cursor = request.position.coerceIn(0, code.length)

        hostTapTargetContext(code, cursor)?.let { context ->
            return completion(context.boundsStart, context.boundsEnd, hostTapTargetItems(context.prefix))
        }

        hostTapArgumentContext(code, cursor)?.let { context ->
            return completion(context.boundsStart, context.boundsEnd, hostTapArgumentItems(context))
        }

        recordFieldContext(code, cursor)?.let { context ->
            val fields = recordFieldItems(code, context)
            if (fields.isNotEmpty()) return completion(context.boundsStart, context.boundsEnd, fields)
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
            val type = typedVisibleNames(context.sourceForTypes, context.sourceForTypes.length)[context.receiver] as? TRecord
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
                    declaration = "$symbol : ${TypePrinter().print(scheme.body)}",
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

    private fun completion(boundsStart: Int, boundsEnd: Int, items: List<Metadata>): Completion? =
        if (items.isEmpty()) null else Completion(boundsStart = boundsStart, boundsEnd = boundsEnd, metadata = items)

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
                        tail = printer.print(scheme.body),
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
            append(TypePrinter().print(scheme.body))
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
        val fallback = typedVisibleNameFallback(code, cursor)
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

    private fun typedVisibleNameFallback(code: String, cursor: Int): Map<String, Type> {
        val before = code.take(cursor)
        val out = linkedMapOf<String, Type>()
        Regex("""(?m)^\s*let\s+([a-z_][A-Za-z0-9_']*)\s*=\s*(.+)$""")
            .findAll(before)
            .forEach { match ->
                val name = match.groupValues[1]
                val source = match.groupValues[2].trim()
                if (source.isBlank() || source.contains("|>")) return@forEach
                FieldMLCompiler.compile(source).typed?.type?.let { out[name] = it }
            }
        return out
    }

    private fun pipeInputType(code: String, cursor: Int): Type? {
        val pipe = code.lastIndexOf("|>", startIndex = (cursor - 1).coerceAtLeast(0))
        if (pipe < 0) return null
        val leftSource = code.substring(0, pipe).trimEnd()
        if (leftSource.isBlank()) return null
        return FieldMLCompiler.compile(leftSource).typed?.type
    }

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
            sourceForTypes = code.substring(0, nameEnd),
            boundsStart = bounds.first,
            boundsEnd = bounds.second,
        )
    }

    private fun recordFieldItems(code: String, context: RecordFieldContext): List<Metadata> {
        val type = typedVisibleNames(context.sourceForTypes, context.sourceForTypes.length)[context.receiver] as? TRecord ?: return emptyList()
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

    private fun findFunctionSignature(text: String, name: String): String? {
        val regex = Regex("""(?m)(?:^|\n)\s*(?:public\s+|internal\s+|private\s+)?fun\s+(?:[A-Za-z_][A-Za-z0-9_.<>?]*\.)?$name\s*\(""")
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
    private data class RecordFieldContext(
        val receiver: String,
        val prefix: String,
        val sourceForTypes: String,
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
    )
    private fun compatibleTypes(left: Type, right: Type): Boolean =
        left == right ||
            left is TVar ||
            right is TVar ||
            left is THost && right is THost && left.name == right.name
}
