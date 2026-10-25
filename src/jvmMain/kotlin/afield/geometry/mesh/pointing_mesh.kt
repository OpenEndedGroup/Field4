@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.mesh

import afield.animation.keyframes.h
import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.data.graph.main
import afield.document.graph.minusAssign
import afield.document.graph.named
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.graphics.interaction.PickScene
import afield.graphics.interaction.Pointer
import afield.graphics.interaction.RayCamera
import afield.graphics.interaction.rayCamera
import afield.graphics.interaction.rayCameraFor
import afield.graphics.render.rangeTo
import afield.graphics.render.rect
import afield.graphics.shader.mesh
import afield.io.osc.main
import afield.lang.stacksl.h
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.control.main
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.linalg.x
import afield.math.statistics.main
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.video.image
import afield.runtime.lifecycle.Once
import afield.simulation.agents.step
import afield.simulation.automata.main
import afield.simulation.automata.step
import afield.ui.output.h

import afield.audio.synth.*
import afield.graphics.interaction.Ray
import afield.graphics.visionos.VisionGeometryPreview
import afield.math.linalg.Matrix4f
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turning a place a person is looking at back into a place in the world.
 *
 * This file does one step of that: **pixel → ray**, the unprojection that gets you from a mouse
 * position, a controller pose or a headset's aim to a line in the scene. [PickScene] is the next
 * step, ray → named part, and [Pointer] the step before, person → pixel-or-pose. Once you have the
 * ray, [MeshBvh.raycast], `intersectRayTriangle` and `intersectRayPlane` answer what it hits.
 *
 * # The two camera models
 *
 * Field has two unrelated camera models, and confusing them is the main hazard here:
 *
 *  - **[MeshView]** — the orbital camera behind [TriMesh.renderPng], Field's CPU rasterizer. Yaw,
 *    pitch, a distance *in multiples of the mesh's own bounding-sphere radius*, and a field of view.
 *    This is the one that makes pixels with no GPU, no window and no AWT, so it is the one a headless
 *    test or a docs capture can prove things with. [MeshView.rayCamera] inverts it, and does so by
 *    calling straight into [VisionGeometryPreview.ViewFrame] — *the same object the rasterizer
 *    projects with*, so the unprojection is the renderer's inverse by construction rather than by
 *    transcription.
 *  - **[afield.graphics.vulkan.util.Camera]** — the live Vulkan scene's camera: a `lookAt` model-view and a
 *    [afield.graphics.vulkan.util.SuperProjection], which can be a perspective frustum, an off-centre one, an
 *    orthographic prism, or a Lie-group interpolation *between* perspective and ortho. There is no
 *    closed-form frustum to re-derive for that last case, so [rayCameraFor] handles all of them the
 *    same way: it inverts the clip matrix numerically, in double precision.
 *
 * # Conventions
 *
 *  - **Pixel coordinates run from the top-left of the frame, y downward**, which is what GLFW's
 *    cursor reports (`Window.cursor` is `glfwGetCursorPos`) and what an image's rows are indexed by.
 *    Pixel *centres* are at half-integers: pixel `(0, 0)` covers `[0,1) x [0,1)` and its centre is
 *    `(0.5, 0.5)`. All the `rayThroughPixel` overloads take the pixel *index* and aim at its centre.
 *  - **Field's clip space is Vulkan's depth range, `z` in `0..1`**, and `y` is *up* in NDC despite
 *    Vulkan's default being down — [afield.graphics.vulkan.Renderer] flips it with a negative-height viewport
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

/** Convenience: [MeshBvh.raycast] down a [Ray]. Distances come back in world units. */
fun MeshBvh.raycast(
    ray: Ray,
    maxDistance: Double = Double.MAX_VALUE,
    cullBackFaces: Boolean = false,
): RayHit? = raycast(ray.origin, ray.direction, maxDistance, cullBackFaces)

// ==============================================================================================
// Cameras, running backwards
// ==============================================================================================

