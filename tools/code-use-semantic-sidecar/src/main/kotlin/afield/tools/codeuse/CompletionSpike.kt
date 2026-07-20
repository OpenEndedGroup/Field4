package afield.tools.codeuse

import com.intellij.openapi.util.Disposer
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.KaK1Unsupported
import org.jetbrains.kotlin.analysis.api.components.KaExtensionApplicabilityResult
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.findTopLevelCallables
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import java.nio.file.Paths
import java.util.Base64
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

/**
 * PHASE 1 spike: Kotlin completion candidate-generation on the K2 Analysis API (KaSession),
 * out-of-process (the standalone AA classpath must stay out of the live Field runtime). No turnkey
 * AA completion API exists (that's the KTNB-916 gap), so we build candidates ourselves:
 *   - dot position `recv.pre` → resolve recv's KaType, enumerate its member scope, filter by prefix
 *   - (name position is a follow-up)
 *
 * Run over the Phase 0 corpus and eyeball against K1's recorded output. The win conditions are the
 * K1 warts: no `Any?`-extension noise dominating, no keywords after `.`, real member types.
 *
 * Usage: pass --workspace <root> --corpus <tsv>. Reads label\tbase64(code with ‸)\tmember lines.
 */
object CompletionSpike {

    private const val CARET = '‸'

