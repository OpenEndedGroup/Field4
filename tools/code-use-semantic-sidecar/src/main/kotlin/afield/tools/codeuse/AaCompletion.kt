package afield.tools.codeuse

import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.KaK1Unsupported
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaExtensionApplicabilityResult
import org.jetbrains.kotlin.analysis.api.components.KaScopeKind
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.symbols.findTopLevelCallables
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * A single completion candidate produced by the K2 Analysis API. Structured (not a display string) so
 * it can be diffed against K1 (Stage B) and mapped to Field's `SourceCodeCompletionVariant` (Stage C).
 */
data class AaCandidate(
    val name: String,
    val kind: Kind,
    /** Substituted return type for callables / expanded type for classifiers; null when not applicable. */
    val returnType: String?,
    /** Package (top-level / classifier) or receiver type (member), for display + de-duplication. */
    val container: String?,
    /** fieldSuggest icon: "method" | "val" | "var" | "class" | "property". */
    val icon: String = "property",
    /** Rendered parameter list for functions, e.g. "(v: Vec3)"; null for non-functions. */
    val params: String? = null,
) {
    enum class Kind { MEMBER, EXTENSION, TOP_LEVEL, CLASSIFIER, PARAM }

    /** Stable key so true duplicates collapse but distinct overloads don't. Includes [icon] so a
     *  property and a same-named/same-typed method (e.g. Vector3f's `var x: Float` and `fun x(): Float`)
     *  both survive — otherwise the property gets clobbered by the method. */
    val dedupKey: String get() = "$name|$kind|$icon|$returnType|$params"

    /** Synthetic wrapper artifacts from the completion harness (`__c`, `__r0`, …) must never surface. */
    val isSynthetic: Boolean get() = name.startsWith("__")
}

/** Candidates plus a human-readable note (populated when the list is empty, to explain why). */
data class AaCompletionResult(val candidates: List<AaCandidate>, val note: String = "")

/**
 * Compact a type string for display: `afield/graphics/FLine` → `FLine`,
 * `kotlin.collections.List<kotlin.Int>` → `List<Int>`, `kotlin.Float?` → `Float?`. Package segments
 * (lowercase) before a Capitalised class name are dropped; generics/nullability are preserved.
 */
internal fun shortenType(type: String): String =
    type.replace('/', '.')
        // AA renders a type alias as "{Vec3=} Vector3f" — prefer the alias name the user wrote.
        .replace(Regex("""\{([^=}]+)=}\s*[^\s,>)]+"""), "$1")
        .replace(Regex("""\b[a-z][A-Za-z0-9_]*(?:\.[a-z][A-Za-z0-9_]*)*\.([A-Z][A-Za-z0-9_]*)"""), "$1")

/**
 * JVM-only stdlib members that do NOT exist in the Kotlin/JS stdlib. A `mainLanguage="kotlinjs"` box
 * is compiled Kotlin→JS and runs in a browser / vision head with no JVM, yet AA completion is scoped
 * to the *JVM* kotlin-stdlib jar, so Kotlin's JVM built-ins customizer augments `kotlin.collections.*`
 * / `kotlin.String` with JDK members (`stream`, `spliterator`, `toArray`, `chars`, …). Offering them
 * on `.` either fails to compile to JS or throws at runtime. Grounded empirically (2026-07-24) against
 * a stdlib-only completion on List/Collection/MutableList/String; see kotlinjs roadmap Stage 2b.
 *
 * These names return Kotlin types, so the java-signature rule in [isJvmOnlyForJsTarget] misses them;
 * they're denied by name but ONLY on a `kotlin.*` receiver, so a user's own `fun stream()` on their
 * own kotlinjs class is never hidden.
 */
internal val JVM_ONLY_STDLIB_MEMBERS: Set<String> = setOf(
    "toArray",                 // java.util.Collection.toArray (→ kotlin.Array, escapes the java-type rule)
    "getChars",                // java.lang.String.getChars (→ kotlin.Unit)
    "parallel", "sequential",  // java.util.stream.BaseStream, should a Stream ever be a receiver
)

