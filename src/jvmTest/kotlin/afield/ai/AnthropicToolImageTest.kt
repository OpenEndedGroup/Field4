package afield.ai

import afield.ai.core.ANTHROPIC_MAX_IMAGE_BASE64_CHARS
import afield.ai.core.AgentToolExtensions
import afield.ai.core.CaptureRenderWindowResult
import afield.ai.core.CarriesToolImages
import afield.ai.core.SummarisesForToolLog
import afield.ai.core.ToolExecutionContext
import afield.ai.core.POST_RELOAD_SCREENSHOT_CAPTION
import afield.ai.core.ReloadShadersResult
import afield.ai.core.ToolImage
import afield.ai.core.aiToolMapper
import afield.ai.core.decodeDataUrl
import afield.ai.core.toolResultBlock
import afield.four.modules.GeometryAgentTools
import afield.four.modules.InspectGeometryFileResult
import afield.stdlib.icosphereMesh
import afield.stdlib.saveObj
import com.fasterxml.jackson.databind.ObjectMapper
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ToolResultBlockParam
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Anthropic bridge sends a tool's pictures.
 *
 * # What was wrong
 *
 * `CarriesToolImages`' documentation says "the bridge appends the images to the conversation", and
 * `AnthropicBridge` never read the interface. Every tool result went out as `content(resultJson)`, a
 * bare string, so under `AFIELD_AI_PROVIDER=anthropic` a geometry tool's entire reason for existing —
 * letting the model *look* at what it did — evaporated with no error and no log line. The only symptom
 * available to anyone was a model confidently reasoning about a picture it had never been shown.
 *
 * It was an unfinished feature, not a limitation: the Messages API has accepted images inside a
 * `tool_result`'s content the whole time, and the SDK models it as
 * `ToolResultBlockParam.Content.Block.ofImage`. The conversion was simply missing.
 *
 * # What these tests assert, and why the JSON one matters most
 *
 * Every case here builds the **real** `ToolResultBlockParam` the bridge puts in its request, and
 * [theImageReachesTheSerialisedRequestPayload] serialises it with the SDK's own mapper — the same one
 * the HTTP layer uses — and asserts on the wire JSON. An assertion over the object graph alone could
 * pass while the field never made it into the body; this one cannot.
 *
 * No network, no API key, no model. `toolResultBlock` was extracted from the agent loop precisely so
 * that this could be true.
 */
class AnthropicToolImageTest {

    /**
     * The SDK's own Jackson mapper — the one its HTTP layer serialises a request with.
     *
     * Reached reflectively because `com.anthropic.core.ObjectMappers` is `internal` in the SDK's Kotlin
     * metadata, so the Kotlin compiler refuses it across the module boundary even though the JVM method
     * is public. Worth the reflection rather than substituting a plain `ObjectMapper`: these parameter
     * types carry custom serializers, and a mapper without them would produce JSON that is not what
     * Anthropic receives — which would make an assertion over it worse than no assertion at all.
     */
    private val mapper: ObjectMapper =
        Class.forName("com.anthropic.core.ObjectMappers")
            .getMethod("jsonMapper")
            .invoke(null) as ObjectMapper

    /** A 1×1 PNG, small enough to inline and real enough to be a PNG. */
    private val pngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private data class Pictured(
        val note: String,
        override val toolImages: List<ToolImage>,
    ) : CarriesToolImages, SummarisesForToolLog {
        override fun toolLogSummary() = "note=$note images=${toolImages.size}"
    }

    private fun blocksOf(param: ToolResultBlockParam): List<ToolResultBlockParam.Content.Block> =
        param.content().orElseThrow { AssertionError("tool_result had no content at all") }
            .blocks().orElseThrow { AssertionError("tool_result content was a plain string, not blocks") }

    // ------------------------------------------------------------------ the regression

