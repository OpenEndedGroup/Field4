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
 * Pointing a [Ray] at a mesh and asking what it hits.
 *
 * One extension lives here, because the two halves of this question are in different packages.
 * Rays are made in `afield.graphics.interaction`: [Pointer] is what a person points with, and the
 * unprojection behind [RayCamera] turns the pixel or the pose it reports into a [Ray]. The
 * structure that answers which triangle a ray meets is [MeshBvh], which belongs with the meshes and
 * takes an origin and a direction as two separate [Vec3] arguments. The overload below unpacks a
 * [Ray] into them.
 *
 * Two things follow from that hop and are easier to state here than at the call site. A [Ray]
 * normalises its direction when it is constructed, so the distance in the [RayHit] that comes back
 * is in world units however the caller built the ray. And a [Ray] holds its origin and direction in
 * double precision while [Vec3] is float, so both narrow at this call before the intersection test
 * widens them again and runs in double.
 *
 * For ray to *named part* rather than ray to triangle, see [PickScene].
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

