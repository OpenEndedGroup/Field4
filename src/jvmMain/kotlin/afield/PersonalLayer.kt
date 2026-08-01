package afield

import afield.logger.lprintln
import java.io.File
import java.lang.ref.WeakReference
import java.net.URLClassLoader
import java.util.IdentityHashMap

/**
 * The classloader layer that holds `src/personal`.
 *
 * `src/personal` is gitignored, local-only code. It compiles as its own JVM compilation (see the
 * `personal` compilation on the jvm target in build.gradle.kts) into a single jar, and that jar is
 * deliberately kept *off* the launch `-cp`. Instead the launch args carry
 * `-Dafield.personal.classpath=<jar>` and the app loads it here, in a [URLClassLoader] slotted
 * between the app classloader and [KotlinInterface2.sharedClassLoader]:
 *
 * ```
 *   app loader          core Field, LWJGL, ktor, the JNI-bearing deps   [stable]
 *     └─ personal       afield-personal.jar                            [swappable]
 *          └─ KI2.sharedClassLoader (empty URLClassLoader)
 *               └─ per-snippet REPL loaders (K2ReplEvaluator chain)
 * ```
 *
 * Nothing in core references a personal package, so nothing above the boundary needs personal
 * classes; everything that does — box code, and the REPL compiler resolving it — reaches them by
 * ordinary parent delegation from below. The compile side needs no configuration: KI2's
 * `dependenciesFromClassloader(wholeClasspath = true)` walks the parent chain collecting each
 * loader's URLs, so the personal jar lands in the REPL compile classpath automatically.
 *
 * The point of the indirection is that the layer is *droppable*: with personal reachable only
 * through one loader, the layer can be retired ([advance]) and rebuilt without restarting. See
 * `reloadPersonal` in PersonalReload.kt for what that costs on the far side — the engines holding
 * stale classes, and how they are found.
 *
 * Note that personal's third-party *dependencies* stay on the main classpath, in the stable layer,
 * rather than moving up with it. Several of them (ortools, jolt-jni) load JNI natives, and a native
 * library can only ever be loaded by one classloader per JVM — a swappable layer that loaded them
 * would die on the second reload with "Native Library ... already loaded in another classloader".
 */
object PersonalLayer {

    /** Set by [requiredRunJvmArgs] in build.gradle.kts; `File.pathSeparator`-separated. */
    const val CLASSPATH_PROPERTY = "afield.personal.classpath"

