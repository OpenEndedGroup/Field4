package afield.four.fabricated

import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deviceless half of the path tracer's instrument.
 *
 * Three of the four things added alongside [PathTracingRenderer] are pure and therefore pinnable
 * without a Vulkan device — the `HARDWARE_QUERY` source transform, the two renderings of a
 * [PathTraceStatus], and the convergence log's bookkeeping. That is exactly why the renderings take
 * a [PathTraceStatus] rather than a live tracer: a hand-built status is a complete input, so the
 * shape of what a person sees is testable on a machine with no GPU at all, and only "does the image
 * change" needs the real device.
 *
 * The negative assertions are the load-bearing ones. `.h` is only useful if it stays short enough to
 * sit above the next box, and short means *rows that are at their default do not appear*. A test
 * that only checked the interesting rows were present would pass on a panel four times too tall.
 */
class PathTraceInstrumentTest {

    // ── the HARDWARE_QUERY transform ─────────────────────────────────

    @Test
    fun `the packaged integrator is a real shader and keeps the markers pathTracingHere splits on`() {
        val source = PathTracingRenderer.packagedIntegrator()
        // A floor near the number, not an existence check: the resource loading but arriving empty
        // (a mis-declared Gradle resource set, say) would satisfy "not null" and nothing else here.
        assertTrue(source.length > 8_000, "trace.comp is ${source.length} chars; expected well over 8000")
        assertTrue(source.contains("#version 460"), "no #version 460 to anchor the define on")
        assertFalse(source.contains("#define HARDWARE_QUERY"), "the packaged source must carry no define")
        // `pathTracingHere` slices the same resource on these two markers to build its shader
        // templates (PathTraceShaderBoxes.kt:26-30). Reload shares the source, so if a change ever
        // drops a marker, the macro path breaks silently and this is the cheapest place to notice.
        assertTrue(source.contains("// MATERIAL_BEGIN"), "MATERIAL_BEGIN marker is gone; pathTracingHere would break")
        assertTrue(source.contains("// MATERIAL_END"), "MATERIAL_END marker is gone; pathTracingHere would break")
    }

    @Test
    fun `the hardware define is inserted once, right after the version, and only for ray queries`() {
        val source = PathTracingRenderer.packagedIntegrator()

        val soft = PathTracingRenderer.withHardwareQuery(source, hardware = false)
        assertEquals(0, soft.split("#define HARDWARE_QUERY").size - 1, "COMPUTE_BVH must get no define")
        assertEquals(source, soft, "a define-free source asked to stay define-free must come back byte-identical")

        val hard = PathTracingRenderer.withHardwareQuery(source, hardware = true)
        assertEquals(1, hard.split("#define HARDWARE_QUERY").size - 1, "exactly one define")
        assertTrue(
            hard.contains("#version 460\n#define HARDWARE_QUERY"),
            "the define has to follow #version immediately or the preprocessor rejects it"
        )
    }

    @Test
    fun `applying the transform twice is the same as applying it once, in both directions`() {
        val source = PathTracingRenderer.packagedIntegrator()
        val hard = PathTracingRenderer.withHardwareQuery(source, hardware = true)

        // This is the property the scratch box did not have. It appended the define without
        // stripping first, so source that had already been through the step came out with two.
        assertEquals(hard, PathTracingRenderer.withHardwareQuery(hard, hardware = true), "not idempotent")
        assertEquals(
            PathTracingRenderer.withHardwareQuery(source, hardware = false),
            PathTracingRenderer.withHardwareQuery(hard, hardware = false),
            "a hardware source asked for software must lose its define"
        )
    }

    // ── the camera basis ─────────────────────────────────────────────

    @Test
    fun `an up that is already perpendicular and unit length agrees, and a tilted one does not`() {
        val agreeing = basis(declaredUp = Vector3f(0f, 1f, 0f))
        assertTrue(agreeing.upAgrees, "tilt was ${agreeing.upTiltDegrees}")
        // Not merely small — exactly zero after the clamp. An unclamped acos of a dot product that
        // floats to 1.0000001 is NaN, which would read as a degenerate camera.
        assertEquals(0.0, agreeing.upTiltDegrees, 1e-9)

        val scaled = basis(declaredUp = Vector3f(0f, 3f, 0f))
        assertTrue(scaled.upAgrees, "length alone must not count as disagreement; tilt was ${scaled.upTiltDegrees}")

        val tilted = basis(declaredUp = Vector3f(0f, 1f, 1f))
        assertFalse(tilted.upAgrees)
        assertEquals(45.0, tilted.upTiltDegrees, 1e-4)

        val degenerate = basis(declaredUp = Vector3f(0f, 0f, 0f))
        assertTrue(degenerate.upTiltDegrees.isNaN())
        assertFalse(degenerate.upAgrees, "a degenerate up must not be reported as agreeing")
    }

