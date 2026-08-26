@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.graphics.VisionGeometryPreview
import afield.stdlib.linalg.Matrix4f
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turning a place a person is looking at back into a place in the world — the half of picking that
 * Field did not have.
 *
 * # Why this exists
 *
 * Field could already answer *"what does this ray hit"* — [MeshBvh.raycast], `intersectRayTriangle`,
 * `intersectRayPlane`. What it could not do was **produce the ray**. There was no unprojection
 * anywhere in `afield`: no way to get from a mouse position, a controller pose or a headset's aim to
 * a line in the scene. Every picker Field owned ran in JavaScript, and the JVM's rich hit record
 * ([FLineIntersection]) had exactly one constructor, decoding a browser payload.
 *
 * So this file is the missing middle: pixel → ray. [PickScene] is the next step, ray → named part,
 * and [Pointer] the step after, person → pixel-or-pose.
 *
 * # The two camera models, and why there are two
 *
 * Field has two unrelated camera models, and confusing them is the main hazard here:
 *
 *  - **[MeshView]** — the orbital camera behind [TriMesh.renderPng], Field's CPU rasterizer. Yaw,
 *    pitch, a distance *in multiples of the mesh's own bounding-sphere radius*, and a field of view.
 *    This is the one that makes pixels with no GPU, no window and no AWT, so it is the one a headless
 *    test or a docs capture can prove things with. [MeshView.rayCamera] inverts it, and does so by
 *    calling straight into [VisionGeometryPreview.ViewFrame] — *the same object the rasterizer
 *    projects with*. The unprojection is the renderer's inverse by construction, not by
 *    transcription, which is the only version of that claim worth making.
 *  - **[afield.four.util.Camera]** — the live Vulkan scene's camera: a `lookAt` model-view and a
 *    [afield.four.util.SuperProjection], which can be a perspective frustum, an off-centre one, an
 *    orthographic prism, or a Lie-group interpolation *between* perspective and ortho. There is no
 *    closed-form frustum to re-derive for that last case, so [rayCameraFor] does the only thing that
 *    is right for all of them: it inverts the clip matrix numerically, in double precision.
 *
 * # Conventions, stated because getting one wrong is silent
 *
 *  - **Pixel coordinates run from the top-left of the frame, y downward**, which is what GLFW's
 *    cursor reports (`Window.cursor` is `glfwGetCursorPos`) and what an image's rows are indexed by.
 *    Pixel *centres* are at half-integers: pixel `(0, 0)` covers `[0,1) x [0,1)` and its centre is
 *    `(0.5, 0.5)`. All the `rayThroughPixel` overloads take the pixel *index* and aim at its centre.
 *  - **Field's clip space is Vulkan's depth range, `z` in `0..1`**, and `y` is *up* in NDC despite
 *    Vulkan's default being down — [afield.four.Renderer] flips it with a negative-height viewport
 *    (`setViewportAndScissor` sets `y = rect.y + rect.h` and `height = -rect.h`), precisely so that
 *    the familiar GL-style projection matrices keep working. So NDC `y = +1` is the **top** row.
 *  - **A ray's direction is unit length**, so [RayCamera] hit distances are in world units and
 *    comparable between pointers.
 *
 * # Typical use
 *
 * ```kotlin
 * val mesh = icosphere(2)
 * val view = MeshView(yawDegrees = 30.0, pitchDegrees = 20.0)
 * val camera = view.rayCamera(mesh)
 * val hit = mesh.bvh().raycast(camera.rayThroughPixel(360, 270))
 * println(hit)                                  // the triangle under the middle of the picture
 * ```
 */

// ==============================================================================================
// Rays
// ==============================================================================================

/**
 * A half-line in world space: where it starts and, in unit length, where it goes.
 *
 * Stored in `Double`. That is not fussiness — an unprojection divides by a focal length and a depth,
 * and `Float` storage of the result costs about `1e-7` relative, which is the same order as the
 * tolerance a projection round trip wants to assert. [origin] and [direction] hand out fresh
 * single-precision [Vec3]s for the mesh queries, which take floats anyway.
 */
