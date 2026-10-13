package afield.audio.csound

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every `data-lang=csound` fence in the docs plays, or fails the way it says it fails.
 *
 * The docs runner captures these fences too, but under `incremental` it reuses a committed image and
 * a fence edited into nonsense keeps its old picture. This test does what [CsoundExecution] does with
 * a box's text ([CsdDocument.parse], then a [CsoundInstance] of the orchestra and score), renders the
 * score offline and asks for sound:
 *
 * - a fence without `expect-error=true` must compile and make a sample louder than -60 dBFS;
 * - a fence with it must be refused, with the problem placed on an orchestra line.
 *
 * Without a built libcsound it prints SKIPPED, as every Csound test does; the fence count is checked
 * either way, so a page that stops declaring its fences is caught on any machine.
 */
class CsoundDocsFencesTest {

    data class Fence(val page: String, val line: Int, val attrs: String, val body: String) {
        val expectsError get() = Regex("""\bexpect-error=(true|"true")""").containsMatchIn(attrs)
        override fun toString() = "$page:$line"
    }

    private val pages = listOf("public/docs/csound.md")

    private fun fences(): List<Fence> = pages.flatMap { page ->
        val lines = File(page).readLines()
        val out = ArrayList<Fence>()
        var i = 0
        while (i < lines.size) {
            val open = lines[i]
            if (open.startsWith("```") && Regex("""data-lang=("?)csound\1[\s}]""").containsMatchIn(open)) {
                val end = (i + 1 until lines.size).firstOrNull { lines[it].startsWith("```") }
                    ?: fail("$page:${i + 1} csound fence never closes")
                out += Fence(page, i + 1, open, lines.subList(i + 1, end).joinToString("\n"))
                i = end
            }
            i++
        }
        out
    }

    @Test fun thePageStillDeclaresItsCsoundFences() {
        val all = fences()
        assertTrue(all.count { !it.expectsError } >= 1, "csound.md should have a playing csound fence; found $all")
        assertTrue(all.count { it.expectsError } >= 1, "csound.md should have an expect-error csound fence; found $all")
    }

    @Test fun everyCsoundFencePlaysOrFailsAsDeclared() {
        if (!CsoundLibrary.isAvailable) {
            println("SKIPPED Csound docs fences: ${CsoundLibrary.describe()}")
            return
        }
        val problems = ArrayList<String>()
        for (fence in fences()) {
            val doc = CsdDocument.parse(fence.body)
            val flags = doc.optionFlags.filterNot { f -> CsoundExecution.DEVICE_FLAGS.any { f.startsWith(it) } }
            val score = doc.score?.body?.takeIf { it.isNotBlank() } ?: "f 0 1"
            val outcome = try {
                CsoundInstance(doc.orchestra.body, score, options = flags).use { cs ->
                    val (left, right) = cs.renderOffline(maxSeconds = 20.0)
                    val peak = maxOf(left.maxOfOrNull { abs(it) } ?: 0f, right.maxOfOrNull { abs(it) } ?: 0f)
                    Result.success(peak)
                }
            } catch (e: CsoundCompileError) {
                Result.failure(e)
            }
            if (fence.expectsError) {
                val e = outcome.exceptionOrNull() as? CsoundCompileError
                if (e == null) problems += "$fence is expect-error=true but Csound accepted it"
                else if (e.problems.none { it.line > 0 }) problems += "$fence was refused, but no problem names an orchestra line: ${e.problems}"
            } else {
                outcome.fold(
                    onSuccess = { peak -> if (peak < 1e-3f) problems += "$fence compiled but was silent (peak $peak)" },
                    onFailure = { e -> problems += "$fence does not compile: ${(e as CsoundCompileError).problems.joinToString("; ") { "line ${it.line}: ${it.message}" }}" },
                )
            }
            println("csound fence $fence: " + (outcome.exceptionOrNull()?.let { "refused" } ?: "peak ${outcome.getOrNull()}"))
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
