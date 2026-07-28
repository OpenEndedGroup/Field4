@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a [TriMesh] comes from: the primitives ([boxMesh], [uvSphereMesh], [icosphereMesh],
 * [torusMesh], [cylinderMesh], [coneMesh], [capsuleMesh], the five platonics) and the *derived*
 * generators that build one out of something you already have ([extrudeMesh], [revolveMesh],
 * [tubeMesh], [heightfieldMesh], [polygonMesh]).
 *
 * # Two conventions worth knowing before you use any of them
 *
 * **Everything here is welded and watertight where it can be.** A generator returns the minimal
 * vertex set, so [MeshTopology.eulerCharacteristic] of a box, a sphere, a cone or a capped cylinder
 * is exactly `2`, of a torus exactly `0`, and [TriMesh.isWatertight] is true. That is a much stronger
 * guarantee than "it renders", it is what the volume / curvature / decimation code needs, and it is
 * checked in the tests.
 *
 * **Normals are only attached where they are unambiguous.** A sphere, icosphere, torus and capsule
 * are smooth everywhere, so they carry per-vertex normals. A box, cylinder, cone, extrusion or
 * platonic solid has *creases*: the two faces meeting at an edge want different normals at the same
 * vertex, and you cannot have both a shared vertex and two normals. Rather than silently duplicate
 * the seam (breaking watertightness) or silently average across the crease (rounding it off), these
 * ship with **no** normals, and you pick:
 *
 * ```kotlin
 * boxMesh().flatShaded()          // one normal per triangle: hard edges, 3 verts per tri
 * boxMesh().shadedNormals(35.0)   // split only where faces disagree by > 35 degrees
 * boxMesh().withVertexNormals()   // one averaged normal per shared vertex: rounded corners
 * ```
 *
 * The preview / render bridges in `mesh_field.kt` call [TriMesh.shadedNormals] for you when a mesh
 * arrives without normals, so a bare `boxMesh()` still looks like a box.
 *
 * # Orientation
 *
 * `+y` is up, matching the preview camera. Radial generators sweep `x = r cos u, z = -r sin u`, which
 * is the handedness that makes counter-clockwise-from-outside windings come out right. [planeMesh]
 * and [polygonMesh] live in the `xy` plane facing `+z`; [heightfieldMesh] lives in the `xz` plane
 * facing `+y` — the difference is deliberate, a plane is a wall and a heightfield is a landscape.
 */

// ==============================================================================================
// Boxes and planes
// ==============================================================================================

/**
 * An axis-aligned box centred on the origin, 8 vertices and 12 triangles — the minimal watertight
 * cube.
 *
 * No normals (see the file overview): the eight corners are shared by three faces each. `χ = 2`.
 *
 * @param sizeX full extent along x (not the half-extent).
 * @param sizeY full extent along y.
 * @param sizeZ full extent along z.
 */
fun boxMesh(sizeX: Number = 1.0, sizeY: Number = 1.0, sizeZ: Number = 1.0): TriMesh {
    val hx = sizeX.toFloat() / 2f; val hy = sizeY.toFloat() / 2f; val hz = sizeZ.toFloat() / 2f
    return triMesh {
        // bit 0 = +x, bit 1 = +y, bit 2 = +z
        for (i in 0..7) {
            vertex(
                if (i and 1 != 0) hx else -hx,
                if (i and 2 != 0) hy else -hy,
                if (i and 4 != 0) hz else -hz,
            )
        }
        quad(4, 5, 7, 6) // +z
        quad(1, 0, 2, 3) // -z
        quad(1, 3, 7, 5) // +x
        quad(0, 4, 6, 2) // -x
        quad(2, 6, 7, 3) // +y
        quad(0, 1, 5, 4) // -y
    }
}

/** A cube: [boxMesh] with all three extents equal to [size]. */
fun cubeMesh(size: Number = 1.0): TriMesh = boxMesh(size, size, size)

/**
 * A rectangular grid of quads in the `xy` plane at `z = 0`, facing `+z`, with normals and uvs.
 *
 * A disc, not a solid: `χ = 1`, one boundary loop, [TriMesh.isWatertight] false. Use it as a wall, a
 * sprite, or the input to a displacement.
 *
 * @param nx quads across (>= 1), so `nx + 1` vertices.
 * @param ny quads up.
 * @param width total extent along x, centred on the origin.
 * @param height total extent along y.
 */
fun planeMesh(nx: Int = 1, ny: Int = 1, width: Number = 1.0, height: Number = 1.0): TriMesh {
    require(nx >= 1 && ny >= 1) { "planeMesh needs at least one quad in each direction, got ${nx}x$ny" }
    val w = width.toFloat(); val h = height.toFloat()
    val up = Vec3(0f, 0f, 1f)
    return triMesh {
        for (j in 0..ny) for (i in 0..nx) {
            val u = i.toFloat() / nx
            val v = j.toFloat() / ny
            vertex((u - 0.5f) * w, (v - 0.5f) * h, 0f, normal = Vec3(up), uv = Vec2(u, v))
        }
        fun at(i: Int, j: Int) = j * (nx + 1) + i
        for (j in 0 until ny) for (i in 0 until nx) {
            quad(at(i, j), at(i + 1, j), at(i + 1, j + 1), at(i, j + 1))
        }
    }
}

