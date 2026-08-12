package afield.ai

import afield.ai.core.AgentToolExtensions
import afield.ai.core.CarriesToolImages
import afield.ai.core.SummarisesForToolLog
import afield.four.modules.GeometryAgentTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the two pages that document the AI tool-authoring seam to the code they document.
 *
 * # What this is modelled on, and where it stops
 *
 * `afield.brief.BriefDocsTest` is the in-repo pattern: read the markdown off disk at test time,
 * scrape its examples with a regex, and run them through the real front end. That works there
 * because Brief has a front end a test can call. Kotlin does not — there is no compile harness in the
 * tree that a unit test can hand a snippet to — so this does the strongest thing that is available
 * without one, and says so rather than implying more:
 *
 *  1. **Every API call the pages make is real.** Each `AgentToolExtensions.foo(` and
 *     `GeometryAgentTools.foo(` in the prose or the fences is checked against the declared members by
 *     reflection. A renamed or deleted method fails here, which is the failure mode a docs page
 *     actually suffers.
 *  2. **The schema the page prints is the schema the code emits.** The JSON block in
 *     `aiFeatures.md` is compared against the committed evidence, which
 *     `GeometryAgentToolsTest.theCommittedSchemaEvidenceIsWhatDescribeActuallyEmits` in turn compares
 *     against a live `describe()`. So the chain page -> evidence -> code is closed.
 *  3. **Every number the page quotes is a number the tool produced**, read out of the committed
 *     result JSON rather than out of somebody's memory of a run.
 *  4. **Every file path the dev doc's table names exists.** That table is the entry point for a
 *     reader, and a dead path in it is worse than no table.
 *
 * What it does **not** do: compile the Kotlin. A snippet can name only real methods and still not
 * compile. That gap is stated in the dev doc's "What is not covered" section rather than papered over.
 */
class AiToolAuthoringDocsTest {

    private val publicPage = File("public/docs/aiFeatures.md")
    private val devDoc = File("dev_docs/ai/AGENT_TOOL_EXTENSIONS.md")
    private val evidenceDir = File("public/docs/assets/ai_tool_demo")

    private fun page() = publicPage.readText()
    private fun dev() = devDoc.readText()

    // ------------------------------------------------------------------ the section exists

    @Test
    fun theToolAuthoringSectionIsOnThePageAndTheDevDocIsThere() {
        assertTrue(publicPage.isFile, "expected ${publicPage.absolutePath}")
        assertTrue(devDoc.isFile, "expected ${devDoc.absolutePath}")

        val text = page()
        assertTrue(
            "## Give the AI a tool of your own" in text,
            "the public page must carry the tool-authoring section",
        )
        assertTrue(
            "dev_docs/ai/AGENT_TOOL_EXTENSIONS.md" in text,
            "and must point a reader at the design notes",
        )
        assertTrue(
            "aiFeatures.md" in dev(),
            "and the design notes must point back at the page",
        )
    }

    // ------------------------------------------------------------------ the API is real

    /**
     * Every `Object.method(` the two pages name has to be a declared member of that object.
     *
     * This is the cheap half of "does the example still work", and it is the half that catches what
     * actually goes wrong: a method renamed in a refactor while the page keeps the old spelling. The
     * regex deliberately matches prose as well as fences, because the prose names methods too.
     */
    @Test
    fun everyApiCallOnBothPagesNamesARealMember() {
        val objects = mapOf(
            "AgentToolExtensions" to AgentToolExtensions::class,
            "GeometryAgentTools" to GeometryAgentTools::class,
        )
        // `internal` members are name-mangled on the JVM — `applyTo` is `applyTo$afield_afield` — and
        // the dev doc names `applyTo` because that is what it is called in the source a reader will
        // open. Compare on the part before the mangling suffix.
        val declared = objects.mapValues { (_, kclass) ->
            (kclass.java.methods.map { it.name } + kclass.java.declaredFields.map { it.name })
                .map { it.substringBefore('$') }
                .toSet()
        }

        val calls = Regex("""\b(AgentToolExtensions|GeometryAgentTools)\.([A-Za-z][A-Za-z0-9]*)\s*\(""")
        val seen = mutableSetOf<Pair<String, String>>()
        for (source in listOf(publicPage to page(), devDoc to dev())) {
            for (match in calls.findAll(source.second)) {
                val (owner, member) = match.groupValues[1] to match.groupValues[2]
                seen += owner to member
                assertTrue(
                    member in declared.getValue(owner),
                    "${source.first.path} calls $owner.$member(...), which is not a member of $owner. " +
                        "Members: ${declared.getValue(owner).sorted()}",
                )
            }
        }

        // A floor, so that gutting the examples cannot make this test vacuously pass.
        assertTrue(seen.size >= 8, "expected the pages to exercise at least 8 distinct calls, saw $seen")
        assertTrue("AgentToolExtensions" to "register" in seen, "the central call must appear")
        assertTrue("AgentToolExtensions" to "execute" in seen, "and the no-model path must be shown")
    }

    /** The two result interfaces the pages tell people to implement, and the members they name. */
    @Test
    fun theTwoResultInterfacesThePagesRecommendExistWithTheNamedMembers() {
        val text = page() + dev()
        assertTrue("SummarisesForToolLog" in text && "CarriesToolImages" in text)

        assertEquals(
            "toolLogSummary",
            SummarisesForToolLog::class.java.methods.single().name,
            "the page tells people to override toolLogSummary()",
        )
        assertTrue(
            CarriesToolImages::class.java.methods.any { it.name == "getToolImages" },
            "the page tells people to override toolImages",
        )
        assertTrue(
            "@get:JsonIgnore" in text,
            "and both pages must explain the one thing an implementor has to get right",
        )
    }

