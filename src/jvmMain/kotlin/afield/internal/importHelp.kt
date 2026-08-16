package afield

import afield.logger.lprintln
import io.github.classgraph.ClassGraph
import kotlin.concurrent.thread
import kotlin.time.measureTime

/**
 * The two inputs, both outside the checked-in source, that decide what [kotlinImportStatementFor] answers.
 *
 * `kotlinImportStatementFor` emits an import only when a simple name resolves to exactly ONE importable
 * class (see [importStatementFrom]), so *anything* that adds or removes a candidate silently changes what
 * a macro writes into a box's preamble. Two such things exist, and neither is in git:
 *
 *  - **the personal layer.** [classpathIndex] scans `PersonalLayer.current`, so a class in
 *    `src/personal` shares the namespace with core Field. One `data class Window` in `rectgame` is
 *    enough to make `prop<Window>(window)` stop emitting `import afield.four.util.Window`.
 *  - **the import blacklist.** [findClassesNamed] filters through `currentFieldPreferences()`, read from
 *    `$user.dir/.field/fieldPreferencesJson` — gitignored, and absent in a fresh checkout. Dropping
 *    `*awt*` from it would un-hide `java.awt.Window` and suppress the same import for a different reason.
 *
 * For live completion that is correct: a user with personal code really does have two `Window`s, and
 * really did ask for that blacklist. For anything that has to give the *same* answer in every checkout —
 * `afield.CompletionCorpusHarvest` and its `checkCompletionCorpusFixtures` gate — it is not. Those set
 * [hermetic] (or `AFIELD_IMPORT_HERMETIC=1`), which resolves against core Field alone and
 * [defaultImportClassBlacklist], so the fixtures describe the repository rather than the machine.
 *
 * This is not hypothetical. `559348acc` removed the `Window` import from nineteen harvested preambles,
 * `10f3fbaf5` put it back, `389e1d8ff` removed it again, and none of the three was wrong: they ran the
 * same command against different classpaths. See `dev_docs/HERMETIC_COMPLETION_INPUTS.md`.
 */
object ImportResolution {

    /** `AFIELD_IMPORT_HERMETIC=1` turns this on for a whole JVM without a code change. */
    @Volatile
    var hermetic: Boolean = System.getenv("AFIELD_IMPORT_HERMETIC")?.trim() == "1"

    /**
     * Flipping [hermetic] can never serve an index built in the other mode: the two are separate
     * [lazy]s, and when there is no personal layer they are the same scan (see [hermeticIndex]).
     */
    internal fun index(): Map<String, List<String>> = if (hermetic) hermeticIndex else classpathIndex

    internal fun blacklist(): List<String> =
        if (hermetic) defaultImportClassBlacklist else kotlinCompletionBlacklistPatterns

    /** One line naming both inputs, for a harvest banner or a bug report. */
    fun describe(): String = buildString {
        append(if (hermetic) "hermetic" else "machine")
        append(" (personal layer: ")
        append(
            when {
                !PersonalLayer.isPresent -> "absent"
                hermetic -> "present but EXCLUDED (${PersonalLayer.roots.joinToString { it.name }})"
                else -> "present (${PersonalLayer.roots.joinToString { it.name }})"
            }
        )
        append("; blacklist: ")
        append(if (hermetic) "built-in default" else "\$user.dir/.field/fieldPreferencesJson")
        append(", ${blacklist().size} patterns)")
    }

    /**
     * For each of [names], the import this machine would emit when it differs from the hermetic answer.
     *
     * The point is to make the disagreement legible *on the machine that has it*: a harvest run in a
     * checkout with personal code prints, rather than silently produces, "Window resolves differently
     * here". Costs a second classpath scan only when a personal layer is actually present.
     */
    fun divergences(names: Collection<String>): List<Divergence> {
        val was = hermetic
        return try {
            hermetic = true
            val h = names.associateWith { kotlinImportStatementFor(it) }
            hermetic = false
            names.mapNotNull { n ->
                val machine = kotlinImportStatementFor(n)
                if (machine == h[n]) null else Divergence(n, h[n], machine, classpathIndex[n].orEmpty())
            }
        } finally {
            hermetic = was
        }
    }

