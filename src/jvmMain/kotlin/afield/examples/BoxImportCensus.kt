package afield.examples

import afield.runtime.kotlin.BoxImport
import afield.runtime.kotlin.ImportResolution
import afield.runtime.kotlin.ImportVerdict
import afield.runtime.kotlin.analyzeBoxImports
import afield.runtime.kotlin.boxImports
import afield.document.graph.intro
import afield.runtime.kotlin.k2ImportEvidence
import afield.runtime.kotlin.referencedNames
import java.io.File
import kotlin.system.exitProcess

/**
 * Reads the imports of every published box and reports what they are for. Writes nothing but its
 * report: it changes no box.
 *
 * For each box (a `code: |` block of a top-level transcript, a `*_code_.kt` sidecar, or a Kotlin
 * fence on a docs page) it records [analyzeBoxImports]'s verdict on every import. Then, across the
 * corpus, it ranks the imports the box intro does not already provide: these are the candidates for
 * growing the intro. For a candidate star package it lists the class names that would clash with a
 * name an intro star package already offers, and the boxes that use such a name and would stop
 * compiling with "ambiguous" errors. The clash check knows classes only (the classpath index has no
 * top-level functions), so a function clash is not reported.
 *
 * `./gradlew boxImportCensus --args="[--k2] [--out build/box-imports] [path...]"`: with no paths it
 * reads the whole corpus; `--k2` asks the code-use sidecar for resolution evidence, one request per
 * box. Writes `report.md` and `findings.tsv` under `--out`.
 */
object BoxImportCensus {
    private data class CorpusBox(val source: String, val line: Int, val code: String)

    private val ROOTS = listOf(
        "dev_docs/agents/transcripts", "dev_docs/agents/demos", "public/docs", "templates",
    )
    private const val GENERATED = "public/docs/assets/agent_transcripts"

    @JvmStatic
    fun main(args: Array<String>) {
        val useK2 = "--k2" in args
        val outIndex = args.indexOf("--out")
        val out = File(if (outIndex >= 0) args[outIndex + 1] else "build/box-imports").also { it.mkdirs() }
        val paths = args.filterIndexed { i, a -> !a.startsWith("--") && (outIndex < 0 || i != outIndex + 1) }
        val repo = repoRoot()
        val files = if (paths.isEmpty()) corpusFiles(repo) else paths.map { File(it).absoluteFile }

        val boxes = files.flatMap { boxesIn(it, repo) }
        println("BoxImportCensus: ${boxes.size} box(es) in ${files.size} file(s)${if (useK2) ", with K2 evidence" else ""}")

        val rows = mutableListOf<String>()
        val verdicts = mutableMapOf<ImportVerdict, Int>()
        val uncovered = mutableMapOf<String, MutableSet<String>>() // import path -> sources
        val namesBySource = mutableMapOf<String, Set<String>>()
        for (box in boxes) {
            val evidence = if (useK2) k2ImportEvidence(box.code) else null
            namesBySource["${box.source}:${box.line}"] = referencedNames(box.code).orEmpty()
            for (f in analyzeBoxImports(box.code, resolution = evidence)) {
                verdicts.merge(f.verdict, 1, Int::plus)
                rows += listOf(box.source, (box.line + f.import.line + 1).toString(), f.verdict.name, f.import.path, f.reason)
                    .joinToString("\t")
                if (f.verdict == ImportVerdict.NEEDED || f.verdict == ImportVerdict.KEPT)
                    uncovered.getOrPut(f.import.path) { mutableSetOf() } += box.source
            }
        }

        File(out, "findings.tsv").writeText("source\tline\tverdict\timport\treason\n" + rows.joinToString("\n") + "\n")
        File(out, "report.md").writeText(report(boxes.size, files.size, verdicts, uncovered, namesBySource))
        println("BoxImportCensus: ${verdicts.entries.joinToString { "${it.key.name.lowercase()} ${it.value}" }}; wrote ${out.path}")
        exitProcess(0)
    }

    private fun report(
        boxCount: Int,
        fileCount: Int,
        verdicts: Map<ImportVerdict, Int>,
        uncovered: Map<String, Set<String>>,
        namesBySource: Map<String, Set<String>>,
    ): String = buildString {
        appendLine("# Box imports")
        appendLine()
        appendLine("$boxCount boxes in $fileCount files.")
        appendLine()
        appendLine("| verdict | imports |")
        appendLine("| --- | --- |")
        ImportVerdict.entries.forEach { appendLine("| ${it.name.lowercase()} | ${verdicts[it] ?: 0} |") }
        appendLine()
        appendLine("## Intro candidates")
        appendLine()
        appendLine("Imports boxes keep that the intro does not provide, most-used first. A star package's")
        appendLine("clashes are class names an intro star package already offers.")
        appendLine()
        appendLine("| import | files | clashing names | boxes using a clashing name |")
        appendLine("| --- | --- | --- | --- |")
        val introStars = boxImports(intro).filter(BoxImport::star).map(BoxImport::pkg).toSet()
        uncovered.entries.sortedByDescending { it.value.size }.take(60).forEach { (path, sources) ->
            val clashes = if (path.endsWith(".*")) clashingNames(path.removeSuffix(".*"), introStars) else emptySet()
            val affected = namesBySource.filterValues { names -> names.any { it in clashes } }.keys
            appendLine("| `$path` | ${sources.size} | ${clashes.sorted().joinToString(", ").ifEmpty { "—" }} | ${affected.size} |")
        }
        packageTable(uncovered, namesBySource, introStars)
    }

