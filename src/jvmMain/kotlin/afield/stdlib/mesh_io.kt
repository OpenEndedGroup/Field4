@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Getting a [TriMesh] in and out of the two formats every 3D tool on the machine can already read:
 * **OBJ** (text, indexed, carries normals and uvs) and **STL** (ASCII or binary, triangle soup, the
 * lingua franca of 3D printing).
 *
 * Both directions, no dependencies, and honest about what each format can carry:
 *
 *  - [TriMesh.toObj] / [objToTriMesh] keep the index structure, so a watertight mesh round-trips as a
 *    watertight mesh. Normals and uvs survive; per-vertex colours do not (OBJ has no standard for
 *    them). Faces with more than three corners are fan-triangulated on read, so a quad-mesh OBJ from
 *    a modeller loads fine.
 *  - [TriMesh.toStlAscii] / [TriMesh.toStlBinary] / [stlToTriMesh] are *triangle soup* by design:
 *    STL has no vertex sharing at all, so a mesh written to STL and read back has `3 *
 *    triangleCount` vertices and reports every edge as a boundary. `stlToTriMesh(bytes).weld()` gives
 *    you the mesh back. That is a property of the format, not of this code, and the read path says
 *    so in its KDoc rather than silently welding for you — welding needs a tolerance, and choosing
 *    one for you is exactly the kind of guess that loses a hairline feature.
 *
 * ```kotlin
 * val mesh = icosphereMesh(3)
 * File("/tmp/ball.obj").writeText(mesh.toObj())
 * File("/tmp/ball.stl").writeBytes(mesh.toStlBinary())
 * val back = objToTriMesh(File("/tmp/ball.obj").readText())
 * ```
 *
 * These two formats, and no others — see [meshFromFile] for where to go for PLY, glTF and GLB, and
 * `public/docs/geometry_from_disk.md` for the measured account of what each format loses.
 */

// ==============================================================================================
// OBJ
// ==============================================================================================

/**
 * This mesh as Wavefront OBJ text: `v` positions, then `vn` normals and `vt` uvs if present, then
 * `f` faces with 1-based indices.
 *
 * @param name written as the `o` object name.
 * @param decimals coordinate precision. 6 is lossless enough for float positions and keeps the file
 *   readable; raise it if you are round-tripping through a double-precision tool.
 */
fun TriMesh.toObj(name: String = "mesh", decimals: Int = 6): String {
    val fmt = "%.${decimals}f"
    val sb = StringBuilder(vertexCount * 32 + triangleCount * 24)
    sb.append("# written by Field afield.stdlib mesh_io.kt\n")
    sb.append("o ").append(name).append('\n')
    for (v in 0 until vertexCount) {
        sb.append("v ").append(fmt.format(x(v))).append(' ')
            .append(fmt.format(y(v))).append(' ').append(fmt.format(z(v))).append('\n')
    }
    uvs?.let { t ->
        for (v in 0 until vertexCount) {
            sb.append("vt ").append(fmt.format(t[v * 2])).append(' ').append(fmt.format(t[v * 2 + 1])).append('\n')
        }
    }
    normals?.let { n ->
        for (v in 0 until vertexCount) {
            sb.append("vn ").append(fmt.format(n[v * 3])).append(' ')
                .append(fmt.format(n[v * 3 + 1])).append(' ').append(fmt.format(n[v * 3 + 2])).append('\n')
        }
    }
    val hasT = uvs != null
    val hasN = normals != null
    for (t in 0 until triangleCount) {
        sb.append('f')
        for (k in 0..2) {
            val v = indices[t * 3 + k] + 1
            sb.append(' ').append(v)
            when {
                hasT && hasN -> sb.append('/').append(v).append('/').append(v)
                hasT -> sb.append('/').append(v)
                hasN -> sb.append("//").append(v)
            }
        }
        sb.append('\n')
    }
    return sb.toString()
}

/**
 * Parse Wavefront OBJ text into a [TriMesh].
 *
 * Handles `v`, `vn`, `vt`, `f` with any of the `i`, `i/j`, `i//k`, `i/j/k` corner forms, negative
 * (relative) indices, and faces of any size (fan-triangulated). Everything else — materials, groups,
 * curves, smoothing directives — is skipped.
 *
 * The wrinkle every OBJ reader has to decide: OBJ indexes positions, normals and uvs *separately*, so
 * one position can appear with several normals. Here a vertex is emitted per **distinct
 * `(position, uv, normal)` corner triple**, which is the faithful reading — a hard-edged cube from a
 * modeller comes back with 24 vertices, not 8, exactly as it was written. Call [TriMesh.weld] if you
 * want the topological mesh back.
 */
