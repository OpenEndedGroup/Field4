@file:OptIn(
    org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class,
    org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    org.jetbrains.kotlin.CoreEnvironmentDeprecation::class,
)

package afield.tools.refactor

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import kotlinx.serialization.json.*

/** Syntax evidence only. Unresolved qualified uses and package splits stay review findings. */
data class Recipe(
    val packages: Map<String, String>,
    val symbols: Map<String, String>,
    val splitPackages: Set<String>,
    // Exact imports supplied by the destination script host, without the `import` keyword.
    val scriptImports: Set<String> = emptySet(),
    val rewriteComments: Boolean = false,
    val knownPackages: Set<String> = emptySet(),
    val retainedSymbols: Set<String> = emptySet(),
    val splitStarImports: Map<String, List<String>> = emptyMap(),
    val scriptImportsKnown: Boolean = false,
) {
    private fun prefix(path: String, old: String) = path == old || path.startsWith("$old.")
    fun retained(path: String) = retainedSymbols.any { prefix(path, it) }
    private val roots = (packages.keys + symbols.keys + splitPackages).map { it.substringBefore('.') }.toSet()
    fun affected(path: String): Boolean {
        if (path.substringBefore('.') !in roots || retained(path)) return false
        if (packages.keys.any { prefix(path, it) } || symbols.keys.any { prefix(path, it) }) return true
        val owner = knownPackages.filter { prefix(path, it) }.maxByOrNull(String::length)
        return if (owner != null) owner in splitPackages else splitPackages.any { prefix(path, it) }
    }
    fun affectedText(text: String, includePaths: Boolean = true) = Regex("(?<![\\w.])[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*")
        .findAll(text).any { (includePaths || text.getOrNull(it.range.last + 1) != '/') && affected(it.value) }

    fun moved(path: String): String? {
        if (retained(path)) return null
        val symbol = symbols.keys.filter { prefix(path, it) }.maxByOrNull(String::length)
        if (symbol != null) return symbols.getValue(symbol) + path.removePrefix(symbol)
        val pkg = packages.keys.filter { prefix(path, it) }.maxByOrNull(String::length) ?: return null
        return packages.getValue(pkg) + path.removePrefix(pkg)
    }

    fun packageMove(path: String): String? {
        val old = packages.keys.filter { prefix(path, it) }.maxByOrNull(String::length) ?: return null
        return packages.getValue(old) + path.removePrefix(old)
    }

    fun rewriteText(text: String): String = Regex("(?<![\\w.])[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)+")
        .replace(text) { match -> if (text.getOrNull(match.range.last + 1) == '/') match.value else moved(match.value) ?: match.value }
}

data class Edit(val start: Int, val end: Int, val replacement: String)
data class Finding(val kind: String, val line: Int, val detail: String)
data class Result(val code: String, val findings: List<Finding>, val imports: List<String>, val packageName: String)

