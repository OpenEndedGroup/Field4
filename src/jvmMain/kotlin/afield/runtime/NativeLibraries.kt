/**
 * Every optional native library Field knows how to use, whether each is installed, and what the
 * welcome page says about the ones that are not.
 *
 * Field builds and runs without any of them. Each library's own locator object (`ManifoldLibrary`,
 * `CsoundLibrary`, `MorseSmaleLibrary`, ...) implements [NativeCapability]: where it looks, whether
 * the file is there, the Gradle task that builds it, and the shell commands that install its
 * prerequisites on macOS, Linux and Windows. [NativeLibraries] registers one per library and answers
 * without loading any of them, so asking never fails.
 *
 * [NativeLibraries.status] reports every library as a [NativeLibraryStatus], which
 * `GET /api/native-libraries-status` serves to the welcome page's Optional native libraries panel,
 * grouped by [NativeCapability.area]. Building that report loads none of them.
 * `tools/test_native_capability_registry.py` fails when a source file reads an
 * `afield.<name>.library` property for a library that is not registered here.
 */

package afield.runtime

import kotlinx.serialization.Serializable

/**
 * One optional native library, as [NativeLibraries] sees it.
 *
 * [isAvailable] and [location] must answer without loading the library, so asking never fails.
 * [describe] may load it to report a version, so the welcome page never calls it for a library that
 * is there; [NativeLibraries.describe] catches whatever it throws.
 */
interface NativeCapability {
    /** The registry key, e.g. `manifold`. */
    val name: String

    /** A short human title, for the welcome page. */
    val title: String

    /** The welcome page's group heading, one of the [NativeArea] constants. */
    val area: String

    val isAvailable: Boolean

    /** Where it was found, as a path, without loading it; `null` when [isAvailable] is false. */
    val location: String?

    /** One line: where the library was found, or why not and how to get it. */
    fun describe(): String

    /** The docs page, relative to the docs root (e.g. `manifold.md`). */
    val docsPage: String

    /** The Gradle task that builds or fetches it, or an empty string when it is installed by a package manager. */
    val buildTask: String

    /**
     * Shell commands that install its prerequisites and build it, per OS (`macos`, `linux`,
     * `windows`). A line starting `#` is advice rather than a command. An empty list for an OS means
     * the library is not available there yet.
     */
    val install: Map<String, List<String>>
}

/** The group headings of the welcome page's Optional native libraries panel, in display order. */
object NativeArea {
    const val GEOMETRY = "Geometry"
    const val SOLIDS = "Solids and CAD"
    const val OPTIMIZATION = "Optimization"
    const val AUDIO = "Audio"
    const val GRAPHICS = "Graphics and video"
    const val LANGUAGES = "Languages"

    val order: List<String> = listOf(GEOMETRY, SOLIDS, OPTIMIZATION, AUDIO, GRAPHICS, LANGUAGES)
}

/** One library's state, as `GET /api/native-libraries-status` reports it to the welcome page. */
@Serializable
data class NativeLibraryStatus(
    val name: String,
    val title: String,
    val area: String,
    val available: Boolean,
    val description: String,
    val docsPage: String,
    val buildTask: String,
    val install: Map<String, List<String>>,
)

/**
 * Which of Field's optional native libraries are installed.
 *
 * ```
 * NativeLibraries.available("manifold")
 * NativeLibraries.describe()      // every library, one line each
 * ```
 *
 * Each library is loaded on first use by the code that needs it. Calling into an absent one throws
 * an error that says how to get it.
 */
object NativeLibraries {
    private val modules = linkedMapOf<String, () -> NativeCapability>()

    init {
        // Each entry names a library's locator object, which only knows where its file would be;
        // nothing is loaded until the library is used. Keep areas together, in NativeArea.order.
        register("morseSmale") { afield.geometry.topology.morsesmale.MorseSmaleLibrary }
        register("principalCurves") { afield.geometry.curves.principal.PrincipalCurvesLibrary }
        register("polyvector") { afield.geometry.vectorize.polyvector.PolyvectorLibrary }
        register("tetgen") { afield.geometry.mesh.tetgen.TetgenLibrary }
        register("manifold") { afield.geometry.solid.manifold.ManifoldLibrary }
        register("solidcraft") { afield.geometry.solid.solidcraft.SolidCraftLibrary }
        register("opennurbs") { afield.geometry.rhino.OpenNurbsLibrary }
        register("ceres") { afield.math.optimization.ceres.CeresNativeLibrary }
        register("csound") { afield.audio.csound.CsoundLibrary }
        register("libpd") { afield.audio.pd.PdLibrary }
        register("steamAudio") { afield.audio.spatial.steamaudio.SteamAudioNatives }
        register("loris") { afield.audio.analysis.loris.LorisNative }
        register("paint") { afield.graphics.paint.PaintLibrary }
        register("syphon") { afield.io.syphon.SyphonLibrary }
        register("lua") { afield.lang.lua.LuaLibrary }
    }

    /** Registers a library; the factory is evaluated only when asked. */
    fun register(name: String, capability: () -> NativeCapability) {
        modules[name] = capability
    }

    /** Library names Field knows about, installed or not. */
    val names: Set<String> get() = modules.keys

    /** The library registered as [name], or `null`. */
    fun capability(name: String): NativeCapability? = modules[name]?.invoke()

    fun available(name: String): Boolean = capability(name)?.let { c -> runCatching { c.isAvailable }.getOrDefault(false) } ?: false

    fun describe(name: String): String {
        val c = capability(name) ?: return "$name: not an optional native library (known: ${names.joinToString()})"
        return runCatching { c.describe() }.getOrElse { e -> "$name: cannot tell: ${e.message}" }
    }

    fun describe(): String = names.joinToString("\n") { describe(it) }

    /** Every library's state, for the welcome page, in [NativeArea.order]. Never throws. */
    fun status(): List<NativeLibraryStatus> = names.map { name ->
        val c = capability(name)!!
        val available = available(name)
        NativeLibraryStatus(
            name = c.name,
            title = c.title,
            area = c.area,
            available = available,
            // describe() of a library that is there may load it to read a version; the welcome
            // page asks on every load, so report the path instead and leave loading to its users.
            description = if (available) "found at ${runCatching { c.location }.getOrNull() ?: "an unreported path"}"
            else describe(name),
            docsPage = c.docsPage,
            buildTask = c.buildTask,
            install = c.install,
        )
    }.sortedBy { NativeArea.order.indexOf(it.area).let { i -> if (i < 0) Int.MAX_VALUE else i } }
}