    /** One name whose import depends on something outside the checkout. */
    data class Divergence(
        val name: String,
        val hermetic: String?,
        val machine: String?,
        val candidates: List<String>,
    )

    /**
     * How many simple names on this classpath an import can actually be emitted for.
     *
     * The `Window` defect was never about `Window`. [importStatementFrom] refuses to guess between two
     * same-named classes, so **every** contested simple name is a type that `prop<T>(name)` will
     * declare a property of and then not import — silently, with only an `lprintln` to say so. This
     * counts the blast radius; [Census.ambiguousNames] is the list a reviewer can read down.
     *
     * A name is [resolvable] when exactly one candidate survives the `$`/blacklist filter (an import is
     * emitted), [ambiguous] when two or more do (**no import at all**), and [unimportable] when none do
     * — also no import, but correctly, because there was nothing to import.
     */
    data class Census(
        val simpleNames: Int,
        val resolvable: Int,
        val ambiguous: Int,
        val unimportable: Int,
        val ambiguousNames: List<String>,
    ) {
        /** Share of names that *could* be imported but are not, because more than one class claims them. */
        val ambiguousShare: Double
            get() = if (resolvable + ambiguous == 0) 0.0
            else ambiguous.toDouble() / (resolvable + ambiguous)
    }

    /**
     * Every type name that this machine's personal layer has taken away.
     *
     * A name that resolves to exactly one importable class in a clean checkout, and to two or more
     * once `src/personal` is on the classpath, is a type `prop<T>(name)` **stops** being able to
     * import here — silently, for the same reason `Window` did. Computed as a set difference over the
     * two indexes rather than by subtracting two counts, so it names the casualties instead of
     * counting them.
     *
     * Empty when there is no personal layer, at no cost: the two indexes are then the same object.
     */
    fun personalLayerCasualties(): List<String> {
        if (!PersonalLayer.isPresent) return emptyList()
        val blacklist = blacklist()
        fun importable(candidates: List<String>?) = candidates.orEmpty().count { isImportableClassName(it, blacklist) }
        return hermeticIndex.keys
            .filter { importable(hermeticIndex[it]) == 1 && importable(classpathIndex[it]) > 1 }
            .sorted()
    }

    fun census(index: Map<String, List<String>> = index(), blacklist: List<String> = blacklist()): Census {
        var resolvable = 0
        var ambiguous = 0
        var unimportable = 0
        val contested = mutableListOf<String>()
        for ((name, candidates) in index) {
            when (candidates.count { isImportableClassName(it, blacklist) }) {
                0 -> unimportable++
                1 -> resolvable++
                else -> { ambiguous++; contested += name }
            }
        }
        return Census(index.size, resolvable, ambiguous, unimportable, contested.sorted())
    }
}

/**
 * Every simple name on the classpath, including the personal layer.
 *
 * Name PersonalLayer explicitly rather than relying on the context classloader: this cache can
 * be built from any thread, and src/personal is only reachable through that layer now that it
 * is off -cp. ClassGraph still walks the layer's parents, so this is a superset of the default
 * scan; with no personal code `current` is just the app loader and nothing changes.
 */
val cache: Map<String, List<String>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    scanSimpleNames(PersonalLayer.current)
}

/** [cache] under its role-describing name; `cache` stays because box code and tools use it. */
internal val classpathIndex: Map<String, List<String>> get() = cache

/**
 * The same index built over core Field alone — what a clean checkout of this repository has.
 *
 * With no personal layer this *is* [cache], deliberately: a fresh worktree pays for one scan, not two,
 * and the two modes are then indistinguishable by construction rather than by two scans agreeing.
 */
internal val hermeticIndex: Map<String, List<String>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    if (!PersonalLayer.isPresent) cache
    else scanSimpleNames(PersonalLayer::class.java.classLoader ?: ClassLoader.getSystemClassLoader())
}