class Ray(
    /** Where the ray starts. */
    val originX: Double,
    val originY: Double,
    val originZ: Double,
    dirX: Double,
    dirY: Double,
    dirZ: Double,
) {
    /** Unit-length direction. */
    val dirX: Double
    val dirY: Double
    val dirZ: Double

    init {
        val len = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
        require(len > 0.0 && len.isFinite()) {
            "a ray needs a direction with a finite non-zero length, got ($dirX, $dirY, $dirZ)"
        }
        this.dirX = dirX / len
        this.dirY = dirY / len
        this.dirZ = dirZ / len
    }

    /** A **fresh** [Vec3] at the ray's start — mutating it does not touch the ray. */
    val origin: Vec3 get() = Vec3(originX.toFloat(), originY.toFloat(), originZ.toFloat())

    /** A **fresh** unit-length [Vec3] along the ray. */
    val direction: Vec3 get() = Vec3(dirX.toFloat(), dirY.toFloat(), dirZ.toFloat())

    /** The point [t] world units along the ray, in double precision. */
    fun at(t: Double): DoubleArray =
        doubleArrayOf(originX + dirX * t, originY + dirY * t, originZ + dirZ * t)

    /** [at], as a fresh [Vec3]. */
    fun pointAt(t: Double): Vec3 {
        val p = at(t)
        return Vec3(p[0].toFloat(), p[1].toFloat(), p[2].toFloat())
    }

    override fun toString(): String =
        "Ray(from (%.4f, %.4f, %.4f) along (%.4f, %.4f, %.4f))"
            .format(originX, originY, originZ, dirX, dirY, dirZ)

    companion object {
        /** A ray from two points. The direction need not be normalized; [Ray] normalizes it. */
        fun through(from: Vec3, to: Vec3): Ray = Ray(
            from.x.toDouble(), from.y.toDouble(), from.z.toDouble(),
            (to.x - from.x).toDouble(), (to.y - from.y).toDouble(), (to.z - from.z).toDouble(),
        )
    }
}

/** Convenience: [MeshBvh.raycast] down a [Ray]. Distances come back in world units. */
fun MeshBvh.raycast(
    ray: Ray,
    maxDistance: Double = Double.MAX_VALUE,
    cullBackFaces: Boolean = false,
): RayHit? = raycast(ray.origin, ray.direction, maxDistance, cullBackFaces)

// ==============================================================================================
// Cameras, running backwards
// ==============================================================================================

/** Where a world point landed: a pixel, and how far in front of the eye it was. */
data class PixelAt(
    /** Column, in pixels from the left edge. Not rounded — a point lands between pixels. */
    val x: Double,
    /** Row, in pixels from the **top** edge. */
    val y: Double,
    /**
     * Depth. Its units are the camera's own business — [MeshViewRayCamera] reports view-space `z`
     * in world units, [ClipMatrixRayCamera] reports normalized device `z` in `0..1` — so compare
     * two depths from the same camera and nothing else.
     */
    val depth: Double,
) {
    /** True when this landed inside a `width` x `height` frame. */
    fun isInside(width: Int, height: Int): Boolean = x >= 0 && y >= 0 && x < width && y < height
}

/**
 * A camera you can run backwards: pixel to ray, and world point back to pixel.
 *
 * Both directions, because one direction alone cannot be tested. A round trip — unproject a pixel,
 * take a point along the ray, project it back — is the only cheap check that catches a flipped y, a
 * transposed matrix, an aspect ratio applied to the wrong axis or a caption band left out. That check
 * is only meaningful if the projection is non-degenerate to begin with, which is why
 * [isNonDegenerate] exists and why the tests assert it *first*.
 */
interface RayCamera {
    /** Frame width in pixels. */
    val pixelWidth: Int

    /** Frame height in pixels. */
    val pixelHeight: Int

    /** The ray leaving the eye through the point `(x, y)` in pixel coordinates, y downward. */
    fun rayThrough(x: Double, y: Double): Ray

    /** [rayThrough] the *centre* of pixel `(x, y)` — i.e. through `(x + 0.5, y + 0.5)`. */
    fun rayThroughPixel(x: Int, y: Int): Ray = rayThrough(x + 0.5, y + 0.5)

    /**
     * Where a world point lands, or `null` when it is at or behind the eye and so has no pixel.
     *
     * The inverse of [rayThrough] up to the depth [rayThrough] cannot know.
     */
    fun pixelOf(x: Double, y: Double, z: Double): PixelAt?

    /** [pixelOf] a [Vec3]. */
    fun pixelOf(point: Vec3): PixelAt? = pixelOf(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())

