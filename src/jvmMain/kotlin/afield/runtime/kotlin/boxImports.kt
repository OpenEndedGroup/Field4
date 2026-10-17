package afield.runtime.kotlin

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.document.graph.preamble
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.tools.docs.format
import afield.ui.notation.with

import afield.app.diagnostics.CodeUseSemanticAnalyzeRequest
import afield.app.diagnostics.CodeUseTelemetryService
import afield.audio.synth.*
import afield.document.graph.intro
import afield.document.graph.introImports
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.map
import afield.ui.output.templates.filter
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Which of a box's imports it needs.
 *
 * A box is compiled with the `intro` preamble prepended (`Boxes.kt`), so an import already provided
 * by that preamble adds nothing, and agents write such lines all the time. This reads a box's own
 * import lines and says, for each one, whether it is redundant (the intro already provides it),
 * unused (nothing in the code names it), needed, or kept because the evidence is not good enough to
 * drop it. `formatBoxImports` removes the first two kinds and sorts the rest; nothing here ever adds
 * an import.
 *
 * The rules are conservative, because a wrongly dropped import is a compile error:
 *
 * - An explicit import `a.b.C` (or `a.b.C as D`) is needed when its name appears as an identifier in
 *   the code, outside comments and strings. Operator-convention names (`plusAssign`, `getValue`,
 *   `invoke`, ...) are always kept: code uses them without writing them.
 * - It is redundant when the intro has the same line, or `a.b.*` and no other visible package
 *   (including Kotlin/JVM defaults) offers a competing `C`. An aliased import is never redundant.
 * - A star import `a.b.*` is redundant when the intro has the same star. It is unused only on the
 *   evidence `k2ImportEvidence` collects: K2 resolved every reference in the box and none of them
 *   lives in `a.b`. Without that evidence a star import is kept.
 */

/** One `import` line of a box. */
data class BoxImport(val line: Int, val text: String, val path: String, val alias: String?) {
    val star: Boolean get() = path.endsWith(".*")
    val pkg: String get() = if (star) path.removeSuffix(".*") else path.substringBeforeLast('.', "")
    /** The name the import brings into scope: the alias, the last segment, or null for a star. */
    val name: String? get() = alias ?: if (star) null else path.substringAfterLast('.')
}

enum class ImportVerdict { NEEDED, REDUNDANT, UNUSED, KEPT }

data class ImportFinding(val import: BoxImport, val verdict: ImportVerdict, val reason: String)

/**
 * What K2 resolved in a box's code: every resolved reference's fully qualified name, and how many
 * references it could not resolve. Produced by [k2ImportEvidence]; null when no analyzer ran.
 */
data class K2ImportEvidence(val resolvedFqns: Set<String>, val unresolvedCount: Int)

private val IMPORT_LINE = Regex("""^\s*import\s+([\w.]+(?:\.\*)?)(?:\s+as\s+(\w+))?\s*;?\s*(?://.*)?$""")

/** Names Kotlin calls by convention, which an import can provide without the code ever spelling them. */
private val OPERATOR_NAMES = setOf(
    "plus", "minus", "times", "div", "rem", "rangeTo", "rangeUntil", "unaryPlus", "unaryMinus", "not",
    "inc", "dec", "plusAssign", "minusAssign", "timesAssign", "divAssign", "remAssign", "get", "set",
    "invoke", "contains", "compareTo", "equals", "iterator", "next", "hasNext", "getValue", "setValue",
    "provideDelegate",
) + (1..9).map { "component$it" }

/** The box's own import lines, in order. */
fun boxImports(code: String): List<BoxImport> =
    code.lines().mapIndexedNotNull { i, line ->
        IMPORT_LINE.matchEntire(line)?.let { BoxImport(i, line.trim(), it.groupValues[1], it.groupValues[2].ifEmpty { null }) }
    }