    @JvmStatic
    fun main(args: Array<String>) {
        var workspace = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        var corpus = "tools/completion-corpus/scraped.tsv"
        var classpathFile = ""
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--workspace" -> workspace = Paths.get(args[++i]).toAbsolutePath().normalize()
                "--corpus" -> corpus = args[++i]
                "--classpath-file" -> classpathFile = args[++i]
            }
            i++
        }

        // Resolve afield + stdlib + deps from field's compiled runtime classpath (binary roots),
        // so receiver types resolve their full member/extension scope.
        // Field's own compiled output is NOT in jvmRuntimeClasspath (that's deps only); add it so
        // afield types (Vec4, FLine, Functions, toggleNetwork, …) resolve.
        val ownOutput = listOf("build/classes/kotlin/jvm/main", "build/processedResources/jvm/main")
            .map { workspace.resolve(it) }
        val libRoots = (java.io.File(classpathFile).readLines().map { Paths.get(it.trim()) } + ownOutput)
            .filter { it.toString().isNotBlank() && it.exists() }.distinct()
        // Tiny dummy source module just to host the dangling snippet's context file.
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
        @OptIn(KaExperimentalApi::class, KaIdeApi::class, KaK1Unsupported::class)
        run {
            analyze(contextFile) {
                for (probe in listOf("kotlin.collections" to "map", "afield.stdlib" to "toggleNetwork")) {
                    val n = findTopLevelCallables(
                        org.jetbrains.kotlin.name.FqName(probe.first),
                        org.jetbrains.kotlin.name.Name.identifier(probe.second)
                    ).count()
                    System.err.println("[selftest] findTopLevelCallables(${probe.first}, ${probe.second}) = $n")
                }
            }
        }

        val defaultImports = listOf(
            // Kotlin default (auto) imports the compiler applies implicitly — needed so stdlib
            // extensions (List.map, etc.) are in scope for KaTypeScope.
            "kotlin.*", "kotlin.collections.*", "kotlin.sequences.*", "kotlin.text.*",
            "kotlin.io.*", "kotlin.ranges.*", "kotlin.comparisons.*", "kotlin.math.*",
            "afield.stdlib.*", "afield.stdlib.linalg.*", "afield.graphics.*",
            "afield.four.*", "afield.four.util.*", "afield.four.synth.*",
        )

        // Binary package scopes won't enumerate names (getPossibleCallableNames/callables both empty),
        // but findTopLevelCallables(pkg, exactName) resolves fine — so build a package→names index by
        // ASM-scanning facade classes once (the classpath rarely changes).
        val importedPackages = defaultImports.map { it.removeSuffix(".*") }.toSet()
        val (nameIndex, classifierIndex) = buildIndex(libRoots, importedPackages)
        System.err.println("[spike] index: ${nameIndex.values.sumOf { it.size }} callables, ${classifierIndex.values.sumOf { it.size }} classifiers")

        val tsv = workspace.resolve(corpus)
        var surfaced = 0; var total = 0
        tsv.toFile().readLines().filter { it.isNotBlank() }.forEach { line ->
            val cols = line.split('\t')
            val label = cols[0]; val code = String(Base64.getDecoder().decode(cols[1])); val member = cols.getOrElse(2){""}
            val caret = code.indexOf(CARET); if (caret < 0) return@forEach
            val before = code.substring(0, caret); val after = code.substring(caret + 1)
            total++

            // dot vs name: trailing identifier prefix, then check for '.'
            val prefix = Regex("[A-Za-z0-9_]*$").find(before)?.value ?: ""
            val beforePrefix = before.dropLast(prefix.length)
            val isDot = beforePrefix.trimEnd().endsWith(".")

            val header = defaultImports.joinToString("\n") { "import $it" } + "\n\nfun __c() {\n"
            val synthetic = header + before + after + "\n}\n"
            val cursorOffset = header.length + before.length

            val cands = try {
                val file = KtPsiFactory.contextual(contextFile, markGenerated = false, eventSystemEnabled = false)
                    .createFile("Spike.kt", synthetic)
                completeAt(file, cursorOffset, isDot, prefix, nameIndex, classifierIndex)
            } catch (t: Throwable) {
                listOf("<error ${t.javaClass.simpleName}: ${t.message}>")
            }

            val hit = cands.any { it.startsWith("$member(") || it == member || it.startsWith("$member ") || it.startsWith("$member:") }
            if (hit) surfaced++
            println("────────────────────────────────────────")
            println("[$label]  ${if (isDot) "DOT" else "NAME"}  prefix='$prefix'  intended .$member  ${if (hit) "✓" else "✗"}  (${cands.size} cands)")
            cands.take(15).forEachIndexed { k, c -> println("   ${k + 1}. $c") }
        }
        println("════════════════════════════════════════")
        println("SPIKE SUMMARY: intended member surfaced in $surfaced/$total fixtures")
        Disposer.dispose(disposable)
    }

    /**
     * One ASM pass over the imported packages' binary roots → (package→top-level callable names,
     * package→top-level classifier names). Standalone AA can't enumerate binary package members by
     * prefix, but resolves them fine by exact name (findTopLevelCallables / findClassLike). Facade
     * classes (`*Kt`) give top-level functions; other top-level `.class` files give classifier names.
     * (Type aliases have no `.class` and are not indexed here — a later refinement via @Metadata.)
     */
    private fun buildIndex(
        roots: List<java.nio.file.Path>,
        packages: Set<String>,
    ): Pair<Map<String, Set<String>>, Map<String, Set<String>>> {
        val callables = HashMap<String, MutableSet<String>>()
        val classifiers = HashMap<String, MutableSet<String>>()
        fun consider(entryPath: String, bytes: () -> ByteArray) {
            if (!entryPath.endsWith(".class")) return
            val slash = entryPath.lastIndexOf('/')
            val pkg = if (slash < 0) "" else entryPath.substring(0, slash).replace('/', '.')
            if (pkg !in packages) return
            val simple = entryPath.substring(slash + 1).removeSuffix(".class")
            if (simple.contains('$')) return // inner / synthetic
            if (simple.endsWith("Kt")) {
                // Kotlin file facade → top-level functions are its public static methods.
                runCatching {
                    org.jetbrains.org.objectweb.asm.ClassReader(bytes()).accept(
                        object : org.jetbrains.org.objectweb.asm.ClassVisitor(org.jetbrains.org.objectweb.asm.Opcodes.ASM9) {
                            override fun visitMethod(access: Int, name: String, desc: String?, sig: String?, exc: Array<String>?): org.jetbrains.org.objectweb.asm.MethodVisitor? {
                                val pub = access and org.jetbrains.org.objectweb.asm.Opcodes.ACC_PUBLIC != 0
                                val stat = access and org.jetbrains.org.objectweb.asm.Opcodes.ACC_STATIC != 0
                                if (pub && stat && name != "<clinit>" && !name.contains('$')) {
                                    callables.getOrPut(pkg) { hashSetOf() }.add(name)
                                }
                                return null
                            }
                        },
                        org.jetbrains.org.objectweb.asm.ClassReader.SKIP_CODE or
                            org.jetbrains.org.objectweb.asm.ClassReader.SKIP_DEBUG or
                            org.jetbrains.org.objectweb.asm.ClassReader.SKIP_FRAMES
                    )
                }
            } else {
                classifiers.getOrPut(pkg) { hashSetOf() }.add(simple)
            }
        }
        for (root in roots) {
            if (root.toString().endsWith(".jar")) {
                runCatching {
                    java.util.zip.ZipFile(root.toFile()).use { zf ->
                        zf.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                            consider(e.name) { zf.getInputStream(e).use { it.readBytes() } }
                        }
                    }
                }
            } else if (java.nio.file.Files.isDirectory(root)) {
                runCatching {
                    java.nio.file.Files.walk(root).use { s ->
                        s.filter { java.nio.file.Files.isRegularFile(it) }.forEach { f ->
                            consider(root.relativize(f).toString().replace('\\', '/')) { java.nio.file.Files.readAllBytes(f) }
                        }
                    }
                }
            }
        }
        return callables to classifiers
    }

    @OptIn(KaExperimentalApi::class, KaIdeApi::class, KaK1Unsupported::class)
    private fun completeAt(file: KtFile, offset: Int, isDot: Boolean, prefix: String, nameIndex: Map<String, Set<String>>, classifierIndex: Map<String, Set<String>>): List<String> {
        val leaf = file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return listOf("<no element at cursor>")
        val match: (org.jetbrains.kotlin.name.Name) -> Boolean =
            { n -> prefix.isEmpty() || n.asString().startsWith(prefix) }
        return analyze(file) {
            fun callable(sym: KaCallableSymbol): String? {
                val nm = (sym as? KaNamedSymbol)?.name?.asString() ?: return null
                val ext = if (sym.isExtension) " [ext]" else ""
                return "$nm : ${runCatching { sym.returnType.toString() }.getOrDefault("?")}$ext"
            }
            if (isDot) {
                val dq = PsiTreeUtil.getParentOfType(leaf, KtDotQualifiedExpression::class.java)
                    ?: return@analyze listOf("<no dot-qualified expr>")
                val recv: KtExpression = dq.receiverExpression
                val type = recv.expressionType ?: return@analyze listOf("<receiver type UNRESOLVED: ${recv.text}>")
                val typeStr = runCatching { type.toString() }.getOrDefault("?")

                // members (substituted) of the receiver type
                val members = type.scope?.getCallableSignatures(match).orEmpty()
                    .mapNotNull { sig ->
                        val nm = (sig.symbol as? KaNamedSymbol)?.name?.asString() ?: return@mapNotNull null
                        "$nm : ${runCatching { sig.returnType.toString() }.getOrDefault("?")}"
                    }.toList()

                // applicable extensions: candidate callables in scope + implicit-receiver members,
                // filtered through the IDE's own extension-applicability checker.
                val selector = dq.selectorExpression as? KtSimpleNameExpression
                var extCand = 0; var applicable = 0; var pkgFound = 0
                val extensions: List<String> = if (selector != null) {
                    val checker = createExtensionCandidateChecker(file, selector, recv)
                    val ctx = file.scopeContext(selector)
                    // Use the prebuilt name index (binary package scopes don't enumerate), then resolve
                    // each prefix-matching name with findTopLevelCallables.
                    val fromPackages = nameIndex.asSequence().flatMap { (pkg, names) ->
                        val fq = org.jetbrains.kotlin.name.FqName(pkg)
                        names.asSequence()
                            .filter { prefix.isEmpty() || it.startsWith(prefix) }
                            .flatMap { nm -> findTopLevelCallables(fq, org.jetbrains.kotlin.name.Name.identifier(nm)) }
                    }.also { pkgFound = nameIndex.size }
                    val candidates = (ctx.compositeScope().callables(match) + fromPackages +
                        ctx.implicitReceivers.asSequence().flatMap { r ->
                            r.type.scope?.getCallableSignatures(match)?.map { it.symbol } ?: emptySequence()
                        }).filter { it.isExtension }.toList()
                    extCand = candidates.size
                    val ok = candidates.filter { checker.computeApplicability(it) is KaExtensionApplicabilityResult.Applicable }
                    applicable = ok.size
                    ok.mapNotNull(::callable)
                } else emptyList()

                val out = (members + extensions).distinct().sorted()
                if (out.isEmpty()) listOf("<0 matched; type=$typeStr; members=${members.size} pkgFound=$pkgFound extCand=$extCand applicable=$applicable>") else out
            } else {
                val pos = PsiTreeUtil.getParentOfType(leaf, KtElement::class.java)
                    ?: (leaf as? KtElement) ?: return@analyze listOf("<no position element>")

                // argument position → offer the enclosing call's remaining (not-yet-supplied) parameters
                val argList = PsiTreeUtil.getParentOfType(leaf, KtValueArgumentList::class.java)
                val namedParams: List<String> = if (argList != null) {
                    val call = PsiTreeUtil.getParentOfType(argList, KtCallExpression::class.java)
                    // Union parameters across ALL overload candidates (an incomplete/empty call may
                    // "successfully" resolve to a no-arg overload, hiding the interesting ones).
                    val callCands = call?.resolveToCallCandidates().orEmpty()
                        .mapNotNull { it.candidate as? KaFunctionCall<*> }
                    val supplied = argList.arguments.mapNotNull { it.getArgumentName()?.asName?.asString() }.toSet()
                    callCands.flatMap { it.partiallyAppliedSymbol.signature.valueParameters }
                        .map { it.symbol }
                        .distinctBy { it.name.asString() }
                        .filter { it.name.asString() !in supplied }
                        .filter { prefix.isEmpty() || it.name.asString().startsWith(prefix) }
                        .map { "${it.name.asString()} = : ${runCatching { it.returnType.toString() }.getOrDefault("?")} [param]" }
                } else emptyList()

                val ctx = file.scopeContext(pos)
                // local + imported scope callables/classifiers
                val fromScopes: List<String> = ctx.compositeScope().callables(match).mapNotNull(::callable).toList()
                // implicit-receiver members, e.g. `with(Functions){ si| }` → Functions.sin
                val fromReceivers: List<String> = ctx.implicitReceivers.asSequence().flatMap { rec ->
                    rec.type.scope?.getCallableSignatures(match)?.mapNotNull { sig ->
                        (sig.symbol as? KaNamedSymbol)?.name?.asString()?.let { "$it : ${runCatching { sig.returnType.toString() }.getOrDefault("?")}" }
                    } ?: emptySequence()
                }.toList()
                // Global symbols (index-backed) only once the user has typed a prefix — an empty
                // prefix at a name/arg position shouldn't flood with every class in the classpath.
                val topCallables: List<String> = if (prefix.isEmpty()) emptyList() else nameIndex.asSequence().flatMap { (pkg, names) ->
                    val fq = org.jetbrains.kotlin.name.FqName(pkg)
                    names.asSequence().filter { it.startsWith(prefix) }
                        .flatMap { nm -> findTopLevelCallables(fq, org.jetbrains.kotlin.name.Name.identifier(nm)) }
                }.filter { !it.isExtension }.mapNotNull(::callable).toList()
                // classifier (class / type) names from the index
                val classifiers: List<String> = if (prefix.isEmpty()) emptyList() else classifierIndex.asSequence().flatMap { (pkg, names) ->
                    val fq = org.jetbrains.kotlin.name.FqName(pkg)
                    names.asSequence().filter { it.startsWith(prefix) }
                        .mapNotNull { nm ->
                            val sym = findClassLike(org.jetbrains.kotlin.name.ClassId(fq, org.jetbrains.kotlin.name.Name.identifier(nm)))
                            if (sym != null) "$nm  ($pkg) [class]" else null
                        }
                }.toList()
                (namedParams + fromScopes + fromReceivers + topCallables + classifiers).distinct().sorted()
            }
        }
    }
}
