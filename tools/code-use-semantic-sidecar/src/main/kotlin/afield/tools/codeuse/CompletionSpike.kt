package afield.tools.codeuse

import com.intellij.openapi.util.Disposer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.findTopLevelCallables
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Paths
import java.util.Base64
import kotlin.io.path.exists

/**
 * PHASE 1 spike harness for K2 Analysis-API completion. The candidate-generation logic now lives in
 * the reusable [AaCompleter] / [AaSymbolIndex]; this file just builds a standalone AA session over
 * field's binary classpath and runs the Phase 0 corpus through it, comparing the intended member
 * against the produced candidates. Out-of-process on purpose (the standalone AA classpath must stay
 * out of the live Field runtime).
 *
 * Usage: --workspace <root> --corpus <tsv> --classpath-file <file> [--require-all]. Corpus rows are
 * `label\tbase64(code-with-‸)\tmember`.
 */
/** One AA candidate as dumped for the K1-vs-AA differential (Stage B). */
@Serializable
data class AaDumpCandidate(val name: String, val kind: String, val returnType: String? = null, val container: String? = null)

/** Per-fixture AA result as dumped for the differential. `member` = the intended member the fixture targets. */
@Serializable
data class AaDumpFixture(
    val id: String,
    val member: String,
    val isDot: Boolean,
    val prefix: String,
    val note: String,
    val candidates: List<AaDumpCandidate>,
)

object CompletionSpike {

    private const val CARET = '‸'
    private val dumpJson = Json { encodeDefaults = true; prettyPrint = true }

    /** The corpus harness has no live Field to ask, so it analyses under the shared fallback. This was
     *  a third hand-written copy of that list, which had drifted from the sidecar's (no `afield.*`, no
     *  `afield.four.modules.*`) — so the differential was measuring AA under a scope no real box has.
     *  See [CompletionImports] for where the real REPL imports come from. */
    private val defaultImports = CompletionImports.default

