package afield.graphics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The viewer's camera preset against the CPU rasterizer's, executed rather than eyeballed.
 *
 * # Why this test exists
 *
 * When the mesh examples moved from [VisionGeometryPreview]'s rasterizer onto the Three.js viewer,
 * the camera came with them: a docs figure picks its angle deliberately — half of what
 * `geometry_from_disk.md` is *for* is that the toy car is seen from the one viewpoint that shows the
 * draped cloth — so `MeshView`'s four numbers had to mean the same thing on both sides.
 *
 * That made the viewer's `cameraPresetEye` a **second implementation** of
 * `VisionGeometryPreview.renderPixels`'s eye placement. Written twice, in two languages, and the
 * failure mode if they drift is not a crash: it is sixty docs figures that are each slightly, silently
 * recomposed, which is exactly the kind of diff nobody reads carefully.
 *
 * So the JavaScript *runs*, on GraalJS, through [ViewerJavaScript] — the seam built for precisely this.
 *
 * # The two details worth the trouble
 *
 * Both are the sort of thing that looks right and is not:
 *
 *  * **`radius` is half the bounding-box diagonal**, not half its largest dimension. The
 *    [aLongThinMeshIsWhereTheDiagonalAndTheLargestDimensionDisagree] counterfactual measures what the
 *    wrong one costs — for a 10 × 1 × 1 box it is a 40% error in eye distance, which frames the mesh
 *    visibly closer without ever looking obviously broken.
 *  * **yaw drives `sin` on x and `cos` on z**, so yaw 0 / pitch 0 stands the eye on `+z` looking down
 *    `-z`. Swapping them transposes the turntable — every angle in `MeshView.turntable` lands
 *    somewhere else, and a four-up contact sheet still looks like a perfectly reasonable contact
 *    sheet.
 */
class ThreeCameraPresetParityTest {

    /** The Kotlin original: `VisionGeometryPreview.renderPixels`'s eye placement, and only that. */
    private fun kotlinEye(spec: ThreeCameraSpec, bounds: DoubleArray): DoubleArray {
        val cx = (bounds[0] + bounds[3]) / 2
        val cy = (bounds[1] + bounds[4]) / 2
        val cz = (bounds[2] + bounds[5]) / 2
        val radius = max(
            1e-4,
            0.5 * sqrt(
                (bounds[3] - bounds[0]) * (bounds[3] - bounds[0]) +
                    (bounds[4] - bounds[1]) * (bounds[4] - bounds[1]) +
                    (bounds[5] - bounds[2]) * (bounds[5] - bounds[2]),
            ),
        )
        val yaw = Math.toRadians(spec.yawDegrees)
        val pitch = Math.toRadians(spec.pitchDegrees)
        val eyeDistance = radius * spec.distance
        return doubleArrayOf(
            cx + eyeDistance * cos(pitch) * sin(yaw),
            cy + eyeDistance * sin(pitch),
            cz + eyeDistance * cos(pitch) * cos(yaw),
            radius,
        )
    }

    private fun boundsJson(b: DoubleArray) =
        """{"min":[${b[0]},${b[1]},${b[2]}],"max":[${b[3]},${b[4]},${b[5]}]}"""

    private fun specJson(s: ThreeCameraSpec) =
        """{"yawDegrees":${s.yawDegrees},"pitchDegrees":${s.pitchDegrees},""" +
            """"distance":${s.distance},"fieldOfViewDegrees":${s.fieldOfViewDegrees}}"""

    /** A spread that covers all four quadrants of yaw, both signs of pitch, and two lens lengths. */
    private val specs = buildList {
        for (yaw in listOf(0.0, 28.0, 90.0, 180.0, 270.0, 315.0, -45.0)) {
            for (pitch in listOf(0.0, 18.0, 52.0, -30.0)) {
                add(ThreeCameraSpec(yaw, pitch, 2.7, 40.0))
            }
        }
        add(ThreeCameraSpec(34.0, 26.0, 2.4, 42.0))
        add(ThreeCameraSpec(22.0, 14.0, 3.2, 25.0))
    }

