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
) {
    private fun prefix(path: String, old: String) = path == old || path.startsWith("$old.")
    fun affected(path: String): Boolean =
        packages.keys.any { prefix(path, it) } || symbols.keys.any { prefix(path, it) } ||
            splitPackages.any { prefix(path, it) }

    fun moved(path: String): String? {
        val symbol = symbols.keys.filter { prefix(path, it) }.maxByOrNull(String::length)
        if (symbol != null) return symbols.getValue(symbol) + path.removePrefix(symbol)
        val pkg = packages.keys.filter { prefix(path, it) }.maxByOrNull(String::length) ?: return null
        return packages.getValue(pkg) + path.removePrefix(pkg)
    }

    fun packageMove(path: String): String? {
        val old = packages.keys.filter { prefix(path, it) }.maxByOrNull(String::length) ?: return null
        return packages.getValue(old) + path.removePrefix(old)
    }
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
        for (directive in file.importDirectives) {
            val path = directive.importedFqName?.asString() ?: continue
            val expression = directive.importedReference ?: continue
            val destination = recipe.moved(path) ?: path
            val destinationImport = destination + if (directive.isAllUnder) ".*" else ""
            val importComments = PsiTreeUtil.findChildrenOfType(directive, PsiComment::class.java)
            // A star can cover individually moved declarations even if its containing package stays.
            val split = directive.isAllUnder && (path in recipe.splitPackages ||
                recipe.symbols.keys.any { it.substringBeforeLast('.') == path })
            if (split) {
                findings += Finding("split-star", line(directive.textOffset), "Resolve uses of $path.* before selecting destination imports")
            } else if (script && directive.aliasName == null && destinationImport in recipe.scriptImports &&
                importComments.none { it.textOffset < expression.textRange.endOffset }) {
                // Remove only an identical host import. A star does not prove that a named import
                // is redundant: that explicit import might disambiguate two star packages.
                // Leave surrounding whitespace, semicolons and trailing comments untouched.
                var end = importComments.minOfOrNull { it.textOffset } ?: directive.textRange.endOffset
                while (end > directive.textOffset && normalized[end - 1].isWhitespace()) end--
                edits += Edit(offsets[directive.textRange.startOffset], offsets[end], "")
            } else {
                val moved = recipe.moved(path)
                if (moved != null) edits += Edit(offsets[expression.textRange.startOffset], offsets[expression.textRange.endOffset], moved)
                else if (recipe.affected(path)) findings += Finding("unmapped-import", line(directive.textOffset), path)
            }
        }
        val pkg = file.packageFqName.asString()
        if (recipe.affected(pkg) && pkg.isNotBlank()) {
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
        // adapter or explicit consumer rule. Comments are intentionally not rewritten.
        val oldNames = recipe.packages.keys + recipe.symbols.keys + recipe.splitPackages
        for (comment in PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java)) {
            if (oldNames.any { old -> Regex("(?<![\\w.])${Regex.escape(old)}(?![\\w/])").containsMatchIn(comment.text) })
                findings += Finding("comment-reference", line(comment.textOffset), "KDoc, example or historical reference; review without changing prose blindly")
        }
        for (literal in PsiTreeUtil.findChildrenOfType(file, KtStringTemplateExpression::class.java)) {
            if (oldNames.any { old -> Regex("(?<![\\w.])${Regex.escape(old)}(?=[.\\s\"$]|$)").containsMatchIn(literal.text) })
                findings += Finding("string-reference", line(literal.textOffset), "Potential embedded code or dynamic name; requires a typed adapter")
        }
        var after = code
        // A longer symbol rule supersedes a package-prefix edit discovered on a child expression.
        val effectiveEdits = edits.distinct().groupBy { it.start }.values.map { group -> group.maxBy { it.end } }
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
                val config = request.getValue("recipe").jsonObject
                val recipe = Recipe(config.mapping("packages"), config.mapping("symbols"),
                    config["splitPackages"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                    config["scriptImports"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty())
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