/**
 * True when [c] is a JVM-platform-only member that must be filtered from kotlinjs-scoped completion.
 * Principled where possible: any `java.`/`javax.` type in the signature is a JVM type with no JS
 * equivalent (catches `stream`/`parallelStream`/`spliterator`/`chars`/`codePoints`). The curated
 * [JVM_ONLY_STDLIB_MEMBERS] covers the residue that returns Kotlin types.
 */
internal fun isJvmOnlyForJsTarget(c: AaCandidate): Boolean {
    if (c.kind != AaCandidate.Kind.MEMBER && c.kind != AaCandidate.Kind.EXTENSION) return false
    val signature = (c.returnType ?: "") + " " + (c.params ?: "")
    if (Regex("""\bjavax?[./]""").containsMatchIn(signature)) return true
    val onStdlibReceiver = (c.container ?: "").let { it.startsWith("kotlin/") || it.startsWith("kotlin.") }
    return onStdlibReceiver && c.name in JVM_ONLY_STDLIB_MEMBERS
}

/**
 * Prefix-enumeration index for binary library packages. Standalone AA can NOT enumerate a binary
 * package's members by prefix (`packageScope.callables(nameFilter)` and `getPossibleCallableNames()`
 * both return empty for binary packages), but `findTopLevelCallables(pkg, exactName)`,
 * `findClassLike(ClassId)` and `findTypeAlias(ClassId)` resolve fine by exact name. So we scan the
 * binary roots once with ASM to learn which names exist per package, then resolve on demand.
 */
