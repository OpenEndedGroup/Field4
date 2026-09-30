package afield.tools.codeuse

import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.KaExtensionApplicabilityResult
import org.jetbrains.kotlin.analysis.api.components.KaScopeKind
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.symbols.findTopLevelCallables
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The imports a snippet is analysed under when the caller does not say.
 *
 * THE SOURCE OF TRUTH FOR FIELD'S REPL IMPORTS IS `afield.Boxes.intro`, surfaced as the list
 * `afield.introImports` and sent per request as [CompletionRequest.replImports]. Nothing here is
 * authoritative and nothing here should be edited to "add a package a box needs" — add it to `intro`.
 *
 * This module cannot see `afield` (it is a standalone tool built against the Kotlin compiler, not
 * against Field), so for callers that send no `replImports` — the corpus spike, and any future
 * harness — there has to be a floor. That floor used to be written out twice, here and in
 * [CompletionSpike], and the two had already drifted apart from each other as well as from `intro`.
 * One copy now, so at worst it is wrong in one place.
 */
object CompletionImports {
    /** Kotlin's own auto-imports. Part of the language, identical for every caller, never sent. */
    val kotlinLanguage = listOf(
        "kotlin.*", "kotlin.collections.*", "kotlin.sequences.*", "kotlin.text.*",
        "kotlin.io.*", "kotlin.ranges.*", "kotlin.comparisons.*", "kotlin.math.*",
    )

    /**
     * A strict subset of Field's REPL imports, used ONLY when a request carries no `replImports`.
     *
     * It is the historical hand-written guess, kept because the corpus fixtures resolve under it —
     * not because it is right. It is missing `afield.nano`, `afield.graphquery`, `afield.four.targets`,
     * `afield.four.buffers`, `afield.four.texture` and the `afield.stdlib.linalg` sub-packages, all of
     * which `intro` has. Those now arrive from the live client, which is why this is allowed to stay
     * wrong rather than being another thing to keep in step.
     */
    val fieldFallback = listOf(
        "afield.*", "afield.stdlib.*", "afield.stdlib.linalg.*", "afield.graphics.*",
        "afield.four.*", "afield.four.util.*", "afield.four.synth.*", "afield.four.modules.*",
    )

    /** What a session's ASM prefix index is seeded with before any request has been seen. A package a
     *  request names but this misses is scanned on demand ([AaCompleter.indexFor]). */
    val default = kotlinLanguage + fieldFallback
}

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
    /**
     * What to type into the editor, when that differs from [name]. An enum entry is offered by its
     * bare name (`SD_COLLAPSED` — that is what the panel should read) but must be *inserted* qualified
     * (`Scheme.SD_COLLAPSED`), because Kotlin does not resolve a bare entry from the expected type
     * outside a `when` subject. Null = insert [name] (every other candidate).
     */
    val insertText: String? = null,
    /**
     * This candidate's type fits the type expected at the caret ([AaCompleter.expectedTypesAt]).
     * Consumed by the server as a ranking boost, and — at an untyped caret — as the signal to show
     * *only* these, which is what makes `d.scheme = ‸` a three-row panel instead of a three-hundred
     * row one. See `execution.kt`'s `typeFitFilterActive`.
     */
    val fitsExpected: Boolean = false,
    /**
     * Where this candidate is DECLARED — the containing class's fq name for a member, the declaring
     * package for a top-level extension, `""` when unknown (a local, or a preamble declaration).
     *
     * Not the same question as [container], which says what the candidate hangs off in *this* panel. The
     * distinction is load-bearing for sectioning: the server used to infer a candidate's category from
     * the expression's receiver, so on a `ComputeMesh.` panel `let`, `also` and `apply` were all filed
     * under GRAPHICS — a section header that described the receiver rather than the row. Categorising on
     * the declaration puts `also` under Kotlin, `T.onBeat` (afield.four.synth) under Audio, and leaves
     * GRAPHICS meaning what it says.
     */
    val declaredIn: String = "",
    /**
     * How close this candidate's declaration is to the receiver's own type — [Proximity].
     *
     * The panel's job after a `.` is to answer "what can this thing do", and a name declared on `Any` or
     * on an unconstrained `T` answers that for every thing, so it carries the least information and
     * should sit below the names that are actually about this type. This is the signal that lets the
     * client rank on that principle instead of on a hardcoded list of low-value names (`equals`,
     * `hashCode`, `toString` — which are simply members declared on `Any`, and fall out of the rule).
     */
    val proximity: Proximity = Proximity.UNKNOWN,
) {
    enum class Kind { MEMBER, EXTENSION, TOP_LEVEL, CLASSIFIER, PARAM }

    /** Declaration distance from the receiver's own type. Ordered nearest-first. */
    enum class Proximity {
        /** Declared on the receiver's own class, or in the box itself (a preamble/local declaration). */
        OWN,
        /** Inherited from, or an extension on, a proper supertype of the receiver. */
        SUPERTYPE,
        /** Declared on `Any`/`Any?` or on an unconstrained type parameter: true of everything. */
        UNIVERSAL,
        /** Not a receiver-relative candidate at all (name position, classifier, parameter). */
        UNKNOWN,
    }

    /** Stable key so true duplicates collapse but distinct overloads don't. Includes [icon] so a
     *  property and a same-named/same-typed method (e.g. Vector3f's `var x: Float` and `fun x(): Float`)
     *  both survive — otherwise the property gets clobbered by the method. */
    val dedupKey: String get() = "$name|$kind|$icon|$returnType|$params"

    /** Synthetic wrapper artifacts from the completion harness (`__c`, `__r0`, …) must never surface. */
    val isSynthetic: Boolean get() = name.startsWith("__")
}

/**
 * Candidates plus a human-readable note (populated when the list is empty, to explain why).
 *
 * [error] is set only when completion *threw*: an empty list with a note is an answer ("no element at
 * cursor"), an empty list with an error is a broken backend, and the two must not look alike on the
 * wire — every completion failing with `KotlinReflectionNotSupportedError` looked like "no candidates".
 */
data class AaCompletionResult(val candidates: List<AaCandidate>, val note: String = "", val error: String? = null)

/**
 * THE LOWERING: a box's editing context reduced to one synthetic Kotlin file, plus the map of where
 * each injected piece ended up in it. Produced by [AaCompleter.lower] and consumed by both
 * [AaCompleter.complete] (analyse at [cursorOffset]) and [AaCompleter.diagnose] (type-check the whole
 * thing) — so what completion answers from and what the invariant checks are the same artifact by
 * construction, not by two functions that agree today.
 *
 * The ranges are what make the invariant *attributable*: an error inside [preambleRange] means the
 * lowering is broken, an error inside [snippetRange] means the box's own code needs runtime context
 * this session doesn't model. Those are different bugs with different owners, and a single error count
 * conflates them. An absent region is [IntRange.EMPTY].
 */
data class AaLoweredFile(
    val text: String,
    val cursorOffset: Int,
    /** The inherited preamble, emitted verbatim at file scope. Empty when it didn't parse (see `parses`). */
    val preambleRange: IntRange,
    /** A synthetic language profile's file-scope declarations (KotlinJS heads). */
    val declarationsRange: IntRange,
    /** The local stubs re-emitted inside `fun __c`. */
    val stubsRange: IntRange,
    /** The box's own code. */
    val snippetRange: IntRange,
    /** False when the preamble was non-blank but unparseable, so only stubs represent it. */
    val preambleInjected: Boolean,
    val stubs: List<String>,
    val boxImports: List<String>,
    val providedImports: List<String>,
    val isDot: Boolean,
    val prefix: String,
) {
    /** Which injected region [offset] falls in — the label an error is reported under. */
    fun regionAt(offset: Int): String = when (offset) {
        in preambleRange -> "preamble"
        in declarationsRange -> "declarations"
        in stubsRange -> "stubs"
        in snippetRange -> "snippet"
        else -> "wrapper"
    }
}

/** One error the lowered file type-checks to, with the region of the lowering it belongs to. */
data class AaLoweringError(
    val region: String,
    val factory: String,
    val message: String,
    val line: Int,
    val excerpt: String,
)

