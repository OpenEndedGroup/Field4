package afield.four.fabricated

import afield.four.buffers.DynamicBuffer
import afield.four.buffers.DynamicallyWriteable
import afield.stdlib.Vec3
import org.lwjgl.util.par.ParShapes.*
import org.lwjgl.util.par.ParShapesMesh
import kotlin.math.sqrt

/**
 * Ten par_shapes primitives, written straight into vertex-pulling buffers as triangle soup.
 *
 * [StandardShapes] is the layer under `StandardRenderer.Bundle.registerCube`, `registerSphere` and
 * the eight other `register*` shape helpers, one per function here. Each builds a par_shapes mesh,
 * scales it, translates it if the call gave a centre, and writes it into two buffers the caller
 * supplies, one for positions and one for normals. The write is **non-indexed**: the source mesh's
 * triangle list is expanded, so three vertices go out per triangle and a vertex-pulling shader
 * reads a position and its normal at the same index with no indirection. `ShapeWriteResult` comes
 * back carrying the source mesh's vertex and triangle counts alongside what was actually written,
 * which is how a caller sizes its mesh afterwards instead of predicting the triangle count up
 * front.
 *
 * `WriteConfig` covers the two decisions a caller usually has to make. `padVec3To4` writes each
 * vector as four floats for std430 alignment and is on by default; `append` chooses between
 * continuing after whatever is already in the buffer and starting at its beginning, and all ten
 * `register*` helpers pass `append = false`. Normals come from par_shapes, computed there if the
 * mesh arrived without any, with an index that falls outside the normal buffer reading the
 * triangle's face normal instead. Each call frees its par_shapes mesh before returning.
 */

/**
 * Thin Kotlin helpers around par_shapes that emit **non-indexed** positions + normals
 * into caller-provided [DynamicallyWriteable] buffers (useful for vertex-pulling tests).
 */
object StandardShapes {

    data class WriteConfig(
        val append: Boolean = true,            // when false, starts writing at buffer start
        val padVec3To4: Boolean = true,        // write vec3 as (x,y,z,w) for std430 alignment
        val positionW: Float = 1f,
        val normalW: Float = 0f
    )

    data class ShapeWriteResult(
        val sourceVertices: Int,
        val sourceTriangles: Int,
        val writtenVertices: Int,
        val positionFloatsWritten: Int,
        val normalFloatsWritten: Int
    )

    fun sphere(
        positions: DynamicallyWriteable,
        normals: DynamicallyWriteable,
        radius: Float = 1f,
        slices: Int = 32,
        stacks: Int = 32,
        center: Vec3? = null,
        config: WriteConfig = WriteConfig()
    ): ShapeWriteResult = buildAndWrite(positions, normals, config, {
        par_shapes_create_parametric_sphere(slices, stacks)
    }) { mesh ->
        par_shapes_scale(mesh, radius, radius, radius)
        center?.let { par_shapes_translate(mesh, it.x, it.y, it.z) }
    }

    fun hemisphere(
        positions: DynamicallyWriteable,
        normals: DynamicallyWriteable,
        radius: Float = 1f,
        slices: Int = 32,
        stacks: Int = 32,
        config: WriteConfig = WriteConfig()
    ): ShapeWriteResult = buildAndWrite(positions, normals, config, {
        par_shapes_create_hemisphere(slices, stacks)
    }) { mesh ->
        par_shapes_scale(mesh, radius, radius, radius)
    }

    fun cylinder(
        positions: DynamicallyWriteable,
        normals: DynamicallyWriteable,
        radius: Float = 1f,
        height: Float = 1f,
        slices: Int = 32,
        stacks: Int = 1,
        config: WriteConfig = WriteConfig()
    ): ShapeWriteResult = buildAndWrite(positions, normals, config, {
        par_shapes_create_cylinder(slices, stacks)
    }) { mesh ->
        par_shapes_scale(mesh, radius, height, radius)
    }

