@file:afield.runtime.completion.CompletionCategory("Math")

package afield.geometry.mesh

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.geometry.spatial.Cuboid
import afield.geometry.spatial.aabbBounds
import afield.geometry.spatial.center
import afield.geometry.spatial.convexHullPeelingMedian
import afield.geometry.spatial.medianVec
import afield.geometry.spatial.obbBounds
import afield.graphics.render.rangeTo
import afield.graphics.text.layout
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.median
import afield.study.ui.numbers

import afield.audio.synth.*
import afield.math.linalg.Vec3
import afield.math.linalg.i
import afield.math.linalg.x
import com.github.quickhull3d.QuickHull3D
import afield.math.linalg.Vector2f
import afield.math.linalg.Vector2fc
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector3fc
import afield.math.linalg.Vector4f
import afield.math.linalg.Vector4fc
import afield.math.linalg.Quaternionf
import afield.math.linalg.Quaternionfc
import afield.math.linalg.Matrix3f
import afield.math.linalg.Matrix3fc
import afield.math.linalg.Matrix4f
import afield.math.linalg.Matrix4fc
import afield.math.linalg.DualQuaternionf
import afield.math.linalg.*
import kotlin.math.max
import kotlin.math.min

/**
 * The 3D convex hull of a list of [Vec3], computed with the QuickHull3D library.
 *
 * One function, `convexHull`, which keeps the vertices on the outside of the cloud and drops the
 * interior ones. Vertices only, in the library's own order, with no faces and no winding. Fewer
 * than four points, or a cloud QuickHull cannot triangulate, returns the input unchanged rather
 * than throwing, so a result the same size as the input may be a hull or a refusal and the two
 * cannot be told apart from the return value.
 *
 * The vector types themselves are in `linalg.kt`. Centroids, medians and bounding boxes for the
 * same point collections are in `linalg_spatial.kt`, which also holds `convexHullPeelingMedian`
 * and the `asQuickHullPoints` packer.
 */

/**
 * The vertices of the 3D convex hull, via QuickHull3D — the points on the outside, with the interior
 * ones dropped.
 *
 * Vertices only, in the library's own order, with no faces or winding; and they are fresh [Vec3]s, not
 * the receiver's instances. Fewer than four points, or a degenerate cloud QuickHull cannot triangulate
 * (all coplanar, all collinear, duplicates), returns **the input unchanged** rather than throwing — so
 * a result the same size as the input means "hull, or gave up", and the two are not distinguishable
 * from the return value alone.
 */
fun List<Vec3>.convexHull(): List<Vec3> {
    if (size < 4) return this.toList()  // Need at least 4 non-coplanar points
    
    val points = DoubleArray(size * 3)
    for (i in indices) {
        points[i * 3] = this[i].x.toDouble()
        points[i * 3 + 1] = this[i].y.toDouble()
        points[i * 3 + 2] = this[i].z.toDouble()
    }
    
    return try {
        val qh = QuickHull3D()
        qh.build(points)
        
        val hullVerts = qh.vertices
        hullVerts.map { Vec3(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }
    } catch (e: Exception) {
        // If hull computation fails (coplanar points, etc.), return original
        this.toList()
    }
}