/** [AaCompleter.diagnose]'s answer: the lowering, and every error in it. */
data class AaLoweringResult(
    val text: String,
    val preambleInjected: Boolean,
    val preambleBlank: Boolean,
    val stubCount: Int,
    val errors: List<AaLoweringError>,
    val note: String = "",
)

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
    /**
     * ASM internal receiver name (`java/nio/IntBuffer`, `afield/stdlib/Functions$RectifyMode`) -> the
     * `package|name` of every top-level callable whose FIRST PARAMETER is that type — which, for an
     * extension, is exactly its receiver (`fun Img.sobel()` compiles to `static sobel(Img)`).
     *
     * This is what lets a bare `.` offer extensions at all. Enumerating them by NAME PREFIX means there
     * is nothing to enumerate until a character is typed, which is why `recv.` answered with members only
     * and the caret-recall sweep measured a 25-point gap between a bare dot and two characters typed —
     * on Field's extension-heavy API that gap is most of what people type after a dot. Keyed by receiver
     * the work is bounded by "extensions on THIS type" instead of "all names in the index", so no prefix
     * gate is needed.
     *
     * Over-inclusive on purpose: a plain `fun display(box: Box)` lands here too, since ASM cannot tell it
     * from an extension. Resolution filters on `isExtension` and receiver conformance anyway, so the cost
     * of a false entry is one symbol lookup, not a wrong candidate.
     */
    val extensionsByReceiver: Map<String, List<String>> = emptyMap(),
    /**
     * EVERY package seen on the classpath, in scope or not — the vocabulary for completing a package path.
     * `afield.‸` has to offer `stdlib`/`four`/`graphics` whether or not they were imported, and standalone
     * AA cannot enumerate sub-packages of a binary root, so the ASM walk records them on the way past.
     */
    val allPackages: List<String> = emptyList(),
) {
    val callableCount: Int get() = callables.values.sumOf { it.size }
    val classifierCount: Int get() = classifiers.values.sumOf { it.size }
    val typeAliasCount: Int get() = typeAliases.values.sumOf { it.size }

    /** Union, for folding an on-demand package scan into the prebuilt index. */
    operator fun plus(other: AaSymbolIndex): AaSymbolIndex {
        if (other.callables.isEmpty() && other.classifiers.isEmpty() && other.typeAliases.isEmpty()) return this
        fun merge(a: Map<String, List<String>>, b: Map<String, List<String>>) =
            (a.keys + b.keys).associateWith { k -> (a[k].orEmpty() + b[k].orEmpty()).distinct() }
        return AaSymbolIndex(merge(callables, other.callables), merge(classifiers, other.classifiers),
            merge(typeAliases, other.typeAliases), merge(extensionsByReceiver, other.extensionsByReceiver),
            (allPackages + other.allPackages).distinct())
    }

    companion object {
        /** Bump when the on-disk format changes. Scan SEMANTICS are covered by [scanCodeVersion]. */
        private const val FORMAT_VERSION = 5

        /**
         * A hash of the scan's own bytecode, folded into the cache fingerprint.
         *
         * The fingerprint used to cover the classpath and a hand-maintained [FORMAT_VERSION], so a change
         * to WHAT THE SCAN RECORDS — the whole point of touching this code — left every existing cache
         * file valid and the sidecar happily served the old index. That cost an hour of a night's work
         * exactly once: a fixed `kotlinNames` looked like it had no effect, while the cache under
         * `build/…/completion-index` answered with the names the previous build had written. Nobody should
         * have to remember a version bump for a behaviour change; the class files ARE the version.
         *
         * Costs two class-file reads per fingerprint, once per session build.
         */
        private val scanCodeVersion: String by lazy {
            val md = MessageDigest.getInstance("SHA-256")
            for (cls in listOf(FacadeVisitor::class.java, AaSymbolIndex::class.java, MetadataVisitor::class.java)) {
                val resource = cls.name.replace('.', '/') + ".class"
                val bytes = runCatching {
                    cls.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
                }.getOrNull()
                // A missing resource must NOT silently fingerprint as "same": fall back to something that
                // changes every run, so the cache is bypassed rather than trusted.
                md.update(bytes ?: System.nanoTime().toString().toByteArray())
            }
            md.digest().joinToString("") { "%02x".format(it) }.take(12)
        }
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        val EMPTY = AaSymbolIndex(emptyMap(), emptyMap(), emptyMap())

        /**
         * Scan [packages] on demand, for a package the prebuilt index does not cover — anything the
         * box imports that is not one of the REPL default imports (`sentences`, a user's own jar).
         * Same scan as [build]; separate entry point because it happens per request rather than per
         * session, and is memoised by the caller (~0.3s per package, once).
         */
        fun scanPackages(roots: List<Path>, packages: Set<String>): AaSymbolIndex =
            if (packages.isEmpty()) EMPTY else build(roots, packages)

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
            md.update(scanCodeVersion.toByteArray())
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
            val byReceiver = HashMap<String, MutableSet<String>>()

            val allPackages = HashSet<String>()

            fun consider(entryPath: String, bytes: () -> ByteArray) {
                if (!entryPath.endsWith(".class")) return
                val slash = entryPath.lastIndexOf('/')
                val pkg = if (slash < 0) "" else entryPath.substring(0, slash).replace('/', '.')
                // Recorded BEFORE the in-scope filter: package completion (`afield.‸` → `stdlib`) has to
                // know about packages nobody has imported — that is the whole point of writing a package
                // path out by hand. Costs a string per class file and nothing else; no bytes are read.
                if (pkg.isNotEmpty()) allPackages.add(pkg)
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
                                onReceiver = { receiver, name ->
                                    byReceiver.getOrPut(receiver) { hashSetOf() }.add("$pkg|$name")
                                },
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
            // The JDK's own packages are not on the classpath — they live in modules — so `java.‸` had
            // nothing to list ("0 matched in package java", 12 of the caret sweep's misses) even though
            // AA resolves `java` as a package perfectly well and `java.util.Random` is ordinary box code.
            // The boot layer knows their names, which is all package completion needs: no bytes are read
            // and nothing is resolved here. `jdk.internal.*` and `sun.*` come along; they are only ever
            // listed under a path someone typed on purpose.
            runCatching {
                ModuleLayer.boot().modules().forEach { m -> allPackages.addAll(m.descriptor.packages()) }
            }
            return AaSymbolIndex(
                callables = callables.mapValues { it.value.toList() },
                classifiers = classifiers.mapValues { it.value.toList() },
                typeAliases = typeAliases.mapValues { it.value.toList() },
                extensionsByReceiver = byReceiver.mapValues { it.value.toList() },
                allPackages = allPackages.sorted(),
            )
        }
    }
}

/**
 * ASM visitor over a `*Kt` facade: collects public-static method names, @Metadata type aliases, and — for
 * [AaSymbolIndex.extensionsByReceiver] — the first-parameter type of each method paired with the Kotlin
 * name it is reachable by.
 */
