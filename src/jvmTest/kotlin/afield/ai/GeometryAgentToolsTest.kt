package afield.ai

import afield.ai.core.AgentToolExtensions
import afield.ai.core.ToolExecutionContext
import afield.four.modules.GeometryAgentTools
import afield.four.modules.InspectGeometryFileResult
import afield.four.modules.ListGeometryFilesResult
import afield.stdlib.icosphereMesh
import afield.stdlib.saveObj
import afield.stdlib.saveStl
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `afield.four.modules.GeometryAgentTools` driven the way a model drives it — by name, with a JSON
 * argument string, through `AgentToolExtensions.execute` — and with no language model anywhere.
 *
 * # Why this is worth a test file of its own
 *
 * `AgentToolExtensionsTest` checks the *seam*. This checks that an actual tool set built on the seam
 * behaves, which is a different claim and the one a reader copying the example depends on. Everything
 * here is the production path: `execute` is what the MCP backdoor calls and is one Jackson call away
 * from what the OpenAI bridge calls, so a request class whose defaults do not survive deserialization
 * fails here rather than in somebody's conversation.
 *
 * It also pins the numbers `public/docs/aiFeatures.md` prints. A docs page that quotes a count nothing
 * checks is how docs pages rot.
 *
 * Every file is written to a temp directory this test deletes. Nothing is written into the repository.
 */
class GeometryAgentToolsTest {

    private val ctx = ToolExecutionContext()
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private lateinit var dir: File
    private lateinit var ball: File

    /**
     * The same icosphere `MeshFromDiskTest` uses, for the same reason: its counts are quoted in a docs
     * page, so two tests disagreeing about them is a signal rather than an annoyance.
     */
    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("afield-geometry-agent-tools").toFile()
        val mesh = icosphereMesh(3, 1.0).withColor(0.3, 0.7, 1.0)
        ball = File(dir, "ball.obj").also { mesh.saveObj(it) }
        File(dir, "ball.stl").also { mesh.saveStl(it) }
        File(dir, "notes.txt").writeText("not geometry")
        File(dir, "nested").mkdirs()
        File(dir, "nested/small.obj").also { icosphereMesh(1, 0.5).saveObj(it) }