// ==============================================================================================
// Spheres
// ==============================================================================================

/**
 * A latitude/longitude sphere: two pole vertices and `rings - 1` rows of [segments].
 *
 * Watertight with `χ = 2`, no degenerate pole triangles (the poles are single vertices with a
 * triangle fan, not a collapsed quad row), and smooth per-vertex normals. The seam is welded, which
 * means the uv parameterisation would be discontinuous there, so no uvs are attached — a
 * watertight sphere and a seamless uv layout are mutually exclusive.
 *
 * [icosphereMesh] has more uniform triangles and is usually the better sphere; this one exists
 * because its rows are a predictable lattice, which some displacement work wants.
 *
 * @param segments longitude divisions (>= 3).
 * @param rings latitude bands (>= 2), so `rings - 1` interior rows.
 */
fun uvSphereMesh(segments: Int = 32, rings: Int = 16, radius: Number = 1.0): TriMesh {
    require(segments >= 3) { "uvSphereMesh needs >= 3 segments, got $segments" }
    require(rings >= 2) { "uvSphereMesh needs >= 2 rings, got $rings" }
    val r = radius.toFloat()
    return triMesh {
        val north = vertex(0f, r, 0f, normal = Vec3(0f, 1f, 0f))
        val rows = Array(rings - 1) { IntArray(segments) }
        for (j in 1 until rings) {
            val theta = PI * j / rings
            val y = (cos(theta) * r).toFloat()
            val ringR = (sin(theta) * r).toFloat()
            for (i in 0 until segments) {
                val phi = 2.0 * PI * i / segments
                val x = (cos(phi) * ringR).toFloat()
                val z = (-sin(phi) * ringR).toFloat()
                rows[j - 1][i] = vertex(x, y, z, normal = Vec3(x / r, y / r, z / r))
            }
        }
        val south = vertex(0f, -r, 0f, normal = Vec3(0f, -1f, 0f))
        val top = rows[0]
        for (i in 0 until segments) triangle(north, top[i], top[(i + 1) % segments])
        for (j in 0 until rings - 2) {
            val upper = rows[j]; val lower = rows[j + 1]
            for (i in 0 until segments) {
                val n = (i + 1) % segments
                quad(upper[n], upper[i], lower[i], lower[n])
            }
        }
        val bottom = rows[rings - 2]
        for (i in 0 until segments) triangle(south, bottom[(i + 1) % segments], bottom[i])
    }
}

/**
 * A geodesic sphere: an icosahedron with each triangle split into four [subdivisions] times, every
 * vertex pushed out to [radius].
 *
 * `20 * 4^subdivisions` triangles, all near-equilateral — which is why this is the sphere to reach
 * for when the triangles themselves matter (curvature, decimation, relaxation, anything that dislikes
 * the sliver triangles a UV sphere has at its poles). Watertight, `χ = 2`, smooth normals.
 *
 * @param subdivisions 0 gives the bare icosahedron; 3 gives 1280 triangles; 6 gives 81920.
 */
fun icosphereMesh(subdivisions: Int = 2, radius: Number = 1.0): TriMesh {
    require(subdivisions >= 0) { "subdivisions must be >= 0, got $subdivisions" }
    val r = radius.toFloat()
    var mesh = icosahedronMesh(1.0)
    repeat(subdivisions) { mesh = subdivideAndProject(mesh) }
    // Push out to the target radius and attach the exact analytic normals.
    val pos = FloatArray(mesh.positions.size)
    val nor = FloatArray(mesh.positions.size)
    for (v in 0 until mesh.vertexCount) {
        val x = mesh.x(v); val y = mesh.y(v); val z = mesh.z(v)
        val l = sqrt(x * x + y * y + z * z)
        val nx = x / l; val ny = y / l; val nz = z / l
        nor[v * 3] = nx; nor[v * 3 + 1] = ny; nor[v * 3 + 2] = nz
        pos[v * 3] = nx * r; pos[v * 3 + 1] = ny * r; pos[v * 3 + 2] = nz * r
    }
    return TriMesh(pos, mesh.indices, nor)
}

/** One 1-to-4 split of every triangle, midpoints shared across edges and re-projected to the unit sphere. */
private fun subdivideAndProject(mesh: TriMesh): TriMesh {
    val builder = TriMeshBuilder()
    for (v in 0 until mesh.vertexCount) builder.vertex(mesh.x(v), mesh.y(v), mesh.z(v))
    val midpoints = HashMap<Long, Int>()
    val stride = (mesh.vertexCount + 1).toLong()
    fun mid(a: Int, b: Int): Int {
        val lo = min(a, b); val hi = max(a, b)
        return midpoints.getOrPut(lo.toLong() * stride + hi) {
            val x = (mesh.x(a) + mesh.x(b)) / 2f
            val y = (mesh.y(a) + mesh.y(b)) / 2f
            val z = (mesh.z(a) + mesh.z(b)) / 2f
            val l = sqrt(x * x + y * y + z * z)
            builder.vertex(x / l, y / l, z / l)
        }
    }
    for (t in 0 until mesh.triangleCount) {
        val a = mesh.ia(t); val b = mesh.ib(t); val c = mesh.ic(t)
        val ab = mid(a, b); val bc = mid(b, c); val ca = mid(c, a)
        builder.triangle(a, ab, ca)
        builder.triangle(b, bc, ab)
        builder.triangle(c, ca, bc)
        builder.triangle(ab, bc, ca)
    }
    return builder.build()
}