    /**
     * Whether this camera actually spreads the frame out — the premise every round-trip test needs.
     *
     * A camera that collapsed the scene to a point, or whose clip matrix is singular, would make a
     * round trip trivially exact and a picking test vacuous. The check is deliberately about
     * *observable pixels* rather than about a determinant: it fires four rays through the frame's
     * quarter points and requires their directions to be measurably different. See
     * [nonDegeneracyMargin] for the number.
     */
    val isNonDegenerate: Boolean get() = nonDegeneracyMargin() > 1e-3

    /**
     * The smallest angle, in radians, between the rays through the frame's four quarter points.
     *
     * A useful number rather than a boolean: a test can floor it, and a caller can see how wide the
     * camera is. Zero means every pixel looks the same way, which is what a broken projection does.
     */
    fun nonDegeneracyMargin(): Double {
        val qx = pixelWidth * 0.25
        val qy = pixelHeight * 0.25
        val rays = listOf(
            rayThrough(qx, qy), rayThrough(3 * qx, qy),
            rayThrough(qx, 3 * qy), rayThrough(3 * qx, 3 * qy),
        )
        var worst = Double.MAX_VALUE
        for (i in rays.indices) for (j in i + 1 until rays.size) {
            val a = rays[i]; val b = rays[j]
            val dot = (a.dirX * b.dirX + a.dirY * b.dirY + a.dirZ * b.dirZ).coerceIn(-1.0, 1.0)
            val angle = kotlin.math.acos(dot)
            if (angle < worst) worst = angle
        }
        return if (worst == Double.MAX_VALUE) 0.0 else worst
    }
}

// ----------------------------------------------------------------------------------------------
// The rasterizer's camera
// ----------------------------------------------------------------------------------------------

/**
 * [MeshView]'s camera, inverted — the one that maps pixels of a [TriMesh.renderPng] back into the
 * world.
 *
 * It holds a [VisionGeometryPreview.ViewFrame], which is *the rasterizer's own projection object*,
 * not a copy of its arithmetic. So [pixelOf] here and the `sx`/`sy` the renderer writes to the depth
 * buffer are the same expression evaluated twice, and [rayThrough] is that expression solved.
 *
 * ## The mesh, and the caption, are part of the camera
 *
 * Two things about this camera are not in the [MeshView]:
 *
 *  - **[MeshView.distance] is in mesh radii and the eye is aimed at the mesh's bounding-box centre**,
 *    so a camera built for one mesh points at the wrong place for another. [MeshView.rayCamera] takes
 *    the mesh for that reason, and the camera is only valid for it.
 *  - **A caption shortens the frame.** [VisionGeometryPreview.Style.reservedBottom] takes a band off
 *    the bottom for the caption and stats strip, the model is framed in what is left, and the
 *    projection divides `sy` by that shorter height. So a camera built with no caption does not match
 *    a render made *with* one — the answer is wrong by a few percent in y, which looks entirely
 *    plausible. [MeshView.rayCamera] takes the same `caption`/`title` as [TriMesh.renderPng] so the
 *    two agree; pass the same values to both.
 *
 * [depth][PixelAt.depth] here is **view-space z in world units** — the distance in front of the eye
 * measured along the view axis, which is exactly what the rasterizer's z-buffer holds. That makes a
 * direct comparison against the z-buffer possible, and `SpatialPointerPixelTest` makes it.
 */
class MeshViewRayCamera(
    /** The rasterizer's projection, both ways. */
    val frame: VisionGeometryPreview.ViewFrame,
) : RayCamera {

    override val pixelWidth: Int get() = frame.width / frame.supersample
    override val pixelHeight: Int get() = frame.height / frame.supersample

    /** Where the eye stands, as a fresh [Vec3]. */
    val eye: Vec3
        get() = Vec3(frame.eyeX.toFloat(), frame.eyeY.toFloat(), frame.eyeZ.toFloat())

    override fun rayThrough(x: Double, y: Double): Ray {
        val r = frame.rayThroughSample(x * frame.supersample, y * frame.supersample)
        return Ray(r[0], r[1], r[2], r[3], r[4], r[5])
    }

    override fun rayThroughPixel(x: Int, y: Int): Ray {
        val r = frame.rayThroughPixel(x.toDouble(), y.toDouble())
        return Ray(r[0], r[1], r[2], r[3], r[4], r[5])
    }

    override fun pixelOf(x: Double, y: Double, z: Double): PixelAt? {
        val v = frame.toView(x, y, z)
        if (v[2] <= frame.near) return null
        val p = frame.project(v[0], v[1], v[2])
        return PixelAt(p[0] / frame.supersample, p[1] / frame.supersample, v[2])
    }
}