fun objToTriMesh(text: String): TriMesh {
    val vp = ArrayList<Float>(); val vt = ArrayList<Float>(); val vn = ArrayList<Float>()
    data class Corner(val p: Int, val t: Int, val n: Int)
    val cornerIndex = HashMap<Corner, Int>()
    val builder = TriMeshBuilder()
    // Whether this file uses uvs / normals at all is only known once a face references them, so the
    // corners are collected first and the mesh assembled at the end.
    val faces = ArrayList<IntArray>()
    val corners = ArrayList<Corner>()
    var usesT = false
    var usesN = false

    fun resolve(raw: Int, count: Int): Int = if (raw < 0) count + raw else raw - 1

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val parts = line.split(Regex("\\s+"))
        when (parts[0]) {
            "v" -> if (parts.size >= 4) {
                vp.add(parts[1].toFloatOrNull() ?: 0f); vp.add(parts[2].toFloatOrNull() ?: 0f); vp.add(parts[3].toFloatOrNull() ?: 0f)
            }
            "vt" -> if (parts.size >= 3) {
                vt.add(parts[1].toFloatOrNull() ?: 0f); vt.add(parts[2].toFloatOrNull() ?: 0f)
            }
            "vn" -> if (parts.size >= 4) {
                vn.add(parts[1].toFloatOrNull() ?: 0f); vn.add(parts[2].toFloatOrNull() ?: 0f); vn.add(parts[3].toFloatOrNull() ?: 0f)
            }
            "f" -> {
                if (parts.size < 4) continue
                val ring = ArrayList<Int>(parts.size - 1)
                for (i in 1 until parts.size) {
                    val bits = parts[i].split('/')
                    val pi = resolve(bits[0].toIntOrNull() ?: continue, vp.size / 3)
                    val ti = bits.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.let { resolve(it, vt.size / 2) } ?: -1
                    val ni = bits.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.let { resolve(it, vn.size / 3) } ?: -1
                    if (pi < 0 || pi >= vp.size / 3) continue
                    if (ti >= 0) usesT = true
                    if (ni >= 0) usesN = true
                    val corner = Corner(pi, ti, ni)
                    ring.add(cornerIndex.getOrPut(corner) { corners.add(corner); corners.size - 1 })
                }
                // Fan-triangulate: an OBJ face may be a quad or an n-gon.
                for (k in 1 until ring.size - 1) faces.add(intArrayOf(ring[0], ring[k], ring[k + 1]))
            }
        }
    }
    if (corners.isEmpty() || faces.isEmpty()) return TriMesh.empty
    // Emit in position order, not in the order the faces happened to mention the corners. For the
    // common case of one corner per position — anything this family wrote, and any indexed mesh from a
    // modeller — that makes vertex `i` of the result the file's `v` line `i + 1`, so a round trip is
    // index-for-index rather than merely shape-for-shape.
    val order = corners.indices.sortedWith(
        compareBy({ corners[it].p }, { corners[it].t }, { corners[it].n }),
    )
    val slot = IntArray(corners.size)
    order.forEachIndexed { emitted, corner -> slot[corner] = emitted }
    for (i in order) {
        val c = corners[i]
        val uv = if (usesT && c.t >= 0 && c.t * 2 + 1 < vt.size) Vec2(vt[c.t * 2], vt[c.t * 2 + 1]) else if (usesT) Vec2(0f, 0f) else null
        val nrm = if (usesN && c.n >= 0 && c.n * 3 + 2 < vn.size) Vec3(vn[c.n * 3], vn[c.n * 3 + 1], vn[c.n * 3 + 2]) else if (usesN) Vec3(0f, 1f, 0f) else null
        builder.vertex(vp[c.p * 3], vp[c.p * 3 + 1], vp[c.p * 3 + 2], normal = nrm, uv = uv)
    }
    for (f in faces) builder.triangle(slot[f[0]], slot[f[1]], slot[f[2]])
    return builder.build()
}

/** Write this mesh to [file] as OBJ. Creates parent directories. */
fun TriMesh.saveObj(file: File, name: String = file.nameWithoutExtension) {
    file.parentFile?.mkdirs()
    file.writeText(toObj(name))
}

/** Read an OBJ file — see [objToTriMesh] for what survives. */
fun triMeshFromObj(file: File): TriMesh = objToTriMesh(file.readText())

// ==============================================================================================
// STL
// ==============================================================================================

/**
 * This mesh as ASCII STL. One `facet` per triangle with its face normal, no vertex sharing (the
 * format has none).
 *
 * Large and slow relative to [toStlBinary] — roughly 8x the bytes — but human-readable, which is why
 * it still exists.
 */
fun TriMesh.toStlAscii(name: String = "mesh"): String {
    val sb = StringBuilder(triangleCount * 180)
    sb.append("solid ").append(name).append('\n')
    for (t in 0 until triangleCount) {
        val n = faceNormal(t)
        val (a, b, c) = triangle(t)
        sb.append("  facet normal %.6e %.6e %.6e\n".format(n.x, n.y, n.z))
        sb.append("    outer loop\n")
        for (p in listOf(a, b, c)) sb.append("      vertex %.6e %.6e %.6e\n".format(p.x, p.y, p.z))
        sb.append("    endloop\n  endfacet\n")
    }
    sb.append("endsolid ").append(name).append('\n')
    return sb.toString()
}

