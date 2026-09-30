package afield.docs

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [BoxOutputChannelGuard]'s one real decision: whether a channel written twice at
 * brace depth 0 lost a figure, or was being animated the whole time.
 *
 * The guard shipped without tests, and on 2026-09-19 it reported the `confluence_grid` demo — an
 * establish-animate-settle animation — as a lost figure, which is what prompted these. The cases
 * below pin both directions, because the fix for that false positive is exactly the kind that can
 * quietly cost the guard its actual catch.
 */
class BoxOutputChannelGuardTest {

    /** A repo shaped the way [BoxOutputChannelGuard.audit] walks it, holding one demo box. */
    private fun repoWithDemo(boxCode: String): File {
        val repo = Files.createTempDirectory("box-output-channel-guard").toFile()
        // audit() refuses to run with no helper table, and reports a missing transcript corpus.
        File(repo, "src/jvmMain/kotlin").mkdirs()
        File(repo, "dev_docs/agents/transcripts").mkdirs()
        val demo = File(repo, "dev_docs/agents/demos/a_demo").apply { mkdirs() }
        File(repo, "src/jvmMain/kotlin/FakeHelpers.kt")
            .writeText("fun Box.showThing(channel: String = \"thing\", value: Int) {\n}\n")
        File(demo, "box_code_.kt").writeText(boxCode)
        return repo
    }

    @Test
    fun twoStaticFiguresOnOneChannelAreReported() {
        val repo = repoWithDemo(
            """
            display("figure", first())
            display("figure", second())
            """.trimIndent()
        )
        val problems = BoxOutputChannelGuard.audit(repo).problems
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("'figure'"), problems.single())
    }

    @Test
    fun twoFiguresOnTheirOwnChannelsAreClean() {
        val repo = repoWithDemo(
            """
            display("first", one())
            display("second", two())
            """.trimIndent()
        )
        assertEquals(emptyList(), BoxOutputChannelGuard.audit(repo).problems)
    }

    /**
     * The `confluence_grid` shape: one write before the loop so the opening state is on screen, one
     * per iteration inside it, one after so the settled state is not an iteration stale. Three
     * writes, one animation, no lost figure.
     */
    @Test
    fun theEstablishAnimateSettleShapeIsNotReported() {
        val repo = repoWithDemo(
            """
            display("scene", geometry())
            Thread.sleep(300)
            while (frame < 2600) {
                frame++
                display("scene", geometry())
            }
            display("scene", geometry())
            """.trimIndent()
        )
        assertEquals(emptyList(), BoxOutputChannelGuard.audit(repo).problems)
    }

    /**
     * The exemption is keyed on the channel, not on the box. A box that animates one channel must
     * still be told that it threw away a figure on a different one — otherwise a single `animate`
     * block anywhere in a box would switch the whole guard off for it.
     */
    @Test
    fun aStaticCollisionIsStillReportedAlongsideAnAnimatedChannel() {
        val repo = repoWithDemo(
            """
            display("scene", geometry())
            while (frame < 10) {
                display("scene", geometry())
            }
            display("scene", geometry())
            display("legend", legendOne())
            display("legend", legendTwo())
            """.trimIndent()
        )
        val problems = BoxOutputChannelGuard.audit(repo).problems
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("'legend'"), problems.single())
    }

    /** A repo whose transcript corpus holds one Lua box, and no demo boxes. */
    private fun repoWithLuaTranscriptBox(luaCode: String): File {
        val repo = repoWithDemo("display(\"only\", one())")
        val body = luaCode.lines().joinToString("\n") { "          $it" }
        File(repo, "dev_docs/agents/transcripts/lua_fixture.yaml").writeText(
            "- create:\n" +
                "    box: lua_box\n" +
                "    properties:\n" +
                "      code_language: Lua\n" +
                "      code: |\n" +
                body + "\n"
        )
        return repo
    }

    /**
     * 2026-09-30: `lua_demo`'s `-- lines(...) draws them in the box's output` comment was read as
     * a second `lines()` call, because comments were masked only as Kotlin `//`. A Lua box's `--`
     * comment names a helper without calling it.
     */
    @Test
    fun aLuaCommentThatNamesAHelperIsNotACall() {
        val repo = repoWithLuaTranscriptBox(
            """
            -- showThing(...) puts the value in the box's output.
            showThing(1)
            """.trimIndent()
        )
        assertEquals(emptyList(), BoxOutputChannelGuard.audit(repo).problems)
    }

    /** The counterfactual: masking Lua comments must not cost the guard a real Lua collision. */
    @Test
    fun twoRealLuaCallsOnOneChannelAreStillReported() {
        val repo = repoWithLuaTranscriptBox(
            """
            showThing(1) -- the first figure
            local s = "not -- a comment"
            showThing(2)
            """.trimIndent()
        )
        val problems = BoxOutputChannelGuard.audit(repo).problems
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("lua_fixture.yaml#lua_box") && problems.single().contains("'thing'"), problems.single())
    }
}