/**
 * The camera [TriMesh.renderPng] would use for [mesh] under this view — inverted, so its pixels map
 * back into the world.
 *
 * Pass the **same** `caption` and `title` you pass to [TriMesh.renderPng]. They are not decoration:
 * a caption reserves a band at the bottom of the frame and the projection is computed against the
 * shortened height. The defaults here match [TriMesh.renderPng]'s defaults, so
 * `mesh.renderPng(view)` and `view.rayCamera(mesh)` agree with no arguments at all.
 */
fun MeshView.rayCamera(mesh: TriMesh, caption: String? = null, title: String? = null): MeshViewRayCamera =
    MeshViewRayCamera(VisionGeometryPreview.viewFrame(mesh.toCapturedMesh(), camera(), style(caption, title)))

// ----------------------------------------------------------------------------------------------
// The general camera: invert the clip matrix
// ----------------------------------------------------------------------------------------------

/**
 * Any projection at all, inverted numerically: a clip matrix, a frame size, and double-precision
 * Gauss-Jordan.
 *
 * ## Why not re-derive the frustum
 *
 * Because [afield.four.util.SuperProjection] is not always a frustum. It Lie-interpolates in `GL(4)`
 * between an off-centre perspective matrix and an orthographic prism —
 * `P(t) = P0 * exp(t * log(P0^-1 * P1))` — so at `orthoness = 0.3` there is no field of view to take
 * a tangent of and no eye point that all the rays pass through. Inverting the matrix is the only
 * treatment that is correct for perspective, for off-centre perspective, for ortho and for
 * everything in between, and it costs one 4x4 inversion per camera rather than per ray.
 *
 * A consequence worth knowing: **under an orthographic or near-orthographic projection the rays are
 * parallel and [Ray.origin] is not a shared eye point** — it is the point where that pixel's ray
 * crosses the near plane. That is the right answer for picking, and the wrong thing to average.
 *
 * ## Conventions
 *
 * [clip] is the matrix a vertex shader would apply, i.e. `projection * modelView` — Field's shaders
 * spell it exactly that way (`pc.proj * pc.modelView * vec4(pos, 1.0)`). Clip space is Vulkan's
 * depth range, `z` in `0..1` with `0` at the near plane; `y` is **up** in NDC, because
 * [afield.four.Renderer] renders through a negative-height viewport. [PixelAt.depth] is therefore
 * normalized device `z` in `0..1`, *not* a world distance — [Ray] carries the world distance.
 */
class ClipMatrixRayCamera(
    clip: Matrix4f,
    override val pixelWidth: Int,
    override val pixelHeight: Int,
) : RayCamera {

    /** The forward matrix, in double precision, column-major: `forward[c * 4 + r]`. */
    private val forward = DoubleArray(16) { clip[it].toDouble() }

    /** Its inverse, or a throw — a singular clip matrix is a bug, not a state to tolerate. */
    private val inverse: DoubleArray = invert4(forward)
        ?: throw IllegalArgumentException(
            "this clip matrix is singular, so no pixel has a ray behind it. " +
                "A projection with zNear == zFar, or an all-zero one from a camera that was never " +
                "resized, does this. Matrix: ${forward.toList()}"
        )

    init {
        require(pixelWidth > 0 && pixelHeight > 0) {
            "a frame needs a positive size, got ${pixelWidth}x$pixelHeight"
        }
    }

    override fun rayThrough(x: Double, y: Double): Ray {
        val ndcX = 2.0 * x / pixelWidth - 1.0
        val ndcY = 1.0 - 2.0 * y / pixelHeight
        val near = unproject(ndcX, ndcY, 0.0)
        val far = unproject(ndcX, ndcY, 1.0)
        return Ray(
            near[0], near[1], near[2],
            far[0] - near[0], far[1] - near[1], far[2] - near[2],
        )
    }

    override fun pixelOf(x: Double, y: Double, z: Double): PixelAt? {
        val cx = transform4(forward, x, y, z, 1.0)
        // w <= 0 is behind (or on) the eye under perspective; under ortho w is 1 and this never fires,
        // which is correct — an orthographic camera sees what is behind it too, and the depth says so.
        if (cx[3] <= 0.0) return null
        val ndcX = cx[0] / cx[3]
        val ndcY = cx[1] / cx[3]
        val ndcZ = cx[2] / cx[3]
        return PixelAt(
            (ndcX + 1.0) * 0.5 * pixelWidth,
            (1.0 - ndcY) * 0.5 * pixelHeight,
            ndcZ,
        )
    }

    /** NDC to world, dividing through by `w`. */
    private fun unproject(ndcX: Double, ndcY: Double, ndcZ: Double): DoubleArray {
        val p = transform4(inverse, ndcX, ndcY, ndcZ, 1.0)
        val w = p[3]
        require(abs(w) > 1e-12) { "unprojecting ($ndcX, $ndcY, $ndcZ) gave w = $w — the clip matrix maps it to infinity" }
        return doubleArrayOf(p[0] / w, p[1] / w, p[2] / w)
    }

}

