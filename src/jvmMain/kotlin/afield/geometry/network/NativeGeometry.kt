/**
 * Which of Field's optional native geometry libraries are installed.
 *
 * Morse–Smale complexes, principal curves, polyvector vectorization and TetGen meshing each need a
 * native library that Field does not build by default. Each module's library object is a
 * [NativeGeometryCapability], a [NativeCapability] in the [NativeArea.GEOMETRY] area, registered with
 * [NativeLibraries] beside Field's other optional native libraries. [NativeGeometry] is the geometry
 * slice of that registry: [NativeGeometry.available] and [NativeGeometry.describe] answer without
 * loading any of them, so asking never fails. Field runs without any of them, and calling into one
 * that is absent throws an error naming the Gradle task that builds it.
 *
 * [NativeGeometry.status] reports every geometry module as a [NativeGeometryStatus], which
 * `GET /api/native-geometry-status` still serves; the welcome page reads every library from
 * `GET /api/native-libraries-status` instead.
 */

package afield.geometry.network

import afield.runtime.NativeArea
import afield.runtime.NativeCapability
import afield.runtime.NativeLibraries
import kotlinx.serialization.Serializable

/**
 * One optional native geometry library: a [NativeCapability] whose [area] is [NativeArea.GEOMETRY].
 * Implementations must answer [isAvailable] without loading the library's symbols, so asking never
 * fails.
 */
interface NativeGeometryCapability : NativeCapability {
    override val area: String get() = NativeArea.GEOMETRY
}

/** One library's state, as `GET /api/native-geometry-status` reports it. */
@Serializable
data class NativeGeometryStatus(
    val name: String,
    val title: String,
    val available: Boolean,
    val description: String,
    val docsPage: String,
    val buildTask: String,
    val install: Map<String, List<String>>,
)

/**
 * Which of Field's optional geometry libraries are installed.
 *
 * ```
 * NativeGeometry.available("morseSmale")
 * NativeGeometry.describe()      // every module, one line each
 * ```
 *
 * Each module is a separate native library loaded on first use. Field builds and runs without any
 * of them; calling an absent one throws an error that says how to build it.
 */
object NativeGeometry {
    /** Registers a geometry module with [NativeLibraries]; the factory is evaluated only when asked. */
    fun register(name: String, capability: () -> NativeGeometryCapability) {
        NativeLibraries.register(name, capability)
    }

    /** Module names Field knows about, installed or not. */
    val names: Set<String>
        get() = NativeLibraries.names.filterTo(linkedSetOf()) { NativeLibraries.capability(it) is NativeGeometryCapability }

    fun available(name: String): Boolean = name in names && NativeLibraries.available(name)

    fun describe(name: String): String =
        if (name in names) NativeLibraries.describe(name)
        else "$name: not a native geometry module (known: ${names.joinToString()})"

    fun describe(): String = names.joinToString("\n") { describe(it) }

    /** Every module's state. Never throws: a broken library describes itself. */
    fun status(): List<NativeGeometryStatus> = NativeLibraries.status().filter { it.name in names }.map {
        NativeGeometryStatus(it.name, it.title, it.available, it.description, it.docsPage, it.buildTask, it.install)
    }
}