    // ------------------------------------------------------------------ the schema on the page

    /**
     * The JSON block the page prints for `InspectGeometryFile` must be the committed descriptor.
     *
     * Compared as parsed JSON rather than as text, so reformatting the block for the page's width is
     * allowed and changing a description is not.
     */
    @Test
    fun theSchemaPrintedOnThePageIsTheCommittedDescriptor() {
        val block = Regex("""```diffstatic \{data-lang=json}\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
            .find(page())
            ?.groupValues?.get(1)
            ?: error("expected a json fence on ${publicPage.path} carrying the derived schema")

        val onPage = Json.parseToJsonElement(block).jsonObject
        val committed = Json
            .parseToJsonElement(File(evidenceDir, "geometry_tool_schemas.json").readText())
            .jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == "InspectGeometryFile" }

        assertEquals(
            committed.toString(),
            onPage.toString(),
            "the schema on ${publicPage.path} has drifted from public/docs/assets/ai_tool_demo/" +
                "geometry_tool_schemas.json. Regenerate with ./gradlew runAgentToolDemo, then copy " +
                "the InspectGeometryFile object onto the page.",
        )
    }

    // ------------------------------------------------------------------ the numbers and the picture

    /** Every figure the page quotes about the sample has to be a figure the tool actually returned. */
    @Test
    fun theNumbersThePageQuotesComeFromTheCommittedRun() {
        val result = Json
            .parseToJsonElement(File(evidenceDir, "toycar_inspect_result.json").readText())
            .jsonObject
        val text = page()

        assertEquals(77429, result["vertexCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(108936, result["triangleCount"]!!.jsonPrimitive.content.toInt())
        assertTrue("77429" in text, "the page quotes the vertex count")
        assertTrue("108936" in text, "the page quotes the triangle count")

        val extent = result["extent"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }
        val quoted = listOf("0.073", "0.029", "0.074")
        for ((axis, value) in quoted.zip(extent)) {
            assertEquals(
                axis,
                "%.3f".format(value),
                "the page quotes an extent of ${quoted.joinToString(" x ")}; the run says $extent",
            )
            assertTrue(axis in text, "the page must actually contain $axis")
        }
        assertTrue("metres" in text, "and must say what the units are — the file is in metres")
    }

    /** The rendered image the page shows has to be there, and has to be a PNG. */
    @Test
    fun theImageThePageShowsIsACommittedPng() {
        assertTrue(
            "assets/ai_tool_demo/toycar_inspect.png" in page(),
            "the page must show the picture the tool hands the model",
        )
        val png = File(evidenceDir, "toycar_inspect.png")
        assertTrue(png.isFile, "expected ${png.absolutePath}")
        assertEquals(
            listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
            png.readBytes().take(4),
            "PNG magic",
        )
        assertTrue(png.length() > 20_000, "a 640x480 render of 108,936 triangles; was ${png.length()} bytes")
    }

    // ------------------------------------------------------------------ the dev doc's table

    /**
     * Every repository path the dev doc names has to exist.
     *
     * The table at the top of that page is how a reader gets from the prose to the code, and a table
     * of dead links is worse than no table. A trailing `:17` line reference is stripped before the
     * check — the line number is allowed to drift, the file is not allowed to vanish.
     *
     * Anything under `src/personal` is excluded, and that is the point rather than a concession: the
     * house cluster the dev doc describes as its motivating story is gitignored code that is *not* in
     * this repository, the page says so, and a reader on a clean checkout will not have it either.
     */
    @Test
    fun everyPathTheDevDocNamesExists() {
        val paths = Regex("""`((?:src|public|dev_docs|tools|build)[A-Za-z0-9_./-]*\.[A-Za-z0-9]+)(?::\d+)?`""")
            .findAll(dev())
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(paths.size >= 10, "expected the dev doc to name at least 10 files, found $paths")
        val personal = paths.filter { it.startsWith("src/personal/") }
        assertTrue(
            personal.isEmpty() || "not in this repository" in dev(),
            "the dev doc names $personal, which a clean checkout does not have, without saying so",
        )
        val missing = (paths - personal.toSet()).filterNot { File(it).exists() }
        assertEquals(emptyList(), missing, "dead paths in ${devDoc.path}")
    }

    // ------------------------------------------------------------------ the loadable demo

    /**
     * The demo document is the thing the page tells a reader to open, so it has to be there, be two
     * boxes, and use the API the page just taught them — not some older spelling of it.
     */
    @Test
    fun theLoadableDemoIsThereAndUsesTheDocumentedApi() {
        val edn = File(evidenceDir, "metadata.edn")
        assertTrue(edn.isFile, "expected ${edn.absolutePath}")
        assertTrue(
            "assets/ai_tool_demo/metadata.edn" in page(),
            "the page must tell the reader where the loadable demo is",
        )

        val text = edn.readText()
        assertEquals(2, Regex(""":id "ai-tool-demo-""").findAll(text).count(), "two boxes")
        for (needle in listOf(
            "AgentToolExtensions.register",
            "AgentToolExtensions.execute",
            "AgentToolExtensions.describe",
            "GeometryAgentTools.addSearchRoot",
            "SummarisesForToolLog",
            "CarriesToolImages",
        )) {
            assertTrue(needle in text, "the demo document should exercise $needle")
        }
        assertTrue(
            "JsonPropertyDescription" in text,
            "including the annotation that turns a parameter into a documented one",
        )
    }
}