@Serializable
data class AaSymbolIndex(
    /** package fqName -> top-level callable (function/property) simple names. */
    val callables: Map<String, List<String>>,
    /** package fqName -> classifier (class/object/interface/annotation) simple names. */
    val classifiers: Map<String, List<String>>,
    /** package fqName -> type-alias simple names (read from facade @Metadata; they have no .class). */
    val typeAliases: Map<String, List<String>>,
) {
    val callableCount: Int get() = callables.values.sumOf { it.size }
    val classifierCount: Int get() = classifiers.values.sumOf { it.size }
    val typeAliasCount: Int get() = typeAliases.values.sumOf { it.size }

    companion object {
        /** Bump when the on-disk format or the scan semantics change, to invalidate stale caches. */
        private const val FORMAT_VERSION = 2
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        /**
         * Build the index, or load it from [cacheDir] when a cache keyed by the same classpath
         * fingerprint exists. The fingerprint folds in every root's identity + size/mtime, so a
         * recompile or dependency bump transparently rebuilds. Pass a null [cacheDir] to skip caching.
         */
        fun buildOrLoad(roots: List<Path>, packages: Set<String>, cacheDir: Path?): AaSymbolIndex {
            val fingerprint = fingerprint(roots, packages)
            val cacheFile = cacheDir?.resolve("aa-symbol-index-$fingerprint.json")
            if (cacheFile != null && Files.exists(cacheFile)) {
                runCatching { json.decodeFromString<AaSymbolIndex>(Files.readString(cacheFile)) }
                    .getOrNull()?.let { return it }
            }
            val index = build(roots, packages)
            if (cacheFile != null) {
                runCatching {
                    Files.createDirectories(cacheFile.parent)
                    Files.writeString(cacheFile, json.encodeToString(index))
                }
            }
            return index
        }

        /** Cheap (stat-only) content fingerprint: never reads .class bytes. */
        private fun fingerprint(roots: List<Path>, packages: Set<String>): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("v$FORMAT_VERSION".toByteArray())
            md.update(packages.sorted().joinToString(",").toByteArray())
            for (root in roots.sortedBy { it.toString() }) {
                md.update(root.toString().toByteArray())
                if (Files.isDirectory(root)) {
                    // Dir roots are compiled output: the dir's own mtime can lag nested changes, so
                    // fold in the newest .class mtime (a stat walk, cheap vs the byte-reading scan).
                    var newest = 0L; var count = 0L
                    runCatching {
                        Files.walk(root).use { s ->
                            s.filter { it.toString().endsWith(".class") }.forEach {
                                newest = maxOf(newest, runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L))
                                count++
                            }
                        }
                    }
                    md.update("$newest:$count".toByteArray())
                } else if (Files.isRegularFile(root)) {
                    val size = runCatching { Files.size(root) }.getOrDefault(0L)
                    val mtime = runCatching { Files.getLastModifiedTime(root).toMillis() }.getOrDefault(0L)
                    md.update("$size:$mtime".toByteArray())
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }.take(24)
        }

        private fun build(roots: List<Path>, packages: Set<String>): AaSymbolIndex {
            val callables = HashMap<String, MutableSet<String>>()
            val classifiers = HashMap<String, MutableSet<String>>()
            val typeAliases = HashMap<String, MutableSet<String>>()

            fun consider(entryPath: String, bytes: () -> ByteArray) {
                if (!entryPath.endsWith(".class")) return
                val slash = entryPath.lastIndexOf('/')
                val pkg = if (slash < 0) "" else entryPath.substring(0, slash).replace('/', '.')
                if (pkg !in packages) return
                val simple = entryPath.substring(slash + 1).removeSuffix(".class")
                if (simple.contains('$')) return // inner / synthetic
                if (simple.endsWith("Kt")) {
                    // Kotlin file facade: top-level functions are its public static methods, and its
                    // @Metadata (FileFacade) carries the file's type aliases (which have no .class).
                    runCatching {
                        val raw = bytes()
                        ClassReader(raw).accept(
                            FacadeVisitor(
                                onCallable = { callables.getOrPut(pkg) { hashSetOf() }.add(it) },
                                onTypeAlias = { typeAliases.getOrPut(pkg) { hashSetOf() }.add(it) },
                            ),
                            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
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
                } else if (Files.isDirectory(root)) {
                    runCatching {
                        Files.walk(root).use { s ->
                            s.filter { Files.isRegularFile(it) }.forEach { f ->
                                consider(root.relativize(f).toString().replace('\\', '/')) { Files.readAllBytes(f) }
                            }
                        }
                    }
                }
            }
            return AaSymbolIndex(
                callables = callables.mapValues { it.value.toList() },
                classifiers = classifiers.mapValues { it.value.toList() },
                typeAliases = typeAliases.mapValues { it.value.toList() },
            )
        }
    }
}

/** ASM visitor over a `*Kt` facade: collects public-static method names + @Metadata type aliases. */
private class FacadeVisitor(
    private val onCallable: (String) -> Unit,
    private val onTypeAlias: (String) -> Unit,
) : ClassVisitor(Opcodes.ASM9) {
    override fun visitMethod(access: Int, name: String, desc: String?, sig: String?, exc: Array<String>?): MethodVisitor? {
        val pub = access and Opcodes.ACC_PUBLIC != 0
        val stat = access and Opcodes.ACC_STATIC != 0
        if (pub && stat && name != "<clinit>" && !name.contains('$')) onCallable(name)
        return null
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
        if (descriptor != "Lkotlin/Metadata;") return null
        return MetadataVisitor(onTypeAlias)
    }
}

/**
 * Reads `kotlin.Metadata`'s `k` (kind), `d1` (proto data) and `d2` (string table), and — for a file
 * facade (k==2) — decodes the package proto to surface its top-level type-alias names.
 */
private class MetadataVisitor(private val onTypeAlias: (String) -> Unit) : AnnotationVisitor(Opcodes.ASM9) {
    private var kind = 0
    private val d1 = mutableListOf<String>()
    private val d2 = mutableListOf<String>()

    override fun visit(name: String?, value: Any?) {
        if (name == "k" && value is Int) kind = value
    }

    override fun visitArray(name: String?): AnnotationVisitor? = when (name) {
        "d1" -> stringCollector(d1)
        "d2" -> stringCollector(d2)
        else -> null
    }

    private fun stringCollector(into: MutableList<String>) = object : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(name: String?, value: Any?) { if (value is String) into.add(value) }
    }

    override fun visitEnd() {
        if (kind != 2 || d1.isEmpty()) return // only file facades carry top-level type aliases
        runCatching {
            val (nameResolver, pkg) = JvmProtoBufUtil.readPackageDataFrom(d1.toTypedArray(), d2.toTypedArray())
            pkg.typeAliasList.forEach { ta: ProtoBuf.TypeAlias -> onTypeAlias(nameResolver.getString(ta.name)) }
        }
    }
}