/**
 * This mesh as binary STL: an 80-byte header, the triangle count, then 50 bytes per triangle
 * (little-endian float normal, three float positions, a zero attribute short).
 *
 * The format every slicer reads. No vertex sharing, no colours, no units — STL is exactly this much.
 */
fun TriMesh.toStlBinary(header: String = "Field afield.stdlib mesh_io.kt"): ByteArray {
    val out = ByteArrayOutputStream(84 + triangleCount * 50)
    val head = ByteArray(80)
    val bytes = header.toByteArray(Charsets.US_ASCII)
    bytes.copyInto(head, 0, 0, minOf(80, bytes.size))
    out.write(head)
    fun le(size: Int, fill: ByteBuffer.() -> Unit) {
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.fill()
        out.write(bb.array())
    }
    le(4) { putInt(this@toStlBinary.triangleCount) }
    for (t in 0 until triangleCount) {
        val n = faceNormal(t)
        val (a, b, c) = triangle(t)
        le(50) {
            putFloat(n.x); putFloat(n.y); putFloat(n.z)
            putFloat(a.x); putFloat(a.y); putFloat(a.z)
            putFloat(b.x); putFloat(b.y); putFloat(b.z)
            putFloat(c.x); putFloat(c.y); putFloat(c.z)
            putShort(0)
        }
    }
    return out.toByteArray()
}

/**
 * Parse STL, ASCII or binary — the format is sniffed, not declared.
 *
 * The sniff is the careful part: an ASCII file starts with `solid`, but so do plenty of binary files
 * whose 80-byte header happens to, so the deciding test is whether the *byte length* matches
 * `84 + 50 * count` for the count in the binary header. That is the check that does not
 * misidentify either way.
 *
 * The result is a **triangle soup** — `3 * triangleCount` unshared vertices — because that is what
 * the file contains. Follow with [TriMesh.weld] to recover the topology, choosing a tolerance that
 * suits your model's scale.
 */
fun stlToTriMesh(bytes: ByteArray): TriMesh {
    if (bytes.size >= 84) {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(80)
        val count = bb.int
        if (count >= 0 && bytes.size.toLong() == 84L + 50L * count) {
            val builder = TriMeshBuilder()
            for (t in 0 until count) {
                bb.float; bb.float; bb.float // the stored normal is redundant with the winding
                val idx = IntArray(3) {
                    builder.vertex(bb.float, bb.float, bb.float)
                }
                bb.short
                builder.triangle(idx[0], idx[1], idx[2])
            }
            return builder.build()
        }
    }
    val text = String(bytes, Charsets.US_ASCII)
    if (!text.trimStart().startsWith("solid")) return TriMesh.empty
    val builder = TriMeshBuilder()
    val pending = ArrayList<Int>(3)
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (!line.startsWith("vertex")) {
            if (line.startsWith("endloop")) {
                for (k in 1 until pending.size - 1) builder.triangle(pending[0], pending[k], pending[k + 1])
                pending.clear()
            }
            continue
        }
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 4) continue
        pending.add(
            builder.vertex(
                parts[1].toFloatOrNull() ?: 0f,
                parts[2].toFloatOrNull() ?: 0f,
                parts[3].toFloatOrNull() ?: 0f,
            )
        )
    }
    return builder.build()
}

/** Write binary STL to [file]. Creates parent directories. */
fun TriMesh.saveStl(file: File) {
    file.parentFile?.mkdirs()
    file.writeBytes(toStlBinary())
}

/** Read an STL file, ASCII or binary — see [stlToTriMesh]. */
fun triMeshFromStl(file: File): TriMesh = stlToTriMesh(file.readBytes())

/**
 * Load a mesh from a path, choosing the reader by extension (`.obj`, `.stl`).
 *
 * Deliberately narrow, and deliberately dependency-free: these two readers are a few hundred lines of
 * plain Kotlin with no native library behind them. For **any other format** — PLY, glTF, GLB, FBX,
 * DAE, 3DS — reach for `afield.four.modules.meshFromAnyFile`, which handles these two as well and
 * routes the rest through the loaders in `simplePLYSupport.kt` and `simpleGLTFSupport.kt`. The whole
 * route from a file to pixels, and what each format loses on the way, is
 * `public/docs/geometry_from_disk.md`.
 *
 * @throws IllegalArgumentException for an extension this family does not read.
 */
fun meshFromFile(path: String): TriMesh {
    val file = File(path)
    require(file.isFile) { "no such mesh file: $path" }
    return when (file.extension.lowercase()) {
        "obj" -> triMeshFromObj(file)
        "stl" -> triMeshFromStl(file)
        else -> throw IllegalArgumentException(
            "mesh_io.kt reads .obj and .stl; got .${file.extension} for $path"
        )
    }
}