class Engine : AutoCloseable {
    private val disposable = Disposer.newDisposable()
    private val environment = KotlinCoreEnvironment.createForProduction(
        disposable, CompilerConfiguration().apply {
            extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
        }, EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    private val factory = KtPsiFactory(environment.project, false)

    fun declarations(code: String): JsonObject {
        val normalized = code.replace("\r\n", "\n").replace('\r', '\n')
        val file = factory.createFile("index.kt", normalized)
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        return buildJsonObject {
            put("package", file.packageFqName.asString())
            putJsonArray("errors") { errors.forEach { add("line ${normalized.take(it.textOffset).count { c -> c == '\n' } + 1}: ${it.errorDescription}") } }
            putJsonArray("declarations") {
                if (errors.isEmpty()) for (declaration in file.declarations.filterIsInstance<KtNamedDeclaration>()) {
                    val name = declaration.name ?: continue
                    val kind = when (declaration) {
                        is KtNamedFunction -> "function"
                        is KtProperty -> "property"
                        is KtTypeAlias -> "typealias"
                        is KtObjectDeclaration -> "object"
                        is KtClass -> if (declaration.isInterface()) "interface" else "class"
                        else -> "unsupported"
                    }
                    val body = when (declaration) {
                        is KtNamedFunction -> declaration.bodyExpression
                        is KtProperty -> declaration.initializer ?: declaration.delegateExpression
                        is KtClassOrObject -> declaration.body
                        else -> null
                    }
                    val headerStart = declaration.docComment?.textRange?.endOffset ?: declaration.textRange.startOffset
                    val header = normalized.substring(headerStart, body?.textOffset ?: declaration.textRange.endOffset)
                        .replace(Regex("\\s+"), " ").trim()
                    add(buildJsonObject {
                        put("name", name)
                        put("fqn", listOf(file.packageFqName.asString(), name).filter { it.isNotBlank() }.joinToString("."))
                        put("kind", kind); put("signature", header)
                        put("line", normalized.take(declaration.textOffset).count { it == '\n' } + 1)
                        put("visibility", listOf("private", "internal", "protected", "public").firstOrNull {
                            declaration.modifierList?.text?.split(Regex("\\s+"))?.contains(it) == true
                        } ?: "public")
                    })
                }
            }
        }
    }

    fun rewrite(code: String, script: Boolean, recipe: Recipe): Result {
        // IntelliJ PSI requires LF. Preserve a UTF-16 offset map back to the untouched input.
        val offsets = mutableListOf<Int>()
        val normalized = buildString {
            var i = 0
            while (i < code.length) {
                offsets += i
                if (code[i] == '\r') {
                    append('\n')
                    i += if (code.getOrNull(i + 1) == '\n') 2 else 1
                } else append(code[i++])
            }
            offsets += code.length
        }
        val file = factory.createFile(if (script) "unit.kts" else "unit.kt", normalized)
        val findings = mutableListOf<Finding>()
        val edits = mutableListOf<Edit>()
        fun line(offset: Int) = normalized.take(offset).count { it == '\n' } + 1
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        if (errors.isNotEmpty()) return Result(code, errors.take(8).map {
            Finding("parse-error", line(it.textOffset), it.errorDescription)
        }, emptyList(), file.packageFqName.asString())
        val imports = file.importDirectives.mapNotNull { it.importedFqName?.asString() }
        fun isSplit(path: String) = path in recipe.splitPackages ||
            recipe.symbols.keys.any { it.substringBeforeLast('.') == path }
        val emittedStars = file.importDirectives.filter { it.isAllUnder && !isSplit(it.importedFqName?.asString().orEmpty()) }
            .mapNotNull { it.importedFqName?.asString() }.map { recipe.moved(it) ?: it }.toMutableSet()
        if (script) emittedStars += recipe.scriptImports.filter { it.endsWith(".*") }.map { it.removeSuffix(".*") }
        val newline = if (code.contains("\r\n")) "\r\n" else "\n"
        for (directive in file.importDirectives) {
            val path = directive.importedFqName?.asString() ?: continue
            val expression = directive.importedReference ?: continue
            val destination = recipe.moved(path) ?: path
            val destinationImport = destination + if (directive.isAllUnder) ".*" else ""
            val importComments = PsiTreeUtil.findChildrenOfType(directive, PsiComment::class.java)
            // A star can cover individually moved declarations even if its containing package stays.
            val split = directive.isAllUnder && (path in recipe.splitPackages ||
                recipe.symbols.keys.any { it.substringBeforeLast('.') == path })
            if (script && directive.aliasName == null && destinationImport in recipe.scriptImports &&
                importComments.none { it.textOffset < expression.textRange.endOffset }) {
                // Remove only an identical host import. A star does not prove that a named import
                // is redundant: that explicit import might disambiguate two star packages.
                // Leave surrounding whitespace, semicolons and trailing comments untouched.
                var end = importComments.minOfOrNull { it.textOffset } ?: directive.textRange.endOffset
                while (end > directive.textOffset && normalized[end - 1].isWhitespace()) end--
                edits += Edit(offsets[directive.textRange.startOffset], offsets[end], "")
            } else if (split) {
                val targets = recipe.splitStarImports[path]
                if (script && !recipe.scriptImportsKnown && recipe.scriptImports.isEmpty()) {
                    findings += Finding("split-star", line(directive.textOffset),
                        "Configure destination scriptImports before expanding $path.*; box imports may already be supplied by the host intro")
                } else if (targets.isNullOrEmpty() || importComments.any { it.textOffset < expression.textRange.endOffset }) {
                    findings += Finding("split-star", line(directive.textOffset),
                        if (targets.isNullOrEmpty()) "Complete the declaration map for $path before expanding $path.*"
                        else "Review comments inside $path.* before expanding it")
                } else {
                    val replacement = targets.distinct().filter { emittedStars.add(it) }
                        .joinToString(newline) { "import $it.*" }
                    var end = importComments.minOfOrNull { it.textOffset } ?: directive.textRange.endOffset
                    while (end > directive.textOffset && normalized[end - 1].isWhitespace()) end--
                    edits += Edit(offsets[directive.textRange.startOffset], offsets[end], replacement)
                }
            } else {
                val moved = recipe.moved(path)
                if (moved != null) edits += Edit(offsets[expression.textRange.startOffset], offsets[expression.textRange.endOffset], moved)
                else if (recipe.affected(path)) findings += Finding("unmapped-import", line(directive.textOffset), path)
            }
        }
        val pkg = file.packageFqName.asString()
        if (pkg.isNotBlank() && (recipe.packageMove(pkg) != null || pkg in recipe.splitPackages ||
                recipe.symbols.keys.any { it.substringBeforeLast('.') == pkg })) {
            val destination = recipe.packageMove(pkg)
            val wholePackage = destination != null && pkg !in recipe.splitPackages &&
                recipe.symbols.keys.none { it.substringBeforeLast('.') == pkg }
            val expression = file.packageDirective?.packageNameExpression
            if (wholePackage && !script && expression != null) {
                // The recipe explicitly moves the entire package: its declarations remain together.
                // The file layer moves the matching source directory and rejects collisions.
                edits += Edit(offsets[expression.textRange.startOffset], offsets[expression.textRange.endOffset], destination)
            } else findings += Finding("source-package", 1, "Relocate $pkg with declaration and same-package binding evidence")
        }
        val importRanges = file.importDirectives.map { it.textRange } + listOfNotNull(file.packageDirective?.textRange)
        val localNames = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java).mapNotNull { it.name }.toSet()
        val importedNames = file.importDirectives.filterNot { it.isAllUnder }.mapNotNull { it.aliasName ?: it.importedFqName?.shortName()?.asString() }.toSet()
        fun expressionNames(expression: KtExpression?): List<KtNameReferenceExpression> = when (expression) {
            is KtNameReferenceExpression -> listOf(expression)
            is KtDotQualifiedExpression -> {
                val receiver = expressionNames(expression.receiverExpression)
                val selector = when (val e = expression.selectorExpression) {
                    is KtNameReferenceExpression -> e
                    is KtCallExpression -> e.calleeExpression as? KtNameReferenceExpression
                    else -> null
                }
                // Never cross a call boundary (factory().afield is a value, not a package).
                if (receiver.isEmpty() || expression.receiverExpression.textRange.endOffset != receiver.last().textRange.endOffset) emptyList()
                else if (selector == null) emptyList() else receiver + selector
            }
            else -> emptyList()
        }
        fun rewriteNames(names: List<KtSimpleNameExpression>, kind: String) {
            if (names.isEmpty()) return
            val path = names.joinToString(".") { it.getReferencedName() }
            if (!recipe.affected(path)) return
            val old = recipe.symbols.keys.filter { path == it || path.startsWith("$it.") }.maxByOrNull(String::length)
                ?: recipe.packages.keys.filter { path == it || path.startsWith("$it.") }.maxByOrNull(String::length)
            if (old == null && kind == "qualified-reference" &&
                (path in recipe.splitPackages || recipe.symbols.keys.any { it.startsWith("$path.") })) return
            val destination = old?.let { recipe.symbols[it] ?: recipe.packages.getValue(it) }
            if (old == null || names.first().getReferencedName() in localNames + importedNames ||
                destination?.substringBefore('.') in localNames + importedNames) {
                findings += Finding(kind, line(names.first().textOffset), "Unmapped or shadowed package reference: $path")
                return
            }
            val last = names[old.count { it == '.' }]
            // Editing only the matched prefix preserves type arguments, calls, nested references
            // and comments between later name components. Comments inside that prefix need review.
            val range = normalized.substring(names.first().textOffset, last.textRange.endOffset)
            if (!Regex("[\\w\\s.`]+").matches(range)) {
                findings += Finding(kind, line(names.first().textOffset), "Comment or type arguments inside moved prefix: $path")
                return
            }
            edits += Edit(offsets[names.first().textOffset], offsets[last.textRange.endOffset], destination!!)
        }
        for (qualified in PsiTreeUtil.findChildrenOfType(file, KtDotQualifiedExpression::class.java)) {
            if (importRanges.any { it.contains(qualified.textRange) }) continue
            rewriteNames(expressionNames(qualified), "qualified-reference")
        }
        for (type in PsiTreeUtil.findChildrenOfType(file, KtUserType::class.java)) {
            if (type.parent is KtUserType || importRanges.any { it.contains(type.textRange) }) continue
            val names = generateSequence(type) { it.qualifier }.toList().asReversed().mapNotNull { it.referenceExpression }
            rewriteNames(names, "qualified-type")
        }
        // Embedded programs, reflection names, and annotations containing strings need a container
        // adapter or explicit consumer rule. Comment rewriting is an explicit recipe option.
        for (comment in PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java)) {
            val afterComment = if (recipe.rewriteComments) recipe.rewriteText(comment.text) else comment.text
            if (afterComment != comment.text)
                edits += Edit(offsets[comment.textRange.startOffset], offsets[comment.textRange.endOffset], afterComment)
            if (recipe.affectedText(afterComment))
                findings += Finding("comment-reference", line(comment.textOffset), "KDoc, example or historical reference; review without changing prose blindly")
        }
        for (literal in PsiTreeUtil.findChildrenOfType(file, KtStringTemplateExpression::class.java)) {
            if (recipe.affectedText(literal.text, includePaths = false))
                findings += Finding("string-reference", line(literal.textOffset), "Potential embedded code or dynamic name; requires a typed adapter")
        }
        var after = code
        // A longer symbol rule supersedes a package-prefix edit discovered on a child expression.
        val effectiveEdits = edits.distinct().groupBy { it.start }.values.map { group -> group.maxBy { it.end } }
        if (effectiveEdits.sortedBy { it.start }.zipWithNext().any { (a, b) -> a.end > b.start })
            return Result(code, findings + Finding("overlapping-edits", 1, "Overlapping code/comment edits require review"), imports, pkg)
        for (edit in effectiveEdits.sortedByDescending { it.start }) after = after.replaceRange(edit.start, edit.end, edit.replacement)
        val check = factory.createFile(if (script) "after.kts" else "after.kt", after.replace("\r\n", "\n").replace('\r', '\n'))
        if (PsiTreeUtil.findChildrenOfType(check, PsiErrorElement::class.java).isNotEmpty())
            return Result(code, findings + Finding("rewrite-parse-error", 1, "Import edit did not parse"), imports, pkg)
        return Result(after, findings.distinct(), imports, pkg)
    }

    override fun close() = Disposer.dispose(disposable)
}