    // ── the .h rendering ─────────────────────────────────────────────

    @Test
    fun `a default tracer shows five rows and none of the optional ones`() {
        val html = pathTraceStatusHtml(status())

        assertTrue(html.contains("PathTracingRenderer"))
        assertTrue(html.contains("test-tracer"))
        assertTrue(html.contains("COMPUTE_BVH"))
        assertTrue(html.contains("software BVH traversal"))
        assertTrue(html.contains("640×360"))
        assertTrue(html.contains("6626 triangles"))
        assertTrue(html.contains("57 spp in generation 3"))
        assertTrue(html.contains("camera motion"))

        // The negative half, which is what keeps the panel short.
        assertFalse(html.contains("aperture"), "lens row appeared on a pinhole camera")
        assertFalse(html.contains("reuse on"), "temporal row appeared with reuse off")
        assertFalse(html.contains("density"), "fog row appeared with fog off")
        assertFalse(html.contains("≠ effective"), "up row appeared on a camera whose up agrees")
        assertFalse(html.contains("recorded"), "recorded row appeared with no log attached")

        // image, samples, last reset, camera, paths. `showHeader = false`, so there is no header row
        // to discount. Pinned as an equality because the value of `.h` here is that it is *short*.
        assertEquals(5, html.split("<tr").size - 1, "expected five body rows for a default tracer")
    }

    @Test
    fun `each optional group costs exactly one row, and all five together cap the panel at ten`() {
        val plain = pathTraceStatusHtml(status()).split("<tr").size - 1

        val lens = status(tuning = tuning(apertureRadius = 1.5f, focusDistance = 9f))
        assertEquals(plain + 1, pathTraceStatusHtml(lens).split("<tr").size - 1)
        assertTrue(pathTraceStatusHtml(lens).contains("aperture 1.5"))
        assertTrue(pathTraceStatusHtml(lens).contains("focus 9"))

        // focusDistance null means "follow the camera target", and the row has to say so rather
        // than print `null`.
        val autoFocus = status(tuning = tuning(apertureRadius = 1f, focusDistance = null))
        assertTrue(pathTraceStatusHtml(autoFocus).contains("camera target"))

        val temporal = status(tuning = tuning(temporalReuse = true))
        assertEquals(plain + 1, pathTraceStatusHtml(temporal).split("<tr").size - 1)
        assertTrue(pathTraceStatusHtml(temporal).contains("reuse on"))

        val fog = status(tuning = tuning(fogDensity = 0.02f))
        assertEquals(plain + 1, pathTraceStatusHtml(fog).split("<tr").size - 1)
        assertTrue(pathTraceStatusHtml(fog).contains("density 0.02"))

        val everything = pathTraceStatusHtml(
            status(
                camera = basis(declaredUp = Vector3f(0f, 1f, 1f)),
                tuning = tuning(apertureRadius = 1f, temporalReuse = true, fogDensity = 0.02f),
                recorded = PathTraceConvergenceSummary(points = 180, seconds = 3.0, samples = 57, resets = 2)
            )
        )
        // five optional rows: up, lens, temporal, fog, recorded. Ten in total, which is the ceiling.
        assertEquals(plain + 5, everything.split("<tr").size - 1)
        assertEquals(10, everything.split("<tr").size - 1)
        assertTrue(everything.contains("45.00° apart"), "the up row must give the angle, not just complain")
        assertTrue(everything.contains("180 points"))
        assertTrue(everything.contains("19.0 spp/s"), "57 samples over 3 s is 19 spp/s")
    }

    @Test
    fun `a paused tracer says so and a degenerate up is described rather than measured`() {
        assertTrue(pathTraceStatusHtml(status(paused = true)).contains("[paused]"))
        val degenerate = pathTraceStatusHtml(status(camera = basis(declaredUp = Vector3f(0f, 0f, 0f))))
        assertTrue(degenerate.contains("is degenerate"))
        assertFalse(degenerate.contains("NaN°"), "a degenerate up must not print NaN as an angle")
    }

    // ── the .h2 rendering ────────────────────────────────────────────