    /**
     * The exact case the bridge dropped: one result, one image. Before the fix this produced a
     * string-content block and `blocksOf` would have thrown.
     */
    @Test
    fun aResultCarryingAnImageBecomesTextPlusAnImageBlock() {
        val result = Pictured(
            "visible",
            listOf(ToolImage("data:image/png;base64,$pngBase64", "the mesh from 34 degrees")),
        )
        val param = toolResultBlock("toolu_01", """{"note":"visible"}""", isError = false, result = result)

        assertEquals("toolu_01", param.toolUseId())

        val blocks = blocksOf(param)
        assertEquals(3, blocks.size, "the JSON result, the caption, and the picture")

        assertEquals(
            """{"note":"visible"}""",
            blocks[0].text().orElseThrow().text(),
            "the JSON result stays first, so a model reads the answer before looking at it",
        )
        assertEquals(
            "the mesh from 34 degrees",
            blocks[1].text().orElseThrow().text(),
            "the caption is a text block immediately before its picture — a bare image says nothing " +
                "about what question it answers",
        )

        val source = blocks[2].image().orElseThrow().source().base64().orElseThrow()
        assertEquals(Base64ImageSource.MediaType.IMAGE_PNG, source.mediaType())
        assertEquals(pngBase64, source.data(), "the payload, with the data: prefix stripped")
    }