private fun JsonObject.mapping(name: String) = this[name]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

/** One request/response per line, one long-lived parser per invocation. No source is executed. */
fun main() {
    Engine().use { engine ->
        generateSequence(::readlnOrNull).forEach { line ->
            val result = try {
                val request = Json.parseToJsonElement(line).jsonObject
                if (request["operation"]?.jsonPrimitive?.content == "declarations") {
                    println(engine.declarations(request.getValue("code").jsonPrimitive.content))
                    System.out.flush()
                    return@forEach
                }
                val config = request.getValue("recipe").jsonObject
                val recipe = Recipe(config.mapping("packages"), config.mapping("symbols"),
                    config["splitPackages"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                    config["scriptImports"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                    config["rewriteComments"]?.jsonPrimitive?.boolean ?: false,
                    config["knownPackages"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                    config["retainedSymbols"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                    config["splitStarImports"]?.jsonObject?.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }.orEmpty(),
                    config.containsKey("scriptImports"))
                val result = engine.rewrite(request.getValue("code").jsonPrimitive.content,
                    request["script"]?.jsonPrimitive?.boolean ?: true, recipe)
                buildJsonObject {
                    put("code", result.code)
                    put("package", result.packageName)
                    put("evidence", "kotlin-psi-syntax")
                    putJsonArray("imports") { result.imports.forEach { add(it) } }
                    putJsonArray("findings") { result.findings.forEach { finding -> add(buildJsonObject {
                        put("kind", finding.kind); put("line", finding.line); put("detail", finding.detail)
                    }) } }
                }
            } catch (error: Exception) {
                buildJsonObject { put("error", "${error.javaClass.simpleName}: ${error.message}") }
            }
            println(result)
            System.out.flush()
        }
    }
}