    /**
     * The roots making up the layer, or empty when there is no personal code — which is the normal
     * state for anyone but Marc, and also what you get before the first `./gradlew personalJar`.
     */
    val roots: List<File> by lazy {
        val declared = System.getProperty(CLASSPATH_PROPERTY).orEmpty()
            .split(File.pathSeparatorChar)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::File)
            .distinct()
        val (present, missing) = declared.partition { it.exists() }
        if (missing.isNotEmpty()) {
            // Worth shouting about: a silently-absent personal layer degrades quietly (box code
            // stops resolving, completion stops offering) rather than failing loudly.
            lprintln(
                "warning,startup",
                "personal layer: ${missing.size} declared root(s) do not exist and were skipped " +
                    "(${missing.joinToString { it.path }}) — run ./gradlew personalJar"
            )
        }
        present
    }

    val isPresent: Boolean get() = roots.isNotEmpty()

    /**
     * How many times [advance] has run. Stamped into each layer's name and into the files it reads,
     * so a stale object can be attributed to a specific reload in an error message.
     */
    var generation: Int = 0
        private set

    private val layers = IdentityHashMap<ClassLoader, ClassLoader>()

    private var stagedRoots: List<File>? = null

    /**
     * The files the live layer actually reads: a per-generation copy of [roots].
     *
     * The copy is not paranoia. A `URLClassLoader` holds its jars open and caches their entries, and
     * the only way to release them is `close()` — which we must never do to a retired layer, since
     * engines still rooted above it would start failing to resolve. So rebuilding
     * `afield-personal.jar` in place and pointing a *new* loader at the same path can serve the old
     * bytes: the reload appears to succeed and changes nothing. Staging each generation at its own
     * path sidesteps the whole question.
     *
     * Directories are passed through — a directory root is re-read from the filesystem on each
     * lookup, so it has no staleness problem to solve. Old generation directories are left behind
     * deliberately: their layers may still be alive, and they are small and under `build/`.
     */
    val activeRoots: List<File>
        @Synchronized get() = stagedRoots ?: stage().also { stagedRoots = it }

    private fun stage(): List<File> = roots.map { root ->
        if (root.isDirectory) return@map root
        runCatching {
            val staged = File(root.parentFile, "generations/gen-$generation/${root.name}")
            staged.parentFile.mkdirs()
            root.copyTo(staged, overwrite = true)
        }.getOrElse {
            lprintln("warning,startup", "personal layer: could not stage ${root.name} (${it.message}); using it in place")
            root
        }
    }

    /**
     * The layer built on top of [base], creating it on first use.
     *
     * Idempotent in the way callers need: [base] may itself be a personal layer (Server.main installs
     * one as the thread context classloader), in which case this resolves to the *current* layer over
     * the same stable base rather than stacking a second one on top. That matters after [advance] —
     * stacking gen-1 above gen-0 would leave gen-0's classes winning parent-first delegation and make
     * the old layer uncollectable, which is precisely the reload silently not happening.
     */
    @Synchronized
    fun wrap(base: ClassLoader): ClassLoader {
        if (roots.isEmpty()) return base
        val stable = generateSequence(base) { it.parent }.firstOrNull { !isOurs(it) } ?: base
        return layers.getOrPut(stable) {
            URLClassLoader(
                "afield-personal-gen$generation",
                activeRoots.map { it.toURI().toURL() }.toTypedArray(),
                stable
            ).also {
                lprintln(
                    "info,startup",
                    "personal layer: gen$generation loaded ${roots.joinToString { it.name }} above ${stable.name ?: stable.javaClass.simpleName}"
                )
            }
        }
    }

    private fun isOurs(loader: ClassLoader): Boolean =
        layers.values.any { it === loader } || retired.any { it.owns(loader) }

    /**
     * Retire every live layer and move to the next generation. The next [wrap]/[current] builds a
     * fresh layer over a fresh copy of the jar; callers are responsible for disposing the engines
     * that were using the old one (see `reloadPersonal`).
     *
     * The retired loaders are deliberately *not* closed. Anything still holding one — an engine we
     * chose not to reset, a live object, a registered callback — must keep resolving; a closed
     * loader would turn a stale-class problem into a `NoClassDefFoundError` storm. They go instead
     * into [retired], weakly, so a class can later be attributed to the reload that orphaned it, and
     * so they can be collected once nothing references them.
     *
     * @return the generation just retired, or null if there was nothing live to retire.
     */
    @Synchronized
    fun advance(): Int? {
        if (roots.isEmpty()) return null
        val retiring = layers.values.distinct()
        val was = generation
        layers.values.forEach { retired += Retired(was, it) }
        layers.clear()
        stagedRoots = null
        generation++
        retired.removeAll { it.collected }
        lprintln("info,personal", "personal layer: retired gen$was (${retiring.size} loader(s)), now on gen$generation")
        return if (retiring.isEmpty()) null else was
    }

    private class Retired(val generation: Int, loader: ClassLoader) {
        private val ref = WeakReference(loader)
        val collected: Boolean get() = ref.get() == null
        fun owns(loader: ClassLoader?): Boolean = loader != null && ref.get() === loader
    }

    private val retired = mutableListOf<Retired>()

    /** Has anything been retired yet? The cheap gate that keeps the scan off the normal path. */
    val hasRetiredLayers: Boolean @Synchronized get() = retired.isNotEmpty()

    /**
     * The generation that owned [type], if it came from a layer that has since been retired — i.e.
     * "this object is stale". Null for everything else, including classes from the *live* layer.
     */
    @Synchronized
    fun retiredGenerationOf(type: Class<*>?): Int? {
        val loader = type?.classLoader ?: return null
        return retired.firstOrNull { it.owns(loader) }?.generation
    }

    /** The layer over the loader that loaded core Field. */
    val current: ClassLoader
        get() = wrap(PersonalLayer::class.java.classLoader ?: ClassLoader.getSystemClassLoader())
}