    fun cone(
        positions: DynamicallyWriteable,
        normals: DynamicallyWriteable,
        radius: Float = 1f,
        height: Float = 1f,
        slices: Int = 32,
        stacks: Int = 1,
        config: WriteConfig = WriteConfig()
    ): ShapeWriteResult = buildAndWrite(positions, normals, config, {
        par_shapes_create_cone(slices, stacks)
    }) { mesh ->
        par_shapes_scale(mesh, radius, height, radius)
    }

    fun disk(
        positions: DynamicallyWriteable,
        normals: DynamicallyWriteable,
        radius: Float = 1f,
        slices: Int = 32,
        stacks: Int = 1,
        config: WriteConfig = WriteConfig()
    ): ShapeWriteResult = buildAndWrite(positions, normals, config, {
        par_shapes_create_parametric_disk(slices, stacks)
    }) { mesh ->
        par_shapes_scale(mesh, radius, radius, radius)
    }

    fun cube(
        positions: DynamicallyWriteable,
        normals: DynamicallyWriteable,
        size: Float = 1f,
        center: Vec3? = null,
        config: WriteConfig = WriteConfig()
    ): ShapeWriteResult = buildAndWrite(positions, normals, config, {
        par_shapes_create_cube()
    }) { mesh ->
        par_shapes_scale(mesh, size, size, size)
        center?.let { par_shapes_translate(mesh, it.x, it.y, it.z) }
    }

    fun torus(
        positions: DynamicallyWriteable,
        normals: DynamicallyWriteable,
        slices: Int = 32,
        stacks: Int = 32,
        tubeRadius: Float = 0.25f,
        majorRadius: Float = 1f,
        config: WriteConfig = WriteConfig()
    ): ShapeWriteResult = buildAndWrite(positions, normals, config, {
        par_shapes_create_torus(slices, stacks, tubeRadius)
    }) { mesh ->
        par_shapes_scale(mesh, majorRadius, majorRadius, majorRadius)
    }

    fun trefoilKnot(
        positions: DynamicallyWriteable,
        normals: DynamicallyWriteable,
        slices: Int = 64,
        stacks: Int = 16,
        radius: Float = 1f,
        config: WriteConfig = WriteConfig()
    ): ShapeWriteResult = buildAndWrite(positions, normals, config, {
        par_shapes_create_trefoil_knot(slices, stacks, 1f)
    }) { mesh ->
        par_shapes_scale(mesh, radius, radius, radius)
    }

    fun kleinBottle(
        positions: DynamicallyWriteable,
        normals: DynamicallyWriteable,
        slices: Int = 32,
        stacks: Int = 32,
        scale: Float = 0.1f,
        config: WriteConfig = WriteConfig()
    ): ShapeWriteResult = buildAndWrite(positions, normals, config, {
        par_shapes_create_klein_bottle(slices, stacks)
    }) { mesh ->
        par_shapes_scale(mesh, scale, scale, scale)
    }

    fun rock(
        positions: DynamicallyWriteable,
        normals: DynamicallyWriteable,
        seed: Int = 1,
        subdivisions: Int = 4,
        radius: Float = 1f,
        config: WriteConfig = WriteConfig()
    ): ShapeWriteResult = buildAndWrite(positions, normals, config, {
        par_shapes_create_rock(seed, subdivisions)
    }) { mesh ->
        par_shapes_scale(mesh, radius, radius, radius)
    }

    private inline fun buildAndWrite(
        positions: DynamicallyWriteable,
        normals: DynamicallyWriteable,
        config: WriteConfig,
        factory: () -> ParShapesMesh?,
        crossinline transform: (ParShapesMesh) -> Unit
    ): ShapeWriteResult {
        val mesh = requireNotNull(factory()) { "par_shapes factory returned null" }
        transform(mesh)
        if (mesh.normals(mesh.npoints() * 3) == null) {
            par_shapes_compute_normals(mesh)
        }
        val result = writeMesh(mesh, positions, normals, config)
        par_shapes_free_mesh(mesh)
        return result
    }