/** Every identifier the code refers to, outside import and package lines, comments and strings. */
fun referencedNames(code: String): Set<String>? = try {
    FieldKtParser.parse(code).collectDescendantsOfType<KtSimpleNameExpression>()
        .filter { it.getParentOfType<KtImportDirective>(false) == null && it.getParentOfType<KtPackageDirective>(false) == null }
        .map { it.getReferencedName() }
        .toSet()
} catch (t: Throwable) {
    null
}

/**
 * The verdict for each of [code]'s imports, against the box intro [introText].
 *
 * @param resolution K2's account of the code, when an analyzer ran; it is the only evidence that
 * can mark a star import unused
 * @param otherCandidates simple name to the fully qualified names the classpath index knows for it,
 * used to keep an explicit import that disambiguates between two intro star packages; the default
 * consults the classpath index ([ImportResolution]) lazily
 */
fun analyzeBoxImports(
    code: String,
    introText: String = intro,
    resolution: K2ImportEvidence? = null,
    otherCandidates: (String) -> Collection<String> = ::classpathCandidates,
): List<ImportFinding> {
    val introImports = boxImports(introText)
    val introPaths = introImports.filter { it.alias == null }.map { it.path }.toSet()
    val introStars = introImports.filter { it.star }.map { it.pkg }.toSet()
    val names = referencedNames(code)

    return boxImports(code).map { imp ->
        when {
            imp.alias == null && imp.path in introPaths ->
                ImportFinding(imp, ImportVerdict.REDUNDANT, "the box intro already has this line")
            imp.star && imp.pkg in introStars ->
                ImportFinding(imp, ImportVerdict.REDUNDANT, "the box intro already imports ${imp.pkg}.*")
            imp.star -> starVerdict(imp, resolution)
            names == null -> ImportFinding(imp, ImportVerdict.KEPT, "the code does not parse")
            imp.name in OPERATOR_NAMES -> ImportFinding(imp, ImportVerdict.KEPT, "an operator convention is used without its name")
            imp.name !in names -> ImportFinding(imp, ImportVerdict.UNUSED, "`${imp.name}` never appears in the code")
            imp.alias == null && imp.pkg in introStars && !disambiguates(imp, introStars, otherCandidates) ->
                ImportFinding(imp, ImportVerdict.REDUNDANT, "the box intro already imports ${imp.pkg}.*")
            else -> ImportFinding(imp, ImportVerdict.NEEDED, "`${imp.name}` is used")
        }
    }
}

private fun starVerdict(imp: BoxImport, resolution: K2ImportEvidence?): ImportFinding {
    if (resolution == null) return ImportFinding(imp, ImportVerdict.KEPT, "no K2 analysis to show it unused")
    if (resolution.resolvedFqns.any { it.startsWith(imp.pkg + ".") })
        return ImportFinding(imp, ImportVerdict.NEEDED, "K2 resolves names in ${imp.pkg}")
    if (resolution.unresolvedCount > 0)
        return ImportFinding(imp, ImportVerdict.KEPT, "K2 left ${resolution.unresolvedCount} name(s) unresolved")
    return ImportFinding(imp, ImportVerdict.UNUSED, "K2 resolved every name and none is in ${imp.pkg}")
}

/** Whether removing [imp] exposes a competing Field or Kotlin/JVM default import. */
private fun disambiguates(imp: BoxImport, introStars: Set<String>, otherCandidates: (String) -> Collection<String>): Boolean {
    val name = imp.name ?: return false
    // Kotlin/JVM default imports are visible alongside the Field intro. In particular,
    // dropping Vulkan's Target imports silently selects kotlin.annotation.Target.
    val visibleStars = introStars + setOf(
        "kotlin", "kotlin.annotation", "kotlin.collections", "kotlin.comparisons", "kotlin.io",
        "kotlin.ranges", "kotlin.sequences", "kotlin.text", "kotlin.jvm", "java.lang",
    )
    return otherCandidates(name).any { fqn -> fqn != imp.path && fqn.substringBeforeLast('.', "") in visibleStars }
}

/**
 * The type a box already sees under the simple name [name] through [introText]'s imports, as a
 * fully qualified name, or null when there is none: what a class generated with that name (a shader
 * block's, say) would hide.
 */
