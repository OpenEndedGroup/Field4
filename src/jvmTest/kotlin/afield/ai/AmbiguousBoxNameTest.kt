package afield.ai

import afield.Box
import afield.SessionInformation
import afield.ai.core.AmbiguousBoxNameException
import afield.ai.core.Ai4BackdoorTools
import afield.ai.core.CompileKotlinRequest
import afield.ai.core.MacroRunRequest
import afield.ai.core.MacroRunResult
import afield.ai.core.uniqueBoxOrRefuse
import afield.needsResync
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A box *name* is not an identifier, and the agent tool path used to pretend it was.
 *
 * Field lets a person name two boxes `render`, and people do. Every agent tool that takes "a box"
 * takes a uid or a name, and on the name path `Ai4BackdoorTools`' `refResolver` and `Ai4Installer`'s
 * `resolveBoxByChatName` both resolved a duplicate with `firstOrNull` — the edit landed on whichever
 * box the document's `ConcurrentHashMap` happened to iterate first, and the agent was told it had
 * changed the box it asked for. Two boxes named `render`, `CompileKotlin` on `render`, and the source
 * of one of them is replaced; which one is not defined anywhere and is not stable.
 *
 * The same namespace's *canonical* implementation has always refused: `tools/field_mcp.py`'s
 * `_resolve_box` raises `"{n} boxes are named {token!r}; reference one by its uid instead."`, and
 * `tools/field_backdoor.py` raises its own ambiguity error. So the two halves of one API disagreed
 * about whether ambiguity was an error, and the half that said "no" was the half that could write.
 *
 * The sharpest evidence that this was an oversight rather than a decision is the *ordering* inside
 * `resolveBoxByChatName`: its weakest tier, a loose case-insensitive substring match, was correctly
 * guarded with `singleOrNull`, while the two strong tiers above it — exact label, then label ignoring
 * case — were `firstOrNull`. The guard was on the guess and not on the claim.
 *
 * # What is asserted
 *
 *  - The refusal happens **through the dispatch an agent actually calls**
 *    ([Ai4BackdoorTools.registry]`.executeByName`), not just in the resolver, because it is that
 *    dispatch which turns the exception into `ok=false` + message rather than a crashed session.
 *  - It happens **before anything is edited**, which is the whole point.
 *  - It does not fire for the cases that are genuinely unambiguous — a uid, and a name only one box
 *    has — because a refusal that also refuses the working case would just be an outage.
 *  - The wording is read out of `tools/field_mcp.py` rather than remembered, so the two paths cannot
 *    drift into giving different accounts of the same condition.
 *
 * Nothing here needs a network, a GPU, a runloop or a Kotlin compiler: the refusal is raised while
 * resolving the target, before any provider touches `Box.ki`.
 */
class AmbiguousBoxNameTest {

    private val temp = createTempDirectory("ambiguous-box-name").toFile()
    private val docPath = File(temp, "duplicates.field").absolutePath

    private val root = Box("root-uid")
    private val firstRender = Box("uid-first")
    private val secondRender = Box("uid-second")
    private val soleAnalysis = Box("uid-analysis")

    private val sessionInfo = SessionInformation(
        filename = docPath,
        root = root,
        boxes = ConcurrentHashMap(
            mapOf(
                firstRender.id to firstRender,
                secondRender.id to secondRender,
                soleAnalysis.id to soleAnalysis,
            )
        ),
    )

    init {
        root.name = "root"
        // The document a person really has: two boxes with one name, and one box with its own.
        firstRender.name = "render"
        secondRender.name = "render"
        soleAnalysis.name = "analysis"
        firstRender.code = "// first render, untouched"
        secondRender.code = "// second render, untouched"
        soleAnalysis.code = "// analysis, untouched"
    }

    private val ctx by lazy { Ai4BackdoorTools.buildContext(sessionInfo) }

    /** Setting a property feeds the process-wide resync queue; don't leave this test's boxes in it. */
    @AfterTest
    fun tearDown() {
        val mine = setOf(root, firstRender, secondRender, soleAnalysis)
        needsResync.removeAll { (box, _) -> box in mine }
        temp.deleteRecursively()
    }