    private fun writeMesh(
        mesh: ParShapesMesh,
        positions: DynamicallyWriteable,
        normals: DynamicallyWriteable,
        config: WriteConfig
    ): ShapeWriteResult {
        val triCount = mesh.ntriangles()
        val srcVertices = mesh.npoints()
        val triangles = mesh.triangles(triCount * 3)
        val srcPositions = mesh.points(srcVertices * 3)
        val srcNormals = mesh.normals(srcVertices * 3)
            ?: error("par_shapes mesh has no normals (even after compute)")

        val outVertices = triCount * 3
        val posStride = if (config.padVec3To4) 4 else 3
        val normStride = if (config.padVec3To4) 4 else 3

        fun writeVec(target: DynamicBuffer, x: Float, y: Float, z: Float, w: Float) {
            if (config.padVec3To4) target.put(x, y, z, w) else {
                target.putScalar(x)
                target.putScalar(y)
                target.putScalar(z)
            }
        }

        fun writeAll(target: DynamicallyWriteable, block: DynamicBuffer.() -> Unit) {
            if (config.append) target.append(block) else target.write(block)
        }

        writeAll(positions) {
            for (t in 0 until triCount) {
                val i0 = triangles.get(t * 3 + 0)
                val i1 = triangles.get(t * 3 + 1)
                val i2 = triangles.get(t * 3 + 2)

                val p0 = i0 * 3
                val p1 = i1 * 3
                val p2 = i2 * 3

                writeVec(this, srcPositions.get(p0 + 0), srcPositions.get(p0 + 1), srcPositions.get(p0 + 2), config.positionW)
                writeVec(this, srcPositions.get(p1 + 0), srcPositions.get(p1 + 1), srcPositions.get(p1 + 2), config.positionW)
                writeVec(this, srcPositions.get(p2 + 0), srcPositions.get(p2 + 1), srcPositions.get(p2 + 2), config.positionW)
            }
        }

        writeAll(normals) {
            for (t in 0 until triCount) {
                val i0 = triangles.get(t * 3 + 0)
                val i1 = triangles.get(t * 3 + 1)
                val i2 = triangles.get(t * 3 + 2)

                val n0 = i0 * 3
                val n1 = i1 * 3
                val n2 = i2 * 3

                // If source normals are missing or degenerate, fall back to the face normal.
                val face = faceNormal(srcPositions, i0, i1, i2)

                writeVec(this, srcNormals.getOrNull(n0 + 0, face[0]), srcNormals.getOrNull(n0 + 1, face[1]), srcNormals.getOrNull(n0 + 2, face[2]), config.normalW)
                writeVec(this, srcNormals.getOrNull(n1 + 0, face[0]), srcNormals.getOrNull(n1 + 1, face[1]), srcNormals.getOrNull(n1 + 2, face[2]), config.normalW)
                writeVec(this, srcNormals.getOrNull(n2 + 0, face[0]), srcNormals.getOrNull(n2 + 1, face[1]), srcNormals.getOrNull(n2 + 2, face[2]), config.normalW)
            }
        }

        return ShapeWriteResult(
            sourceVertices = srcVertices,
            sourceTriangles = triCount,
            writtenVertices = outVertices,
            positionFloatsWritten = outVertices * posStride,
            normalFloatsWritten = outVertices * normStride
        )
    }

    private fun faceNormal(pos: java.nio.FloatBuffer, i0: Int, i1: Int, i2: Int): FloatArray {
        val a = i0 * 3
        val b = i1 * 3
        val c = i2 * 3

        val ax = pos.get(a + 0)
        val ay = pos.get(a + 1)
        val az = pos.get(a + 2)

        val bx = pos.get(b + 0)
        val by = pos.get(b + 1)
        val bz = pos.get(b + 2)

        val cx = pos.get(c + 0)
        val cy = pos.get(c + 1)
        val cz = pos.get(c + 2)

        val ux = bx - ax
        val uy = by - ay
        val uz = bz - az

        val vx = cx - ax
        val vy = cy - ay
        val vz = cz - az

        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx

        val len = sqrt(nx * nx + ny * ny + nz * nz)
        return if (len > 1e-8f) floatArrayOf(nx / len, ny / len, nz / len) else floatArrayOf(0f, 1f, 0f)
    }

    private fun java.nio.FloatBuffer.getOrNull(index: Int, fallback: Float): Float {
        return if (index in 0 until this.limit()) this.get(index) else fallback
    }
}