// ==============================================================================================
// Platonic solids
// ==============================================================================================

/**
 * A regular tetrahedron with its vertices on a sphere of [radius]. 4 vertices, 4 triangles, `χ = 2`.
 * No normals — every face is a crease.
 */
fun tetrahedronMesh(radius: Number = 1.0): TriMesh {
    val s = (radius.toFloat() / sqrt(3f))
    return TriMesh.of(
        listOf(Vec3(s, s, s), Vec3(s, -s, -s), Vec3(-s, s, -s), Vec3(-s, -s, s)),
        listOf(0, 1, 2, 0, 2, 3, 0, 3, 1, 1, 3, 2),
    ).fixWinding()
}

/** A regular octahedron with its 6 vertices on a sphere of [radius]. 8 triangles, `χ = 2`. */
fun octahedronMesh(radius: Number = 1.0): TriMesh {
    val r = radius.toFloat()
    return TriMesh.of(
        listOf(Vec3(r, 0f, 0f), Vec3(-r, 0f, 0f), Vec3(0f, r, 0f), Vec3(0f, -r, 0f), Vec3(0f, 0f, r), Vec3(0f, 0f, -r)),
        listOf(
            0, 2, 4, 2, 1, 4, 1, 3, 4, 3, 0, 4,
            2, 0, 5, 1, 2, 5, 3, 1, 5, 0, 3, 5,
        ),
    ).fixWinding()
}

/**
 * A regular icosahedron with its 12 vertices on a sphere of [radius]. 20 triangles, `χ = 2`.
 *
 * The seed [icosphereMesh] subdivides. Built from the three golden rectangles and then passed
 * through [TriMesh.fixWinding], so the outward orientation is *derived* rather than a hand-typed
 * face table that would be one transposition away from silently inside-out.
 */
fun icosahedronMesh(radius: Number = 1.0): TriMesh {
    val phi = (1.0 + sqrt(5.0)).toFloat() / 2f
    val scale = radius.toFloat() / sqrt(1f + phi * phi)
    // The three mutually perpendicular golden rectangles, four corners each.
    val v = mutableListOf<Vec3>()
    for (s1 in intArrayOf(-1, 1)) for (s2 in intArrayOf(-1, 1)) {
        v.add(Vec3(0f, s1 * scale, s2 * phi * scale))
        v.add(Vec3(s1 * scale, s2 * phi * scale, 0f))
        v.add(Vec3(s1 * phi * scale, 0f, s2 * scale))
    }
    // 12 vertices; the 20 faces are every triple whose mutual distances are the icosahedron's edge
    // length, which is the shortest non-zero distance in the set.
    var shortest = Float.MAX_VALUE
    for (i in v.indices) for (j in i + 1 until v.size) {
        val d = v[i].distance(v[j])
        if (d > 1e-4f && d < shortest) shortest = d
    }
    val tol = shortest * 0.05f
    val faces = mutableListOf<Int>()
    for (i in v.indices) for (j in i + 1 until v.size) for (k in j + 1 until v.size) {
        if (abs(v[i].distance(v[j]) - shortest) < tol &&
            abs(v[j].distance(v[k]) - shortest) < tol &&
            abs(v[k].distance(v[i]) - shortest) < tol
        ) {
            faces.add(i); faces.add(j); faces.add(k)
        }
    }
    check(faces.size == 60) { "icosahedron face search found ${faces.size / 3} faces, expected 20" }
    return TriMesh.of(v, faces).fixWinding()
}

/**
 * A regular dodecahedron with its 20 vertices on a sphere of [radius], as 12 pentagons fanned from
 * their own centres — 32 vertices, 60 triangles, `χ = 2`.
 *
 * Constructed as the **dual of the icosahedron**: the 20 icosahedron face centres are the
 * dodecahedron's vertices, and each icosahedron vertex's 5 incident faces, sorted by angle around
 * it, are one pentagon. That makes this a working demonstration of [MeshTopology] as much as a
 * primitive.
 */