    /** The message `tools/field_mcp.py` gives for this condition, with its format holes filled in. */
    private fun theWordingFieldMcpGives(count: Int, name: String): String {
        val py = File("tools/field_mcp.py")
        assertTrue(py.isFile, "expected ${py.absolutePath}; run from the repo root")
        val template = Regex("""\{len\(matches\)\} boxes are named \{token!r\}; [^"]+""")
            .find(py.readText())
            ?.value
            ?: error("field_mcp.py no longer raises an ambiguity message this test can read")
        return template
            .replace("{len(matches)}", "$count")
            // Python's {token!r} on an ordinary name is the name in single quotes.
            .replace("{token!r}", "'$name'")
    }

    // ------------------------------------------------- the tool refuses, and edits nothing

    /**
     * The bug, at the surface an agent touches. `CompileKotlinRequest(file = "render")` is a *write*:
     * it stages source into the named box. With two boxes named `render` there is no answer, so the
     * call fails and both boxes are left exactly as they were.
     */
    @Test
    fun aToolTargetingADuplicatedNameFailsInsteadOfEditingOneOfThem() {
        val refused = assertFailsWith<AmbiguousBoxNameException> {
            Ai4BackdoorTools.registry.executeByName(
                ctx,
                CompileKotlinRequest::class.java.simpleName,
                """{"file": "render", "source": "val poison = 1"}""",
            )
        }

        assertEquals("render", refused.name)
        assertEquals(2, refused.matchCount, "the count is part of the message an agent has to act on")
        assertEquals(
            theWordingFieldMcpGives(count = 2, name = "render"),
            refused.message,
            "the Kotlin and Python halves of this API must give one account of one condition",
        )

        assertEquals(
            "// first render, untouched",
            firstRender.code,
            "the first box the map iterates is the one the old firstOrNull would have written to",
        )
        assertEquals("// second render, untouched", secondRender.code)
    }

    /**
     * A macro run is the other reachable write, and the one that can rewrite a box wholesale, so it
     * refuses on the same terms rather than replaying over an arbitrary box.
     */
    @Test
    fun aMacroTargetingADuplicatedNameFailsBeforeItReplaysAnything() {
        val refused = assertFailsWith<AmbiguousBoxNameException> {
            Ai4BackdoorTools.registry.executeByName(
                ctx,
                MacroRunRequest::class.java.simpleName,
                """{"name": "any-macro", "targetBoxes": ["render"]}""",
            )
        }
        assertEquals(theWordingFieldMcpGives(count = 2, name = "render"), refused.message)
    }

    // ------------------------------------------------- and still resolves what is unambiguous

    /**
     * The other half of the claim: resolution still happens. A name only one box carries, and a bare
     * uid, both get through — the macro tool is reached and fails on its own terms (there is no macro
     * called `any-macro`), which is only observable if the *target* resolved first.
     *
     * Without this, the refusal above is indistinguishable from "the name path is broken".
     */
    @Test
    fun aNameOnlyOneBoxCarriesStillResolvesAndSoDoesAUid() {
        for (token in listOf("analysis", soleAnalysis.id)) {
            val result = Ai4BackdoorTools.registry.executeByName(
                ctx,
                MacroRunRequest::class.java.simpleName,
                """{"name": "any-macro", "targetBoxes": ["$token"]}""",
            ) as MacroRunResult
            assertTrue(
                !result.success,
                "there is no macro called any-macro, so this must fail — but on the macro, not the box",
            )
            assertTrue(
                "any-macro" in result.message,
                "the failure names the missing macro, i.e. resolution of '$token' got past the box: " +
                    result.message,
            )
        }
    }

    // ------------------------------------------------- the shared helper, directly

    /**
     * `uniqueBoxOrRefuse` is the one place the decision is made, and its three cases are the three
     * things every caller depends on. The de-duplication case is not hypothetical: the chat handler
     * searches `listOf(root) + boxes.values`, and a root that is also in the box map would otherwise
     * look like two boxes sharing a name.
     */
    @Test
    fun theSharedHelperRefusesOnlyGenuineDuplicates() {
        assertNull(uniqueBoxOrRefuse("render", emptyList()), "no match is null, which callers handle")
        assertSame(firstRender, uniqueBoxOrRefuse("render", listOf(firstRender)))
        assertSame(
            firstRender,
            uniqueBoxOrRefuse("render", listOf(firstRender, firstRender)),
            "the same box twice is one box, not an ambiguity",
        )

        val refused = assertFailsWith<AmbiguousBoxNameException> {
            uniqueBoxOrRefuse("render", listOf(firstRender, secondRender, soleAnalysis))
        }
        assertEquals(3, refused.matchCount, "the count is of matches, however they were gathered")
        assertEquals(theWordingFieldMcpGives(count = 3, name = "render"), refused.message)
    }
}