    /**
     * The strongest form of the claim: the picture is in the bytes that go over the wire, in the shape
     * the Messages API documents, and the base64 is *not* sitting in a text block being charged as text.
     */
    @Test
    fun theImageReachesTheSerialisedRequestPayload() {
        val result = Pictured("x", listOf(ToolImage("data:image/png;base64,$pngBase64", "a caption")))
        val json = mapper.writeValueAsString(
            toolResultBlock("toolu_02", """{"note":"x"}""", isError = false, result = result)
        )

        assertTrue(""""type":"tool_result"""" in json, "still a tool_result; was: $json")
        assertTrue(""""type":"image"""" in json, "an image block reaches the payload; was: $json")
        assertTrue(""""type":"base64"""" in json, "as an inline base64 source")
        assertTrue(""""media_type":"image/png"""" in json, "with the media type the API keys on")
        assertTrue(pngBase64 in json, "and the actual bytes")

        // The one thing that would make all of the above worthless: the same payload also appearing
        // inside a text block, where it is spent as tokens and cannot be seen.
        val textBlockPayloads = blocksOf(
            toolResultBlock("toolu_02", """{"note":"x"}""", isError = false, result = result)
        ).mapNotNull { it.text().orElse(null)?.text() }
        assertTrue(
            textBlockPayloads.none { pngBase64 in it },
            "the base64 must not also be in a text block; found in $textBlockPayloads",
        )
    }

    // ------------------------------------------------------------------ the untouched path

    /**
     * A result with no images is byte-for-byte what it always was — a plain string. Worth pinning:
     * almost every tool has no pictures, and turning them all into single-element block lists would be
     * a gratuitous change to every request the bridge makes.
     */
    @Test
    fun aResultWithNoImagesIsStillAPlainStringContent() {
        val param = toolResultBlock("toolu_03", """{"ok":true}""", isError = false, result = mapOf("ok" to true))
        assertEquals(
            """{"ok":true}""",
            param.content().orElseThrow().string().orElseThrow(),
            "unchanged: a string, not a one-element block list",
        )
        assertTrue(param.content().orElseThrow().blocks().isEmpty)
    }

    /** An empty image list is the same as no interface at all. */
    @Test
    fun anEmptyImageListDoesNotChangeTheShape() {
        val param = toolResultBlock("toolu_04", "{}", isError = false, result = Pictured("none", emptyList()))
        assertTrue(param.content().orElseThrow().string().isPresent, "no images, so no block list")
    }

    /** The error flag survives both shapes, because an erroring tool can still return a picture. */
    @Test
    fun theErrorFlagSurvivesBothShapes() {
        val plain = toolResultBlock("t", "{}", isError = true, result = mapOf("error" to true))
        assertEquals(true, plain.isError().orElse(false))

        val pictured = toolResultBlock(
            "t", "{}", isError = true,
            result = Pictured("failed", listOf(ToolImage("data:image/png;base64,$pngBase64", "what it looked like"))),
        )
        assertEquals(true, pictured.isError().orElse(false))
        assertEquals(3, blocksOf(pictured).size)
    }

    // ------------------------------------------------------------------ what gets dropped, and loudly

    /**
     * An oversized image is dropped and the text answer still goes. The alternative is a 400 from the
     * API that fails the whole turn — losing the answer to save a bonus — so the trade is deliberate,
     * and the drop is reported rather than silent, because a silent drop is the bug this file exists for.
     */
    @Test
    fun anOversizedImageIsDroppedWithAReasonAndTheAnswerStillGoes() {
        val huge = "A".repeat(ANTHROPIC_MAX_IMAGE_BASE64_CHARS + 1)
        val warnings = mutableListOf<String>()

        val param = toolResultBlock(
            "toolu_05", """{"note":"big"}""", isError = false,
            result = Pictured("big", listOf(ToolImage("data:image/png;base64,$huge", "enormous"))),
            onOversize = { warnings += it },
        )

        assertEquals(
            """{"note":"big"}""",
            param.content().orElseThrow().string().orElseThrow(),
            "falls all the way back to the plain string when nothing survives",
        )
        assertEquals(1, warnings.size)
        assertTrue("exceeds" in warnings.single(), "the reason names the limit; got: ${warnings.single()}")
        assertTrue(
            "text result was still sent" in warnings.single(),
            "and says what did happen, not only what did not",
        )
    }

    /** Anything that is not a base64 image data URL is dropped, also with a reason. */
    @Test
    fun aNonDataUrlIsDroppedWithAReason() {
        val warnings = mutableListOf<String>()
        val param = toolResultBlock(
            "toolu_06", "{}", isError = false,
            result = Pictured("remote", listOf(ToolImage("https://example.com/x.png", "remote"))),
            onOversize = { warnings += it },
        )
        assertTrue(param.content().orElseThrow().string().isPresent)
        assertEquals(1, warnings.size)
        assertTrue("base64 data URL" in warnings.single(), "got: ${warnings.single()}")
    }

    /** One bad image among good ones loses only itself. */
    @Test
    fun oneBadImageDoesNotTakeTheGoodOnesWithIt() {
        val warnings = mutableListOf<String>()
        val param = toolResultBlock(
            "toolu_07", "{}", isError = false,
            result = Pictured(
                "mixed",
                listOf(
                    ToolImage("data:image/png;base64,$pngBase64", "good one"),
                    ToolImage("not-a-url", "bad one"),
                    ToolImage("data:image/jpeg;base64,$pngBase64", "good two"),
                ),
            ),
            onOversize = { warnings += it },
        )
        val blocks = blocksOf(param)
        assertEquals(
            2,
            blocks.count { it.image().isPresent },
            "two survivors, not zero and not three",
        )
        assertEquals(1, warnings.size)
        assertEquals(
            listOf(Base64ImageSource.MediaType.IMAGE_PNG, Base64ImageSource.MediaType.IMAGE_JPEG),
            blocks.mapNotNull { it.image().orElse(null) }.map { it.source().base64().orElseThrow().mediaType() },
            "in order, and with the right media types",
        )
    }

    // ------------------------------------------------------------------ the two real result types

    /**
     * The screenshot from a shader reload reaches an Anthropic model.
     *
     * # What was wrong
     *
     * The general path above was fixed on 2026-08-11; this one was not, and the reason it was missed is
     * worth keeping. Both bridges special-cased `ReloadShadersResult` **by name** — the OpenAI bridge's
     * comment even stated that keeping the paths apart was deliberate, because the post-reload contract
     * had "prompt wording of its own that nothing else wants". A by-name special case is per-bridge by
     * construction, and this bridge never grew its half: it appended a line of prose saying a screenshot
     * had been captured and dropped the screenshot. `visualDataUrl` is `@JsonIgnore`d out of the result
     * JSON on purpose, so the picture was in neither channel.
     *
     * The wording now travels as the image's caption, which is what let the special case go away.
     *
     * The failure mode this pins is the nastiest kind: `visualCheckAttached` still said `true`, so the
     * model was told a picture existed, shown nothing, and left to write about it.
     */
    @Test
    fun aShaderReloadScreenshotReachesTheModel() {
        val result = ReloadShadersResult(
            success = true,
            target = "afield://doc/box/fragment",
            mode = "graphics",
            message = "Shader reload succeeded",
            reloaded = listOf("graphics"),
            visualCheckAttached = true,
            visualCheckMessage = "Captured Window 1 (1600 x 900)",
            visualDataUrl = "data:image/jpeg;base64,$pngBase64",
        )

        val blocks = blocksOf(toolResultBlock("toolu_reload", """{"success":true}""", false, result))
        assertEquals(3, blocks.size, "the JSON result, the caption, and the picture")
        assertEquals(
            POST_RELOAD_SCREENSHOT_CAPTION,
            blocks[1].text().orElseThrow().text(),
            "the settle-delay wording that used to live in the bridge now rides on the image",
        )
        assertEquals(
            pngBase64,
            blocks[2].image().orElseThrow().source().base64().orElseThrow().data(),
            "and the bytes are actually there",
        )
    }

    /** A reload that captured nothing is shaped exactly as it was before any of this. */
    @Test
    fun aShaderReloadWithNoScreenshotIsStillAPlainString() {
        val result = ReloadShadersResult(
            success = true,
            target = "afield://doc/box/fragment",
            mode = "graphics",
            message = "Shader reload succeeded",
            visualCheckAttached = false,
            visualCheckMessage = "Not captured: the reload did not succeed",
        )
        val param = toolResultBlock("toolu_reload_none", """{"success":true}""", false, result)
        assertTrue(
            param.content().orElseThrow().string().isPresent,
            "no picture, so no block list — the overwhelmingly common case must not change shape",
        )
    }

    /** `CaptureRenderWindow`'s whole purpose is the picture, so pin the same two properties. */
    @Test
    fun aRenderWindowCaptureReachesTheModel() {
        val result = CaptureRenderWindowResult(
            success = true,
            message = "Captured Window 1",
            title = "afield.four",
            width = 1600,
            height = 900,
            settledMs = 250,
            dataUrl = "data:image/jpeg;base64,$pngBase64",
        )
        val blocks = blocksOf(toolResultBlock("toolu_capture", """{"success":true}""", false, result))
        assertEquals(3, blocks.size)
        assertTrue(
            "afield.four" in blocks[1].text().orElseThrow().text(),
            "the caption names the window, so a turn with two captures is not ambiguous",
        )
        assertEquals(
            pngBase64,
            blocks[2].image().orElseThrow().source().base64().orElseThrow().data(),
        )
    }

    /**
     * The invariant [CarriesToolImages] is documented around, asserted on the real types.
     *
     * A base64 payload that reaches the result JSON is spent as text tokens, invisibly, and buys
     * nothing — the model cannot see an image encoded in a JSON string. `aiToolMapper` is the mapper the
     * bridges actually serialise a tool result with, so this is the real question and not a proxy for it.
     */
    @Test
    fun neitherResultTypeLeaksItsBase64IntoTheToolJson() {
        val reload = aiToolMapper.writeValueAsString(
            ReloadShadersResult(
                success = true,
                target = "t",
                mode = "graphics",
                message = "ok",
                visualCheckAttached = true,
                visualDataUrl = "data:image/jpeg;base64,$pngBase64",
            )
        )
        val capture = aiToolMapper.writeValueAsString(
            CaptureRenderWindowResult(
                success = true,
                message = "ok",
                dataUrl = "data:image/jpeg;base64,$pngBase64",
            )
        )

        for ((name, json) in listOf("ReloadShadersResult" to reload, "CaptureRenderWindowResult" to capture)) {
            assertFalse(pngBase64 in json, "$name leaked its image payload into the tool JSON: $json")
            assertFalse("toolImages" in json, "$name serialised the image channel itself: $json")
            assertFalse("data:image/" in json, "$name leaked a data URL: $json")
        }

        // ...while still saying, in the JSON, that there is something to look at. A model that is shown
        // a picture and told nothing about it in the result is the other half of this bug.
        assertTrue(""""visualCheckAttached":true""" in reload, "was: $reload")
        assertTrue(""""success":true""" in capture, "was: $capture")
    }

    // ------------------------------------------------------------------ the data URL parser

    @Test
    fun theDataUrlParserAcceptsTheFourFormatsTheApiTakesAndNothingElse() {
        assertEquals(
            Base64ImageSource.MediaType.IMAGE_PNG,
            decodeDataUrl("data:image/png;base64,$pngBase64")?.first,
        )
        assertEquals(
            Base64ImageSource.MediaType.IMAGE_JPEG,
            decodeDataUrl("data:image/JPEG;base64,abc")?.first,
            "case-insensitive, because a data URL in the wild is not always lower-case",
        )
        assertEquals(Base64ImageSource.MediaType.IMAGE_GIF, decodeDataUrl("data:image/gif;base64,abc")?.first)
        assertEquals(Base64ImageSource.MediaType.IMAGE_WEBP, decodeDataUrl("data:image/webp;base64,abc")?.first)

        assertEquals("abc", decodeDataUrl("data:image/png;base64,abc")?.second, "the prefix is stripped")

        assertNull(decodeDataUrl("data:image/svg+xml;base64,abc"), "SVG is not an image type the API takes")
        assertNull(decodeDataUrl("data:text/plain;base64,abc"), "nor is text")
        assertNull(decodeDataUrl("https://example.com/x.png"), "nor a remote URL")
        assertNull(decodeDataUrl("data:image/png,notbase64"), "nor a non-base64 data URL")
        assertNull(decodeDataUrl("data:image/png;base64,"), "nor an empty payload")
        assertNull(decodeDataUrl(""))
    }

    // ------------------------------------------------------------------ the real tool, end to end

    private var dir: File? = null

    @AfterTest
    fun tearDown() {
        GeometryAgentTools.unregister()
        GeometryAgentTools.clearSearchRoots()
        dir?.deleteRecursively()
    }

    /**
     * The flagship example on `public/docs/aiFeatures.md`, on the Anthropic path.
     *
     * The page says a tool can let the model see what it did. That page would have been wrong for
     * anyone running the provider this project is built around, and asserting it with a fixture PNG
     * would not have caught it — so this drives the real `InspectGeometryFile` through the real
     * `AgentToolExtensions.execute` and puts the real result through the real block builder.
     */
    @Test
    fun theGeometryToolsRenderSurvivesTheWholeAnthropicPath() {
        val temp = Files.createTempDirectory("afield-anthropic-tool-image").toFile()
        dir = temp
        icosphereMesh(2, 1.0).saveObj(File(temp, "ball.obj"))

        GeometryAgentTools.clearSearchRoots()
        GeometryAgentTools.addSearchRoot(temp)
        GeometryAgentTools.register()

        val result = AgentToolExtensions.execute(
            ToolExecutionContext(), "InspectGeometryFile", """{"path": "ball.obj"}""",
        ) as InspectGeometryFileResult

        val resultJson = afield.ai.core.aiToolMapper.writeValueAsString(result)
        val param = toolResultBlock("toolu_geom", resultJson, isError = false, result = result)

        val blocks = blocksOf(param)
        assertEquals(3, blocks.size, "result JSON, caption, picture")

        val image = blocks.single { it.image().isPresent }.image().orElseThrow()
        val source = image.source().base64().orElseThrow()
        assertEquals(Base64ImageSource.MediaType.IMAGE_PNG, source.mediaType())

        val png = Base64.getDecoder().decode(source.data())
        assertEquals(
            listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
            png.take(4),
            "a real PNG went into the request, not a placeholder",
        )
        assertTrue(png.size > 4000, "and a real render of it; was ${png.size} bytes")

        // And the measurements are in the text half, which is the property that makes the tool usable
        // even on a provider that drops images.
        val text = blocks.first().text().orElseThrow().text()
        assertTrue(""""triangleCount":320""" in text, "an icosphere(2) is 320 triangles; got $text")
        assertFalse(source.data() in text, "the base64 is not duplicated into the text")
    }
}