fun dodecahedronMesh(radius: Number = 1.0): TriMesh {
    val ico = icosahedronMesh(1.0)
    val topo = ico.topology()
    val r = radius.toFloat()
    // Dual vertices: normalised icosahedron face centroids.
    val centres = (0 until ico.triangleCount).map { t ->
        val (a, b, c) = ico.triangle(t)
        Vec3((a.x + b.x + c.x) / 3f, (a.y + b.y + c.y) / 3f, (a.z + b.z + c.z) / 3f).normalize().mul(r)
    }
    return triMesh {
        val corner = centres.map { vertex(it.x, it.y, it.z) }
        for (v in 0 until ico.vertexCount) {
            val star = topo.vertexTriangles[v]
            check(star.size == 5) { "icosahedron vertex $v has ${star.size} faces, expected 5" }
            val axis = ico.position(v).normalize()
            // A basis in the plane perpendicular to the axis, to sort the ring by angle.
            val helper = if (abs(axis.x) < 0.9f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)
            val e1 = Vec3(helper).cross(axis).normalize()
            val e2 = Vec3(axis).cross(e1).normalize()
            val ring = star.sortedBy { t ->
                val p = centres[t]
                atan2(p.dot(e2).toDouble(), p.dot(e1).toDouble())
            }
            val mid = Vec3(0f, 0f, 0f)
            for (t in ring) mid.add(centres[t])
            mid.mul(1f / ring.size)
            val hub = vertex(mid.x, mid.y, mid.z)
            for (i in ring.indices) {
                triangle(hub, corner[ring[i]], corner[ring[(i + 1) % ring.size]])
            }
        }
    }.fixWinding()
}

// ==============================================================================================
// Surfaces of revolution
// ==============================================================================================

/**
 * A cylinder along `y`, centred on the origin. Watertight with [caps] (`χ = 2`); an open tube
 * without them (`χ = 0`, two boundary loops).
 *
 * No normals: the ring vertices are shared between the smooth side and the flat caps, which want
 * different normals. `cylinderMesh(64).shadedNormals(35.0)` renders it correctly — smooth around,
 * hard at the rim.
 *
 * @param segments divisions around the axis (>= 3).
 */
fun cylinderMesh(segments: Int = 32, radius: Number = 0.5, height: Number = 1.0, caps: Boolean = true): TriMesh {
    require(segments >= 3) { "cylinderMesh needs >= 3 segments, got $segments" }
    val r = radius.toFloat(); val hy = height.toFloat() / 2f
    return triMesh {
        val bottom = IntArray(segments); val top = IntArray(segments)
        for (i in 0 until segments) {
            val phi = 2.0 * PI * i / segments
            val x = (cos(phi) * r).toFloat(); val z = (-sin(phi) * r).toFloat()
            bottom[i] = vertex(x, -hy, z)
            top[i] = vertex(x, hy, z)
        }
        for (i in 0 until segments) {
            val n = (i + 1) % segments
            quad(bottom[i], bottom[n], top[n], top[i])
        }
        if (caps) {
            val topHub = vertex(0f, hy, 0f)
            val bottomHub = vertex(0f, -hy, 0f)
            for (i in 0 until segments) {
                val n = (i + 1) % segments
                triangle(topHub, top[i], top[n])
                triangle(bottomHub, bottom[n], bottom[i])
            }
        }
    }
}

/**
 * A cone along `y` with its apex at `+height/2` and its base at `-height/2`. Watertight with [cap]
 * (`χ = 2`). No normals — the apex is a genuine singularity.
 *
 * @param segments divisions around the axis (>= 3).
 */
fun coneMesh(segments: Int = 32, radius: Number = 0.5, height: Number = 1.0, cap: Boolean = true): TriMesh {
    require(segments >= 3) { "coneMesh needs >= 3 segments, got $segments" }
    val r = radius.toFloat(); val hy = height.toFloat() / 2f
    return triMesh {
        val apex = vertex(0f, hy, 0f)
        val ring = IntArray(segments) { i ->
            val phi = 2.0 * PI * i / segments
            vertex((cos(phi) * r).toFloat(), -hy, (-sin(phi) * r).toFloat())
        }
        for (i in 0 until segments) triangle(apex, ring[i], ring[(i + 1) % segments])
        if (cap) {
            val hub = vertex(0f, -hy, 0f)
            for (i in 0 until segments) triangle(hub, ring[(i + 1) % segments], ring[i])
        }
    }
}

/**
 * A torus in the `xz` plane: [majorRadius] out to the centre of the tube, [minorRadius] the tube
 * itself. Watertight, smooth normals, and the family's canonical **genus 1** shape — `χ = 0`,
 * [MeshTopology.genus] `1`.
 *
 * @param majorSegments divisions the long way round (>= 3).
 * @param minorSegments divisions around the tube (>= 3).
 */
fun torusMesh(
    majorSegments: Int = 48,
    minorSegments: Int = 24,
    majorRadius: Number = 1.0,
    minorRadius: Number = 0.35,
): TriMesh {
    require(majorSegments >= 3 && minorSegments >= 3) {
        "torusMesh needs >= 3 segments each way, got ${majorSegments}x$minorSegments"
    }
    val bigR = majorRadius.toFloat(); val smallR = minorRadius.toFloat()
    return triMesh {
        val grid = Array(majorSegments) { IntArray(minorSegments) }
        for (i in 0 until majorSegments) {
            val u = 2.0 * PI * i / majorSegments
            val cu = cos(u).toFloat(); val su = (-sin(u)).toFloat()
            for (j in 0 until minorSegments) {
                val v = 2.0 * PI * j / minorSegments
                val cv = cos(v).toFloat(); val sv = sin(v).toFloat()
                val nx = cv * cu; val ny = sv; val nz = cv * su
                grid[i][j] = vertex(
                    (bigR + smallR * cv) * cu, smallR * sv, (bigR + smallR * cv) * su,
                    normal = Vec3(nx, ny, nz),
                )
            }
        }
        for (i in 0 until majorSegments) for (j in 0 until minorSegments) {
            val i2 = (i + 1) % majorSegments; val j2 = (j + 1) % minorSegments
            quad(grid[i][j], grid[i2][j], grid[i2][j2], grid[i][j2])
        }
    }
}