private fun scanSimpleNames(loader: ClassLoader): Map<String, List<String>> {
    val v = mutableMapOf<String, MutableList<String>>()
    ClassGraph().enableClassInfo().enableSystemJarsAndModules().enableMemoryMapping()
        .addClassLoader(loader)
        .scan().use { scanResult ->                // Start the scan
            for (cz in scanResult.allStandardClasses) {
                v.computeIfAbsent(cz.simpleName) { mutableListOf() }.add(cz.name)
            }
        }
    return v
}

fun findClassesNamed(name: String): List<String>? {
    val blacklist = ImportResolution.blacklist()
    return ImportResolution.index()[name]
        ?.filter { isImportableClassName(it, blacklist) }
        ?.takeIf { it.isNotEmpty() }
}

fun isImportableClassName(name: String, blacklist: List<String> = kotlinCompletionBlacklistPatterns): Boolean {
    if ('$' in name) return false
    return !isBlacklistedKotlinCompletionName(name, blacklist)
}

fun classesWithPrefix(name: String, hideDollarSignClasses : Boolean = true): List<Pair<String, String>> {
    return cache.entries.mapNotNull {
        if (it.key.startsWith(name) && (!hideDollarSignClasses || !(it.key.contains("$") || it.value.contains("$")))
            && !isBlacklistedKotlinCompletionName(it.key)) {
            it.key.removePrefix(name) to it.value
        } else null
    }.flatMap { (suffix, l) ->
        l.filter { isImportableClassName(it) }.map {
            suffix to it
        }
    }
}

private val kotlinDefaultImportedTypeNames = setOf(
    "Any",
    "Annotation",
    "Array",
    "Boolean",
    "BooleanArray",
    "Byte",
    "ByteArray",
    "Char",
    "CharArray",
    "CharSequence",
    "Comparable",
    "Double",
    "DoubleArray",
    "Enum",
    "Float",
    "FloatArray",
    "Int",
    "IntArray",
    "Long",
    "LongArray",
    "Nothing",
    "Number",
    "Short",
    "ShortArray",
    "String",
    "Throwable",
    "Unit"
)

private val defaultImportedPackages = setOf(
    "kotlin",
    "kotlin.annotation",
    "kotlin.collections",
    "kotlin.comparisons",
    "kotlin.io",
    "kotlin.ranges",
    "kotlin.sequences",
    "kotlin.text",
    "java.lang"
)

/** True for a type Kotlin already has in scope, so a missing import for it is not a missing import. */
internal fun isDefaultImportedTypeName(name: String): Boolean {
    val trimmed = name.trim().removeSurrounding("`")
    if (trimmed.isBlank()) return false
    if (trimmed in kotlinDefaultImportedTypeNames) return true
    val packageName = trimmed.substringBeforeLast('.', missingDelimiterValue = "")
    return packageName in defaultImportedPackages
}

/**
 * The whole decision, as a function of its inputs — no classpath, no preferences, no global state.
 *
 * Split out from [kotlinImportStatementFor] because it is the line the nineteen-fixture disagreement
 * turned on, and a rule that fires on "exactly one candidate" is only testable if the candidate list is
 * an argument. [candidates] is every class on the classpath with this simple name, unfiltered.
 *
 * @return the import to write, or null — which is also what "ambiguous" looks like, on purpose: a macro
 * that guessed between two same-named classes would put a wrong import in a preamble.
 */
internal fun importStatementFrom(name: String, candidates: List<String>, blacklist: List<String>): String? {
    if (isDefaultImportedTypeName(name)) return null

    val n = candidates.filter { isImportableClassName(it, blacklist) }
    if (n.isEmpty()) return null
    if (n.size > 1) {
        lprintln("warning,import", "multiple classes called ${name} = $candidates")
        return null
    }

    if (isDefaultImportedTypeName(n[0])) return null

    return "import ${n[0].replace("$", ".")}"
}

fun kotlinImportStatementFor(name: String): String? =
    importStatementFrom(name, ImportResolution.index()[name].orEmpty(), ImportResolution.blacklist())

fun initializeClassCache() {
    thread {
        var size = 0
        var time = measureTime {
            size = cache.size
        }
        lprintln("info,import", "found ${size} classes on classpath in ${time.inWholeMilliseconds / 1000.0}s")
    }
}
