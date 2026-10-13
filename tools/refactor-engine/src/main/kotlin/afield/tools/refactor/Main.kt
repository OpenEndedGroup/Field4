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
            // A star can cover individually moved declarations even if its containing package stays.
            val split = directive.isAllUnder && (path in recipe.splitPackages ||
                recipe.symbols.keys.any { it.substringBeforeLast('.') == path })
            if (split) {
                findings += Finding("split-star", line(directive.textOffset), "Resolve uses of $path.* before selecting destination imports")
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
        for (qualified in PsiTreeUtil.findChildrenOfType(file, KtDotQualifiedExpression::class.java)) {
            if (qualified.parent is KtDotQualifiedExpression || importRanges.any { it.contains(qualified.textRange) }) continue
            // This is a review candidate, never a textual replacement of a possibly local variable.
            val text = qualified.text
            if (recipe.affected(text)) findings += Finding("qualified-reference", line(qualified.textOffset), text.take(160))
        }
        for (type in PsiTreeUtil.findChildrenOfType(file, KtUserType::class.java)) {
            if (type.parent is KtUserType || importRanges.any { it.contains(type.textRange) }) continue
            if (recipe.affected(type.text)) findings += Finding("qualified-type", line(type.textOffset), type.text.take(160))
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
        for (edit in edits.sortedByDescending { it.start }) after = after.replaceRange(edit.start, edit.end, edit.replacement)
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
                    config["splitPackages"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty())
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