    /** The by-package table: every kept import grouped by the package it imports from. */
    private fun StringBuilder.packageTable(uncovered: Map<String, Set<String>>, namesBySource: Map<String, Set<String>>, introStars: Set<String>) {
        appendLine()
        appendLine("### By package")
        appendLine()
        appendLine("The same imports grouped by package: what `import <package>.*` in the intro would cover.")
        appendLine()
        appendLine("| package | files | distinct imports | clashing names | boxes using a clashing name |")
        appendLine("| --- | --- | --- | --- | --- |")
        val byPackage = uncovered.entries.groupBy { (path, _) -> if (path.endsWith(".*")) path.removeSuffix(".*") else path.substringBeforeLast('.') }
        byPackage.filterKeys { it !in introStars }.entries.sortedByDescending { (_, e) -> e.flatMap { it.value }.toSet().size }.take(40).forEach { (pkg, entries) ->
            val files = entries.flatMap { it.value }.toSet()
            val clashes = clashingNames(pkg, introStars)
            val affected = namesBySource.filterValues { names -> names.any { it in clashes } }.keys
            appendLine("| `$pkg` | ${files.size} | ${entries.size} | ${clashes.sorted().take(12).joinToString(", ").ifEmpty { "—" }} | ${affected.size} |")
        }
    }

    /** Simple class names in [pkg] that some intro star package also has a class of. */
    private fun clashingNames(pkg: String, introStars: Set<String>): Set<String> =
        ImportResolution.index().filter { (name, fqns) ->
            writableClassName(name) &&
                fqns.any { it.substringBeforeLast('.') == pkg } && fqns.any { it.substringBeforeLast('.') in introStars }
        }.keys

    /** A name box code could write: not a compiler-generated class (`Companion`, `1`, `FooKt`, ...). */
    private fun writableClassName(name: String) =
        Regex("[A-Z][A-Za-z0-9_]*").matches(name) && !name.endsWith("Kt") &&
            name !in setOf("Companion", "DefaultImpls", "WhenMappings", "EntriesMappings")

    private fun corpusFiles(repo: File): List<File> = ROOTS.map { File(repo, it) }.filter(File::exists)
        .flatMap { root -> root.walkTopDown().filter(File::isFile).toList() }
        .filter { f ->
            val rel = f.relativeTo(repo).invariantSeparatorsPath
            !rel.startsWith(GENERATED) && when {
                rel.endsWith("_code_.kt") -> true
                rel.endsWith(".md") -> rel.startsWith("public/docs/")
                rel.endsWith(".yaml") -> rel.startsWith("dev_docs/agents/transcripts/") && rel.count { it == '/' } == 3
                else -> false
            }
        }.sorted()

    private fun boxesIn(file: File, repo: File): List<CorpusBox> {
        val rel = file.relativeToOrSelf(repo).invariantSeparatorsPath
        val lines = file.readLines()
        return when {
            file.name.endsWith(".kt") -> listOf(CorpusBox(rel, 0, lines.joinToString("\n")))
            file.extension == "md" -> blocks(lines, { Regex("""^`{3,}\s*(?:\{?\s*\.?kotlin\b|\S*\s*\{[^}]*\bdata-lang=kotlin\b)""").containsMatchIn(it.trim()) },
                { Regex("""^`{3,}\s*$""").matches(it.trim()) }).map { (start, body) -> CorpusBox(rel, start, body) }
            else -> yamlCodeBlocks(lines).map { (start, body) -> CorpusBox(rel, start, body) }
        }
    }

    private fun blocks(lines: List<String>, opens: (String) -> Boolean, closes: (String) -> Boolean): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var start = -1
        lines.forEachIndexed { i, line ->
            if (start < 0 && opens(line)) start = i + 1
            else if (start >= 0 && closes(line)) { out += start to lines.subList(start, i).joinToString("\n"); start = -1 }
        }
        return out
    }

    private fun yamlCodeBlocks(lines: List<String>): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (i < lines.size) {
            val m = Regex("""^(\s*)(?:-\s+)?code:\s*\|[-+]?\s*$""").matchEntire(lines[i])
            if (m == null) { i++; continue }
            val keyIndent = m.groupValues[1].length
            val start = i + 1
            var j = start
            while (j < lines.size && (lines[j].isBlank() || lines[j].length - lines[j].trimStart().length > keyIndent)) j++
            val block = lines.subList(start, j)
            val indent = block.filter(String::isNotBlank).minOfOrNull { it.length - it.trimStart().length } ?: 0
            out += start to block.joinToString("\n") { if (it.isBlank()) "" else it.substring(indent) }
            i = j
        }
        return out
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir ?: File(".").absoluteFile
    }
}