/**
 * A capsule (cylinder with hemispherical ends) along `y`: total length `height + 2 * radius`.
 *
 * Watertight with `χ = 2` and smooth normals **everywhere** — the hemisphere meets the cylinder
 * tangentially, so there is no crease and one normal per vertex is correct. That makes it the
 * complex primitive that still needs no shading pass.
 *
 * @param segments divisions around the axis (>= 3).
 * @param rings latitude bands per hemisphere (>= 1).
 * @param height length of the straight section, `0` for a sphere.
 */
fun capsuleMesh(segments: Int = 32, rings: Int = 8, radius: Number = 0.4, height: Number = 1.0): TriMesh {
    require(segments >= 3) { "capsuleMesh needs >= 3 segments, got $segments" }
    require(rings >= 1) { "capsuleMesh needs >= 1 ring per hemisphere, got $rings" }
    val r = radius.toFloat(); val hy = height.toFloat() / 2f
    return triMesh {
        val north = vertex(0f, hy + r, 0f, normal = Vec3(0f, 1f, 0f))
        val rows = ArrayList<IntArray>()
        // theta sweeps 0..pi; the sphere centre jumps from +hy to -hy at the equator, which is what
        // inserts the straight section without breaking the normal field.
        val total = 2 * rings
        for (j in 1 until total) {
            val theta = PI * j / total
            val ct = cos(theta).toFloat(); val st = sin(theta).toFloat()
            val offset = if (j <= rings) hy else -hy
            val row = IntArray(segments)
            for (i in 0 until segments) {
                val phi = 2.0 * PI * i / segments
                val cx = cos(phi).toFloat(); val cz = (-sin(phi)).toFloat()
                val nx = st * cx; val ny = ct; val nz = st * cz
                row[i] = vertex(nx * r, offset + ny * r, nz * r, normal = Vec3(nx, ny, nz))
            }
            rows.add(row)
        }
        val south = vertex(0f, -hy - r, 0f, normal = Vec3(0f, -1f, 0f))
        val top = rows.first()
        for (i in 0 until segments) triangle(north, top[i], top[(i + 1) % segments])
        for (j in 0 until rows.size - 1) {
            val upper = rows[j]; val lower = rows[j + 1]
            for (i in 0 until segments) {
                val n = (i + 1) % segments
                quad(upper[n], upper[i], lower[i], lower[n])
            }
        }
        val bottom = rows.last()
        for (i in 0 until segments) triangle(south, bottom[(i + 1) % segments], bottom[i])
    }
}

/**
 * A lathe: a 2D [profile] of `(radius, y)` points swept around the `y` axis.
 *
 * Profile points with radius `0` collapse to a single pole vertex rather than [segments] coincident
 * ones, so a profile that starts and ends on the axis produces a watertight solid — that is how
 * `revolveMesh(listOf(vec(0,1), vec(1,0), vec(0,-1)), 48)` gives a closed lens with `χ = 2`.
 *
 * @param profile `(x = radius >= 0, y)`, in order along the surface. At least 2 points.
 * @param segments divisions around the axis (>= 3).
 * @param turns fraction of a full revolution. `1.0` closes the sweep; anything less leaves it open
 *   (and open sweeps are surfaces, not solids — no caps are added).
 */
fun revolveMesh(profile: List<Vec2>, segments: Int = 48, turns: Double = 1.0): TriMesh {
    require(profile.size >= 2) { "revolveMesh needs >= 2 profile points, got ${profile.size}" }
    require(segments >= 3) { "revolveMesh needs >= 3 segments, got $segments" }
    require(profile.all { it.x >= -1e-6f }) { "revolveMesh profile radii must be >= 0" }
    val closed = abs(turns - 1.0) < 1e-9
    val columns = if (closed) segments else segments + 1
    return triMesh {
        val grid = Array(columns) { IntArray(profile.size) }
        val poles = HashMap<Int, Int>()
        for (i in 0 until columns) {
            val u = 2.0 * PI * turns * i / segments
            val cu = cos(u).toFloat(); val su = (-sin(u)).toFloat()
            for (j in profile.indices) {
                val rad = profile[j].x
                grid[i][j] = if (rad <= 1e-6f) {
                    poles.getOrPut(j) { vertex(0f, profile[j].y, 0f) }
                } else {
                    vertex(rad * cu, profile[j].y, rad * su)
                }
            }
        }
        for (i in 0 until (if (closed) columns else columns - 1)) {
            val i2 = (i + 1) % columns
            for (j in 0 until profile.size - 1) {
                val a = grid[i][j]; val b = grid[i2][j]; val c = grid[i2][j + 1]; val d = grid[i][j + 1]
                // A profile point on the axis collapses to one shared vertex, so one half of the quad
                // becomes a repeated-index triangle. Emit only the half that is a real triangle.
                val poleBelow = a == b
                val poleAbove = c == d
                if (!poleBelow) triangle(a, b, c)
                if (!poleAbove) triangle(a, c, d)
            }
        }
    }.removeDegenerateTriangles()
}