private class FacadeVisitor(
    private val onCallable: (String) -> Unit,
    private val onTypeAlias: (String) -> Unit,
    private val onReceiver: (receiver: String, name: String) -> Unit = { _, _ -> },
) : ClassVisitor(Opcodes.ASM9) {

    private companion object {
        /** JVM descriptor letters → the Kotlin classifier a `fun Int.foo()`-style receiver resolves to. */
        val PRIMITIVES = mapOf(
            'I' to "kotlin/Int", 'J' to "kotlin/Long", 'F' to "kotlin/Float", 'D' to "kotlin/Double",
            'Z' to "kotlin/Boolean", 'B' to "kotlin/Byte", 'S' to "kotlin/Short", 'C' to "kotlin/Char",
        )
        val PRIMITIVE_ARRAYS = mapOf(
            "[I" to "kotlin/IntArray", "[J" to "kotlin/LongArray", "[F" to "kotlin/FloatArray",
            "[D" to "kotlin/DoubleArray", "[Z" to "kotlin/BooleanArray", "[B" to "kotlin/ByteArray",
            "[S" to "kotlin/ShortArray", "[C" to "kotlin/CharArray",
        )

        /** The first parameter of [desc] as an ASM-internal type name, or null when there isn't one. */
        fun firstParameter(desc: String): String? {
            val params = desc.substringAfter('(', "").substringBefore(')')
            if (params.isEmpty()) return null
            return when {
                params[0] == 'L' -> params.substring(1, params.indexOf(';').takeIf { it > 0 } ?: return null)
                params.startsWith("[L") -> null                     // Array<T>: not worth keying
                params.length >= 2 && params.take(2) in PRIMITIVE_ARRAYS -> PRIMITIVE_ARRAYS[params.take(2)]
                else -> PRIMITIVES[params[0]]
            }
        }

        /**
         * The Kotlin-visible names a JVM method name stands for. An extension PROPERTY compiles to
         * `getPos(Img)`/`setPos(Img, v)`, so indexing the JVM name alone left every extension property
         * unreachable — the accessor name is not what anyone types. Both are offered; resolution decides.
         */
        fun kotlinNames(jvm: String): List<String> {
            if (jvm.length <= 3 || !(jvm.startsWith("get") || jvm.startsWith("set")) || !jvm[3].isUpperCase())
                return listOf(jvm)
            val rest = jvm.substring(3)
            // BOTH un-manglings, because the JVM name does not say which one it came from: `getPos` is
            // `pos`, but `getCYCLIC_LABYRINTH` is `CYCLIC_LABYRINTH` — a SHOUTING_CASE property (every
            // rule constant, every palette) is not decapitalised, and guessing `cYCLIC_LABYRINTH` left it
            // as unreachable as indexing the raw accessor name did. Resolution decides which spelling
            // exists; the cost of the other is one lookup that finds nothing.
            return listOf(jvm, rest, rest[0].lowercaseChar() + rest.substring(1)).distinct()
        }
    }

    override fun visitMethod(access: Int, name: String, desc: String?, sig: String?, exc: Array<String>?): MethodVisitor? {
        val stat = access and Opcodes.ACC_STATIC != 0
        // NOT filtered on ACC_PUBLIC. A `@kotlin.internal.InlineOnly` function is `public inline` in Kotlin
        // and PRIVATE in bytecode — it has no callable entry point, only an inlined body — so requiring
        // public here silently excluded `let`, `run`, `also`, `apply`, `takeIf` and `String.format`: the
        // most-typed extensions in the language. (`"%.3f".format(…)` was in the caret sweep's miss list
        // with a 200-candidate panel that couldn't contain it.) A genuinely `private` Kotlin declaration
        // gets indexed too and then resolves as private, where the use-site visibility check drops it —
        // one wasted lookup, versus a whole family of names being unreachable.
        if (stat && name != "<clinit>" && !name.contains('$')) {
            // [kotlinNames], not the raw JVM name. A top-level PROPERTY compiles to `getCYCLIC_LABYRINTH()`,
            // and `getCYCLIC_LABYRINTH` is not what anyone types — so indexing the JVM name alone made
            // every top-level `val`/`var` in a binary package unreachable at a name position: the index
            // had no name starting with `CY`, and `findTopLevelCallables` (which needs an EXACT Kotlin
            // name) was never asked. `CYCLIC_LABYRINTH`, `GRAY_SCOTT_MAZE` and the `val Box.once` /
            // `val Box.strategy` family were 33 of the caret sweep's misses. The receiver index below has
            // always done this; the by-package index simply hadn't.
            kotlinNames(name).forEach(onCallable)
            val receiver = desc?.let(::firstParameter)
            if (receiver != null) kotlinNames(name).forEach { onReceiver(receiver, it) }
        }
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
 * The identifier characters already typed at [cursor] — the completion prefix.
 *
 * Shared rather than spelled out twice: the lowering decides which PATH a request takes from this, and
 * the transport decides which candidates survive a cap from it (see `K2CompletionAnalyzer.complete`).
 * Two copies of the rule would be two definitions of "what the user has typed".
 */
internal fun prefixAt(snippet: String, cursor: Int): String =
    Regex("[A-Za-z0-9_]*$").find(snippet.substring(0, cursor.coerceIn(0, snippet.length)))?.value ?: ""

/** A line that is nothing but an `import` — `import a.b.C`, `import a.b.*`, `import a.b.C as D`. */
private val IMPORT_LINE = Regex("""\s*import\s+\w+(?:\.\w+)*(?:\.\*)?(?:\s+as\s+\w+)?\s*""")

/**
 * The dummy name spliced in at an otherwise-empty expression slot, so there is a `KtExpression` at the
 * caret whose *expected* type the Analysis API can report. (IntelliJ does the same with
 * `IntellijIdeaRulezzz`.) Starts with `__`, so [AaCandidate.isSynthetic] guarantees it can never itself
 * be offered as a completion.
 */
private const val CARET_MARKER = "__fieldCaret__"

/** `return` as a whole trailing token — `return ‸` is an expression slot, `myreturn‸` is not. */
private val RETURN_TAIL = Regex("""(?:^|[^A-Za-z0-9_.])return$""")

/**
 * How many receiver-keyed extension names one request will resolve. Generous — the point of keying by
 * receiver is that the list is already bounded by the type — but not unbounded: `Any`'s extensions are
 * every `also`/`let`/`takeIf` in scope plus anything a box declares on `Any?`, and a pathological receiver
 * should slow a keystroke down, not stall it. Truncation is reported in the result note.
 *
 * SIZED AGAINST FIELD'S REAL CLASSPATH, not the stdlib. At 800 this cut the commonest receivers in the
 * language: measured over Field's 445-entry classpath, `String.` yields 973 receiver-keyed hits, `Vec3.`
 * 983 (its generated swizzles), `List<Int>.` 818 — so a String lost `zip`/`zipWithNext`/`windowed`/
 * `withIndex` (275 distinct names truncated to 142) and a Vec3 lost 37 swizzles. The tell was maddening:
 * analysing the same snippet against the stdlib jar ALONE stayed under the cap and answered correctly, so
 * ADDING classpath roots appeared to REMOVE stdlib extensions. Worse, [AaSymbolIndex.extensionsByReceiver]
 * holds each receiver's names in hash order, so which names died was arbitrary and differed per index —
 * `uppercase` survived while `zip` did not, for no reason a reader could see.
 *
 * 4000 leaves ~4x headroom over the largest receiver measured while still bounding a pathological one.
 * It is not free, but it is cheap: warm, `String.` measured 201-232ms returning all 434 rows against 124ms
 * returning the truncated 253, and `Vec3.` was no slower (96-169ms either way). Cold, the first request to
 * a fresh session costs ~2.6s whatever the receiver — that is session warmup, not this cap (measured by
 * asking for the SMALLEST receiver first: `Any.`, 25 rows, also took 2.6s).
 *
 * Truncation, when it does bite, drops the most GENERIC keys first: [internalNamesOf] returns the
 * receiver's own classifier before its supertypes and `Any`/`Object` last, and this cap keeps a prefix of
 * that order.
 */
private const val RECEIVER_INDEX_CAP = 4000

/**
 * How many of a PACKAGE's top-level names one request will resolve — `kotlin.math.‸`.
 *
 * Deliberately NOT [RECEIVER_INDEX_CAP], though it used to be. The two caps guard opposite things: the
 * receiver cap bounds a list a TYPE has already narrowed, so it should be as high as the classpath needs,
 * while this one bounds an UNNARROWED walk of a package. `afield.stdlib` alone has 3947 top-level names,
 * so raising the shared constant to 4000 would have brought it under the cap and started enumerating it at
 * a bare dot. Measured, with this cap temporarily at 4000: `afield.stdlib.` answers, but with 1200 rows
 * (the transport limit) in 196ms warm / 933ms cold — the cost is real but modest, and the LATENCY is not
 * the reason to refuse. The reason is the answer: 3947 names cut to 1200 by the transport and then to the
 * client's 80 visible rows, all in alphabetical order, is the "truncate the alphabet" non-answer this file
 * fights everywhere else. Saying "type a character" is the better answer, and one character brings any
 * package back under the cap.
 */
private const val PACKAGE_ENUMERATION_CAP = 800

/**
 * Named-argument completion needs declaration names, not a call-site-substituted signature. Keeping
 * this separate from [KaFunctionCall.valueArgumentMapping] makes that distinction explicit and avoids
 * the deprecated `partiallyAppliedSymbol` container.
 */
private fun <S : KaFunctionSymbol> KaFunctionCall<S>.declaredValueParameters() =
    symbol.valueParameters

/**
 * K2 Analysis-API completion candidate generation. Reusable across the spike harness (Stage A/B) and
 * the sidecar HTTP endpoint (Stage C). Construct once per session with a [contextFile] from that
 * session (used to contextualize in-memory dangling snippet files) and a prebuilt [index].
 */
class AaCompleter(
    private val contextFile: KtFile,
    private val index: AaSymbolIndex,
    private val defaultImports: List<String>,
    /** The session's binary roots, so a package the box imports but [index] does not cover (anything
     *  outside [defaultImports] — `sentences`, a user's own jar) can be scanned on demand. Empty
     *  disables that, leaving only the prebuilt index. */
    private val binaryRoots: List<Path> = emptyList(),
) {
    /** One-line index size summary, for sidecar startup logging. */
    val indexSummary: String
        get() = "${index.callableCount} callables, ${index.classifierCount} classifiers, ${index.typeAliasCount} type-aliases"

    /** On-demand package scans, memoised — including empty ones, so an unknown package is probed once. */
    private val scanned = ConcurrentHashMap<String, AaSymbolIndex>()

    /** [index], plus the packages that were added to it for this request (see [indexFor]). */
    private data class IndexScope(val index: AaSymbolIndex, val extraPackages: Set<String>)

    /**
     * The prebuilt index plus any imported package it doesn't already cover. The prebuilt index is
     * keyed to the REPL default imports, so a box that writes its own `import sentences.*` had NO
     * enumerable names for that package: standalone AA can't enumerate a binary package by prefix
     * (the reason the index exists), so `sentences.flines` — a top-level extension on the box's own
     * type — was unreachable at every prefix, whether or not the receiver resolved.
     */
    private fun starPackagesOf(imports: List<String>): List<String> =
        imports.map { it.removePrefix("import ").trim().substringBefore(" as ").trim() }
            .filter { it.endsWith(".*") }.map { it.removeSuffix(".*") }
            .filter { it.isNotEmpty() }.distinct()

    /**
     * @param boxImports      imports this box wrote (or inherited) for itself — few, and the place its own
     *                        extensions live, so they are worth enumerating even at an empty prefix.
     * @param providedImports the scope the box got for free (Kotlin's auto-imports plus the caller's REPL
     *                        imports). Indexed the same way, but deliberately NOT enumerable at an empty
     *                        prefix: `afield.stdlib` alone would put thousands of symbol resolutions on
     *                        every bare `.` keystroke, which is what the empty-prefix gate exists to avoid.
     */
    private fun indexFor(boxImports: List<String>, providedImports: List<String>): IndexScope {
        if (binaryRoots.isEmpty()) return IndexScope(index, emptySet())
        val fromBox = starPackagesOf(boxImports)
        val wanted = (fromBox + starPackagesOf(providedImports)).distinct()
            .filter { it !in index.callables && it !in index.classifiers }
        if (wanted.isEmpty()) return IndexScope(index, emptySet())
        val missing = wanted.filter { !scanned.containsKey(it) }
        if (missing.isNotEmpty()) {
            val fresh = AaSymbolIndex.scanPackages(binaryRoots, missing.toSet())
            for (pkg in missing) {
                scanned[pkg] = AaSymbolIndex(
                    fresh.callables.filterKeys { it == pkg },
                    fresh.classifiers.filterKeys { it == pkg },
                    fresh.typeAliases.filterKeys { it == pkg },
                )
            }
        }
        return IndexScope(
            wanted.fold(index) { acc, pkg -> acc + (scanned[pkg] ?: AaSymbolIndex.EMPTY) },
            wanted.filter { it in fromBox }.toSet(),
        )
    }

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
        replImports: List<String> = emptyList(),
        jsTarget: Boolean = false,
    ): AaCompletionResult {
        val lowered = try {
            lower(snippet, cursor, preamble, declarations, implicitReceivers, extraImports, replImports)
        } catch (t: Throwable) {
            val why = "lower error ${t.javaClass.simpleName}: ${t.message}"
            return AaCompletionResult(emptyList(), why, error = why)
        }
        return try {
            val file = KtPsiFactory.contextual(contextFile, markGenerated = false, eventSystemEnabled = false)
                .createFile("AaCompletion.kt", lowered.text)
            val scope = indexFor(lowered.boxImports, lowered.providedImports)
            completeAt(file, lowered.cursorOffset, lowered.isDot, lowered.prefix, jsTarget, scope.index, scope.extraPackages)
        } catch (t: Throwable) {
            val why = "error ${t.javaClass.simpleName}: ${t.message}"
            AaCompletionResult(emptyList(), why, error = why)
        }
    }

    /**
     * Type-check the lowering itself — the invariant behind every "completion answered with junk" report.
     *
     * Completion can only be as good as the file it analyses, and until this existed nothing ever asked
     * whether that file was *sound*: a preamble form the lowering mangled produced a plausible-looking
     * wrong answer (the `tri` → `TriOsc` case) with no signal anywhere. Now the same artifact completion
     * uses is handed to the compiler's own checkers, and every error is attributed to a region — an error
     * in `preamble` is the lowering's fault and gate-able; an error in `snippet` is the box needing runtime
     * context a headless session cannot have, which is a *prediction* of where completion will be weak
     * rather than a defect in itself.
     *
     * Same signature as [complete] because it must be the same request. Put the caret at the end of the
     * snippet (no caret marker is spliced there) to check the box exactly as written.
     */
    @OptIn(KaExperimentalApi::class)
    fun diagnose(
        snippet: String,
        cursor: Int,
        preamble: String = "",
        declarations: String = "",
        implicitReceivers: List<String> = emptyList(),
        extraImports: List<String> = emptyList(),
        replImports: List<String> = emptyList(),
    ): AaLoweringResult {
        val lowered = try {
            lower(snippet, cursor, preamble, declarations, implicitReceivers, extraImports, replImports)
        } catch (t: Throwable) {
            return AaLoweringResult("", false, preamble.isBlank(), 0, emptyList(),
                "lower error ${t.javaClass.simpleName}: ${t.message}")
        }
        return try {
            val file = KtPsiFactory.contextual(contextFile, markGenerated = false, eventSystemEnabled = false)
                .createFile("AaLowering.kt", lowered.text)
            val errors = analyze(file) {
                file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                    .filter { it.severity == KaSeverity.ERROR }
                    .map { d ->
                        val offset = runCatching { d.psi.textRange.startOffset }.getOrDefault(0)
                        AaLoweringError(
                            region = lowered.regionAt(offset),
                            factory = runCatching { d.factoryName }.getOrDefault("?"),
                            message = runCatching { d.defaultMessage }.getOrDefault("?").take(200),
                            line = lowered.text.take(offset).count { it == '\n' } + 1,
                            excerpt = runCatching { d.psi.text }.getOrDefault("").take(80).replace('\n', ' '),
                        )
                    }
            }
            AaLoweringResult(lowered.text, lowered.preambleInjected, preamble.isBlank(), lowered.stubs.size, errors)
        } catch (t: Throwable) {
            AaLoweringResult(lowered.text, lowered.preambleInjected, preamble.isBlank(), lowered.stubs.size,
                emptyList(), "diagnose error ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Build the synthetic file for a request. See [AaLoweredFile]; the KDoc on [complete] documents the params. */
    internal fun lower(
        snippet: String,
        cursor: Int,
        preamble: String = "",
        declarations: String = "",
        implicitReceivers: List<String> = emptyList(),
        extraImports: List<String> = emptyList(),
        replImports: List<String> = emptyList(),
    ): AaLoweredFile {
        // A box's own `import` lines arrive inside [snippet] — but the snippet is pasted into the
        // synthetic `fun __c() { … }` below, where an import is a syntax error. So `import sentences.*`
        // on line 1 left `SentenceDiagram` unresolved and every member of the box's own type missing.
        // Hoist them to the file header instead (blanked in place, same length, so [cursor] still
        // means what the caller said). The line the caret sits on is left alone: completing an import
        // path is a name position like any other.
        val (body, snippetImports) = hoistImports(snippet, cursor)
        val before = body.substring(0, cursor)
        val after = body.substring(cursor)
        val prefix = prefixAt(before, before.length)
        // What KIND of position the caret is in is decided on CODE, not on prose. Read off the raw text,
        // the last non-space character before the caret is whatever the previous line happened to end
        // with — and a comment that ends in a full stop is how people write comments:
        //
        //     // …so `n` counts generations rather than pixels.
        //     animateEvolution(‸                       <- read as a DOT position, of `pixels.`
        //
        // which took the dot path, found no `KtQualifiedExpression` (there is none), and returned "no
        // qualified expr" with zero candidates — 19 of the caret sweep's misses, every one of them an
        // ordinary call at the start of a statement. Worse than empty when the caret happens to sit
        // inside an outer qualified expression: then it answers with the members of THAT receiver.
        // Blanked in place, so every offset still means what the caller said.
        val beforeCode = blankCommentsAndStrings(before)
        // Covers both `recv.` and safe `recv?.` — both end in '.' after dropping the prefix.
        val isDot = beforeCode.dropLast(prefix.length).trimEnd().endsWith(".")
        // At an untyped expression slot (`d.scheme = ‸`, `f(‸`) there is no expression at the caret to
        // ask the expected type of — and the parser doesn't even leave a hole: `d.scheme =` followed by
        // `display(…)` on the next line parses as `d.scheme = display(…)`. So splice a dummy name in,
        // the way IntelliJ does. See [wantsCaretMarker] for why this is limited to those slots.
        val spliceCaret = !isDot && prefix.isEmpty() && wantsCaretMarker(beforeCode)
        // …and the same trick for an EMPTY SELECTOR SLOT. `recv.‸` at the end of a line parses to no
        // `KtQualifiedExpression` at all, so the dot path had nothing to take a receiver from and bailed
        // with "no qualified expr" — 12 of the caret sweep's misses, and every one of them the most
        // ordinary thing a user does. A marker in the slot makes it `recv.__fieldCaret__`, which parses,
        // and as a bonus gives the IDE's extension-applicability checker the selector it needs (it could
        // never be built at a bare dot before). Only when the slot really is empty: if the next character
        // could continue an identifier there IS a selector, and appending to it would rename it.
        val emptySelector = after.firstOrNull()?.let { !it.isLetterOrDigit() && it != '_' } ?: true
        val spliceDotCaret = isDot && prefix.isEmpty() && emptySelector

        // A dangling file's own TOP-LEVEL declarations do NOT enter the completion scope (only imports,
        // implicit receivers, and LOCAL declarations do). So the inherited preamble is split: import
        // lines stay at file top level, and every declaration is re-emitted as a *local* stub inside the
        // completion function (name + type preserved, extension receiver / `by` delegate stripped so it's
        // local-legal). This surfaces `prop<>` and `@exported` inherited names — the load-bearing invariant.
        val (preambleImports, localStubs) = splitPreamble(preamble)
        val (declarationImports, declarationStubs) = splitPreamble(declarations)
        // …but a *stub* cannot stand in for everything a preamble declares. It keeps a name and a type,
        // which is all a `prop<>`/`@exported` value needs, and it drops classes entirely and reduces a
        // function to its signature-by-regex. Two preamble forms need more than that:
        //   - a CLASS (`/*supermap*/ class MappedStructure { lateinit var tri: IntBuffer … }`), which no
        //     local stub is even emitted for;
        //   - an extension function whose parameter is a LAMBDA WITH A RECEIVER
        //     (`fun Mesh.set(…, builder: MappedStructure.() -> Unit)`), where losing either the receiver
        //     or the class means the lambda body has no implicit receiver.
        // Without them `m.set(3, 1) { tri.‸ }` had no `MappedStructure` in scope, so `tri` resolved to the
        // next thing in scope by that name — `afield.four.synth.tri`, a triangle oscillator, via `intro`'s
        // `import afield.four.synth.*` — and the panel filled with `TriOsc`'s members. So the preamble is
        // ALSO emitted verbatim at file scope, where it is legal (it came from one) and where a class and a
        // receiver-typed lambda parameter both survive. The local stubs stay: they shadow harmlessly (a
        // local `val window: Window` over a top-level `var Box.window`, same name, same type) and they are
        // what makes bare-name discovery of inherited values reliable.
        val fileScopePreamble = preambleDeclarations(preamble).takeIf { it.isNotBlank() && parses(it) }.orEmpty()

        // What the box wrote or inherited, versus what it was handed. Kept apart because only the former
        // is small enough to enumerate at an empty prefix — see [indexFor].
        val boxImports = extraImports + snippetImports + preambleImports + declarationImports
        val providedImports = defaultImports + replImports
        val allImports = providedImports + boxImports
        val sb = StringBuilder()
        allImports.map { it.removePrefix("import ").trim() }
            .filter { it.isNotEmpty() }.distinct().forEach { sb.append("import ").append(it).append('\n') }
        sb.append('\n')
        val preambleStart = sb.length
        if (fileScopePreamble.isNotBlank()) sb.append(fileScopePreamble).append("\n\n")
        val preambleRange = if (fileScopePreamble.isBlank()) IntRange.EMPTY else preambleStart until sb.length
        val declarationsStart = sb.length
        declarations.trimIndent().lineSequence()
            .filterNot { it.trimStart().startsWith("import ") || it.trimStart().startsWith("package ") }
            .forEach { sb.append(it).append('\n') }
        if (declarations.isNotBlank()) sb.append('\n')
        val declarationsRange = if (declarations.isBlank()) IntRange.EMPTY else declarationsStart until sb.length

        val recvs = implicitReceivers.filter { it.isNotBlank() }
        val params = recvs.mapIndexed { i, t -> "__r$i: $t" }.joinToString(", ")
        sb.append("fun __c(").append(params).append(") {\n")
        val stubs = localStubs + declarationStubs
        val stubsStart = sb.length
        stubs.forEach { sb.append("    ").append(it).append('\n') }
        val stubsRange = if (stubs.isEmpty()) IntRange.EMPTY else stubsStart until sb.length
        // `with<T, Unit>` — both type arguments explicit. `with(__r0) { … }` leaves `R` to be inferred
        // from the block, so a box whose last expression doesn't type (which is the normal state of code
        // being edited, and was the state of all 5 visionOS/remoteAR boxes the lowering invariant flagged)
        // fails to infer `with` itself, and an un-inferred wrapper puts the receiver scope at risk for the
        // whole box. Pinning `R = Unit` costs nothing — a block's value is coerced to Unit — and takes the
        // wrapper out of inference entirely.
        recvs.forEachIndexed { i, t -> sb.append("with<").append(t).append(", Unit>(__r").append(i).append(") { ") }
        val snippetStart = sb.length
        sb.append(before)
        if (spliceCaret || spliceDotCaret) sb.append(CARET_MARKER)
        val cursorOffset = sb.length
        sb.append(after)
        val snippetRange = snippetStart until sb.length
        recvs.forEach { _ -> sb.append(" }") }
        sb.append("\n}\n")

        return AaLoweredFile(
            text = sb.toString(),
            cursorOffset = cursorOffset,
            preambleRange = preambleRange,
            declarationsRange = declarationsRange,
            stubsRange = stubsRange,
            snippetRange = snippetRange,
            preambleInjected = fileScopePreamble.isNotBlank(),
            stubs = stubs,
            boxImports = boxImports,
            providedImports = providedImports,
            isDot = isDot,
            prefix = prefix,
        )
    }

    /** A preamble's declaration text — everything but its `import`/`package` lines, which are hoisted
     *  into the synthetic file's header separately (and are illegal after a declaration anyway). */
    internal fun preambleDeclarations(preamble: String): String =
        preamble.trimIndent().lineSequence()
            .filterNot { it.trimStart().startsWith("import ") || it.trimStart().startsWith("package ") }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    /**
     * Does [text] stand on its own as a Kotlin file? The gate on injecting a preamble at file scope.
     *
     * A preamble is declarations by contract, but it is assembled by string concatenation from macro
     * expansions (`writeSuperMap`'s `;`-joined one-liner among them) and inherited up a box chain, so it
     * is not guaranteed to be *parseable* declarations. Splicing an unparseable one into the synthetic
     * file would break the whole request rather than the one name it describes, and the stub path already
     * handles the value-shaped cases — so an unparseable preamble degrades to stubs-only, as before.
     */
    internal fun parses(text: String): Boolean = runCatching {
        val probe = KtPsiFactory.contextual(contextFile, markGenerated = false, eventSystemEnabled = false)
            .createFile("AaPreambleProbe.kt", text)
        !PsiTreeUtil.hasErrorElements(probe)
    }.getOrDefault(false)

    /**
     * True when the caret sits where a *value* is expected and there is nothing there yet, so splicing
     * [CARET_MARKER] in buys us an expression to ask [expectedTypesAt] about.
     *
     * Deliberately narrow. The marker perturbs the synthetic file for every request it fires on, and
     * outside these slots it buys nothing while risking the PSI recovery the other paths already rely
     * on (`f(a,‸)` recovers to a value-argument list today; `foo()‸` would become the parse error
     * `foo()__caret__`). So: only after a token that cannot be followed by anything but an expression.
     * A typed prefix needs no marker at all — `d.scheme = SD‸` already *has* a name expression in the
     * slot, and its expected type resolves from that.
     */
    /**
     * [code] with every comment and string/char literal replaced by spaces — same length, so every offset
     * still means what it meant. Used to decide the KIND of position the caret is in: prose is not code,
     * and a comment ending in a full stop had been turning the next line into a dot position (see the
     * comment at [lower]'s `isDot`). An unterminated literal — `"abc‸`, the normal state while typing — is
     * blanked to end of line, which is how the lexer would recover.
     *
     * Deliberately a lexer-lite rather than a PSI walk: this runs before the file is built, on every
     * keystroke, and the only question it answers is "what is the last real code character before here".
     */
    internal fun blankCommentsAndStrings(code: String): String {
        val out = code.toCharArray()
        fun blank(from: Int, to: Int) {
            for (k in from until minOf(to, out.size)) if (out[k] != '\n') out[k] = ' '
        }
        // A string TEMPLATE is real code inside a literal: `"x(1.0) = ${fit.fitted.sample(1.0)}"`. Blanking
        // it would hide the receiver from a caret inside the template, so blank the literal and then put
        // every `${…}` span back (brace-counted, so a nested lambda or a `${if (a) "" else ""}` survives).
        fun blankLiteral(from: Int, to: Int) {
            blank(from, to)
            var k = from
            while (k < to - 1) {
                if (code[k] == '$' && code[k + 1] == '{' && (k == from || code[k - 1] != '\\')) {
                    var depth = 0
                    var j = k + 1
                    while (j < to) {
                        if (code[j] == '{') depth++ else if (code[j] == '}') { depth--; if (depth == 0) break }
                        j++
                    }
                    val end = minOf(j + 1, to)
                    for (m in k until end) out[m] = code[m]
                    k = end
                } else k++
            }
        }
        var i = 0
        while (i < code.length) {
            when {
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    blank(i, end); i = end
                }
                code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                    blank(i, end); i = end
                }
                code.startsWith("\"\"\"", i) -> {
                    val end = code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it + 3 }
                    blankLiteral(i, end); i = end
                }
                code[i] == '"' || code[i] == '\'' -> {
                    val q = code[i]
                    var j = i + 1
                    while (j < code.length && code[j] != q && code[j] != '\n') { if (code[j] == '\\') j++; j++ }
                    blankLiteral(i, minOf(j + 1, code.length)); i = minOf(j + 1, code.length)
                }
                else -> i++
            }
        }
        return String(out)
    }

    internal fun wantsCaretMarker(before: String): Boolean {
        val head = before.trimEnd()
        if (head.isEmpty()) return false
        // `=` covers assignment, a named argument's value, and `==`/`>=`/`+=`; `(`/`,` an argument;
        // `[` an index. `<` is NOT here: it is ambiguous with a type-argument list.
        if (head.last() in "=(,[") return true
        if (head.endsWith("->") || head.endsWith("?:") || head.endsWith("&&") || head.endsWith("||")) return true
        // `return ‸` — a keyword, so match it as a whole token.
        return RETURN_TAIL.containsMatchIn(head)
    }

    /**
     * Blank every whole-line `import` in [snippet] (leaving the one holding the caret) and return the
     * blanked text — character-for-character the same length, so caret offsets survive — plus the
     * import statements found. Only a line that is nothing but an import is touched, so an `import`
     * inside a string literal or a comment is left where it is.
     */
    internal fun hoistImports(snippet: String, cursor: Int): Pair<String, List<String>> {
        if (!snippet.contains("import ")) return snippet to emptyList()
        val out = StringBuilder(snippet)
        val imports = mutableListOf<String>()
        var lineStart = 0
        for (line in snippet.split('\n')) {
            val text = line.trimEnd('\r')
            val lineEnd = lineStart + text.length
            if (IMPORT_LINE.matches(text) && cursor !in lineStart..lineEnd) {
                imports += text.trim()
                for (i in lineStart until lineEnd) out.setCharAt(i, ' ')
            }
            lineStart += line.length + 1
        }
        return out.toString() to imports
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

    @OptIn(KaExperimentalApi::class, KaIdeApi::class)
    private fun completeAt(
        file: KtFile, offset: Int, isDot: Boolean, prefix: String, jsTarget: Boolean,
        /** [AaCompleter.index] widened with any package this request's imports added. */
        index: AaSymbolIndex,
        /** Just those added packages — small, and enumerable even at an empty prefix. */
        extraPackages: Set<String>,
    ): AaCompletionResult {
        val leaf = file.findElementAt((offset - 1).coerceAtLeast(0))
            ?: return AaCompletionResult(emptyList(), "no element at cursor")
        val match: (Name) -> Boolean = { n -> prefix.isEmpty() || n.asString().startsWith(prefix) }
        // In a kotlinjs box (compiled to JS, no JVM) drop JVM-only stdlib members; a no-op otherwise.
        val jsFilter: (AaCandidate) -> Boolean = { c -> !jsTarget || !isJvmOnlyForJsTarget(c) }

        return analyze(file) {
            /**
             * Can the box actually WRITE this symbol? A type's member scope is the declaration's, not the
             * use site's: it hands back `private`, Java package-private and other-module `internal`
             * members alike. Nothing filtered them, so `tri.` on a `TriOsc` offered `_cache`, `phase`,
             * `integrator` and `freqBuf` — four of the top rows in the panel were uncompilable — and
             * `IntBuffer` offered `java.nio.Buffer`'s package-private `address`/`base()`/`checkIndex()`.
             *
             * The use-site checker is the IDE's own answer and handles every case a hand-rolled visibility
             * rule gets wrong (a private member IS visible from inside its own class; `protected` from a
             * subclass; `internal` within a module). PUBLIC short-circuits it, which is the overwhelming
             * majority of candidates. LOCAL is the preamble's own stubs — always visible, and not
             * something the checker is asked about. If the checker can't be built at all, only the
             * unambiguously-inaccessible visibilities are dropped, so a checker-less session loses no
             * candidate it would have kept.
             */
            val visibilityChecker = runCatching { createUseSiteVisibilityChecker(file.symbol, null, leaf) }.getOrNull()
            fun accessible(sym: KaSymbol): Boolean {
                val decl = sym as? KaDeclarationSymbol ?: return true
                return when (runCatching { decl.visibility }.getOrNull()) {
                    null, KaSymbolVisibility.PUBLIC, KaSymbolVisibility.LOCAL, KaSymbolVisibility.UNKNOWN -> true
                    KaSymbolVisibility.INTERNAL ->
                        visibilityChecker?.let { c -> runCatching { c.isVisible(decl) }.getOrDefault(true) } ?: true
                    else -> visibilityChecker?.let { c -> runCatching { c.isVisible(decl) }.getOrDefault(false) } ?: false
                }
            }

            /**
             * The receiver's own classifier plus every supertype, as ASM-internal names — the keys to look
             * up in [AaSymbolIndex.extensionsByReceiver]. Supertypes are included because
             * `fun Iterable<T>.map(…)` has to reach a `List`, and `fun Any?.also(…)` has to reach anything.
             * `ClassId.asString()` already writes the package with `/`; the remaining `.` separate nested
             * classes, which ASM writes as `$`.
             */
            fun internalNamesOf(t: KaType): List<String> {
                val out = LinkedHashSet<String>()
                fun visit(ty: KaType, depth: Int) {
                    if (depth > 8 || out.size > 64) return
                    val classType = ty as? KaClassType ?: return
                    val id = runCatching { classType.classId }.getOrNull() ?: return
                    // Kotlin's built-ins have a different name in bytecode than in the type system:
                    // `fun Iterable<T>.map(…)` compiles to `map(Ljava/lang/Iterable;…)`, `String.format`
                    // to `format(Ljava/lang/String;…)`. Walking supertypes yields the KOTLIN names, so
                    // without the mapping a `List` receiver found none of `kotlin.collections` and a
                    // `String` receiver none of `kotlin.text` — the two biggest extension families there
                    // are. `JavaToKotlinClassMap` is the compiler's own table; a hand-written one would be
                    // wrong the first time someone hit `CharSequence` or `MutableList`.
                    runCatching {
                        JavaToKotlinClassMap.mapKotlinToJava(id.asSingleFqName().toUnsafe())
                            ?.let { out.add(it.asString().replace('.', '$')) }
                    }
                    if (!out.add(id.asString().replace('.', '$'))) return
                    val sym = runCatching { classType.symbol as? KaClassSymbol }.getOrNull() ?: return
                    runCatching { sym.superTypes }.getOrDefault(emptyList()).forEach { visit(it, depth + 1) }
                }
                visit(t, 0)
                out.add("kotlin/Any")   // `also`/`let`/`takeIf` and friends hang off Any?, which no
                out.add("java/lang/Object")  // supertype walk of a Java type ever mentions as `kotlin/Any`.
                return out.toList()
            }

            fun iconFor(sym: KaCallableSymbol): String = when (sym) {
                is KaNamedFunctionSymbol -> "method"
                is KaPropertySymbol -> if (sym.isVal) "val" else "var"
                else -> "property"
            }
            fun paramsOf(sym: KaCallableSymbol): String? = (sym as? KaNamedFunctionSymbol)?.valueParameters
                ?.joinToString(", ", "(", ")") { p: KaValueParameterSymbol ->
                    "${p.name.asString()}: ${shortenType(runCatching { p.returnType.toString() }.getOrDefault("?"))}"
                }

            /**
             * The type a value at the caret has to have: the property's type at `d.scheme = ‸`, the
             * parameter's type at `f(word = ‸)` / `f(‸`, the function's return type at `return ‸`, and
             * every other slot `KaExpressionTypeProvider.expectedType` knows about (if/while condition,
             * elvis operand, `val x: T = ‸`, …). Empty when there is no expected type, or when it is too
             * weak to be worth acting on ([isUsefulExpectation]).
             *
             * Two sources, in order:
             *  1. `expectedType` on the expression at the caret — the real answer, and the only one that
             *     is substituted (`f<Scheme>(‸)`) and slot-shape aware.
             *  2. For an argument slot only: the current expression's parameter signature from each
             *     `resolveToCallCandidates` candidate's `valueArgumentMapping`. This is the fallback for
             *     a call that does not fully resolve — the normal state of code being typed — and the
             *     mapping retains call-site type substitution without the deprecated partially-applied
             *     call container.
             */
            fun expectedTypesAt(): List<KaType> {
                val ref = PsiTreeUtil.getParentOfType(leaf, KtSimpleNameExpression::class.java)
                val direct = ref?.let { runCatching { it.expectedType }.getOrNull() }
                if (direct != null) return listOf(direct)

                val arg = PsiTreeUtil.getParentOfType(leaf, KtValueArgument::class.java) ?: return emptyList()
                val list = arg.parent as? KtValueArgumentList ?: return emptyList()
                val call = PsiTreeUtil.getParentOfType(list, KtCallExpression::class.java) ?: return emptyList()
                val expression = arg.getArgumentExpression() ?: return emptyList()
                return runCatching { call.resolveToCallCandidates() }.getOrDefault(emptyList())
                    .mapNotNull { it.candidate as? KaFunctionCall<*> }
                    .mapNotNull { c ->
                        val p = c.valueArgumentMapping[expression]
                        runCatching { p?.returnType }.getOrNull()
                    }
                    .distinctBy { runCatching { it.toString() }.getOrDefault("?") }
            }

            /**
             * Is this expectation specific enough to rank by? `Any`/`Any?` is satisfied by literally
             * every candidate, so boosting on it says nothing while the untyped-caret collapse it feeds
             * would hide nothing — pure cost. An error type is not a type (the same trap the dot path
             * documents: `errorType` makes `isSubtypeOf` vacuously true, so EVERYTHING would "fit").
             * `Unit`/`Nothing` are not values anyone completes.
             */
            fun isUsefulExpectation(t: KaType): Boolean {
                if (t is KaErrorType) return false
                val fq = ((t as? KaClassType)?.classId)?.asFqNameString() ?: return false
                return fq !in setOf("kotlin.Any", "kotlin.Unit", "kotlin.Nothing")
            }

            val expectedTypes: List<KaType> =
                if (isDot) emptyList()
                else runCatching { expectedTypesAt().filter(::isUsefulExpectation) }.getOrDefault(emptyList())

            /** Would a value of type [t] be accepted at the caret? Drives [AaCandidate.fitsExpected]. */
            fun fits(t: KaType?): Boolean {
                if (expectedTypes.isEmpty() || t == null || t is KaErrorType) return false
                return expectedTypes.any { e -> runCatching { t.isSubtypeOf(e) }.getOrDefault(false) }
            }

            /**
             * Where [sym] is declared, as an fq name: the containing class for a member, the package for a
             * top-level callable, `""` for anything with no `CallableId` (a local, or a name a box's
             * preamble declared into the synthetic file). See [AaCandidate.declaredIn].
             */
            fun declaredIn(sym: KaCallableSymbol): String = runCatching {
                val id = sym.callableId ?: return ""
                id.classId?.asFqNameString() ?: id.packageName.asString()
            }.getOrDefault("")

            /**
             * [AaCandidate.Proximity] of [sym] relative to the receiver whose classifier is [receiverIds]
             * (its own class id first, then its supertypes — the list [internalNamesOf] walks).
             *
             * For an EXTENSION the question is its receiver parameter: an unconstrained `T` (as in
             * `fun <T> T.onBeat(…)`) or an `Any`/`Any?` receiver applies to everything and is therefore
             * UNIVERSAL. For a MEMBER it is the declaring class: `Any`'s members (`equals`, `hashCode`,
             * `toString`) are universal by the same rule, which is why no name list is needed.
             */
            fun proximityOf(sym: KaCallableSymbol, ownId: String?, superIds: Set<String>): AaCandidate.Proximity {
                val declaring: String? = runCatching {
                    val receiverType = sym.receiverParameter?.returnType
                    when {
                        receiverType != null && receiverType is KaTypeParameterType -> return AaCandidate.Proximity.UNIVERSAL
                        receiverType != null -> (receiverType as? KaClassType)?.classId?.asFqNameString()
                        else -> sym.callableId?.classId?.asFqNameString()
                    }
                }.getOrNull()
                return when {
                    declaring == null -> AaCandidate.Proximity.OWN   // local/preamble: about this box
                    declaring == "kotlin.Any" || declaring == "java.lang.Object" -> AaCandidate.Proximity.UNIVERSAL
                    declaring == ownId -> AaCandidate.Proximity.OWN
                    declaring in superIds -> AaCandidate.Proximity.SUPERTYPE
                    else -> AaCandidate.Proximity.SUPERTYPE
                }
            }

            fun asCandidate(
                sym: KaCallableSymbol,
                kind: AaCandidate.Kind,
                container: String?,
                proximity: AaCandidate.Proximity = AaCandidate.Proximity.UNKNOWN,
            ): AaCandidate? {
                val nm = (sym as? KaNamedSymbol)?.name?.asString() ?: return null
                if (!accessible(sym)) return null
                val type = runCatching { sym.returnType }.getOrNull()
                return AaCandidate(
                    nm, kind, runCatching { type?.toString() }.getOrNull(), container, iconFor(sym), paramsOf(sym),
                    fitsExpected = fits(type),
                    declaredIn = declaredIn(sym),
                    proximity = proximity,
                )
            }

            /**
             * Resolve a dotted receiver that is a CLASS QUALIFIER rather than a value — `FnGradientMode`,
             * `afield.stdlib.Functions.RectifyMode` — to its class symbol.
             *
             * There is no reference-resolution helper wired into this standalone session, but
             * `findClassLike(ClassId)` resolves fine by exact name (the same property the binary name
             * index relies on). So try the plausible `ClassId` splits of the receiver text: for a dotted
             * receiver, every package/nested-class boundary; for a bare name, the file's own package plus
             * each import (explicit and star), which is how `afield.stdlib.*` in a box body brings
             * `FnGradientMode` into scope.
             */
            /** Is [text] a package? The index knows every package on the classpath; AA is the fallback. */
            fun isPackageName(text: String, index: AaSymbolIndex): Boolean =
                index.allPackages.contains(text) ||
                    index.allPackages.any { it.startsWith("$text.") } ||
                    runCatching { findPackage(FqName(text)) != null }.getOrDefault(false)

            /**
             * What lives under a package qualifier: its immediate SUB-PACKAGES, its classifiers, and — once
             * something is typed — its top-level callables (`afield.stdlib.si‸n`). Sub-packages and
             * classifiers come from the ASM index, which is the only thing that can enumerate a binary
             * root; callables are resolved through AA so the use-site visibility check can drop the
             * private ones the index deliberately keeps (see [FacadeVisitor.visitMethod]).
             */
            var packageTruncated: String? = null
            fun packageCandidates(pkg: String, index: AaSymbolIndex): List<AaCandidate> {
                val out = ArrayList<AaCandidate>()
                index.allPackages.asSequence()
                    .filter { it.startsWith("$pkg.") }
                    .map { it.removePrefix("$pkg.").substringBefore('.') }
                    .filter { it.isNotEmpty() && match(Name.identifier(it)) }
                    .distinct()
                    .forEach { out.add(AaCandidate(it, AaCandidate.Kind.CLASSIFIER, null, pkg, "package")) }
                index.classifiers[pkg].orEmpty().asSequence()
                    .filter { match(Name.identifier(it)) }
                    .forEach { out.add(AaCandidate(it, AaCandidate.Kind.CLASSIFIER, null, pkg, "class")) }
                index.typeAliases[pkg].orEmpty().asSequence()
                    .filter { match(Name.identifier(it)) }
                    .forEach { out.add(AaCandidate(it, AaCandidate.Kind.CLASSIFIER, null, pkg, "class")) }
                // At a BARE `kotlin.math.‸` too, capped rather than gated. This used to require a prefix,
                // on the reasoning that `afield.stdlib.` would resolve thousands of symbols for one
                // keystroke — but the consequence was that a fully-qualified call answered with
                // sub-packages only: `kotlin.math.‸sin` came back with 1 candidate and no `sin`, and a bare
                // path was 7 of the caret sweep's misses per mode. Writing a package path out by hand is a
                // deliberate act, and [PACKAGE_ENUMERATION_CAP] (800 resolutions, well inside the measured
                // p50) bounds it. Truncation is reported in the note, as for the receiver index.
                val callableNames = index.callables[pkg].orEmpty()
                    .filter { prefix.isEmpty() || it.startsWith(prefix) }
                // …but only a package that FITS. Resolving 800 of `afield.stdlib`'s 3947 top-level names in
                // alphabetical order is the same "truncate the alphabet" mistake the transport used to
                // make, and it costs 233ms of a keystroke to make it. So enumerate a package that fits the
                // cap (`kotlin.math` — 100 rows, and `sin` among them), and otherwise say so in the note:
                // one typed character brings any package back under the cap.
                if (callableNames.size > PACKAGE_ENUMERATION_CAP && prefix.isEmpty()) {
                    packageTruncated = "$pkg has ${callableNames.size} top-level names — " +
                        "not enumerated at an empty prefix; type a character"
                } else {
                    callableNames.asSequence()
                        .flatMap { nm -> findTopLevelCallables(FqName(pkg), Name.identifier(nm)) }
                        .take(PACKAGE_ENUMERATION_CAP)
                        .forEach { sym -> asCandidate(sym, AaCandidate.Kind.TOP_LEVEL, pkg)?.let(out::add) }
                }
                return out
            }

            fun resolveQualifier(recv: KtExpression): org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol? {
                // ASK THE RESOLVER FIRST. Everything below guesses candidate packages from the file's
                // imports plus two hardcoded `afield` ones, which cannot see Kotlin's DEFAULT imports —
                // `java.lang` above all — so `Math.` resolved to no qualifier at all and fell through to
                // the value path, where it answered with `equals`/`hashCode`/`toString` and the `Any`
                // extensions: 13 rows, no `abs`, no `toRadians`, no `PI`. `Math.` and `System.` are in
                // ordinary box code, and the same hole covered `Integer.`, `Thread.`, `Files.` and every
                // `import x.Y as Z` alias. The reference knows the answer; the file's real import scope is
                // exactly what it resolves against.
                runCatching {
                    val ref = (recv as? KtNameReferenceExpression)
                        ?: (recv as? KtQualifiedExpression)?.selectorExpression as? KtNameReferenceExpression
                    val sym = ref?.mainReference?.resolveToSymbol()
                    if (sym is org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol) return sym
                }
                val text = recv.text?.trim()?.takeIf { it.isNotEmpty() && it.none { ch -> ch.isWhitespace() } }
                    ?: return null
                val parts = text.split('.')
                if (parts.any { it.isEmpty() }) return null

                fun classAt(pkg: List<String>, nested: List<String>): org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol? {
                    if (nested.isEmpty()) return null
                    val id = runCatching {
                        ClassId(
                            if (pkg.isEmpty()) FqName.ROOT else FqName(pkg.joinToString(".")),
                            FqName(nested.joinToString(".")),
                            false
                        )
                    }.getOrNull() ?: return null
                    return runCatching { findClassLike(id) as? org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol }
                        .getOrNull()
                }

                if (parts.size > 1) {
                    // Fully/partially qualified: try every package|nested split, longest package first
                    // so `afield.stdlib` + `Functions.RectifyMode` is preferred over root + the whole path.
                    for (split in parts.size - 1 downTo 0) {
                        classAt(parts.take(split), parts.drop(split))?.let { return it }
                    }
                    return null
                }

                // Bare name: the file's package, then its imports.
                val candidatePackages = buildList {
                    add(file.packageFqName.asString())
                    for (imp in file.importDirectives) {
                        val fq = imp.importedFqName?.asString() ?: continue
                        if (imp.isAllUnder) add(fq) else if (fq.endsWith(".$text")) add(fq.removeSuffix(".$text"))
                    }
                    // Box bodies lean on these two whether or not an import is written out.
                    add("afield.stdlib")
                    add("afield")
                }
                for (pkg in candidatePackages.distinct()) {
                    classAt(if (pkg.isEmpty()) emptyList() else pkg.split('.'), listOf(text))?.let { return it }
                }
                return null
            }

            /**
             * Everything reachable through a class qualifier: enum entries, `values()`/`valueOf()`/
             * `entries`, Java statics, companion-object members, and nested classifiers. Collected from
             * both the static scope and the companion scope because an enum's entries and its companion's
             * members live in different places.
             */
            fun staticScopeCandidates(recv: KtExpression): List<AaCandidate> {
                val cls = resolveQualifier(recv) ?: return emptyList()
                val label = runCatching { cls.classId?.asFqNameString() }.getOrNull() ?: recv.text
                val out = ArrayList<AaCandidate>()

                fun harvest(scope: org.jetbrains.kotlin.analysis.api.scopes.KaScope?, kind: AaCandidate.Kind) {
                    if (scope == null) return
                    runCatching {
                        for (sym in scope.callables(match)) {
                            asCandidate(sym, kind, label)?.let(out::add)
                        }
                    }
                    runCatching {
                        for (sym in scope.classifiers(match)) {
                            val nm = (sym as? KaNamedSymbol)?.name?.asString() ?: continue
                            out.add(AaCandidate(nm, AaCandidate.Kind.CLASSIFIER, null, label, "class"))
                        }
                    }
                }

                runCatching { harvest(cls.staticDeclaredMemberScope, AaCandidate.Kind.MEMBER) }
                runCatching { harvest(cls.staticMemberScope, AaCandidate.Kind.MEMBER) }
                // `Img.generate(…)` — a companion member off the class qualifier. Read the companion
                // straight off the symbol: `KaNamedClassSymbol.companionObject`. It used to be hunted for
                // as a COMPANION_OBJECT-kind classifier of `declaredMemberScope`, which is EMPTY for a
                // binary class (the same caveat the enum-entry harvest below documents) — so for every
                // class on Field's classpath the hunt found nothing and `Img.` answered with one useless
                // row, the nested `Companion` classifier itself. `Img.generate` was unreachable. The
                // caret-recall sweep put this at 62 of its 244 misses, the single largest cluster.
                runCatching {
                    (cls as? KaNamedClassSymbol)?.companionObject?.let { harvest(it.memberScope, AaCandidate.Kind.MEMBER) }
                }
                // An enum's entries are declared members of the class itself, not of its static scope in
                // every AA version, so include the declared scope's callables too. Instance members are
                // NOT valid off a qualifier, so only enum entries are taken from here.
                runCatching {
                    for (sym in cls.declaredMemberScope.callables(match)) {
                        if (sym is org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol) {
                            asCandidate(sym, AaCandidate.Kind.MEMBER, label)?.let(out::add)
                        }
                    }
                }
                return out
            }

            /**
             * The shortest source-legal way to write [id] in this file: the bare (or nested) name when
             * the file's imports already bring it in, else fully qualified. An insertion has to compile
             * in the box it is inserted into, and the box may not have imported anything.
             */
            fun qualifierFor(id: ClassId): String {
                val pkg = id.packageFqName.asString()
                val rel = id.relativeClassName.asString()          // "Scheme" | "Functions.RectifyMode"
                val fq = if (pkg.isEmpty()) rel else "$pkg.$rel"
                val outer = if (rel.contains('.')) fq.substringBeforeLast('.') else null
                for (d in file.importDirectives) {
                    val imported = d.importedFqName?.asString() ?: continue
                    // `import sentences.Scheme`, `import a.b.Functions.RectifyMode` → the short name.
                    if (!d.isAllUnder && imported == fq) return id.shortClassName.asString()
                    // `import a.b.Functions.*` → a nested name without its outer class.
                    if (d.isAllUnder && outer != null && imported == outer) return id.shortClassName.asString()
                    // `import sentences.*` → the name relative to the package.
                    if (d.isAllUnder && imported == pkg) return rel
                }
                return if (pkg == file.packageFqName.asString()) rel else fq
            }

            /**
             * The values that can be *written out* at the caret, for an expected type whose inhabitants
             * are enumerable: an enum's entries, a sealed hierarchy's inheritors, and Boolean. This is
             * the compact answer — `d.scheme = ‸` is `SD_BASIC | SD_COLLAPSED | UD`, not the several
             * hundred names that happen to be in scope at that point in the file.
             *
             * Offered under the bare name (what the panel should read, and what the user is about to
             * type) but *inserted* qualified via [AaCandidate.insertText], because Kotlin only resolves a
             * bare enum entry from the expected type inside a `when` subject.
             */
            fun enumerableValuesOf(t: KaType): List<AaCandidate> {
                val id = (t as? KaClassType)?.classId ?: return emptyList()
                val typeName = shortenType(runCatching { t.toString() }.getOrDefault(id.shortClassName.asString()))
                if (id.asFqNameString() == "kotlin.Boolean")
                    return listOf("true", "false").filter { prefix.isEmpty() || it.startsWith(prefix) }
                        .map { AaCandidate(it, AaCandidate.Kind.TOP_LEVEL, "Boolean", null, "val", fitsExpected = true) }

                val cls = runCatching { findClassLike(id) as? KaClassSymbol }.getOrNull() ?: return emptyList()
                if (cls.classKind == KaClassKind.ENUM_CLASS) return runCatching {
                    val qualifier = qualifierFor(id)
                    // Which scope an enum's entries live in is not stable across AA versions / origins —
                    // for a BINARY enum `declaredMemberScope` comes back empty and the entries are in the
                    // static scopes, which is why the dot path (`staticScopeCandidates`) harvests all
                    // three. Same union here, deduplicated by name.
                    listOf(cls.staticDeclaredMemberScope, cls.staticMemberScope, cls.declaredMemberScope)
                        .flatMap { scope -> runCatching { scope.callables(match).toList() }.getOrDefault(emptyList()) }
                        .filterIsInstance<KaEnumEntrySymbol>()
                        .distinctBy { it.name.asString() }
                        .map {
                            val name = it.name.asString()
                            AaCandidate(
                                name, AaCandidate.Kind.MEMBER, typeName, id.asFqNameString(), "val", null,
                                insertText = "$qualifier.$name", fitsExpected = true,
                            )
                        }
                }.getOrDefault(emptyList())

                if (cls.modality == KaSymbolModality.SEALED) return runCatching {
                    (cls as? KaNamedClassSymbol)?.sealedClassInheritors.orEmpty()
                        .filter { match(it.name) }
                        .mapNotNull { sub ->
                            val subId = sub.classId ?: return@mapNotNull null
                            // An object IS the value; a subclass has to be constructed.
                            val isObject = sub.classKind == KaClassKind.OBJECT
                            AaCandidate(
                                sub.name.asString(), AaCandidate.Kind.MEMBER, typeName, id.asFqNameString(),
                                if (isObject) "val" else "class", null,
                                insertText = qualifierFor(subId) + if (isObject) "" else "(",
                                fitsExpected = true,
                            )
                        }
                }.getOrDefault(emptyList())

                return emptyList()
            }

            if (isDot) {
                val dq = PsiTreeUtil.getParentOfType(leaf, KtQualifiedExpression::class.java)
                    ?: return@analyze AaCompletionResult(emptyList(), "no qualified expr")
                val recv: KtExpression = dq.receiverExpression

                // A receiver can be a VALUE (`someList.`, `Functions.`) or a TYPE QUALIFIER
                // (`FnGradientMode.`, `Functions.RectifyMode.`). K2 gives a qualifier a perfectly good
                // `expressionType` — the class's own type — so the member path below runs and returns the
                // type's INSTANCE members. For a qualifier those are exactly the wrong answer: it offered
                // `equals`/`hashCode`/`toString` and none of the enum's entries, `values()`, `valueOf()`
                // or `entries`. That is what `SomeEnum.` did for EVERY enum in Field, and K1 surfaced the
                // entries, so the 2026-07-20 cutover to this backend lost the whole class silently — the
                // differential corpus had no `Enum.` fixture, so the gate could not see it. The `enum#*`
                // fixtures in tools/completion-corpus/name-fixtures.tsv now cover it, including two enums
                // that predate the autodiff work as a control arm.
                //
                // Objects are the case that must NOT change: `Functions.` IS a value, its instance
                // members are correct, and it is how the head APIs are browsed. So switch on the resolved
                // class kind rather than on "did the name start with a capital".
                val qualifierClass = resolveQualifier(recv)
                val qualifierKind = qualifierClass?.classKind
                val isTypeQualifier = qualifierClass != null &&
                    qualifierKind != org.jetbrains.kotlin.analysis.api.symbols.KaClassKind.OBJECT &&
                    qualifierKind != org.jetbrains.kotlin.analysis.api.symbols.KaClassKind.COMPANION_OBJECT &&
                    qualifierKind != org.jetbrains.kotlin.analysis.api.symbols.KaClassKind.ANONYMOUS_OBJECT
                if (isTypeQualifier) {
                    val out = staticScopeCandidates(recv)
                        .filterNot { it.isSynthetic }.filter(jsFilter)
                        .distinctBy { it.dedupKey }.sortedBy { it.name }
                    return@analyze AaCompletionResult(
                        out,
                        if (out.isEmpty()) "0 matched; type qualifier=${recv.text} kind=$qualifierKind" else ""
                    )
                }

                // A PACKAGE qualifier: `afield.‸` → `stdlib`, `four`, `graphics`. Checked after the class
                // qualifier above, so a class beats a same-named package the way Kotlin's own resolution
                // does. Before this, `afield.` fell through to the value path, where the receiver has no
                // real type and the `Any?` extensions matched everything — the panel answered a package
                // path with `also`/`apply`/`let`/`matches`. Suppressing that is worth as much as offering
                // the sub-packages: junk after a dot is what makes people stop trusting the panel.
                val recvText = recv.text?.trim().orEmpty()
                val looksLikePath = recvText.isNotEmpty() &&
                    recvText.all { it.isLetterOrDigit() || it == '_' || it == '.' } && !recvText.endsWith(".")
                // A PACKAGE qualifier: `afield.‸` → `stdlib`, `four`, `graphics`. Last of the three, because
                // a VALUE and a CLASS both shadow a package — checked before this, a lambda's `it.` matched
                // the package `it.unimi.dsi` (fastutil) and answered a real receiver with a package listing.
                // Reached only when the receiver has no usable type at all, which is exactly the case where
                // the value path would otherwise return `Any?` extensions: before this, `afield.` answered
                // with `also`/`apply`/`let`/`matches`, and junk after a dot is what makes people stop
                // trusting the panel.
                // Does the receiver name a VALUE? That is the question to ask, and it has to be asked of
                // the resolver rather than guessed from the type: a package receiver has an
                // `expressionType` (`afield.` types as `Unit`), so "no usable type" does not identify one,
                // and a name that *is* a value must win — a lambda's `it.` matched the package
                // `it.unimi.dsi` (fastutil) and answered a real Int receiver with a package listing.
                val recvIsValue = runCatching {
                    val sym = (recv as? KtNameReferenceExpression)?.mainReference?.resolveToSymbol()
                    sym is KaCallableSymbol
                }.getOrDefault(false)
                // An `it` the resolver could NOT bind to a value is a receiver we have no answer for, and
                // the important thing is to say so rather than to guess. Binding a lambda's `it` needs the
                // enclosing call to type-check, so in `(_look / "x").lenses<Float>().forEach { it.‸ }` —
                // where `_look` is a REPL value the headless session cannot see — `it` binds to nothing,
                // and both paths below then answer confidently and wrongly: the package path, because `it`
                // really is a root package here (fastutil's `it.unimi.dsi`), returned the single row
                // `unimi`; suppress that and the value path types the same package reference as `Unit` and
                // returns ~29 `Any?` extensions (`also`, `let`, `onBeat`, `graphPaths`). Either way the
                // result is NON-EMPTY, which `AaCompletionClient` reads as "AA served this" — suppressing
                // the K1 fallback, which has the REPL's accumulated state, knows `_look`, and had
                // `Lens.set` ready. Typing one character emptied the package listing and the fallback came
                // back, which is the whole of "it only completes if I guess a letter first". So bail like
                // the error-type case below: an empty AA result is what hands the question to K1.
                //
                // The cost is that `it.u‸` no longer offers fastutil's `unimi`. Nobody reaches that package
                // through a bare `it`, and a dotted `it.unimi.‸` receiver is unaffected — it is not this
                // name. What everybody does type is `it.` inside a lambda.
                if (!recvIsValue && recvText == "it")
                    return@analyze AaCompletionResult(emptyList(), "`it` UNBOUND (enclosing call unresolved)")
                if (!recvIsValue && looksLikePath && isPackageName(recvText, index)) {
                    val out = packageCandidates(recvText, index).filterNot { it.isSynthetic }
                        .distinctBy { it.dedupKey }.sortedBy { it.name }
                    return@analyze AaCompletionResult(
                        out,
                        if (out.isEmpty()) "0 matched in package $recvText"
                        else packageTruncated?.let { "truncated $it" }.orEmpty(),
                    )
                }

                val type = recv.expressionType
                    ?: return@analyze AaCompletionResult(emptyList(), "receiver type UNRESOLVED: ${recv.text}")
                // An ERROR type is not a type. `errorType.isSubtypeOf(anything)` is vacuously true, so
                // the extension-conformance test below accepted EVERY prefix-matching extension in the
                // index: `d.fl` on an unresolved `d` filled the panel with flatMap/flatten/flatShaded.
                // Worse, a non-empty result tells AaCompletionClient that AA served the request, which
                // suppresses the K1 fallback that would have answered correctly — so the damage was not
                // just noise, it was the right answer being withheld. Bail like the null case above.
                if (type is KaErrorType)
                    return@analyze AaCompletionResult(emptyList(), "receiver type UNRESOLVED (error): ${recv.text}")
                val typeStr = runCatching { type.toString() }.getOrDefault("?")

                // The receiver's own classifier and its supertypes, as fq names — what [proximityOf] scores
                // a declaration's distance against. Same walk the receiver-keyed extension index uses,
                // read back as source-level names rather than ASM-internal ones.
                val ownId = runCatching { (type as? KaClassType)?.classId?.asFqNameString() }.getOrNull()
                val superIds = internalNamesOf(type).asSequence()
                    .map { it.replace('/', '.').replace('$', '.') }.filter { it != ownId }.toSet()

                // Substituted members of the receiver type.
                val members = type.scope?.getCallableSignatures(match).orEmpty().mapNotNull { sig ->
                    val nm = (sig.symbol as? KaNamedSymbol)?.name?.asString() ?: return@mapNotNull null
                    if (!accessible(sig.symbol)) return@mapNotNull null
                    AaCandidate(
                        nm, AaCandidate.Kind.MEMBER, runCatching { sig.returnType.toString() }.getOrNull(),
                        typeStr, iconFor(sig.symbol), paramsOf(sig.symbol),
                        declaredIn = declaredIn(sig.symbol),
                        proximity = proximityOf(sig.symbol, ownId, superIds),
                    )
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
                val fromPackages = index.callables.asSequence()
                    // A bare `.` must not walk the whole index — thousands of resolves per keystroke.
                    // But [extraPackages] (the box's own `import sentences.*`) is a handful of names and
                    // is exactly where its extensions live, so `d.` still has to offer `flines`.
                    .filter { prefix.isNotEmpty() || it.key in extraPackages }
                    .flatMap { (pkg, names) ->
                        val fq = FqName(pkg)
                        names.asSequence().filter { prefix.isEmpty() || it.startsWith(prefix) }
                            .flatMap { nm -> findTopLevelCallables(fq, Name.identifier(nm)) }
                    }

                // Extensions looked up BY RECEIVER TYPE rather than by name prefix — the answer to a bare
                // `.`, where there is no prefix to enumerate by and where the by-prefix path above is
                // therefore switched off. Bounded by the receiver's own hierarchy, so this is a handful of
                // resolutions for `IntBuffer` and a legitimately long list for `List`, instead of a walk
                // over every name in the index. See [AaSymbolIndex.extensionsByReceiver].
                val receiverKeys = internalNamesOf(type)
                val receiverIndexHits = receiverKeys.asSequence()
                    .flatMap { rn -> index.extensionsByReceiver[rn]?.asSequence() ?: emptySequence() }
                    .map { it.substringBefore('|') to it.substringAfter('|') }
                    .filter { (_, nm) -> prefix.isEmpty() || nm.startsWith(prefix) }
                    .distinct().toList()
                // Truncation is reported rather than silent: a capped list that looks complete is how a
                // missing candidate becomes a mystery.
                val receiverIndexCapped = receiverIndexHits.size > RECEIVER_INDEX_CAP
                val fromReceiverIndex = receiverIndexHits.asSequence().take(RECEIVER_INDEX_CAP)
                    .flatMap { (pkg, nm) -> findTopLevelCallables(FqName(pkg), Name.identifier(nm)) }
                // …plus the synthetic file's OWN top-level callables, which is where the preamble now
                // lands. A dangling file's top-level declarations do not enter `scopeContext` (the finding
                // the local-stub path exists for), so the preamble's `val Mesh.pos by FromInput()` and
                // `fun Mesh.set(…)` — the box's own graphics API, and the reason the preamble is injected
                // at all — were absent from `m.` even though writing them out by hand resolves. Read off
                // the file's declarations directly; `__c` itself is not an extension, so it never enters.
                val fromFile = file.declarations.asSequence()
                    .mapNotNull { runCatching { it.symbol as? KaCallableSymbol }.getOrNull() }
                    .filter { sym -> match((sym as? KaNamedSymbol)?.name ?: return@filter false) }
                val extCandidates = (ctx.compositeScope().callables(match) + fromPackages + fromFile +
                    fromReceiverIndex +
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
                }.mapNotNull { asCandidate(it, AaCandidate.Kind.EXTENSION, typeStr, proximityOf(it, ownId, superIds)) }

                // Object/companion scopes can expose nested classifiers (`THREE.Vector3`). They are
                // not callables, so the member-only path used to make namespace-like head APIs blank.
                val nestedClassifiers = type.scope?.getClassifierSymbols(match).orEmpty().mapNotNull { symbol ->
                    val name = (symbol as? KaNamedSymbol)?.name?.asString() ?: return@mapNotNull null
                    if (!accessible(symbol)) return@mapNotNull null
                    AaCandidate(name, AaCandidate.Kind.CLASSIFIER, null, typeStr, "class")
                }.toList()

                val out = (members + extensions + nestedClassifiers)
                    .filterNot { it.isSynthetic }.filter(jsFilter).distinctBy { it.dedupKey }.sortedBy { it.name }
                AaCompletionResult(
                    out,
                    when {
                        out.isEmpty() -> "0 matched; type=$typeStr; members=${members.size}"
                        receiverIndexCapped -> "receiver-index truncated at $RECEIVER_INDEX_CAP of " +
                            "${receiverIndexHits.size} candidates for $typeStr"
                        else -> ""
                    }
                )
            } else {
                val pos = PsiTreeUtil.getParentOfType(leaf, KtElement::class.java) ?: (leaf as? KtElement)
                    ?: return@analyze AaCompletionResult(emptyList(), "no position element")

                // Argument position → the enclosing call's remaining (not-yet-supplied) named params,
                // unioned across all overloads (an incomplete call may resolve to a no-arg overload).
                val ctx = file.scopeContext(pos)
                val argList = PsiTreeUtil.getParentOfType(leaf, KtValueArgumentList::class.java)
                // …but NOT once the caret is past a named argument's `=`: at `f(word = ‸)` the caret is in
                // the VALUE slot, where `family = ` is not just unhelpful, it is a syntax error. The
                // parameter names were offered there anyway until the expected-type work made that slot
                // answerable at all.
                val inNamedValueSlot = PsiTreeUtil.getParentOfType(leaf, KtValueArgument::class.java)
                    ?.let { it.getArgumentName() != null && it.getArgumentExpression() != null } == true
                val namedParams: List<AaCandidate> = if (argList != null && !inNamedValueSlot) {
                    val call = PsiTreeUtil.getParentOfType(argList, KtCallExpression::class.java)
                    val callCands = call?.resolveToCallCandidates().orEmpty()
                        .mapNotNull { it.candidate as? KaFunctionCall<*> }
                    val supplied = argList.arguments.mapNotNull { it.getArgumentName()?.asName?.asString() }.toSet()
                    // A call being edited often doesn't resolve — one bad argument is enough — and then
                    // `resolveToCallCandidates` returns nothing and the parameter names go with it. That is
                    // backwards: the names are *most* wanted while the call is still wrong. So fall back to
                    // the callee's NAME: whatever functions are in scope (or in the index) under that name,
                    // unioned. `animateEvolution(totalSteps = …, columns = …, cellSide = ‸)` is the shape
                    // the caret sweep kept missing.
                    val byCalleeName: List<KaCallableSymbol> = if (callCands.isNotEmpty()) emptyList() else {
                        val callee = (call?.calleeExpression as? KtSimpleNameExpression)?.getReferencedName()
                        if (callee == null) emptyList() else {
                            val exact: (Name) -> Boolean = { it.asString() == callee }
                            val fromScope = runCatching { ctx.compositeScope().callables(exact).toList() }
                                .getOrDefault(emptyList())
                            val fromIndex = index.callables.asSequence()
                                .filter { callee in it.value }
                                .flatMap { (pkg, _) -> findTopLevelCallables(FqName(pkg), Name.identifier(callee)) }
                                .toList()
                            (fromScope + fromIndex).filterIsInstance<KaNamedFunctionSymbol>()
                        }
                    }
                    (callCands.flatMap { it.declaredValueParameters() } +
                        byCalleeName.filterIsInstance<KaNamedFunctionSymbol>().flatMap { it.valueParameters })
                        .distinctBy { it.name.asString() }
                        .filter { it.name.asString() !in supplied }
                        .filter { prefix.isEmpty() || it.name.asString().startsWith(prefix) }
                        // Marked as fitting whenever the caret HAS an expectation: at `f(‸` the parameter
                        // names are the canonical answer, so the untyped-caret collapse that the flag
                        // drives must keep them (it would otherwise hide `x =` behind Float-valued values).
                        .map {
                            AaCandidate(
                                it.name.asString(), AaCandidate.Kind.PARAM,
                                runCatching { it.returnType.toString() }.getOrNull(), null, "var",
                                fitsExpected = expectedTypes.isNotEmpty(),
                            )
                        }
                } else emptyList()

                // The enumerable inhabitants of the expected type — the compact "what goes here" list.
                val expectedValues = expectedTypes.flatMap {
                    runCatching { enumerableValuesOf(it) }.getOrDefault(emptyList())
                }

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
                // The file's own classifiers are added explicitly for the same reason its callables are on
                // the dot path: a dangling file's top-level declarations don't reach `scopeContext`, so a
                // class the preamble declares (`/*supermap*/ class MappedStructure`) was unnameable.
                val fileClassifiers = file.declarations.asSequence()
                    .filterIsInstance<KtClassOrObject>().mapNotNull { it.nameAsName }.filter(match)
                    .map { AaCandidate(it.asString(), AaCandidate.Kind.CLASSIFIER, null, null, "class") }
                val sourceClassifiers = (ctx.compositeScope(scopeFilter).classifiers(match).mapNotNull { symbol ->
                    val name = (symbol as? KaNamedSymbol)?.name?.asString() ?: return@mapNotNull null
                    AaCandidate(name, AaCandidate.Kind.CLASSIFIER, null, null, "class")
                } + fileClassifiers).toList()
                // At an empty-prefix ARGUMENT position (`f(‸`) the call's parameters are what matters;
                // dumping every implicit-receiver (e.g. `Box`) member there just buries the params. Skip
                // them until a prefix is typed. At a name position (blank line) they stay — that's how you
                // discover the receiver's API.
                val suppressReceivers = argList != null && prefix.isEmpty()
                val fromReceivers = if (suppressReceivers) emptyList() else ctx.implicitReceivers.asSequence().flatMap { rec ->
                    rec.type.scope?.getCallableSignatures(match)?.mapNotNull { sig ->
                        (sig.symbol as? KaNamedSymbol)?.name?.asString()?.let {
                            AaCandidate(it, AaCandidate.Kind.MEMBER, runCatching { sig.returnType.toString() }.getOrNull(),
                                runCatching { rec.type.toString() }.getOrNull(), iconFor(sig.symbol), paramsOf(sig.symbol),
                                fitsExpected = fits(runCatching { sig.returnType }.getOrNull()))
                        }
                    } ?: emptySequence()
                }.toList()
                // Global symbols (index-backed) only once a prefix is typed — an empty prefix shouldn't
                // flood the client with every class in the classpath (client owns ranking, not volume).
                // Counted, not just collected: `0 candidates at name position` was the least informative
                // note in the whole residual — it cannot distinguish "the index never held that name" from
                // "it held it and resolution came back empty", which are opposite bugs with opposite fixes.
                var indexNameHits = 0
                val indexCallables: List<KaCallableSymbol> = if (prefix.isEmpty()) emptyList() else
                    index.callables.asSequence().flatMap { (pkg, names) ->
                        val fq = FqName(pkg)
                        names.asSequence().filter { it.startsWith(prefix) }
                            .onEach { indexNameHits++ }
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

                val out = (expectedValues + namedParams + fromScopes + sourceClassifiers + fromReceivers +
                    topCallables + receiverExtensions + classifiers)
                    .filterNot { it.isSynthetic }.filter(jsFilter).distinctBy { it.dedupKey }.sortedBy { it.name }
                AaCompletionResult(
                    out,
                    // The breakdown goes AFTER a colon on purpose: the probe's histogram keys on the text
                    // before it, so the reason stays one bucket instead of fragmenting per prefix, while a
                    // paste-able example still carries the numbers.
                    if (out.isEmpty()) "0 candidates at name position: prefix=$prefix " +
                        "index-names=$indexNameHits resolved=${indexCallables.size} scope=${fromScopes.size} " +
                        "receivers=${fromReceivers.size} classifiers=${classifiers.size}"
                    else if (expectedTypes.isNotEmpty())
                        "expected=${expectedTypes.joinToString("|") { shortenType(runCatching { it.toString() }.getOrDefault("?")) }}" +
                            " values=${expectedValues.size} fits=${out.count { it.fitsExpected }}"
                    else ""
                )
            }
        }
    }
}
