package afield.graphics.paint

import org.junit.Assume
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every paint test that needs the native paint library starts with this. Without the dylib (the
 * lander, a fresh checkout) the test is reported SKIPPED with the reason, rather than returning
 * early and counting as a pass.
 */
internal fun requirePaintLibrary() {
    Assume.assumeTrue("native paint library not available: ${PaintLibrary.describe()}", PaintLibrary.isAvailable)
}

/** Keeps a new paint test from regressing to the old `if (!available()) return` vacuous pass. */
class PaintTestsSkipLoudlyTest {
    @Test
    fun paintTestsDoNotReturnEarlyWhenTheLibraryIsMissing() {
        val dir = File("src/jvmTest/kotlin/afield/graphics/paint")
        assertTrue(dir.isDirectory, "run from the repository root: ${dir.absolutePath}")
        val offenders = dir.listFiles { f -> f.name.endsWith(".kt") && f.name != "PaintTestSupport.kt" }!!
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if (line.contains("PaintLibrary.isAvailable") || Regex("""if\s*\(\s*!\s*available\(\)\s*\)\s*return""").containsMatchIn(line))
                        "${f.name}:${i + 1}: ${line.trim()}" else null
                }
            }
        assertTrue(offenders.isEmpty(), "paint tests must call requirePaintLibrary() instead of checking PaintLibrary.isAvailable themselves:\n" + offenders.joinToString("\n"))
    }
}
