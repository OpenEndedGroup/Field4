@file:afield.runtime.completion.CompletionCategory("Workspace")

package afield.runtime.lifecycle

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.named
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.files
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.audio.synth.*
import afield.document.workspace.Kits
import afield.document.workspace.absolutePath
import afield.document.workspace.file
import afield.document.workspace.path
import afield.study.build.classIdentity
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import kotlin.reflect.KClass

/**
 * [RuntimeIdentity] records which compiled classes and native libraries produced a result, as
 * hashes, and throws [RuntimeIdentityMismatch] from [RuntimeIdentity.verify] when a host has loaded different code.
 */

/** A native library's file name is its key in a [RuntimeIdentity]; [path] is where it was loaded from, kept for messages. */
data class NativeLibraryIdentity(val path: String, val sha256: String)

/** [RuntimeIdentity.verify] found code other than the pinned code; [differences] has one line per class or library. */
class RuntimeIdentityMismatch(message: String, val differences: List<String>) : IllegalStateException(message)

/**
 * Which compiled classes and native libraries a result was made with, as hashes, so a host that
 * loaded other code is refused instead of quietly producing something else.
 *
 * Pin the code that does the work once, when you have checked its results, and keep the pin with
 * the kit or the document (`files/runtime-identity.json`):
 *
 * ```kotlin
 * RuntimeIdentity.capture(listOf(Solid::class), natives = listOf(File(ManifoldLibrary.resolvedPath!!)))
 *     .write("runtime-identity.json".file)
 * ```
 *
 * and check it before building on that code:
 *
 * ```kotlin
 * RuntimeIdentity.verify("runtime-identity.json".file, natives = listOf(File(ManifoldLibrary.resolvedPath!!)))
 * ```
 *
 * A class's hash covers its bytecode and every class compiled inside it ([classIdentity]). A native
 * library is identified by its file name and the hash of its bytes, not by where it was loaded
 * from: the same library in another checkout or worktree is the same code. Name the classes that
 * do the work, not the whole provider: a pin over everything changes whenever anything does.
 */
data class RuntimeIdentity(val classes: Map<String, String>, val natives: Map<String, NativeLibraryIdentity> = emptyMap()) {
    /** Write this identity as JSON to [file]. */
    fun write(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this) + "\n")
        return file
    }

    /** One line per pinned class or library that [loaded] lacks or holds differently. */
    fun differences(loaded: RuntimeIdentity): List<String> {
        val classLines = classes.mapNotNull { (name, pinned) ->
            val now = loaded.classes[name]
            when {
                now == null -> "$name: pinned ${pinned.take(8)}, not loaded"
                now != pinned -> "$name: pinned ${pinned.take(8)}, loaded ${now.take(8)}"
                else -> null
            }
        }
        val nativeLines = natives.mapNotNull { (name, pinned) ->
            val now = loaded.natives[name]
            when {
                now == null -> "$name: pinned ${pinned.sha256.take(8)} (${pinned.path}), not given to verify"
                now.sha256 != pinned.sha256 -> "$name: pinned ${pinned.sha256.take(8)} (${pinned.path}), loaded ${now.sha256.take(8)} (${now.path})"
                else -> null
            }
        }
        return classLines + nativeLines
    }

    companion object {
        private val mapper = jacksonObjectMapper()

        /** The identity of [classes] as loaded here, and of the native library files [natives]. */
        fun capture(classes: Iterable<KClass<*>>, natives: Iterable<File> = emptyList()): RuntimeIdentity =
            RuntimeIdentity(classes.associate { it.java.name to classIdentity(it) }.toSortedMap(), nativeIdentities(natives))

        /** Read an identity [write] wrote. */
        fun read(file: File): RuntimeIdentity {
            require(file.isFile) { "No runtime identity at ${file.path}" }
            return mapper.readValue(file)
        }

        /**
         * Check that this host loaded the classes [pinned] names with the same code, and that the
         * library files [natives] (the ones this host actually loaded) match the pinned ones by name.
         * Returns what is loaded; throws [RuntimeIdentityMismatch] naming each difference otherwise.
         */
        fun verify(pinned: RuntimeIdentity, natives: Iterable<File> = emptyList(), pinnedIn: String = "the pinned identity"): RuntimeIdentity {
            val loader = Thread.currentThread().contextClassLoader ?: RuntimeIdentity::class.java.classLoader
            val loaded = RuntimeIdentity(pinned.classes.keys.mapNotNull { name ->
                runCatching { Class.forName(name, false, loader) }.getOrNull()?.let { name to classIdentity(it.kotlin) }
            }.toMap(), nativeIdentities(natives))
            val differences = pinned.differences(loaded)
            if (differences.isNotEmpty()) throw RuntimeIdentityMismatch(
                "This Field loaded different code from $pinnedIn:\n  " + differences.joinToString("\n  ") +
                    "\nStart Field from the code the identity was pinned with, or check what changed and pin it again " +
                    "with RuntimeIdentity.capture(...).write(file).", differences)
            return loaded
        }

        /** [verify] against the identity in [pinned]. */
        fun verify(pinned: File, natives: Iterable<File> = emptyList()): RuntimeIdentity = verify(read(pinned), natives, pinned.name)

        private fun nativeIdentities(natives: Iterable<File>): Map<String, NativeLibraryIdentity> {
            val result = sortedMapOf<String, NativeLibraryIdentity>()
            for (file in natives) {
                require(file.isFile) { "No native library at ${file.path}" }
                require(result.put(file.name, NativeLibraryIdentity(file.absolutePath, Kits.fileHash(file))) == null) {
                    "Two native libraries are named '${file.name}'"
                }
            }
            return result
        }
    }
}