        GeometryAgentTools.clearSearchRoots()
        GeometryAgentTools.register()
    }

    @AfterTest
    fun tearDown() {
        GeometryAgentTools.unregister()
        GeometryAgentTools.clearSearchRoots()
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------- the gate

    /**
     * With no search root there is nothing to look at, so the tools are not offered at all — they are
     * absent from the schema list rather than present and failing. Adding a root opens both together.
     */
    @Test
    fun bothToolsAreGatedOnHavingSomewhereToLook() {
        assertFalse(GeometryAgentTools.enabled(), "no roots yet")
        assertFalse(AgentToolExtensions.isAvailable(ctx, "ListGeometryFiles"))
        assertFalse(AgentToolExtensions.isAvailable(ctx, "InspectGeometryFile"))
        assertEquals(
            emptyList(),
            AgentToolExtensions.availableToolNames(ctx).filter { it.endsWith("GeometryFile") || it.endsWith("GeometryFiles") },
        )
        assertFailsWith<IllegalStateException> {
            AgentToolExtensions.execute(ctx, "ListGeometryFiles", "{}")
        }

        GeometryAgentTools.addSearchRoot(dir)

        assertTrue(GeometryAgentTools.enabled())
        assertEquals(
            listOf("InspectGeometryFile", "ListGeometryFiles"),
            AgentToolExtensions.availableToolNames(ctx)
                .filter { it == "InspectGeometryFile" || it == "ListGeometryFiles" },
            "both, and sorted",
        )

        GeometryAgentTools.clearSearchRoots()
        assertFalse(AgentToolExtensions.isAvailable(ctx, "InspectGeometryFile"), "and the gate shuts again")
    }

    /** Adding the same root twice is one root, so a re-run of the box that adds it does not duplicate. */
    @Test
    fun addingARootTwiceIsIdempotent() {
        GeometryAgentTools.addSearchRoot(dir)
        GeometryAgentTools.addSearchRoot(dir)
        GeometryAgentTools.addSearchRoot(File(dir, "nested/.."))
        assertEquals(1, GeometryAgentTools.searchRoots().size, "canonicalised, so all three are the same root")
        assertFailsWith<IllegalArgumentException> { GeometryAgentTools.addSearchRoot(ball) }
    }

    // ------------------------------------------------------------------- the schema

    /**
     * The exact schema a model is shown for `InspectGeometryFile`. The same object is committed as
     * evidence in `public/docs/assets/ai_tool_demo/geometry_tool_schemas.json` — see
     * [theCommittedSchemaEvidenceIsWhatDescribeActuallyEmits], which is the check that the page and
     * the code cannot drift apart.
     */
    @Test
    fun theInspectToolsSchemaIsWhatTheDocsPageShows() {
        GeometryAgentTools.addSearchRoot(dir)

        val descriptor = AgentToolExtensions.describe(ctx)
            .map { Json.parseToJsonElement(it).jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == "InspectGeometryFile" }

        val schema = descriptor["inputSchema"]!!.jsonObject
        val properties = schema["properties"]!!.jsonObject
        assertEquals(
            listOf("path", "render", "yawDegrees", "pitchDegrees", "wireframe"),
            properties.keys.toList(),
            "in primary-constructor order, which is the order the model reads them in",
        )
        assertEquals(
            listOf("path"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            "only `path` is required; every camera argument has a default",
        )
        assertEquals("string", properties["path"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", properties["render"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("number", properties["yawDegrees"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(
            descriptor["description"]!!.jsonPrimitive.content.contains("ListGeometryFiles"),
            "the description points at the other tool, which is how a model learns the order to call them in",
        )
    }

    /**
     * The committed schema evidence is regenerated by nothing and checked by this — so if a
     * `@JsonPropertyDescription` changes, the page stops matching the code and this says so.
     *
     * Reads the committed file and compares; it never writes one. Regenerate deliberately with
     * `./gradlew runAgentToolDemo` and commit the diff.
     */
    @Test
    fun theCommittedSchemaEvidenceIsWhatDescribeActuallyEmits() {
        GeometryAgentTools.addSearchRoot(dir)

        val committed = File("public/docs/assets/ai_tool_demo/geometry_tool_schemas.json")
        assertTrue(committed.isFile, "expected committed evidence at ${committed.absolutePath}")

        val live = AgentToolExtensions.describe(ctx)
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["name"]!!.jsonPrimitive.content in setOf("ListGeometryFiles", "InspectGeometryFile") }
            .sortedBy { it["name"]!!.jsonPrimitive.content }

        val onDisk = Json.parseToJsonElement(committed.readText()).jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject }
            .sortedBy { it["name"]!!.jsonPrimitive.content }

        assertEquals(
            onDisk.map { it.toString() },
            live.map { it.toString() },
            "public/docs/assets/ai_tool_demo/geometry_tool_schemas.json has drifted from what " +
                "AgentToolExtensions.describe() emits. Regenerate with ./gradlew runAgentToolDemo.",
        )
    }

    // ------------------------------------------------------------------- listing

    /** The listing tool over JSON arguments: what is there, what is filtered out, and what recursion finds. */
    @Test
    fun listingFindsLoadableFilesAndIgnoresEverythingElse() {
        GeometryAgentTools.addSearchRoot(dir)

        val all = AgentToolExtensions.execute(ctx, "ListGeometryFiles", "{}") as ListGeometryFilesResult
        assertEquals(
            listOf("ball.obj", "ball.stl", "nested/small.obj"),
            all.files.map { it.path },
            "sorted, recursive, and notes.txt is not geometry",
        )
        assertEquals(3, all.totalMatches)
        assertFalse(all.truncated)
        assertEquals(listOf(dir.canonicalPath), all.searchRoots)
        assertEquals(setOf("obj", "stl"), all.files.map { it.format }.toSet())
        assertTrue(all.files.all { it.bytes > 0 }, "every entry reports its real size")

        val filtered = AgentToolExtensions.execute(
            ctx, "ListGeometryFiles", """{"nameContains": "SMALL"}""",
        ) as ListGeometryFilesResult
        assertEquals(listOf("nested/small.obj"), filtered.files.map { it.path }, "case-insensitive")

        val capped = AgentToolExtensions.execute(
            ctx, "ListGeometryFiles", """{"limit": 1}""",
        ) as ListGeometryFilesResult
        assertEquals(1, capped.files.size)
        assertEquals(3, capped.totalMatches, "totalMatches counts what was there, not what was returned")
        assertTrue(capped.truncated, "and says so, so a model knows to narrow the filter")
        assertEquals("files=1/3 truncated=true roots=1", capped.toolLogSummary())
    }

    // ------------------------------------------------------------------- inspecting

    /**
     * The measurements, asserted exactly where they are exact.
     *
     * The fixture is a 3-times-subdivided icosphere of radius 1: 642 vertices, 1280 triangles, closed,
     * `chi = 2`. Those are the same figures `MeshFromDiskTest` and `public/docs/geometry_from_disk.md`
     * quote for the same mesh. The area and volume are floors and ceilings rather than equalities,
     * because a subdivided icosphere approaches the sphere from *inside* — the numbers are a little
     * under 4pi and 4pi/3 and would change if the tessellation ever did.
     */
    @Test
    fun inspectingReportsTheMeasurementsTheDocsPageQuotes() {
        GeometryAgentTools.addSearchRoot(dir)

        val report = AgentToolExtensions.execute(
            ctx, "InspectGeometryFile", """{"path": "ball.obj", "render": false}""",
        ) as InspectGeometryFileResult

        assertEquals("obj", report.format)
        assertEquals(642, report.vertexCount, "the page quotes 642 vertices")
        assertEquals(1280, report.triangleCount, "the page quotes 1280 triangles")
        assertTrue(report.hasNormals, "OBJ carries vn")
        assertFalse(report.hasColors, "OBJ has no standard per-vertex colour, so they were lost on write")
        assertFalse(report.hasUvs)
        assertTrue(report.watertight)
        assertEquals(2, report.eulerCharacteristic, "a closed sphere")
        assertEquals(0, report.boundaryLoopCount)

        for ((axis, extent) in listOf("x", "y", "z").zip(report.extent)) {
            assertTrue(extent > 1.98 && extent <= 2.0, "unit-radius sphere, $axis extent was $extent")
        }
        assertTrue(
            report.surfaceArea > 12.4 && report.surfaceArea < 12.566,
            "a little under 4pi = 12.566, from inside; was ${report.surfaceArea}",
        )
        val volume = report.volume
        assertTrue(volume != null && volume > 4.15 && volume < 4.18879, "a little under 4pi/3; was $volume")

        assertTrue(report.notes.isEmpty(), "a clean closed mesh with normals needs no caveats")

        val summary = report.toolLogSummary()
        assertTrue(
            summary.startsWith("ball.obj verts=642 tris=1280 watertight=true extent="),
            "the tool-log line leads with the file and the counts that matter; was: $summary",
        )
        assertTrue(summary.endsWith(" images=0"), "and ends with the image count; was: $summary")
        assertTrue(
            summary.length < 120,
            "SummarisesForToolLog is one line in a log, not a report; was ${summary.length} chars",
        )
    }

    /**
     * The notes exist so a model does not misread a normal fact as a defect. STL is the clearest case:
     * it is triangle soup, so the vertex count triples and `chi` becomes meaningless, and a model that
     * is not told will report a broken mesh.
     */
    @Test
    fun theStlNotesExplainTheThingAModelWouldOtherwiseGetWrong() {
        GeometryAgentTools.addSearchRoot(dir)

        val report = AgentToolExtensions.execute(
            ctx, "InspectGeometryFile", """{"path": "ball.stl", "render": false}""",
        ) as InspectGeometryFileResult

        assertEquals(3840, report.vertexCount, "1280 triangles x 3 unshared vertices")
        assertEquals(1280, report.triangleCount)
        assertFalse(report.watertight, "unwelded soup has no shared edges")
        assertEquals(null, report.volume, "and therefore no meaningful volume — null, not a wrong number")
        assertTrue(
            report.notes.any { it.contains("triangle soup") },
            "the soup note must be there; got ${report.notes}",
        )
        assertTrue(
            report.notes.any { it.contains("Not watertight") },
            "and the watertightness note, which says why it is usually not a defect",
        )
    }

    // ------------------------------------------------------------------- the picture

    /**
     * The image channel end to end: real PNG bytes of the right size, carried on [CarriesToolImages],
     * and **absent** from the JSON the model is sent as function output.
     *
     * That last assertion is the one worth having. A base64 PNG that leaks into the function output is
     * spent as text tokens, invisibly, and buys nothing — a model cannot see an image encoded in a JSON
     * string. `@get:JsonIgnore` on the interface is what prevents it, and this is the check that the
     * annotation is actually inherited.
     */
    @Test
    fun theRenderedImageIsRealPngAndStaysOutOfTheJson() {
        GeometryAgentTools.addSearchRoot(dir)

        val report = AgentToolExtensions.execute(
            ctx, "InspectGeometryFile", """{"path": "ball.obj", "yawDegrees": 40.0}""",
        ) as InspectGeometryFileResult

        assertEquals(1, report.toolImages.size)
        val image = report.toolImages.single()
        assertTrue(image.dataUrl.startsWith("data:image/png;base64,"))
        assertTrue(image.caption.contains("1280 triangles"), "the caption says what to look for")
        assertTrue(image.caption.contains("40"), "and from where")

        val png = Base64.getDecoder().decode(image.dataUrl.removePrefix("data:image/png;base64,"))
        assertTrue(
            png.size > 4000,
            "a 640x480 shaded render of 1280 triangles is not a handful of bytes; was ${png.size}",
        )
        assertEquals(
            listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
            png.take(4),
            "PNG magic",
        )
        assertEquals(640, readPngWidth(png))
        assertEquals(480, readPngHeight(png))

        val json = mapper.writeValueAsString(report)
        assertFalse("base64" in json, "the data URL never reaches the function-output JSON")
        assertFalse("toolImages" in json, "nor does the property name")
        assertTrue("\"triangleCount\":1280" in json, "but the measurements do")

        val unrendered = AgentToolExtensions.execute(
            ctx, "InspectGeometryFile", """{"path": "ball.obj", "render": false}""",
        ) as InspectGeometryFileResult
        assertEquals(emptyList(), unrendered.toolImages, "render=false really means no render")
    }

    // ------------------------------------------------------------------- path safety

    /**
     * The roots are the whole permission model for a tool that takes a path from a language model, so
     * every way out of them has to be closed. A relative escape, an absolute path elsewhere, and a
     * file that simply is not there each produce a distinct, readable error.
     */
    @Test
    fun aPathOutsideTheSearchRootsIsRefused() {
        val other = Files.createTempDirectory("afield-geometry-outside").toFile()
        try {
            val outsider = File(other, "outside.obj").also { icosphereMesh(1, 1.0).saveObj(it) }
            GeometryAgentTools.addSearchRoot(dir)

            val absolute = assertFailsWith<IllegalArgumentException> {
                AgentToolExtensions.execute(
                    ctx, "InspectGeometryFile",
                    mapper.writeValueAsString(mapOf("path" to outsider.absolutePath)),
                )
            }
            assertTrue(
                "outside every geometry search root" in absolute.message.orEmpty(),
                "got: ${absolute.message}",
            )

            val relative = assertFailsWith<IllegalArgumentException> {
                AgentToolExtensions.execute(
                    ctx, "InspectGeometryFile",
                    mapper.writeValueAsString(mapOf("path" to "../${other.name}/outside.obj")),
                )
            }
            assertTrue(
                "outside every geometry search root" in relative.message.orEmpty(),
                "a `..` that leaves the root is refused after canonicalisation; got: ${relative.message}",
            )

            val missing = assertFailsWith<IllegalArgumentException> {
                AgentToolExtensions.execute(ctx, "InspectGeometryFile", """{"path": "nope.obj"}""")
            }
            assertTrue(
                "call ListGeometryFiles" in missing.message.orEmpty(),
                "a missing file tells the model how to find out what exists; got: ${missing.message}",
            )
        } finally {
            other.deleteRecursively()
        }
    }

    /**
     * A root prefix is not containment. `/tmp/x` must not authorise `/tmp/x-private`, which a
     * `canonicalPath.startsWith(root.canonicalPath)` check would happily allow.
     */
    @Test
    fun aSiblingDirectoryWithTheRootAsANamePrefixIsNotInsideIt() {
        val sibling = File(dir.parentFile, dir.name + "-private").also { it.mkdirs() }
        try {
            val secret = File(sibling, "secret.obj").also { icosphereMesh(1, 1.0).saveObj(it) }
            GeometryAgentTools.addSearchRoot(dir)

            val refused = assertFailsWith<IllegalArgumentException> {
                AgentToolExtensions.execute(
                    ctx, "InspectGeometryFile",
                    mapper.writeValueAsString(mapOf("path" to secret.absolutePath)),
                )
            }
            assertTrue("outside every geometry search root" in refused.message.orEmpty(), "got: ${refused.message}")
        } finally {
            sibling.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------- helpers

    /** PNG IHDR width — bytes 16..19, big-endian. Cheaper and more honest than decoding the image. */
    private fun readPngWidth(png: ByteArray): Int = beInt(png, 16)

    /** PNG IHDR height — bytes 20..23, big-endian. */
    private fun readPngHeight(png: ByteArray): Int = beInt(png, 20)

    private fun beInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