// ----------------------------------------------------------------------------------------------
// Double-precision 4x4, because a projection's inverse in Float is not good enough
// ----------------------------------------------------------------------------------------------

/**
 * `m * (x, y, z, w)`, with `m` stored column-major as `m[column * 4 + row]` — the same layout
 * [Matrix4f]'s indexed accessor uses, so `DoubleArray(16) { matrix[it].toDouble() }` converts.
 */
internal fun transform4(m: DoubleArray, x: Double, y: Double, z: Double, w: Double): DoubleArray =
    DoubleArray(4) { r -> m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r] * w }

/**
 * A 4x4 inverse by Gauss-Jordan with partial pivoting, in double precision. `null` when singular.
 *
 * Written out rather than borrowed from `Matrix4f.invert()` for one reason: a projection matrix's
 * inverse in single precision loses enough near the far plane to move a pick by a pixel, and the
 * point of this file is to be able to state a tolerance and mean it.
 */
internal fun invert4(m: DoubleArray): DoubleArray? {
    // Augmented 4x8, row-major for the elimination — easier to read, converted back at the end.
    val a = Array(4) { r -> DoubleArray(8) { c -> if (c < 4) m[c * 4 + r] else if (c - 4 == r) 1.0 else 0.0 } }
    for (col in 0 until 4) {
        var pivot = col
        for (r in col + 1 until 4) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
        if (abs(a[pivot][col]) < 1e-14) return null
        val swap = a[col]; a[col] = a[pivot]; a[pivot] = swap
        val d = a[col][col]
        for (c in 0 until 8) a[col][c] /= d
        for (r in 0 until 4) {
            if (r == col) continue
            val f = a[r][col]
            if (f == 0.0) continue
            for (c in 0 until 8) a[r][c] -= f * a[col][c]
        }
    }
    return DoubleArray(16) { i -> a[i % 4][4 + i / 4] }
}

/**
 * A [RayCamera] over a projection and a model-view, in the order a vertex shader applies them.
 *
 * The primitive the rest of the layer is built on, and the one a headless test can reach: it takes
 * matrices rather than a live [afield.four.util.Camera], which needs a `Renderer` and therefore a
 * window.
 *
 * ```kotlin
 * val projection = SuperProjection(1280, 720)            // no renderer needed
 * val modelView = Matrix4f().lookAtField(vec(0, 2, 6), vec(0, 0, 0), vec(0, 1, 0))
 * val camera = rayCameraFor(projection.projectionMatrix, modelView, 1280, 720)
 * ```
 */
fun rayCameraFor(projection: Matrix4f, modelView: Matrix4f, pixelWidth: Int, pixelHeight: Int): ClipMatrixRayCamera =
    ClipMatrixRayCamera(Matrix4f(projection).mul(modelView), pixelWidth, pixelHeight)

/**
 * This live scene camera, inverted — so a mouse position in the window becomes a ray in the scene.
 *
 * Evaluates both uniforms *now* ([afield.four.util.SuperProjection.evaluateUniform] runs its `auto`
 * hook, which re-reads the window size), so the camera is a snapshot: build a fresh one each frame,
 * or after the window resizes, rather than holding one.
 */
fun afield.four.util.Camera.rayCamera(
    pixelWidth: Int = renderer.window.width,
    pixelHeight: Int = renderer.window.height,
): ClipMatrixRayCamera = rayCameraFor(p.evaluateUniform(), m.evaluateUniform(), pixelWidth, pixelHeight)