    @Test
    fun `the h2 map is a superset of the reflective property bag it replaces`() {
        val s = status()
        val map = pathTraceStatusMap(s, linkedMapOf("renderer" to "stand-in"))

        assertEquals(
            listOf("name", "backend", "image", "convergence", "camera", "tuning", "objects"),
            map.keys.toList(),
            "the group order is the reading order and is part of the design"
        )

        // The claim in the KDoc and the commit message: registering an adapter replaces a working
        // reflective tree, so nothing it showed may be lost. Every tuning key, by derivation.
        @Suppress("UNCHECKED_CAST")
        val tuningGroup = map["tuning"] as Map<String, Any?>
        assertEquals(s.tuning.keys, tuningGroup.keys)
        s.tuning.forEach { (k, v) -> assertEquals(v, tuningGroup[k], "tuning.$k was dropped or changed") }
        assertTrue(s.tuning.size >= 21, "only ${s.tuning.size} tuning properties; the reflective tree showed more")

        @Suppress("UNCHECKED_CAST")
        val camera = map["camera"] as Map<String, Any?>
        assertTrue(camera.containsKey("declaredUp") && camera.containsKey("effectiveUp"))
        assertTrue(camera.containsKey("upTiltDegrees") && camera.containsKey("upAgrees"))

        @Suppress("UNCHECKED_CAST")
        val convergence = map["convergence"] as Map<String, Any?>
        assertEquals("camera motion", convergence["lastResetReason"])
        assertEquals(3L, convergence["resetGeneration"])

        // The sub-objects have to survive, or the adapter costs a person the ability to drill into
        // the renderer and the module that reflection gave them for free.
        @Suppress("UNCHECKED_CAST")
        val objects = map["objects"] as Map<String, Any?>
        assertEquals("stand-in", objects["renderer"])
    }

    // ── the convergence log ──────────────────────────────────────────