    /** Shapes that exercise the diagonal-versus-largest-dimension distinction. */
    private val shapes = mapOf(
        "unit cube at the origin" to doubleArrayOf(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5),
        "long thin box" to doubleArrayOf(-5.0, -0.5, -0.5, 5.0, 0.5, 0.5),
        "flat sheet, zero depth" to doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 0.0),
        "off-centre 7cm toy, in metres" to doubleArrayOf(0.11, -0.02, 0.4, 0.183, 0.009, 0.474),
        "degenerate point" to doubleArrayOf(2.0, 2.0, 2.0, 2.0, 2.0, 2.0),
    )

    @Test
    fun `the viewer places the eye exactly where the rasterizer does`() {
        ViewerJavaScript.Session.open(
            regionName = "camera-preset",
            exports = listOf("cameraPresetEye"),
        ).use { js ->
            var checked = 0
            for ((shapeName, bounds) in shapes) {
                for (spec in specs) {
                    val expected = kotlinEye(spec, bounds)
                    val actual = js.call(
                        "cameraPresetEye",
                        js.fromJson(specJson(spec)),
                        js.fromJson(boundsJson(bounds)),
                    )
                    val eye = actual.getMember("eye")
                    val radius = actual.getMember("radius").asDouble()

                    // The scale of the geometry, so the tolerance means the same thing for a 7cm toy
                    // and a 10-unit box. Float64 on both sides; this is round-off, not slack.
                    val tolerance = 1e-9 * max(1.0, expected[3])
                    for (axis in 0..2) {
                        val got = eye.getArrayElement(axis.toLong()).asDouble()
                        assertTrue(
                            abs(got - expected[axis]) <= tolerance,
                            "$shapeName, $spec: eye[$axis] was $got, the rasterizer puts it at " +
                                "${expected[axis]}",
                        )
                    }
                    assertTrue(
                        abs(radius - expected[3]) <= tolerance,
                        "$shapeName, $spec: radius was $radius, the rasterizer computes ${expected[3]}",
                    )
                    checked += 1
                }
            }
            assertTrue(checked == shapes.size * specs.size, "expected every pair to be checked")
        }
    }

    /**
     * The near plane too, because a wrong one clips the mesh rather than merely moving the camera.
     *
     * The rasterizer puts it at a twentieth of the radius. A viewer that used a fixed near plane
     * instead would be fine on a unit cube and would slice the front off the 7cm toy car.
     */
    @Test
    fun `the near plane follows the radius, as the rasterizer's does`() {
        ViewerJavaScript.Session.open(
            regionName = "camera-preset",
            exports = listOf("cameraPresetEye"),
        ).use { js ->
            for ((shapeName, bounds) in shapes) {
                val derived = js.call(
                    "cameraPresetEye",
                    js.fromJson(specJson(ThreeCameraSpec())),
                    js.fromJson(boundsJson(bounds)),
                )
                val radius = derived.getMember("radius").asDouble()
                val near = derived.getMember("near").asDouble()
                val expected = max(1e-5, radius * 0.05)
                assertTrue(
                    abs(near - expected) <= 1e-12 * max(1.0, radius),
                    "$shapeName: near was $near, the rasterizer uses $expected",
                )
                assertTrue(near > 0.0, "$shapeName: a near plane at or behind the eye is never right")
                assertTrue(
                    derived.getMember("far").asDouble() > near,
                    "$shapeName: far must be in front of near",
                )
            }
        }
    }

    /**
     * What the diagonal buys, measured rather than asserted.
     *
     * Rewrites the region to use half the largest dimension — the plausible wrong choice — and shows
     * it disagrees. Without this, the parity test above would still pass if *both* sides were wrong
     * in the same way, and it would pass just as loudly.
     */
    @Test
    fun `a long thin mesh is where the diagonal and the largest dimension disagree`() {
        val bounds = shapes.getValue("long thin box")
        val spec = ThreeCameraSpec()
        ViewerJavaScript.Session.open(
            regionName = "camera-preset",
            exports = listOf("cameraPresetEye"),
            perturbation = { source ->
                source.replace(
                    "Math.max(1e-4, 0.5 * Math.sqrt(dx * dx + dy * dy + dz * dz))",
                    "Math.max(1e-4, 0.5 * Math.max(dx, Math.max(dy, dz)))",
                )
            },
        ).use { js ->
            val wrong = js.call(
                "cameraPresetEye",
                js.fromJson(specJson(spec)),
                js.fromJson(boundsJson(bounds)),
            ).getMember("radius").asDouble()
            val right = kotlinEye(spec, bounds)[3]

            // 10 x 1 x 1: the diagonal is sqrt(102)/2 ~= 5.05, the largest dimension 5.0 — but the
            // ratio is what a reader wants, so state it rather than the two numbers.
            val error = abs(wrong - right) / right
            assertTrue(
                error > 0.009,
                "the largest-dimension radius ($wrong) should differ from the diagonal one ($right) " +
                    "on a long thin box; it differed by ${"%.3f".format(error * 100)}%, which is too " +
                    "little for this counterfactual to be measuring anything",
            )
        }
    }
}