    @JvmStatic
    fun main(args: Array<String>) {
        var workspace = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        var corpus = "tools/completion-corpus/scraped.tsv"
        var classpathFile = ""
        var jsonOut = ""
        var requireAll = false
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--workspace" -> workspace = Paths.get(args[++i]).toAbsolutePath().normalize()
                "--corpus" -> corpus = args[++i] // may be a comma-separated list of TSVs
                "--classpath-file" -> classpathFile = args[++i]
                "--json-out" -> jsonOut = args[++i] // dump raw AA candidates per fixture for the differential
                "--require-all" -> requireAll = true
            }
            i++
        }

        // Field's own compiled output is NOT in jvmRuntimeClasspath (deps only); add it so afield types
        // (Vec4, FLine, Functions, toggleNetwork, …) resolve their full member/extension scope.
        val ownOutput = listOf("build/classes/kotlin/jvm/main", "build/processedResources/jvm/main")
            .map { workspace.resolve(it) }
        val libRoots = (java.io.File(classpathFile).readLines().map { Paths.get(it.trim()) } + ownOutput)
            .filter { it.toString().isNotBlank() && it.exists() }.distinct()
        val ctxDir = java.nio.file.Files.createTempDirectory("spike-ctx")
        java.nio.file.Files.writeString(ctxDir.resolve("Ctx.kt"), "package __ctx__\n")
        System.err.println("[spike] building AA session with ${libRoots.size} binary roots …")

        val disposable = Disposer.newDisposable("completion-spike")
        val session = buildStandaloneAnalysisAPISession(disposable, unitTestMode = false) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform
                val sdk = buildKtSdkModule {
                    platform = JvmPlatforms.defaultJvmPlatform
                    libraryName = "jdk"
                    addBinaryRootsFromJdkHome(Paths.get(System.getProperty("java.home")), isJre = false)
                }
                val libs = buildKtLibraryModule {
                    platform = JvmPlatforms.defaultJvmPlatform
                    libraryName = "field-classpath"
                    addRegularDependency(sdk)
                    addBinaryRoots(libRoots)
                }
                addModule(buildKtSourceModule {
                    platform = JvmPlatforms.defaultJvmPlatform
                    moduleName = "ctx"
                    addRegularDependency(sdk)
                    addRegularDependency(libs)
                    addSourceRoots(listOf(ctxDir))
                })
            }
        }
        val contextFile = session.modulesWithFiles.values.asSequence().flatten()
            .filterIsInstance<KtFile>().first()

        // De-risk: can findTopLevelCallables resolve a binary-library top-level function by exact name?
        @OptIn(KaExperimentalApi::class, KaIdeApi::class)
        analyze(contextFile) {
            for (probe in listOf("kotlin.collections" to "map", "afield.stdlib" to "toggleNetwork")) {
                val n = findTopLevelCallables(FqName(probe.first), Name.identifier(probe.second)).count()
                System.err.println("[selftest] findTopLevelCallables(${probe.first}, ${probe.second}) = $n")
            }
        }

        val importedPackages = defaultImports.map { it.removeSuffix(".*") }.toSet()
        val cacheDir = workspace.resolve("build/completion-corpus")
        val index = AaSymbolIndex.buildOrLoad(libRoots, importedPackages, cacheDir)
        System.err.println("[spike] index: ${index.callableCount} callables, ${index.classifierCount} classifiers, ${index.typeAliasCount} type-aliases")

        val completer = AaCompleter(contextFile, index, defaultImports, libRoots)

        val dump = mutableListOf<AaDumpFixture>()
        var surfaced = 0; var total = 0
        corpus.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { corpusPath ->
            workspace.resolve(corpusPath).toFile().readLines().filter { it.isNotBlank() }.forEach { line ->
                val cols = line.split('\t')
                val label = cols[0]; val code = String(Base64.getDecoder().decode(cols[1])); val member = cols.getOrElse(2) { "" }
                val caret = code.indexOf(CARET); if (caret < 0) return@forEach
                val before = code.substring(0, caret); val after = code.substring(caret + 1)
                total++

                val prefix = Regex("[A-Za-z0-9_]*$").find(before)?.value ?: ""
                val isDot = before.dropLast(prefix.length).trimEnd().endsWith(".")

                val result = completer.complete(before + after, before.length)
                val hit = if (member.isEmpty()) result.candidates.isEmpty()
                    else result.candidates.any { it.name == member }
                if (hit) surfaced++
                dump += AaDumpFixture(label, member, isDot, prefix, result.note,
                    result.candidates.map { AaDumpCandidate(it.name, it.kind.name, it.returnType, it.container) })
                println("────────────────────────────────────────")
                println("[$label]  ${if (isDot) "DOT" else "NAME"}  prefix='$prefix'  intended .$member  ${if (hit) "✓" else "✗"}  (${result.candidates.size} cands)${if (result.note.isNotBlank()) "  <${result.note}>" else ""}")
                result.candidates.take(15).forEachIndexed { k, c ->
                    val ext = when (c.kind) {
                        AaCandidate.Kind.EXTENSION -> " [ext]"; AaCandidate.Kind.PARAM -> " [param]"
                        AaCandidate.Kind.CLASSIFIER -> " [class]"; AaCandidate.Kind.TOP_LEVEL -> ""
                        AaCandidate.Kind.MEMBER -> ""
                    }
                    println("   ${k + 1}. ${c.name} : ${c.returnType ?: "?"}$ext${c.container?.let { "  ($it)" } ?: ""}")
                }
            }
        }
        if (jsonOut.isNotBlank()) {
            val out = workspace.resolve(jsonOut)
            java.nio.file.Files.createDirectories(out.parent)
            java.nio.file.Files.writeString(out, dumpJson.encodeToString(dump))
            System.err.println("[spike] wrote ${dump.size} AA fixtures → $out")
        }
        println("════════════════════════════════════════")
        println("SPIKE SUMMARY: intended member surfaced in $surfaced/$total fixtures")
        Disposer.dispose(disposable)
        // The Analysis API / IntelliJ platform init spins up non-daemon threads (AWT, indexing pools)
        // that keep the forked JVM alive after our work is done, so gradle's JavaExec hangs and each
        // run leaks a ~300MB process. Force a clean exit now that results are printed.
        System.out.flush()
        kotlin.system.exitProcess(if (requireAll && surfaced != total) 1 else 0)
    }
}