// ==============================================================================================
// Extrusion, polygons, tubes
// ==============================================================================================

/**
 * Ear-clipping triangulation of a simple (non-self-intersecting) polygon.
 *
 * Returns indices into [polygon], three per triangle, wound **counter-clockwise** whichever way the
 * input was wound. Handles concave polygons — which is why [extrudeMesh] and [polygonMesh] use it
 * rather than [TriMeshBuilder.polygon]'s fan. Holes are not supported.
 *
 * O(n²) in the worst case, which is the standard trade for not needing a sweep-line; a few thousand
 * points is comfortable.
 */
fun triangulatePolygon(polygon: List<Vec2>): List<Int> {
    require(polygon.size >= 3) { "a polygon needs >= 3 points, got ${polygon.size}" }
    val ccw = polygonSignedArea(polygon) >= 0.0
    val order = if (ccw) polygon.indices.toMutableList() else polygon.indices.reversed().toMutableList()
    fun p(i: Int) = polygon[order[i]]
    fun cross(o: Vec2, a: Vec2, b: Vec2): Double =
        (a.x - o.x).toDouble() * (b.y - o.y) - (a.y - o.y).toDouble() * (b.x - o.x)
    fun insideTriangle(a: Vec2, b: Vec2, c: Vec2, q: Vec2): Boolean {
        val d1 = cross(a, b, q); val d2 = cross(b, c, q); val d3 = cross(c, a, q)
        return (d1 >= 0 && d2 >= 0 && d3 >= 0) || (d1 <= 0 && d2 <= 0 && d3 <= 0)
    }
    val out = mutableListOf<Int>()
    var guard = order.size * order.size + 16
    while (order.size > 3 && guard-- > 0) {
        var clipped = false
        for (i in order.indices) {
            val prev = (i + order.size - 1) % order.size
            val next = (i + 1) % order.size
            val a = p(prev); val b = p(i); val c = p(next)
            if (cross(a, b, c) <= 0.0) continue // reflex (or collinear) corner: not an ear
            var containsOther = false
            for (k in order.indices) {
                if (k == prev || k == i || k == next) continue
                if (insideTriangle(a, b, c, p(k))) { containsOther = true; break }
            }
            if (containsOther) continue
            out.add(order[prev]); out.add(order[i]); out.add(order[next])
            order.removeAt(i)
            clipped = true
            break
        }
        if (!clipped) break // degenerate or self-intersecting input; emit what is left as a fan
    }
    if (order.size >= 3) {
        for (i in 1 until order.size - 1) { out.add(order[0]); out.add(order[i]); out.add(order[i + 1]) }
    }
    return out
}

/** Twice the signed area of a 2D polygon: positive for counter-clockwise. */
internal fun polygonSignedArea(polygon: List<Vec2>): Double {
    var sum = 0.0
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[(i + 1) % polygon.size]
        sum += a.x.toDouble() * b.y - b.x.toDouble() * a.y
    }
    return sum / 2.0
}

/**
 * A flat, triangulated polygon in the `xy` plane at `z = 0`, facing `+z`. Concave polygons are
 * handled ([triangulatePolygon]). A disc: `χ = 1`, one boundary loop.
 */
fun polygonMesh(polygon: List<Vec2>): TriMesh {
    val tri = triangulatePolygon(polygon)
    val up = Vec3(0f, 0f, 1f)
    return triMesh {
        for (q in polygon) vertex(q.x, q.y, 0f, normal = Vec3(up))
        for (i in tri.indices step 3) triangle(tri[i], tri[i + 1], tri[i + 2])
    }.removeUnusedVertices()
}

/**
 * A prism: [polygon] in the `xy` plane swept along `+z` by [height].
 *
 * Watertight with [caps] (`χ = 2` for a simple polygon), an open tube without them. Concave
 * polygons are triangulated properly by [triangulatePolygon], and the input's winding does not
 * matter — the result is always wound outward.
 *
 * No normals: an extrusion is all creases. `extrudeMesh(...).flatShaded()` to render.
 */
