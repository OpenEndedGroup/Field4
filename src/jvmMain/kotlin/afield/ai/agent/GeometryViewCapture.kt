package afield.ai.agent

import afield.runtime.lifecycle.Runloop
import afield.ai.tools.ToolImage
import afield.ai.tools.aiToolMapper
import afield.app.server.ensureSharedPlaywrightBrowser
import afield.tools.docs.evaluateWithin
import afield.app.server.globalServerPort
import afield.app.server.playwrightDispatcher
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import javax.imageio.ImageIO

/** Short-lived, independent viewer contexts: no user browser navigation or shared capture-page state. */
internal object GeometryViewCapture {
    fun capture(request: InspectGeometryViewRequest, prepared: Map<String, Any?>): GeometryToolResult {
        if (Runloop.isMainThread) return GeometryToolResult(false, "Geometry capture waits for Field; call it off the Runloop")
        return try {
            runBlocking(playwrightDispatcher) {
                val (browser, _) = ensureSharedPlaywrightBrowser()
                val context = browser.newContext(Browser.NewContextOptions().setViewportSize(1400, 1000)
                    .setDeviceScaleFactor(1.0).setIgnoreHTTPSErrors(true))
                try {
                    val page = context.newPage()
                    page.navigate("https://localhost:$globalServerPort/threejs?uid=" + URLEncoder.encode(request.viewId, Charsets.UTF_8),
                        Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT).setTimeout(20_000.0))
                    page.waitForFunction("""() => {
                        const status = window.FieldViewer?.captureStatus?.();
                        if (status?.error) throw new Error(status.error);
                        return Boolean(window.FieldInspector?.snapshot().artifactDigest) && status?.ready === true;
                    }""", null,
                        Page.WaitForFunctionOptions().setTimeout(20_000.0))
                    // Playwright arguments keep note text and ids as data, never executable source.
                    val args = mapOf("request" to aiToolMapper.readValue(aiToolMapper.writeValueAsString(request), Map::class.java),
                        "note" to prepared["note"], "state" to prepared["state"], "observationText" to prepared["observationText"])
                    // The Playwright Java argument codec accepts a narrower set of number classes
                    // than our JSON tree (notably not Long). Carry one JSON string and decode in JS.
                    val stateJson = evaluateWithin(page, INSPECT_SCRIPT, aiToolMapper.writeValueAsString(args),
                        20_000, "Geometry inspection") as String
                    val bytes = page.screenshot(Page.ScreenshotOptions().setTimeout(15_000.0))
                    val after = page.evaluate("() => window.FieldInspector.snapshot().artifactDigest")
                    check(after == request.expectedRevision) { "Geometry changed while its picture was captured; inspect the new revision" }
                    val image = checkNotNull(ImageIO.read(bytes.inputStream())) { "Geometry screenshot did not decode" }
                    val file = File(BoxOutputCapture.captureDir.apply { mkdirs() }, "geometry-${System.currentTimeMillis()}-${UUID.randomUUID()}.png")
                    file.writeBytes(bytes)
                    file.parentFile.listFiles { f -> f.name.startsWith("geometry-") && f.extension == "png" }
                        ?.sortedByDescending { it.lastModified() }?.drop(100)?.forEach { it.delete() }
                    val captured = Json.parseToJsonElement(stateJson).jsonObject.mapValues { it.value.plain() }
                    val details = (if (request.includeMetadata) captured else compactInspectionDetails(captured)) + mapOf(
                        "viewId" to request.viewId, "revision" to request.expectedRevision,
                        "width" to image.width, "height" to image.height, "path" to file.absolutePath)
                    GeometryToolResult(true, "Captured geometry in an independent viewer", details, toolImages = listOf(
                        ToolImage(jpegDataUrl(image, maxSide = 1400), "Current geometry ${request.viewId}, revision ${request.expectedRevision}. Independent inspection camera; the user's camera and manual selection were not changed.")))
                } finally { context.close() }
            }
        } catch (e: Exception) {
            GeometryToolResult(false, "Could not inspect geometry: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private val INSPECT_SCRIPT = """
        async encoded => {
            const {request:r, note, state, observationText} = JSON.parse(encoded);
            const api = window.FieldInspector;
            if (api.snapshot().artifactDigest !== r.expectedRevision) throw new Error('Geometry changed before inspection');
            if (note) api.review(note);
            if (state) api.restore(state);
            let next = api.snapshot();
            if (r.explode != null) next.explode = r.explode;
            if (r.hidden != null) next.hidden = r.hidden;
            if (r.xray != null) next.xray = r.xray;
            if (r.xrayOpacity != null) next.xrayOpacity = r.xrayOpacity;
            if (r.camera != null) next.camera = r.camera;
            if (r.target != null) next.target = r.target;
            if (r.sectionNormal != null) {
                next.sectionEnabled = true;
                next.sectionNormal = r.sectionNormal;
                next.sectionFraction = r.sectionFraction;
            }
            api.restore(next);
            if (r.pick) api.pick(r.pick.x, r.pick.y, r.pick.kind);
            if (r.region) api.region(r.region);
            // A viewer that is not rendering never runs a frame callback; say so instead of waiting.
            await new Promise((resolve, reject) => {
                const timer = setTimeout(() => reject(new Error('The viewer rendered no frame within 5 s')), 5000);
                requestAnimationFrame(() => requestAnimationFrame(() => { clearTimeout(timer); resolve(); }));
            });
            const result = {state: api.snapshot(), sources: api.sources(), parts: api.parts()};
            if (observationText != null) {
                const observation = api.observe(observationText);
                if (!observation.selection) throw new Error('Select a visible feature before reporting a finding');
                observation.evidence = api.capture(observation);
                result.observation = observation;
            }
            return JSON.stringify(result);
        }
    """.trimIndent()
}

/** Preserve identities and full restore state, omitting repeated tree leaves and source provenance. */
internal fun compactInspectionDetails(captured: Map<String, Any?>): Map<String, Any?> {
    fun node(value: Any?): Map<String, Any?> {
        val part = value as? Map<*, *> ?: return emptyMap()
        val children = (part["children"] as? List<*>).orEmpty()
        val ids = (part["ids"] as? List<*>).orEmpty()
        return mapOf("key" to part["key"], "label" to part["label"], "count" to ids.size) +
            if (children.isEmpty()) mapOf("ids" to ids) else mapOf("children" to children.map(::node))
    }
    return captured + mapOf(
        "parts" to node(captured["parts"]),
        "sources" to (captured["sources"] as? List<*>).orEmpty().map { value ->
            (value as? Map<*, *>).orEmpty().filterKeys { it in setOf("id", "label", "precision") }
        },
        "metadataIncluded" to false
    )
}