/**
 * K2 Analysis-API completion candidate generation. Reusable across the spike harness (Stage A/B) and
 * the sidecar HTTP endpoint (Stage C). Construct once per session with a [contextFile] from that
 * session (used to contextualize in-memory dangling snippet files) and a prebuilt [index].
 */
class AaCompleter(
    private val contextFile: KtFile,
    private val index: AaSymbolIndex,
    private val defaultImports: List<String>,
) {
    /** One-line index size summary, for sidecar startup logging. */
    val indexSummary: String
        get() = "${index.callableCount} callables, ${index.classifierCount} classifiers, ${index.typeAliasCount} type-aliases"

    /**
     * @param snippet           the code being edited, WITHOUT any caret marker.
     * @param cursor            caret offset into [snippet].
     * @param preamble          the box's INHERITED preamble (`gatherPreambleUpwards`): top-level
     *                          declarations — `prop<>`-macro `Box`-extension properties, `@exported`
     *                          `_name` aliases, imports. Injected at file top level (these decls are
     *                          not local-legal), so completion resolves against them (the load-bearing
     *                          invariant in dev_docs/COMPLETION_ENGINE.md). May fail to parse for
     *                          non-declaration preambles; the caller (KI2) falls back to K1 on error.
     * @param declarations      synthetic declarations that must remain at file scope, such as a
     *                          Kotlin/JS head's JVM-analysis-safe object/class model.
     * @param implicitReceivers fully-qualified receiver types active in the box (e.g. `afield.Box`).
     *                          Modelled as nested `with(param)` scopes so their members resolve as
     *                          bare names and receiver dot-completion works.
     * @param extraImports      per-request imports (the box's own `import` lines), added to the
     *                          default REPL imports.
     * @param jsTarget          true for a `mainLanguage="kotlinjs"` box (compiled Kotlin→JS, no JVM):
     *                          JVM-only stdlib members ([isJvmOnlyForJsTarget]) are filtered out. Never
     *                          set for regular Kotlin completion, so that path is byte-for-byte unchanged.
     */
    fun complete(
        snippet: String,
        cursor: Int,
        preamble: String = "",
        declarations: String = "",
        implicitReceivers: List<String> = emptyList(),
        extraImports: List<String> = emptyList(),
        jsTarget: Boolean = false,
    ): AaCompletionResult {
        val before = snippet.substring(0, cursor)
        val after = snippet.substring(cursor)
        val prefix = Regex("[A-Za-z0-9_]*$").find(before)?.value ?: ""
        // Covers both `recv.` and safe `recv?.` — both end in '.' after dropping the prefix.
        val isDot = before.dropLast(prefix.length).trimEnd().endsWith(".")

        // A dangling file's own TOP-LEVEL declarations do NOT enter the completion scope (only imports,
        // implicit receivers, and LOCAL declarations do). So the inherited preamble is split: import
        // lines stay at file top level, and every declaration is re-emitted as a *local* stub inside the
        // completion function (name + type preserved, extension receiver / `by` delegate stripped so it's
        // local-legal). This surfaces `prop<>` and `@exported` inherited names — the load-bearing invariant.
        val (preambleImports, localStubs) = splitPreamble(preamble)
        val (declarationImports, declarationStubs) = splitPreamble(declarations)

        val sb = StringBuilder()
        (defaultImports + extraImports + preambleImports + declarationImports).map { it.removePrefix("import ").trim() }
            .filter { it.isNotEmpty() }.distinct().forEach { sb.append("import ").append(it).append('\n') }
        sb.append('\n')
        declarations.trimIndent().lineSequence()
            .filterNot { it.trimStart().startsWith("import ") || it.trimStart().startsWith("package ") }
            .forEach { sb.append(it).append('\n') }
        if (declarations.isNotBlank()) sb.append('\n')

        val recvs = implicitReceivers.filter { it.isNotBlank() }
        val params = recvs.mapIndexed { i, t -> "__r$i: $t" }.joinToString(", ")
        sb.append("fun __c(").append(params).append(") {\n")
        (localStubs + declarationStubs).forEach { sb.append("    ").append(it).append('\n') }
        recvs.indices.forEach { i -> sb.append("with(__r").append(i).append(") { ") }
        sb.append(before)
        val cursorOffset = sb.length
        sb.append(after)
        recvs.forEach { _ -> sb.append(" }") }
        sb.append("\n}\n")

        return try {
            val file = KtPsiFactory.contextual(contextFile, markGenerated = false, eventSystemEnabled = false)
                .createFile("AaCompletion.kt", sb.toString())
            completeAt(file, cursorOffset, isDot, prefix, jsTarget)
        } catch (t: Throwable) {
            AaCompletionResult(emptyList(), "error ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Split an inherited preamble into (import lines, local-legal declaration stubs). Handles the forms
     * `gatherPreambleUpwards` actually produces (see dev_docs/COMPLETION_ENGINE.md): `prop<>` macro
     * expansions `var Box.name by Inheritance<Foo>()`, `@exported` aliases `val Box._name: T get() = …`,
     * plain top-level `val/var/fun`, and imports. Each declaration is reduced to a stub that keeps the
     * NAME and (where recoverable) the TYPE, dropping the extension receiver and delegate so it is legal
     * as a *local* declaration (which is the only file-declaration form completion sees in a dangling
     * file). Best-effort + defensive: an unparseable line is skipped, never breaking the whole request.
     */
    internal fun splitPreamble(preamble: String): Pair<List<String>, List<String>> {
        if (preamble.isBlank()) return emptyList<String>() to emptyList()
        val imports = mutableListOf<String>()
        val stubs = mutableListOf<String>()
        val seen = HashSet<String>()
        // `(val|var) [Recv.]name [: Type] [by Delegate<Type>() | = … | get() …]`
        val prop = Regex("""^\s*(?:@\w+\s+)*(?:val|var)\s+(?:[\w.]+\.)?(\w+)\s*(?::\s*([\w.<>?,\s]+?)\s*)?(?:\bby\s+[\w.]+\s*<\s*([\w.<>?,\s]+?)\s*>|=|\bget\b|$)""")
        val fn = Regex("""^\s*(?:@\w+\s+)*(?:suspend\s+|inline\s+|operator\s+)*fun\s+(?:<[^>]*>\s+)?(?:[\w.]+\.)?(\w+)\s*\(([^)]*)\)\s*(?::\s*([\w.<>?,\s]+?)\s*)?(?:=|\{|$)""")
        preamble.trimIndent().lineSequence().filter { it.isNotBlank() }.forEach { rawLine ->
            runCatching {
                // Nested members belong to their file-scope class/object declaration. Re-emitting
                // them as locals pollutes bare-name completion and loses their receiver.
                if (rawLine.firstOrNull()?.isWhitespace() == true) return@runCatching
                val line = rawLine.trim()
                when {
                    line.startsWith("import ") -> imports += line
                    line.startsWith("package ") -> {} // drop
                    line.startsWith("fun ") || line.contains(Regex("""\bfun\s+\w""")) -> {
                        val m = fn.find(line) ?: return@runCatching
                        val name = m.groupValues[1]
                        val params = m.groupValues[2].trim()
                        val type = m.groupValues[3].trim().ifEmpty { "Any?" }
                        if (seen.add("fun:$name:$params:$type")) stubs += "fun $name($params): $type = TODO()"
                    }
                    else -> {
                        val m = prop.find(line) ?: return@runCatching
                        val name = m.groupValues[1]
                        val type = m.groupValues[2].trim().ifEmpty { m.groupValues[3].trim() }.ifEmpty { "Any?" }
                        if (seen.add("prop:$name")) stubs += "val $name: $type = TODO()"
                    }
                }
            }
        }
        return imports to stubs
    }

    @OptIn(KaExperimentalApi::class, KaIdeApi::class, KaK1Unsupported::class)
    private fun completeAt(file: KtFile, offset: Int, isDot: Boolean, prefix: String, jsTarget: Boolean): AaCompletionResult {
        val leaf = file.findElementAt((offset - 1).coerceAtLeast(0))
            ?: return AaCompletionResult(emptyList(), "no element at cursor")
        val match: (Name) -> Boolean = { n -> prefix.isEmpty() || n.asString().startsWith(prefix) }
        // In a kotlinjs box (compiled to JS, no JVM) drop JVM-only stdlib members; a no-op otherwise.
        val jsFilter: (AaCandidate) -> Boolean = { c -> !jsTarget || !isJvmOnlyForJsTarget(c) }

        return analyze(file) {
            fun iconFor(sym: KaCallableSymbol): String = when (sym) {
                is KaNamedFunctionSymbol -> "method"
                is KaPropertySymbol -> if (sym.isVal) "val" else "var"
                else -> "property"
            }
            fun paramsOf(sym: KaCallableSymbol): String? = (sym as? KaNamedFunctionSymbol)?.valueParameters
                ?.joinToString(", ", "(", ")") { p: KaValueParameterSymbol ->
                    "${p.name.asString()}: ${shortenType(runCatching { p.returnType.toString() }.getOrDefault("?"))}"
                }
            fun asCandidate(sym: KaCallableSymbol, kind: AaCandidate.Kind, container: String?): AaCandidate? {
                val nm = (sym as? KaNamedSymbol)?.name?.asString() ?: return null
                return AaCandidate(nm, kind, runCatching { sym.returnType.toString() }.getOrNull(), container, iconFor(sym), paramsOf(sym))
            }

            if (isDot) {
                val dq = PsiTreeUtil.getParentOfType(leaf, KtQualifiedExpression::class.java)
                    ?: return@analyze AaCompletionResult(emptyList(), "no qualified expr")
                val recv: KtExpression = dq.receiverExpression
                val type = recv.expressionType
                    ?: return@analyze AaCompletionResult(emptyList(), "receiver type UNRESOLVED: ${recv.text}")
                val typeStr = runCatching { type.toString() }.getOrDefault("?")

                // Substituted members of the receiver type.
                val members = type.scope?.getCallableSignatures(match).orEmpty().mapNotNull { sig ->
                    val nm = (sig.symbol as? KaNamedSymbol)?.name?.asString() ?: return@mapNotNull null
                    AaCandidate(nm, AaCandidate.Kind.MEMBER, runCatching { sig.returnType.toString() }.getOrNull(), typeStr, iconFor(sig.symbol), paramsOf(sig.symbol))
                }.toList()

                // Applicable extensions: candidate extension callables from scope + implicit receivers +
                // the binary name index. Two subtleties this handles:
                //  - When the cursor is right after `.` (no name typed yet) there is no selector
                //    KtSimpleNameExpression, so the IDE extension checker can't be built — we anchor scope
                //    on the qualified expression and rely on the conformance fallback below.
                //  - The IDE `computeApplicability` checker returns nothing for binary-index extensions in a
                //    standalone AA session (so kotlin.collections.map on List never surfaced). Fall back to
                //    a receiver-conformance test: keep the extension if the receiver type is a subtype of the
                //    extension's receiver-parameter type — exact/generic-aware first, then an ERASED
                //    classifier check (List<Int> IS-A the `Iterable` classId even though a free `T` makes the
                //    full type check false). The index enumeration is gated on a non-empty prefix so a bare
                //    `.` doesn't walk the whole stdlib.
                val selector = dq.selectorExpression as? KtSimpleNameExpression
                val checker = selector?.let { runCatching { createExtensionCandidateChecker(file, it, recv) }.getOrNull() }
                val ctx = file.scopeContext(selector ?: dq)
                val fromPackages = if (prefix.isEmpty()) emptySequence() else index.callables.asSequence().flatMap { (pkg, names) ->
                    val fq = FqName(pkg)
                    names.asSequence().filter { it.startsWith(prefix) }
                        .flatMap { nm -> findTopLevelCallables(fq, Name.identifier(nm)) }
                }
                val extCandidates = (ctx.compositeScope().callables(match) + fromPackages +
                    ctx.implicitReceivers.asSequence().flatMap { r ->
                        r.type.scope?.getCallableSignatures(match)?.map { it.symbol } ?: emptySequence()
                    }).filter { it.isExtension }.toList()
                val extensions = extCandidates.filter { ext ->
                    if (checker != null && runCatching {
                            checker.computeApplicability(ext) is KaExtensionApplicabilityResult.Applicable
                        }.getOrDefault(false)) return@filter true
                    val rt = ext.receiverParameter?.returnType ?: return@filter false
                    if (runCatching { type.isSubtypeOf(rt) }.getOrDefault(false)) return@filter true
                    val cid = (rt as? KaClassType)?.classId ?: return@filter false
                    runCatching { type.isSubtypeOf(cid) }.getOrDefault(false)
                }.mapNotNull { asCandidate(it, AaCandidate.Kind.EXTENSION, typeStr) }

                // Object/companion scopes can expose nested classifiers (`THREE.Vector3`). They are
                // not callables, so the member-only path used to make namespace-like head APIs blank.
                val nestedClassifiers = type.scope?.getClassifierSymbols(match).orEmpty().mapNotNull { symbol ->
                    val name = (symbol as? KaNamedSymbol)?.name?.asString() ?: return@mapNotNull null
                    AaCandidate(name, AaCandidate.Kind.CLASSIFIER, null, typeStr, "class")
                }.toList()

                val out = (members + extensions + nestedClassifiers)
                    .filterNot { it.isSynthetic }.filter(jsFilter).distinctBy { it.dedupKey }.sortedBy { it.name }
                AaCompletionResult(out, if (out.isEmpty()) "0 matched; type=$typeStr; members=${members.size}" else "")
            } else {
                val pos = PsiTreeUtil.getParentOfType(leaf, KtElement::class.java) ?: (leaf as? KtElement)
                    ?: return@analyze AaCompletionResult(emptyList(), "no position element")

                // Argument position → the enclosing call's remaining (not-yet-supplied) named params,
                // unioned across all overloads (an incomplete call may resolve to a no-arg overload).
                val argList = PsiTreeUtil.getParentOfType(leaf, KtValueArgumentList::class.java)
                val namedParams: List<AaCandidate> = if (argList != null) {
                    val call = PsiTreeUtil.getParentOfType(argList, KtCallExpression::class.java)
                    val callCands = call?.resolveToCallCandidates().orEmpty()
                        .mapNotNull { it.candidate as? KaFunctionCall<*> }
                    val supplied = argList.arguments.mapNotNull { it.getArgumentName()?.asName?.asString() }.toSet()
                    callCands.flatMap { it.partiallyAppliedSymbol.signature.valueParameters }.map { it.symbol }
                        .distinctBy { it.name.asString() }
                        .filter { it.name.asString() !in supplied }
                        .filter { prefix.isEmpty() || it.name.asString().startsWith(prefix) }
                        .map { AaCandidate(it.name.asString(), AaCandidate.Kind.PARAM, runCatching { it.returnType.toString() }.getOrNull(), null, "var") }
                } else emptyList()

                val ctx = file.scopeContext(pos)
                // Scope filtering: (1) implicit-receiver members come from `fromReceivers` below, so drop
                // the `with(...)` TypeScope here to avoid listing them twice; (2) with an empty prefix
                // (right after `f(` or on a blank line) the star-import scopes would flood the panel with
                // every top-level stdlib function, so drop importing scopes until a prefix is typed — keep
                // locals (incl. the preamble stubs) + params. Non-empty prefix re-includes imports.
                val scopeFilter: (KaScopeKind) -> Boolean = { k ->
                    k !is KaScopeKind.TypeScope && (prefix.isNotEmpty() || k !is KaScopeKind.ImportingScope)
                }
                val fromScopes = ctx.compositeScope(scopeFilter).callables(match)
                    .mapNotNull { asCandidate(it, AaCandidate.Kind.TOP_LEVEL, null) }.toList()
                val sourceClassifiers = ctx.compositeScope(scopeFilter).classifiers(match).mapNotNull { symbol ->
                    val name = (symbol as? KaNamedSymbol)?.name?.asString() ?: return@mapNotNull null
                    AaCandidate(name, AaCandidate.Kind.CLASSIFIER, null, null, "class")
                }.toList()
                // At an empty-prefix ARGUMENT position (`f(‸`) the call's parameters are what matters;
                // dumping every implicit-receiver (e.g. `Box`) member there just buries the params. Skip
                // them until a prefix is typed. At a name position (blank line) they stay — that's how you
                // discover the receiver's API.
                val suppressReceivers = argList != null && prefix.isEmpty()
                val fromReceivers = if (suppressReceivers) emptyList() else ctx.implicitReceivers.asSequence().flatMap { rec ->
                    rec.type.scope?.getCallableSignatures(match)?.mapNotNull { sig ->
                        (sig.symbol as? KaNamedSymbol)?.name?.asString()?.let {
                            AaCandidate(it, AaCandidate.Kind.MEMBER, runCatching { sig.returnType.toString() }.getOrNull(),
                                runCatching { rec.type.toString() }.getOrNull(), iconFor(sig.symbol), paramsOf(sig.symbol))
                        }
                    } ?: emptySequence()
                }.toList()
                // Global symbols (index-backed) only once a prefix is typed — an empty prefix shouldn't
                // flood the client with every class in the classpath (client owns ranking, not volume).
                val indexCallables: List<KaCallableSymbol> = if (prefix.isEmpty()) emptyList() else
                    index.callables.asSequence().flatMap { (pkg, names) ->
                        val fq = FqName(pkg)
                        names.asSequence().filter { it.startsWith(prefix) }
                            .flatMap { nm -> findTopLevelCallables(fq, Name.identifier(nm)) }
                    }.toList()
                val topCallables = indexCallables.filter { !it.isExtension }
                    .mapNotNull { asCandidate(it, AaCandidate.Kind.TOP_LEVEL, null) }
                // Imported EXTENSION functions applicable to an implicit receiver — e.g. `Box.threeLines`
                // when the box's implicit `Box` receiver is in scope. These fall through every other path:
                // a binary package's star-import scope doesn't enumerate (the reason the index exists), and
                // `topCallables` excludes extensions. Keep those whose extension-receiver type an implicit
                // receiver conforms to; the container is that receiver type (so member-KDoc resolves).
                val implicitTypes = ctx.implicitReceivers.map { it.type }
                val receiverExtensions: List<AaCandidate> = if (implicitTypes.isEmpty()) emptyList() else
                    indexCallables.filter { it.isExtension }.filter { ext ->
                        val rt = ext.receiverParameter?.returnType ?: return@filter false
                        implicitTypes.any { runCatching { it.isSubtypeOf(rt) }.getOrDefault(false) }
                    }.mapNotNull { asCandidate(it, AaCandidate.Kind.EXTENSION, runCatching { it.receiverParameter?.returnType?.toString() }.getOrNull()) }
                val classifiers: List<AaCandidate> = if (prefix.isEmpty()) emptyList() else buildList {
                    for ((pkg, names) in index.classifiers) {
                        val fq = FqName(pkg)
                        for (nm in names) if (nm.startsWith(prefix)) {
                            if (findClassLike(ClassId(fq, Name.identifier(nm))) != null)
                                add(AaCandidate(nm, AaCandidate.Kind.CLASSIFIER, null, pkg, "class"))
                        }
                    }
                    // Type aliases (no .class; indexed from @Metadata) — resolve via findTypeAlias.
                    for ((pkg, names) in index.typeAliases) {
                        val fq = FqName(pkg)
                        for (nm in names) if (nm.startsWith(prefix)) {
                            val ta = findTypeAlias(ClassId(fq, Name.identifier(nm)))
                            if (ta != null) add(AaCandidate(nm, AaCandidate.Kind.CLASSIFIER,
                                runCatching { ta.expandedType.toString() }.getOrNull(), pkg, "class"))
                        }
                    }
                }

                val out = (namedParams + fromScopes + sourceClassifiers + fromReceivers + topCallables + receiverExtensions + classifiers)
                    .filterNot { it.isSynthetic }.filter(jsFilter).distinctBy { it.dedupKey }.sortedBy { it.name }
                AaCompletionResult(out, if (out.isEmpty()) "0 candidates at name position" else "")
            }
        }
    }
}