fun extrudeMesh(polygon: List<Vec2>, height: Number = 1.0, caps: Boolean = true): TriMesh {
    require(polygon.size >= 3) { "extrudeMesh needs >= 3 polygon points, got ${polygon.size}" }
    val h = height.toFloat()
    // Normalise to counter-clockwise so the side windings below are outward without a case analysis.
    val ring = if (polygonSignedArea(polygon) >= 0.0) polygon else polygon.reversed()
    val n = ring.size
    val mesh = triMesh {
        val bottom = IntArray(n) { vertex(ring[it].x, ring[it].y, 0f) }
        val top = IntArray(n) { vertex(ring[it].x, ring[it].y, h) }
        for (i in 0 until n) {
            val j = (i + 1) % n
            quad(bottom[i], bottom[j], top[j], top[i])
        }
        if (caps) {
            val tri = triangulatePolygon(ring)
            for (k in tri.indices step 3) {
                triangle(top[tri[k]], top[tri[k + 1]], top[tri[k + 2]])
                triangle(bottom[tri[k + 2]], bottom[tri[k + 1]], bottom[tri[k]])
            }
        }
    }
    // A negative height flips the sides; rather than case-analyse, let fixWinding sort it out.
    return if (h < 0f) mesh.fixWinding() else mesh
}

/**
 * A round tube swept along a 3D polyline — the mesh you want for a drawn stroke, a trajectory or a
 * graph edge in 3D.
 *
 * Frames are **rotation-minimising** (double-reflection parallel transport), which is what stops a
 * tube from twisting where the path's curvature swings through vertical — the failure a naive
 * `up`-based frame has, and the reason this is worth 60 lines rather than 10.
 *
 * @param path at least 2 points. Consecutive duplicates are dropped.
 * @param sides divisions around the tube (>= 3).
 * @param closed treat the path as a loop and join the last ring to the first. Watertight when true
 *   (`χ = 0`, a genus-1 torus-like surface); when false the tube gets flat [caps] or is left open.
 * @param caps close the two ends with a fan when `closed` is false, giving `χ = 2`.
 */
fun tubeMesh(
    path: List<Vec3>,
    radius: Number = 0.1,
    sides: Int = 12,
    closed: Boolean = false,
    caps: Boolean = true,
): TriMesh {
    require(sides >= 3) { "tubeMesh needs >= 3 sides, got $sides" }
    val pts = ArrayList<Vec3>(path.size)
    for (p in path) if (pts.isEmpty() || pts[pts.size - 1].distance(p) > 1e-9f) pts.add(Vec3(p))
    if (closed && pts.size > 1 && pts[0].distance(pts[pts.size - 1]) < 1e-9f) pts.removeAt(pts.size - 1)
    require(pts.size >= 2) { "tubeMesh needs >= 2 distinct path points, got ${pts.size}" }
    val r = radius.toFloat()

    // Tangents: central differences inside, one-sided at the ends (wrapped when closed).
    val n = pts.size
    val tangents = Array(n) { i ->
        val prev = pts[if (i == 0) (if (closed) n - 1 else 0) else i - 1]
        val next = pts[if (i == n - 1) (if (closed) 0 else n - 1) else i + 1]
        val t = Vec3(next).sub(prev)
        if (t.length() < 1e-12f) Vec3(0f, 0f, 1f) else t.normalize()
    }

    // Rotation-minimising frames by double reflection (Wang et al.), seeded with any perpendicular.
    val seed = tangents[0]
    val helper = if (abs(seed.x) < 0.9f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)
    var u = Vec3(helper).cross(seed).normalize()
    val frameU = arrayOfNulls<Vec3>(n)
    val frameV = arrayOfNulls<Vec3>(n)
    for (i in 0 until n) {
        if (i > 0) {
            // Reflect u across the plane bisecting tangent[i-1] and tangent[i].
            val t0 = tangents[i - 1]; val t1 = tangents[i]
            val bis = Vec3(t0).add(t1)
            val bl = bis.lengthSquared()
            u = if (bl < 1e-12f) {
                Vec3(u).negate() // a 180-degree turn: reverse rather than divide by zero
            } else {
                val d = 2f * u.dot(bis) / bl
                Vec3(u).sub(bis.x * d, bis.y * d, bis.z * d)
            }
            // Re-orthogonalise against the new tangent so float drift cannot accumulate.
            val along = u.dot(t1)
            u.sub(t1.x * along, t1.y * along, t1.z * along)
            if (u.length() < 1e-9f) {
                val h = if (abs(t1.x) < 0.9f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)
                u = h.cross(t1)
            }
            u.normalize()
        }
        frameU[i] = Vec3(u)
        frameV[i] = Vec3(tangents[i]).cross(u).normalize()
    }

    return triMesh {
        val rings = Array(n) { i ->
            IntArray(sides) { k ->
                val a = 2.0 * PI * k / sides
                val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                val fu = frameU[i]!!; val fv = frameV[i]!!
                val nx = fu.x * ca + fv.x * sa
                val ny = fu.y * ca + fv.y * sa
                val nz = fu.z * ca + fv.z * sa
                vertex(
                    pts[i].x + nx * r, pts[i].y + ny * r, pts[i].z + nz * r,
                    normal = Vec3(nx, ny, nz),
                )
            }
        }
        val spans = if (closed) n else n - 1
        for (i in 0 until spans) {
            val j = (i + 1) % n
            for (k in 0 until sides) {
                val k2 = (k + 1) % sides
                // Around-the-tube first, then along it: the other order gives `tangent x radial`, which
                // is the inward normal, and an inside-out tube.
                quad(rings[i][k], rings[i][k2], rings[j][k2], rings[j][k])
            }
        }
        if (!closed && caps) {
            // A cap hub cannot share the ring's radial normal, so caps make the tube crease-shaded;
            // the ring vertices keep their smooth normals and the hub gets the axial one.
            val startHub = vertex(pts[0].x, pts[0].y, pts[0].z, normal = Vec3(tangents[0]).negate())
            for (k in 0 until sides) triangle(startHub, rings[0][(k + 1) % sides], rings[0][k])
            val endHub = vertex(pts[n - 1].x, pts[n - 1].y, pts[n - 1].z, normal = Vec3(tangents[n - 1]))
            for (k in 0 until sides) triangle(endHub, rings[n - 1][k], rings[n - 1][(k + 1) % sides])
        }
    }
}

