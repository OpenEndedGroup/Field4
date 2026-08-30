package afield.four

import afield.four.util.Options
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector4f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the *sign* of the default perspective projection, and the framing it was pinned to.
 *
 * ## What went wrong
 *
 * [Options.fov] held a bare `30.0`: a degrees literal in a slot that
 * `Matrix4f.setPerspective(fovy, …)` and `SuperProjection`'s `tan(fov / 2)` both read as radians.
 * The interesting part is that this was not a wide lens. `15 rad` wraps into the second quadrant,
 * where tan is negative, so `h = 1 / tan(fov / 2)` came out at `-1.168` and the matrix negated
 * `m00` and `m11` **together** — a 180° rotation of the picture rather than a mirror of it.
 *
 * A rotation is exactly the failure a renderer cannot notice. It preserves triangle winding, so
 * backface culling stayed quiet; it preserves handedness, so nothing about the depth buffer or the
 * lighting looked wrong; and for the roughly symmetric scenes the docs harness renders, it mostly
 * reads as "the picture is upside down", which invites a search for a flipped readback. It was
 * chased into `TexturePreviewCache` twice.
 *
 * ## Why the assertions are shaped like this
 *
 * Asserting an fov *value* would be worthless — the value is the thing that was wrong, and a test
 * restating it just blesses whatever is there. So this asserts the two properties the value has to
 * have, each derived from something outside itself:
 *
 * - **Sign**, from the viewport convention. Every render path uses the negative-height Y-flip (see
 *   [CaptureOrientation.viewportConventionSites]), which maps clip `y = +1` to image row 0. For
 *   world-up to land at the top of the image, `m11` must be positive. `m00` follows, since both
 *   are `h` scaled by aspect and cannot disagree.
 * - **Framing**, from the committed figures. `10π - 30` was chosen so `|tan(fov / 2)|` is unchanged
 *   from the broken `30.0`, because every figure on `fabricate_renderer.md` was composed against
 *   that lens. If someone reframes to a conventional 30° that is a deliberate act with a docs
 *   regeneration attached, and this assertion is the place it announces itself.
 */
class ProjectionOrientationTest {

    private val aspect = 1024f / 768f

    private fun defaultPerspective(): Matrix4f = Matrix4f().setPerspective(
        Options.fov.toFloat(), aspect, Options.zNear.toFloat(), Options.zFar.toFloat(), true
    )

    @Test
    fun defaultFovIsPositive() {
        assertTrue(
            Options.fov > 0.0,
            "Options.fov must be a positive radian value, was ${Options.fov}. A negative fovy does " +
                    "not flip the camera, it 180°-rotates the image via h = 1 / tan(fov / 2)."
        )
        assertTrue(
            Options.fov < PI,
            "Options.fov must be under π radians, was ${Options.fov}. Above that, tan(fov / 2) " +
                    "wraps quadrant and the sign of the projection stops meaning what it says — " +
                    "which is the whole bug this test exists for."
        )
    }

    @Test
    fun defaultProjectionDoesNotMirror() {
        val m = defaultPerspective()
        assertTrue(
            m.m11 > 0f,
            "perspective m11 must be positive, was ${m.m11}. Every viewport in afield.four uses " +
                    "the negative-height Y-flip, so clip y = +1 is image row 0; a negative m11 " +
                    "sends world-up to the bottom of every capture."
        )
        assertTrue(
            m.m00 > 0f,
            "perspective m00 must be positive, was ${m.m00}. A negative m00 mirrors the image " +
                    "left-to-right, and because it arrives together with a negative m11 the result " +
                    "is a winding-preserving 180° rotation that culling cannot flag."
        )
    }

    @Test
    fun worldUpProjectsToPositiveClipY() {
        // Derived from the viewport, not from the matrix: a point above the view axis has to come
        // out at positive clip y, because that is the end the Y-flipped viewport puts at row 0.
        val m = defaultPerspective()
        val above = m * Vector4f(0f, 1f, -5f, 1f)
        assertTrue(
            above.y > 0f,
            "a point one unit above the view axis projected to clip y = ${above.y}; it must be " +
                    "positive or the image is upside down in every render target and the swapchain."
        )
    }

    @Test
    fun defaultFovPreservesTheFramingTheFiguresWereComposedAgainst() {
        // |tan(fov / 2)| of the pre-fix `30.0`, which is the lens fabricate_renderer.md's twelve
        // figures were framed with. Spelled as the old expression rather than as 0.855993 so it is
        // legible as "the thing we are matching".
        val brokenHalfTan = tan(30.0 * 0.5)
        assertTrue(brokenHalfTan < 0.0, "sanity: tan(15 rad) is negative, which was the bug")

        assertEquals(
            abs(brokenHalfTan), tan(Options.fov * 0.5), 1e-12,
            "Options.fov no longer reproduces the framing of the committed figures. That is a " +
                    "reframing, not a bug fix: regenerate public/docs/assets/fabricate-*.png and " +
                    "re-derive the FIELD_TEST region coordinates on that page before changing this."
        )
    }
}