    @Test
    fun `the log records one point per frame until it fills, then halves its resolution`() {
        val log = PathTraceConvergence(maxPoints = 16)
        var t = 0L
        repeat(16) { i -> log.record(samples = i + 1, reason = "first frame", atNanos = t); t += 1_000_000_000L }

        // Filling triggers the halving, so 16 frames leave 8 points at stride 2.
        assertEquals(8, log.points)
        assertEquals(2, log.framesPerPoint)

        val seconds = log.seconds
        assertEquals(8, seconds.size)
        assertEquals(0.0, seconds.first(), 1e-9)
        assertEquals(14.0, seconds.last(), 1e-9, "the span must survive decimation even though the detail does not")
        assertTrue((1 until seconds.size).all { seconds[it] > seconds[it - 1] }, "seconds must stay monotone")
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), log.samples)

        // And again: any run length, bounded memory.
        repeat(64) { log.record(samples = 100, reason = "first frame", atNanos = t); t += 1_000_000_000L }
        assertTrue(log.points <= 16, "grew past maxPoints to ${log.points}")
        assertTrue(log.framesPerPoint >= 4, "stride did not keep doubling; it is ${log.framesPerPoint}")
    }

    @Test
    fun `a falling sample count is recorded as a reset, with the reason the tracer gave`() {
        val log = PathTraceConvergence()
        var t = 0L
        fun frame(samples: Int, reason: String) { log.record(samples, reason, t); t += 16_000_000L }

        frame(1, "first frame"); frame(2, "first frame"); frame(3, "first frame")
        frame(1, "camera motion")          // a reset: 3 → 1
        frame(2, "camera motion")
        frame(1, "integrator reloaded")    // another: 2 → 1

        assertEquals(2, log.resets.size)
        assertEquals(listOf("camera motion", "integrator reloaded"), log.resets.map { it.first })
        assertTrue(log.resets[0].second < log.resets[1].second)
        // Nothing but a reset lowers `samples`, so a rising count must never be logged as one.
        assertEquals(0, PathTraceConvergence().also { it.record(1, "x", 0); it.record(9, "x", 1) }.resets.size)
    }

    @Test
    fun `consecutive resets with one reason collapse to a single mark`() {
        val log = PathTraceConvergence()
        var t = 0L
        // What dragging a camera looks like: it resets on every frame it moves. 200 of them.
        log.record(40, "first frame", t); t += 16_000_000L
        repeat(200) { log.record(1, "camera motion", t); t += 16_000_000L }
        repeat(3) { i -> log.record(i + 2, "camera motion", t); t += 16_000_000L }
        log.record(1, "integrator reloaded", t)

        assertTrue(log.resets.size > 100, "only ${log.resets.size} raw resets; the drag was not recorded")
        val marks = log.resetMarks()
        assertEquals(
            listOf("camera motion", "integrator reloaded"), marks.map { it.first },
            "a drag must contribute one mark, not one per frame — otherwise the plot is a solid block"
        )
        assertEquals(0.016, marks[0].second, 1e-6, "the mark belongs at the start of the run")
        assertTrue(log.resetMarks(limit = 1).size == 1, "limit is not honoured")
    }

    @Test
    fun `the summary needs two points, because one point spans no time`() {
        val log = PathTraceConvergence()
        assertNull(log.summary, "an empty log has no summary")
        log.record(1, "first frame", 0L)
        assertNull(log.summary, "one point spans no time and would give an infinite rate")

        log.record(2, "first frame", 500_000_000L)
        log.record(60, "first frame", 2_000_000_000L)
        val s = assertNotNull(log.summary)
        assertEquals(3, s.points)
        assertEquals(2.0, s.seconds, 1e-9)
        assertEquals(60, s.samples)
        assertEquals(30.0, s.samplesPerSecond, 1e-9)

        log.clear()
        assertNull(log.summary)
        assertEquals(0, log.points)
        assertEquals(1, log.framesPerPoint)
    }

    // ── the reload result ────────────────────────────────────────────

    @Test
    fun `the three reload outcomes are distinguishable, and only failure is not ok`() {
        val unchanged = IntegratorReload.unchanged("abc")
        val reloaded = IntegratorReload.reloaded("abcd")
        val failed = IntegratorReload.failed("abcde", listOf("line 7: no such function 'shade'"))

        assertFalse(unchanged.reloaded); assertTrue(unchanged.ok)
        assertTrue(reloaded.reloaded); assertTrue(reloaded.ok)
        assertFalse(failed.reloaded); assertFalse(failed.ok)

        // The point of the type: `check(result.ok)` must not fire on a no-op reload, which is what
        // wrapping the raw boolean `ComputeModulePointer.reload` in `check(...)` does.
        assertEquals(3, unchanged.sourceLength)
        assertTrue(unchanged.toString().contains("unchanged"))
        assertTrue(reloaded.toString().contains("camera and scene preserved"))
        assertTrue(failed.toString().contains("last working pipeline kept"))
        assertTrue(failed.toString().contains("no such function"), "the compiler's own message must survive")
        assertEquals(emptyList(), reloaded.errors)
    }

    // ── fixtures ─────────────────────────────────────────────────────

    /**
     * A camera looking down −Z from `(0,0,3)`, with [declaredUp] as written and the tracer's own
     * `right × forward` as the effective one — the same construction `updateFrame` performs.
     */
    private fun basis(declaredUp: Vector3f): PathTraceCameraBasis {
        val at = Vector3f(0f, 0f, 3f)
        val target = Vector3f(0f, 0f, 0f)
        val forward = Vector3f(target).sub(at).normalize()
        val up = Vector3f(declaredUp)
        val right = if (up.lengthSquared() < 1e-12f) Vector3f(1f, 0f, 0f)
        else Vector3f(forward).cross(Vector3f(up).normalize()).normalize()
        return PathTraceCameraBasis(
            at = at, target = target, declaredUp = declaredUp,
            effectiveUp = Vector3f(right).cross(forward).normalize(),
            right = right, forward = forward,
            fovRadians = 0.8, distanceToTarget = 3f
        )
    }

    private fun tuning(
        apertureRadius: Float = 0f,
        focusDistance: Float? = null,
        temporalReuse: Boolean = false,
        fogDensity: Float = 0f
    ): Map<String, Any?> = linkedMapOf(
        "maxBounces" to 5,
        "samplesPerFrame" to 1,
        "pixelJitter" to 1f,
        "apertureRadius" to apertureRadius,
        "focusDistance" to focusDistance,
        "exposure" to 1.5f,
        "indirectClamp" to 10f,
        "rayEpsilon" to 0.0001f,
        "environmentIntensity" to 1f,
        "environmentRotation" to 0f,
        "showEnvironment" to true,
        "background" to Vector4f(0f, 0f, 0f, 1f),
        "temporalReuse" to temporalReuse,
        "temporalMaxSamples" to 16,
        "temporalMotionSensitivity" to 1f,
        "temporalRoughnessSensitivity" to 1f,
        "temporalMaxBlurRadius" to 0.5f,
        "fogDensity" to fogDensity,
        "fogStartDistance" to 0f,
        "fogColor" to Vector3f(0.5f, 0.5f, 0.5f),
        "fogBackground" to true
    )

    private fun status(
        paused: Boolean = false,
        camera: PathTraceCameraBasis = basis(Vector3f(0f, 1f, 0f)),
        tuning: Map<String, Any?> = tuning(),
        recorded: PathTraceConvergenceSummary? = null
    ) = PathTraceStatus(
        name = "test-tracer",
        backend = PathTracingRenderer.Backend.COMPUTE_BVH,
        width = 640, height = 360, triangleCount = 6626,
        samples = 57, samplesPerFrame = 1, paused = paused,
        resetGeneration = 3, lastResetReason = "camera motion",
        camera = camera, tuning = tuning, recorded = recorded
    )
}