fun introProvidedType(name: String, introText: String = intro): String? {
    val imports = boxImports(introText)
    imports.firstOrNull { !it.star && it.name == name }?.let { return it.path }
    val stars = imports.filter { it.star }.map { it.pkg }.toSet()
    // A star import brings in a package's top-level classes, not the classes nested in them.
    return classpathCandidates(name).firstOrNull { '$' !in it && it.substringBeforeLast('.', "") in stars }
}

private fun classpathCandidates(name: String): Collection<String> =
    try { ImportResolution.index()[name].orEmpty() } catch (t: Throwable) { emptyList() }

/**
 * [code] with its redundant and unused imports removed and the remaining import lines sorted, in
 * place of the original block. The rest of the code is untouched, comment lines among the imports
 * included; returns [code] itself when nothing would change.
 */
fun formatBoxImports(code: String, findings: List<ImportFinding> = analyzeBoxImports(code)): String {
    if (findings.isEmpty()) return code
    val drop = findings.filter { it.verdict == ImportVerdict.REDUNDANT || it.verdict == ImportVerdict.UNUSED }
        .map { it.import.line }.toSet()
    val kept = findings.filter { it.import.line !in drop }.map { it.import.text }.distinct().sorted()
    val importLines = findings.map { it.import.line }.toSet()
    val lines = code.lines()
    val first = importLines.min()
    val out = ArrayList<String>(lines.size)
    lines.forEachIndexed { i, line ->
        when {
            i == first -> out += kept
            i in importLines -> {}
            else -> out += line
        }
    }
    // A block emptied entirely leaves the blank line that separated it from the code.
    while (kept.isEmpty() && out.size > first && first < out.size && out[first].isBlank()) out.removeAt(first)
    val result = out.joinToString("\n")
    return if (result == code) code else result
}

/**
 * Ask the code-use K2 sidecar what [code] resolves to, for [analyzeBoxImports]. The box's own import
 * lines go to the sidecar as imports and the rest as the snippet, which is how it expects box code.
 * Null when no sidecar answered (`AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL` unset, or it timed out): the
 * source-index fallback is a guess about names, not a resolution, so it is no evidence here.
 *
 * The sidecar resolves against Field's own sources and no classpath, so a library name (`File`,
 * `VK10`) comes back unresolved; that keeps every star import of such a box, which is the safe side.
 */
fun k2ImportEvidence(code: String, timeoutMs: Long = 10_000L): K2ImportEvidence? {
    val imports = boxImports(code)
    val importLines = imports.map { it.line }.toSet()
    val body = code.lines().filterIndexed { i, _ -> i !in importLines }.joinToString("\n")
    val result = try {
        CodeUseTelemetryService.semanticAnalyzeCode(
            CodeUseSemanticAnalyzeRequest(
                code = body,
                sourceName = "BoxImports.kt",
                imports = imports.map { if (it.alias != null) "${it.path} as ${it.alias}" else it.path },
                timeoutMs = timeoutMs,
            )
        )
    } catch (t: Throwable) {
        return null
    }
    if (!result.success || !result.backend.startsWith("k2")) return null
    return K2ImportEvidence(result.resolvedUses.map { it.symbolName }.filter { it.isNotBlank() }.toSet(), result.unresolvedUses.size)
}

/**
 * What Format Kotlin does to a box's `code`: drop its redundant and unused imports (with K2 evidence
 * when the code-use sidecar answers, by name alone when it does not), then format with ktfmt.
 */
fun formatBoxKotlin(code: String, useK2: Boolean = true): String {
    // Remote heads compile without the JVM box intro; its implicit imports cannot justify removal.
    if (runCatching { afield.lang.remotejs.KotlinJsHeadProfiles.resolve(code).explicit }.getOrDefault(false)) {
        return formatKotlinCode(code)
    }
    val evidence = if (useK2) k2ImportEvidence(code) else null
    return formatKotlinCode(formatBoxImports(code, analyzeBoxImports(code, resolution = evidence)))
}
