package afield.geometry.network

/**
 * One optional native geometry library, as [NativeGeometry] sees it. Implementations must answer
 * without loading the library's symbols, so asking never fails.
 */
interface NativeGeometryCapability {
    val name: String
    val isAvailable: Boolean

    /** One line: where the library was found, or why not and how to build it. */
    fun describe(): String
}

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
    private val modules = linkedMapOf<String, () -> NativeGeometryCapability>()

    init {
        // Each entry names a module's library object, which only knows where its file would be;
        // nothing is loaded until a module is used.
        register("morseSmale") { afield.geometry.topology.morsesmale.MorseSmaleLibrary }
    }

    /** Called by each module's library object; the factory is evaluated only when asked. */
    fun register(name: String, capability: () -> NativeGeometryCapability) {
        modules[name] = capability
    }

    /** Module names Field knows about, installed or not. */
    val names: Set<String> get() = modules.keys

    fun available(name: String): Boolean = modules[name]?.invoke()?.isAvailable ?: false

    fun describe(name: String): String =
        modules[name]?.invoke()?.describe() ?: "$name: not a native geometry module (known: ${names.joinToString()})"

    fun describe(): String = names.joinToString("\n") { describe(it) }
}
