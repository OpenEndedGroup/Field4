@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.mesh

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Shrinking an exported GLB with gltfpack, meshoptimizer's glTF optimiser.
 *
 * [Gltfpack] is the whole of it: one call per file, with the quantisation and meshopt-compression
 * settings the madp geometry toolbox exports with. It is an optional extra. Without a gltfpack on
 * disk [Gltfpack.available] is false and Field's GLB writers emit the file uncompressed, so code
 * that wants a smaller GLB only when one is cheap calls [Gltfpack.compressIfSmaller] and never
 * asks whether the tool is there.
 */

/**
 * gltfpack, meshoptimizer's glTF optimiser, for making an exported GLB small: it quantises and
 * meshopt-compresses the buffers (`-cc`), keeping node and material names (`-kn`, `-km`) — the settings
 * the madp geometry toolbox exports with.
 *
 * It is optional. `./gradlew prepareGltfpack` (or first-run setup's **gltfpack** extra) fetches Field's
 * pinned, checksummed build and records it in `build/gltfpack.path`; `AFIELD_GLTFPACK` names another one,
 * and a `gltfpack` on `PATH` is used last. Without any, [available] is false and the GLB writers
 * ([GeometryScene.writeGlb], the three viewer's **Export GLB**) write the GLB uncompressed.
 *
 * ```kotlin
 * val small = Gltfpack.compress(scene.toGlb())
 * ```
 *
 * A compressed GLB needs a loader that knows `EXT_meshopt_compression`: three.js (with its
 * `MeshoptDecoder`), Blender 4.2 and later, and most web viewers do.
 */
object Gltfpack {
    /** The arguments the toolbox's export uses. */
    val DEFAULT_ARGS: List<String> = listOf("-cc", "-kn", "-km")

    /** The gltfpack Field will run, or `null` when there is none. */
    fun executable(): File? {
        System.getenv("AFIELD_GLTFPACK")?.takeIf { it.isNotBlank() }?.let { return File(it).takeIf(File::canExecute) }
        File("build/gltfpack.path").takeIf(File::isFile)?.readText()?.trim()?.let { File(it) }
            ?.takeIf(File::canExecute)?.let { return it }
        val name = if (System.getProperty("os.name").startsWith("Windows")) "gltfpack.exe" else "gltfpack"
        return System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }
    }

    /** Whether a gltfpack is installed. */
    val available: Boolean get() = executable() != null

    /**
     * [glb] through gltfpack with [args]. Throws if there is no gltfpack (see [available]) or it fails,
     * with gltfpack's own message.
     */
    fun compress(glb: ByteArray, args: List<String> = DEFAULT_ARGS, timeoutSeconds: Long = 60): ByteArray {
        val exe = executable() ?: throw IllegalStateException(
            "gltfpack is not installed; run ./gradlew prepareGltfpack (or pick gltfpack in ./field.sh --setup)"
        )
        val dir = Files.createTempDirectory("gltfpack-").toFile()
        try {
            val input = File(dir, "input.glb").apply { writeBytes(glb) }
            val output = File(dir, "output.glb")
            val process = ProcessBuilder(listOf(exe.path, "-i", input.path, "-o", output.path) + args)
                .redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("gltfpack took longer than $timeoutSeconds s")
            }
            check(process.exitValue() == 0 && output.isFile) { "gltfpack failed: ${log.trim().ifEmpty { "exit ${process.exitValue()}" }}" }
            return output.readBytes()
        } finally {
            dir.deleteRecursively()
        }
    }

    /** [compress] when gltfpack is installed and makes [glb] smaller; otherwise [glb] as it was. */
    fun compressIfSmaller(glb: ByteArray, args: List<String> = DEFAULT_ARGS): ByteArray {
        if (!available) return glb
        val packed = runCatching { compress(glb, args) }.getOrNull() ?: return glb
        return if (packed.isNotEmpty() && packed.size < glb.size) packed else glb
    }
}