// ==============================================================================================
// Heightfields
// ==============================================================================================

/**
 * A landscape: a grid in the `xz` plane whose `y` comes from [height], with computed normals and uvs.
 *
 * [height] is called with **world** `(x, z)`, not grid indices, so the same lambda works at any
 * resolution — which is the whole point of sampling a field rather than an array.
 *
 * A disc, not a solid (`χ = 1`, one boundary loop). Normals are the analytic surface normals of the
 * triangulated grid, computed by central differences on the sampled heights.
 *
 * @param nx quads along x (>= 1). @param nz quads along z (>= 1).
 * @param width extent along x, centred on the origin. @param depth extent along z.
 */
fun heightfieldMesh(
    nx: Int = 64,
    nz: Int = 64,
    width: Number = 2.0,
    depth: Number = 2.0,
    height: (Float, Float) -> Float,
): TriMesh {
    require(nx >= 1 && nz >= 1) { "heightfieldMesh needs at least one quad each way, got ${nx}x$nz" }
    val w = width.toFloat(); val d = depth.toFloat()
    val dx = w / nx; val dz = d / nz
    val ys = FloatArray((nx + 1) * (nz + 1))
    fun xAt(i: Int) = (i.toFloat() / nx - 0.5f) * w
    fun zAt(j: Int) = (j.toFloat() / nz - 0.5f) * d
    for (j in 0..nz) for (i in 0..nx) ys[j * (nx + 1) + i] = height(xAt(i), zAt(j))
    fun yAt(i: Int, j: Int) = ys[j.coerceIn(0, nz) * (nx + 1) + i.coerceIn(0, nx)]
    return triMesh {
        for (j in 0..nz) for (i in 0..nx) {
            // Central differences give the tangent plane; the cross product of the two tangents is
            // the normal. One-sided at the border, which is exactly what coerceIn above arranges.
            val dydx = (yAt(i + 1, j) - yAt(i - 1, j)) / ((if (i == 0 || i == nx) 1 else 2) * dx)
            val dydz = (yAt(i, j + 1) - yAt(i, j - 1)) / ((if (j == 0 || j == nz) 1 else 2) * dz)
            val nrm = Vec3(-dydx, 1f, -dydz).normalize()
            vertex(xAt(i), yAt(i, j), zAt(j), normal = nrm, uv = Vec2(i.toFloat() / nx, j.toFloat() / nz))
        }
        fun at(i: Int, j: Int) = j * (nx + 1) + i
        for (j in 0 until nz) for (i in 0 until nx) {
            quad(at(i, j), at(i, j + 1), at(i + 1, j + 1), at(i + 1, j))
        }
    }
}

/**
 * A heightfield sampled from an [Img] — one grid vertex per pixel by default, with the image's
 * luminance (or its single channel) as `y`.
 *
 * The bridge that lets the imaging family drive the mesh family: blur, threshold, distance-transform
 * or edge-detect an image, then look at the result as a surface.
 *
 * @param scale multiplies the sampled value into world `y`.
 * @param width extent along x; [depth] defaults to width scaled by the image's aspect ratio.
 * @param step sample every [step]th pixel — `2` on a 1000x1000 image gives a 500x500 grid.
 */
fun Img.toHeightfieldMesh(
    scale: Number = 0.25,
    width: Number = 2.0,
    depth: Number? = null,
    step: Int = 1,
): TriMesh {
    require(step >= 1) { "step must be >= 1, got $step" }
    val src = if (channels == 1) this else luma()
    val w = width.toFloat()
    val d = depth?.toFloat() ?: (w * src.height / src.width)
    val nx = max(1, (src.width - 1) / step)
    val nz = max(1, (src.height - 1) / step)
    val s = scale.toFloat()
    return heightfieldMesh(nx, nz, w, d) { x, z ->
        val u = ((x / w) + 0.5f).coerceIn(0f, 1f)
        val v = ((z / d) + 0.5f).coerceIn(0f, 1f)
        val px = (u * (src.width - 1)).toInt().coerceIn(0, src.width - 1)
        val py = (v * (src.height - 1)).toInt().coerceIn(0, src.height - 1)
        src.at(px, py) * s
    }
}
