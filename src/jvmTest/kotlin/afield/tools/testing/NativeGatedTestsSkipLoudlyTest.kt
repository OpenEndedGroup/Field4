package afield.tools.testing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A test gated on an optional native library must report SKIPPED when the library is missing.
 *
 * The old idiom, `if (!available()) return`, made every such test a silent pass on a machine
 * without the library, which includes the lander. A red paint test sat on `main` unnoticed that
 * way. The replacement is a JUnit assumption (`Assume.assumeTrue`, usually through a file-local
 * `assumeAvailable()` or a shared helper such as `requirePaintLibrary()`), which Gradle counts as
 * skipped. This scans both JVM test source sets for the old early return.
 */
class NativeGatedTestsSkipLoudlyTest {
    @Test
    fun noTestReturnsEarlyWhenItsNativeLibraryIsMissing() {
        val roots = listOf("src/jvmTest/kotlin", "src/jvmFastTest/kotlin").map(::File)
        assertTrue(roots[0].isDirectory, "run from the repository root: ${roots[0].absolutePath}")
        val earlyReturn = Regex("""^\s*if\s*\(\s*!\s*available\(\)\s*\)\s*return\b""")
        val offenders = roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if (earlyReturn.containsMatchIn(line)) "${f.path}:${i + 1}: ${line.trim()}" else null
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "use Assume.assumeTrue(...) so the test is reported SKIPPED, not passed:\n" + offenders.joinToString("\n"),
        )
    }
}
