package afield.stacksl.cpu

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A characterisation fence on the `Float` path, hashed over raw floats rather than over pixels.
 *
 * The gallery test next door measures the CPU backend against the GPU in **8-bit levels**, which is
 * the right unit for that question and the wrong unit for this one: a refactor can move an
 * intermediate by one ulp, leave all 31 quantised images identical, and still have changed what
 * `GlslValue` promises to be. So this hashes the `CpuRaster.rgba` **bit pattern** of every gallery
 * program, which is exactly the thing
 * [GlslValue]'s file KDoc argues must not move — widening an intermediate would make the CPU result
 * *more* accurate than the shader and therefore a worse reference, and `fract` of a large product
 * amplifies a last-bit difference to O(1).
 *
 * Fenced 2026-09-16 at `cb4377a6c`, before the interpreter was made generic in its scalar
 * ([GlslScalars]). Every one of the 31 hashes below was produced by the pre-refactor code; the
 * refactor's claim is that it changed no arithmetic, and this is where that claim is measured
 * instead of asserted.
 *
 * 64 square, not 512: the question is bit-identity, and a one-ulp divergence shows up in a 64x64
 * raster as surely as in a 512x512 one while costing 64 times less. (Checked, not assumed: with
 * `GlslMath.mix` widened to compute in `Double` and narrow once — the smallest realistic refactor
 * accident, and invisible in 8-bit levels — this test reports **19 of 31** programs moved at 64
 * square.)
 *
 * To re-fence after a *deliberate* change to the Float path, run with `-Dafield.stacksl.manifest.write=true`
 * and commit the rewritten file with the reason in the commit message.
 */
class StackSLCpuFloatPathManifestTest {

    private val root = File(System.getProperty("user.dir"))
    private val manifestFile = File(root, MANIFEST_PATH)

    @Test
    fun `every gallery program renders to the committed float bits`() {
        val entries = StackSLCpuGallery.load(root)
        assertTrue(entries.size >= POPULATION, "gallery shrank: ${entries.size} entries, expected >= $POPULATION")

        val measured = entries.associate { entry ->
            entry.name to sha256(StackSLCpuGallery.render(entry, DIM).rgba)
        }

        if (System.getProperty("afield.stacksl.manifest.write") == "true") {
            manifestFile.parentFile.mkdirs()
            manifestFile.writeText(
                buildString {
                    appendLine("# StackSL CPU Float-path raster hashes: SHA-256 over CpuRaster.rgba's")
                    appendLine("# little-endian float bits, rendered at ${DIM}x$DIM through each entry's")
                    appendLine("# committed fragment template. Regenerate with -Dafield.stacksl.manifest.write=true.")
                    measured.toSortedMap().forEach { (name, hash) -> appendLine("$name $hash") }
                }
            )
            error("manifest rewritten to ${manifestFile.absolutePath} — this run does not assert")
        }

        assertTrue(manifestFile.exists(), "no committed manifest at ${manifestFile.absolutePath}")
        val expected = manifestFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.substringBefore(' ') to line.substringAfter(' ').trim() }

        assertEquals(POPULATION, expected.size, "manifest should hold $POPULATION entries")

        val drifted = expected.keys.filter { name -> measured[name] != null && measured[name] != expected[name] }
        assertTrue(
            drifted.isEmpty(),
            "the Float path moved for ${drifted.size} of ${expected.size} gallery programs: " +
                drifted.joinToString(", ") + " — every one of these is a raster whose float bits " +
                "differ from the pre-refactor bytes"
        )
        val missing = expected.keys - measured.keys
        assertTrue(missing.isEmpty(), "gallery no longer has: $missing")
    }

    private fun sha256(values: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4)
        values.forEach { v ->
            val bits = v.toRawBits()
            bytes[0] = (bits and 0xff).toByte()
            bytes[1] = ((bits shr 8) and 0xff).toByte()
            bytes[2] = ((bits shr 16) and 0xff).toByte()
            bytes[3] = ((bits shr 24) and 0xff).toByte()
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MANIFEST_PATH = "src/jvmTest/resources/stacksl/float_path_manifest.txt"
        const val DIM = 64

        /** The gallery had 31 entries when this fence went in. */
        const val POPULATION = 31
    }
}
