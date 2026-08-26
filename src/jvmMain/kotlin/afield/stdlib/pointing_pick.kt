@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.stdlib.linalg.Matrix4f
import afield.stdlib.polytope.TriangulatedGeometry
import kotlin.math.sqrt

/**
 * A scene you can point at, and a hit record that names the **part** you touched rather than the
 * triangle.
 *
 * # Why a triangle index is not an answer
 *
 * `MeshBvh.raycast` returns a [RayHit], and `RayHit.triangle` is an index into an [TriMesh]'s
 * `indices` array. That is the right thing for a physics query and the wrong thing for a person: a
 * human pointing at a house wants to hear *"the east wall"*, not *"triangle 4471"*, and a triangle
 * index changes every time the geometry is rebuilt, so it cannot be written down, logged, compared
 * between two runs or used as a selection.
 *
 * So a pickable carries [PickParts] — a per-triangle part id with names — and a [PickHit] reports
 * the part. Three things then become possible that were not: naming the thing you touched, keeping
 * a selection across a re-render, and asking whether two pointers found *the same* part.
 *
 * # Where a part id comes from
 *
 * In descending order of how much it is worth trusting:
 *
 *  1. **The geometry already knows.** [PickParts.fromTriangulated] reads
 *     [TriangulatedGeometry.sourceFace] — the polytope layer has recorded, for every triangle it
 *     emits, the polygonal face it was cut from — well covered by the polytope tests and, until now,
 *     read by nothing in `jvmMain` outside `polytope` itself. This is the only
 *     source here where the id means something *outside* the mesh: it is an
 *     [afield.stdlib.polytope.ElementId] in the polytope's own topology, so it survives
 *     re-triangulation with a different triangulator and can be handed back to the polytope API.
 *  2. **The author said so.** [PickParts.of] takes the array, for a builder that knows which
 *     triangles it just appended — see [TriMeshBuilder.groups], which records it for you.
 *  3. **The topology can guess.** [PickParts.byComponent] splits on connected components. Cheap and
 *     cheap, and exactly as good as the mesh's connectivity: a house whose walls all share vertices
 *     is one component and therefore one part, which is a real limitation and not a bug here.
 *
 * ## What happens when the geometry is rebuilt
 *
 * [PickParts] is **keyed by triangle index and valid only for the mesh it was built from.**
 * [Pickable] checks the triangle count on construction, which catches the gross case; it cannot
 * catch a mesh of the same size with different triangles, and does not pretend to. If you rebuild
 * geometry every frame, rebuild the [PickParts] with it and carry the *part id* — never the triangle
 * — across the rebuild. That is the whole reason ids exist.
 *
 * # Typical use
 *
 * ```kotlin
 * val scene = pickScene {
 *     add("floor", plane(4.0), parts = PickParts.single(plane(4.0), "floor"))
 *     add("lamp", icosphere(2), at = Matrix4f().translation(0f, 1.5f, 0f))
 * }
 * val camera = MeshView().rayCamera(scene.mergedMesh())
 * val hit = scene.pick(camera.rayThroughPixel(360, 270))
 * println(hit?.describe())      // "lamp / lamp  tri 812  1.83 away  at (0.31, 1.72, 0.44)"
 * ```
 */

// ==============================================================================================
// Parts
// ==============================================================================================

/**
 * Which named part each of a mesh's triangles belongs to — the "stable picking id through triangles"
 * a pick record needs in order to say something a person can act on.
 *
 * [triangleParts] has one entry per triangle, holding an index into [names]. A triangle may be
 * assigned [UNASSIGNED], in which case it is still hit but reports the name [UNASSIGNED_NAME].
 *
 * Immutable, and cheap: two int arrays and a list of strings. Build one per mesh and hold it beside
 * the [MeshBvh].
 */
class PickParts private constructor(
    /** Per-triangle part index, or [UNASSIGNED]. Copied on construction; the array is not shared. */
    private val storedTriangleParts: IntArray,
    /** Part names, indexed by part id. */
    val names: List<String>,
    /**
     * For [fromTriangulated], the part's id in the geometry's own world — a polytope
     * [afield.stdlib.polytope.ElementId]'s value. `null` for parts that have no such identity.
     *
     * This is the field that makes a part id mean something outside this mesh.
     */
    val externalIds: List<Int?>,
) {
    /** Triangles this covers. Must equal the mesh's `triangleCount`. */
    val triangleCount: Int get() = storedTriangleParts.size

    /** How many named parts there are. */
    val partCount: Int get() = names.size

    /** Per-triangle part indices, as a fresh array. */
    val triangleParts: IntArray get() = storedTriangleParts.copyOf()

    /** Which part triangle [triangle] belongs to, or [UNASSIGNED]. */
    fun partOf(triangle: Int): Int =
        if (triangle in storedTriangleParts.indices) storedTriangleParts[triangle] else UNASSIGNED

    /** The name of the part triangle [triangle] belongs to, or [UNASSIGNED_NAME]. */
    fun nameOfTriangle(triangle: Int): String = nameOfPart(partOf(triangle))

    /** The name of part [part], or [UNASSIGNED_NAME] when it is out of range. */
    fun nameOfPart(part: Int): String = names.getOrNull(part) ?: UNASSIGNED_NAME

    /** Part [part]'s identity in the geometry it came from, or `null`. See [externalIds]. */
    fun externalIdOfPart(part: Int): Int? = externalIds.getOrNull(part)

    /** Every triangle belonging to part [part]. Linear in the triangle count. */
    fun trianglesOfPart(part: Int): IntArray {
        var n = 0
        for (t in storedTriangleParts) if (t == part) n++
        val out = IntArray(n)
        var at = 0
        for (i in storedTriangleParts.indices) if (storedTriangleParts[i] == part) out[at++] = i
        return out
    }

    /** How many triangles each part got, indexed by part. Useful in a caption. */
    fun triangleCounts(): IntArray {
        val out = IntArray(partCount)
        for (t in storedTriangleParts) if (t in out.indices) out[t]++
        return out
    }

    override fun toString(): String =
        "PickParts($partCount part(s) over $triangleCount triangle(s): " +
            names.take(6).joinToString(", ") + if (partCount > 6) ", …)" else ")"

    companion object {
        /** The part id of a triangle nobody claimed. */
        const val UNASSIGNED: Int = -1

        /** What [nameOfPart] answers for [UNASSIGNED]. */
        const val UNASSIGNED_NAME: String = "(unassigned)"

        /**
         * Every triangle in one part.
         *
         * The right default: a pick still names the *object*, and the part simply adds nothing. Much
         * better than pretending each triangle is its own part, which is the answer that reads as
         * information and is not.
         */
        fun single(mesh: TriMesh, name: String = "all"): PickParts =
            PickParts(IntArray(mesh.triangleCount) { 0 }, listOf(name), listOf(null))

        /**
         * The assignment you already have.
         *
         * @param triangleParts one entry per triangle, an index into [names] or [UNASSIGNED].
         */
        fun of(triangleParts: IntArray, names: List<String>, externalIds: List<Int?> = names.map { null }): PickParts {
            require(externalIds.size == names.size) {
                "${names.size} name(s) but ${externalIds.size} external id(s) — supply one per part"
            }
            for (i in triangleParts.indices) {
                require(triangleParts[i] == UNASSIGNED || triangleParts[i] in names.indices) {
                    "triangle $i is assigned to part ${triangleParts[i]}, outside the ${names.size} named part(s)"
                }
            }
            return PickParts(triangleParts.copyOf(), names.toList(), externalIds.toList())
        }

        /**
         * One part per connected component of the mesh, largest first.
         *
         * Explicit about what it can see: components are joined *through shared vertices*, so a model
         * whose parts were welded into one surface is one component and therefore one part. That is
         * exactly the right answer for a bag of separate solids — a `TriMeshBuilder.add` of twelve
         * beams, a marched surface with specks, an STL with no groups — and exactly the wrong answer
         * for a watertight building, which is why [fromTriangulated] and [of] exist.
         *
         * @param name given the component's rank (0 = biggest) and its triangle count.
         */
        fun byComponent(
            mesh: TriMesh,
            name: (rank: Int, triangles: Int) -> String = { rank, _ -> "component $rank" },
        ): PickParts {
            if (mesh.triangleCount == 0) return PickParts(IntArray(0), emptyList(), emptyList())
            val topo = mesh.topology()
            val ownerOfVertex = IntArray(mesh.vertexCount) { UNASSIGNED }
            topo.components.forEachIndexed { c, vertices -> for (v in vertices) ownerOfVertex[v] = c }
            val rawTriangleOwner = IntArray(mesh.triangleCount) { t -> ownerOfVertex[mesh.ia(t)] }
            // Rank by triangle count so "component 0" means "the big one" rather than "whichever
            // vertex the walk happened to start from".
            val counts = IntArray(topo.componentCount)
            for (o in rawTriangleOwner) if (o in counts.indices) counts[o]++
            val order = counts.indices.sortedWith(compareByDescending<Int> { counts[it] }.thenBy { it })
            val rankOf = IntArray(topo.componentCount)
            order.forEachIndexed { rank, component -> rankOf[component] = rank }
            val parts = IntArray(mesh.triangleCount) { t ->
                val o = rawTriangleOwner[t]
                if (o in rankOf.indices) rankOf[o] else UNASSIGNED
            }
            val names = order.mapIndexed { rank, component -> name(rank, counts[component]) }
            return PickParts(parts, names, names.map { null })
        }

        /**
         * One part per polygonal face of the polytope this geometry was triangulated from —
         * consuming [TriangulatedGeometry.sourceFace], which no `jvmMain` code outside `polytope`
         * did before (its ten call sites were all in `src/jvmTest`).
         *
         * This is the strongest kind of part id available, because it is not derived from the
         * triangles at all: the polytope layer recorded, at triangulation time, which face each
         * triangle was cut from, and the id is an [afield.stdlib.polytope.ElementId] in the
         * polytope's own topology. Re-triangulate with a different triangulator, or with a different
         * crease policy, and the *triangles* all change while the face ids do not. That is what
         * "stable through triangles" means.
         *
         * @param name given the face's [afield.stdlib.polytope.ElementId] value and its triangle count.
         */
        fun fromTriangulated(
            geometry: TriangulatedGeometry,
            name: (faceId: Int, triangles: Int) -> String = { faceId, _ -> "face $faceId" },
        ): PickParts {
            val faceIds = IntArray(geometry.triangleCount) { geometry.sourceFace(it).value }
            // First appearance order, so part 0 is the first face the triangulator emitted — which is
            // the polytope's own face order, and therefore reproducible.
            val order = LinkedHashMap<Int, Int>()
            for (id in faceIds) order.getOrPut(id) { order.size }
            val counts = IntArray(order.size)
            for (id in faceIds) counts[order.getValue(id)]++
            val parts = IntArray(faceIds.size) { order.getValue(faceIds[it]) }
            val ids = order.keys.toList()
            return PickParts(parts, ids.mapIndexed { at, id -> name(id, counts[at]) }, ids)
        }
    }
}

/**
 * A [PickParts] recording the groups this builder was told about — the "the author said so" source,
 * and the one to reach for when you are assembling a model out of named pieces.
 *
 * Call [TriMeshBuilder.group] before appending, and every triangle appended after it lands in that
 * group. Triangles appended before the first [TriMeshBuilder.group] are [PickParts.UNASSIGNED] —
 * still hit, reported as `"(unassigned)"`.
 *
 * **Each [TriMeshBuilder.group] call starts a new part, even if the name repeats.** Two
 * `group("wall")` calls give two parts both called `wall`, because the alternative — merging them —
 * would make two distinguishable pieces indistinguishable, and the point of a part id is to
 * distinguish. Name them `wall east` and `wall west` if you meant two, and call `group` once if you
 * meant one.
 */
fun TriMeshBuilder.groups(): PickParts {
    val marks = groupMarks()
    val parts = IntArray(triangleCount) { PickParts.UNASSIGNED }
    marks.forEachIndexed { at, (from, _) ->
        val until = (marks.getOrNull(at + 1)?.first ?: triangleCount).coerceAtMost(triangleCount)
        for (t in from until until) parts[t] = at
    }
    return PickParts.of(parts, marks.map { it.second })
}

// ==============================================================================================
// Pickables and hits
// ==============================================================================================

/**
 * One thing in a scene that a ray can hit: CPU geometry, a name, a part assignment, and where it
 * stands.
 *
 * The BVH is built eagerly, because that is the expensive part and a pickable exists in order to be
 * queried many times. Building a [Pickable] per frame over a large mesh is the wrong shape; build it
 * once and move it with [at].
 *
 * ## The transform, and what a distance means under one
 *
 * [at] is the model-to-world matrix. Picking happens in **model** space — the ray is pushed through
 * the inverse — because that is where the BVH lives and rebuilding a BVH per frame to follow a
 * moving object would be absurd. Two consequences, both handled:
 *
 *  - **A non-uniform scale changes what "distance along the ray" means**, so the model-space
 *    parameter is not the world distance. [PickHit.distance] is therefore measured *in world space*,
 *    from the ray's origin to the transformed hit point, and is comparable across pickables with
 *    different scales. That is what makes "nearest hit wins" correct in [PickScene.pick].
 *  - **A normal does not transform like a direction.** [PickHit.normal] goes through the inverse
 *    transpose of the upper 3x3, so a squashed box's faces still report normals perpendicular to
 *    themselves.
 */
class Pickable(
    /** What to call this when a pick reports it. */
    val name: String,
    /** The geometry. Captured by reference; nothing here mutates it. */
    val mesh: TriMesh,
    /** Which part each triangle belongs to. Defaults to one part named after the object. */
    val parts: PickParts = PickParts.single(mesh, name),
    /** Model-to-world. `null` means the mesh is already in world coordinates, and skips the maths. */
    val at: Matrix4f? = null,
    /** Set false to leave it in the scene but stop it answering — a "look through this" flag. */
    val enabled: Boolean = true,
    leafSize: Int = 8,
) {
    /** The BVH over [mesh], in model space. */
    val bvh: MeshBvh = mesh.bvh(leafSize)

    init {
        require(parts.triangleCount == mesh.triangleCount) {
            "'$name': the part assignment covers ${parts.triangleCount} triangle(s) but the mesh has " +
                "${mesh.triangleCount}. A PickParts is keyed by triangle index and is only valid for " +
                "the mesh it was built from — rebuild it with the mesh."
        }
    }

    /** Model-to-world as doubles, column-major, or `null` when [at] is. */
    internal val toWorld: DoubleArray? = at?.let { m -> DoubleArray(16) { m[it].toDouble() } }

    /** World-to-model, for pushing a ray in. */
    internal val toModel: DoubleArray? = toWorld?.let {
        invert4(it) ?: throw IllegalArgumentException(
            "'$name': its model-to-world matrix is singular, so it occupies no volume and nothing can " +
                "point at it. A zero scale on one axis does this."
        )
    }

    /**
     * The transpose of the inverse's upper 3x3, for normals. Stored row-major as `[r * 3 + c]`,
     * because that is how [PickScene] multiplies it.
     */
    internal val normalMatrix: DoubleArray? = toModel?.let { inv ->
        // normal = (M^-1)^T * n. `inv` is column-major, so inv[c*4+r]; its transpose's (r,c) is
        // inv[r*4+c].
        DoubleArray(9) { i -> inv[(i / 3) * 4 + (i % 3)] }
    }

    override fun toString(): String =
        "Pickable('$name', ${mesh.triangleCount} tris, ${parts.partCount} part(s)" +
            (if (at == null) "" else ", transformed") + (if (enabled) "" else ", disabled") + ")"
}

/**
 * What a pointer touched: which object, which named part, and the geometry of the contact.
 *
 * Shaped like [FLineIntersection], Field's browser-side hit record, so the two read the
 * same way — a kind of thing, an index of the element within its own object, a parameter on the
 * element, and the point *on* the element rather than the point the ray was aimed at. The
 * differences are the real ones: a triangle's parameter is two barycentric weights rather than one
 * curve parameter, and this one has a real depth because it was computed in 3D rather than recovered
 * from a flattened SVG.
 */
class PickHit(
    /** The thing that was hit. */
    val pickable: Pickable,
    /** [Pickable.name], hoisted so a hit can be logged without holding the geometry. */
    val objectName: String,
    /** Part id within [Pickable.parts], or [PickParts.UNASSIGNED]. */
    val part: Int,
    /** The part's name. */
    val partName: String,
    /**
     * The part's identity in the geometry it came from — a polytope face's `ElementId` value — or
     * `null`. This is the id worth writing down; [triangle] is not.
     */
    val externalId: Int?,
    /** Index of the triangle hit, in [Pickable.mesh]. Changes whenever the geometry is rebuilt. */
    val triangle: Int,
    /** Distance from the ray's origin, in **world** units. See [Pickable]'s note on scale. */
    val distance: Double,
    /** The contact point, in world space. */
    val point: Vec3,
    /** The hit triangle's geometric normal in world space, unit length, **not** flipped toward the ray. */
    val normal: Vec3,
    /** Barycentric weight on the triangle's second corner. `u + v <= 1`. */
    val u: Double,
    /** Barycentric weight on the triangle's third corner. `u + v <= 1`. */
    val v: Double,
    /** The contact point in the pickable's own model space — where its geometry thinks it is. */
    val localPoint: Vec3,
) {
    /** True when the normal points back toward where the ray came from. */
    fun isFrontFacing(ray: Ray): Boolean =
        normal.x * ray.dirX + normal.y * ray.dirY + normal.z * ray.dirZ < 0.0

    /** One line a person can read. The `println` of a pick. */
    fun describe(): String =
        "%s / %s  tri %d  %.4f away  at (%.4f, %.4f, %.4f)%s"
            .format(
                objectName, partName, triangle, distance, point.x, point.y, point.z,
                externalId?.let { "  [id $it]" } ?: "",
            )

    override fun toString(): String = "PickHit(${describe()})"
}

// ==============================================================================================
// The scene
// ==============================================================================================

/**
 * Several [Pickable]s, and nearest-hit resolution across them.
 *
 * The whole content of the class is *"which one, and which part of it"* — which sounds trivial and is
 * the thing that was missing. A ray against one mesh was already available; a ray against a scene,
 * answering with a name, was not.
 */
class PickScene(pickables: List<Pickable> = emptyList()) {

    /** Everything in the scene, in the order it was added. */
    val pickables: List<Pickable> = pickables.toList()

    /** The pickable called [name], or `null`. First match wins if two share a name. */
    operator fun get(name: String): Pickable? = pickables.firstOrNull { it.name == name }

    /** A scene with [pickable] added. [PickScene] is immutable; this returns a new one. */
    operator fun plus(pickable: Pickable): PickScene = PickScene(pickables + pickable)

    /** Total triangles across every pickable, enabled or not. */
    val triangleCount: Int get() = pickables.sumOf { it.mesh.triangleCount }

    /**
     * The nearest thing [ray] hits, or `null`.
     *
     * Nearest by **world** distance, so a scaled pickable does not win by having a compressed
     * parameter — see [Pickable]'s note.
     *
     * @param maxDistance ignore anything further away than this.
     * @param cullBackFaces skip triangles facing away, as a renderer would. Off by default, because
     *   "did my ray leave the solid" needs them and because a mesh wound inside-out should still be
     *   pickable rather than mysteriously inert.
     */
    fun pick(
        ray: Ray,
        maxDistance: Double = Double.MAX_VALUE,
        cullBackFaces: Boolean = false,
    ): PickHit? {
        var best: PickHit? = null
        for (pickable in pickables) {
            if (!pickable.enabled) continue
            val hit = pickOne(pickable, ray, best?.distance ?: maxDistance, cullBackFaces) ?: continue
            if (best == null || hit.distance < best.distance) best = hit
        }
        return best
    }

    /**
     * Every hit along [ray], nearest first — one per pickable, its own nearest.
     *
     * For "what is behind this", and for a test that wants to see the ordering rather than trust it.
     */
    fun pickAll(
        ray: Ray,
        maxDistance: Double = Double.MAX_VALUE,
        cullBackFaces: Boolean = false,
    ): List<PickHit> = pickables
        .filter { it.enabled }
        .mapNotNull { pickOne(it, ray, maxDistance, cullBackFaces) }
        .sortedBy { it.distance }

    /**
     * [ray] against one pickable, in its model space, with the answer brought back to world.
     *
     * [maxDistance] is a *world* distance and is passed to the BVH as a model-space bound only when
     * the pickable is untransformed; under a transform the BVH is given no bound and the world
     * distance is checked afterwards. That is the conservative choice — converting a
     * world bound into a model bound needs the scale factor along the ray, and getting that subtly
     * wrong would silently drop hits, which is far worse than visiting a few extra nodes.
     */
    private fun pickOne(pickable: Pickable, ray: Ray, maxDistance: Double, cullBackFaces: Boolean): PickHit? {
        val toModel = pickable.toModel
        val localRay: Ray
        if (toModel == null) {
            localRay = ray
        } else {
            val o = transform4(toModel, ray.originX, ray.originY, ray.originZ, 1.0)
            val d = transform4(toModel, ray.dirX, ray.dirY, ray.dirZ, 0.0)
            if (d[0] == 0.0 && d[1] == 0.0 && d[2] == 0.0) return null
            localRay = Ray(o[0] / o[3], o[1] / o[3], o[2] / o[3], d[0], d[1], d[2])
        }
        val bound = if (toModel == null) maxDistance else Double.MAX_VALUE
        val hit = pickable.bvh.raycast(localRay.origin, localRay.direction, bound, cullBackFaces) ?: return null

        val local = hit.point
        val worldPoint: Vec3
        val worldNormal: Vec3
        if (pickable.toWorld == null) {
            worldPoint = local
            worldNormal = Vec3(hit.normal)
        } else {
            val p = transform4(pickable.toWorld, local.x.toDouble(), local.y.toDouble(), local.z.toDouble(), 1.0)
            worldPoint = Vec3((p[0] / p[3]).toFloat(), (p[1] / p[3]).toFloat(), (p[2] / p[3]).toFloat())
            val n = pickable.normalMatrix!!
            val nx = n[0] * hit.normal.x + n[1] * hit.normal.y + n[2] * hit.normal.z
            val ny = n[3] * hit.normal.x + n[4] * hit.normal.y + n[5] * hit.normal.z
            val nz = n[6] * hit.normal.x + n[7] * hit.normal.y + n[8] * hit.normal.z
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            worldNormal = if (len < 1e-12) Vec3(hit.normal)
            else Vec3((nx / len).toFloat(), (ny / len).toFloat(), (nz / len).toFloat())
        }

        val dx = worldPoint.x - ray.originX
        val dy = worldPoint.y - ray.originY
        val dz = worldPoint.z - ray.originZ
        val worldDistance = sqrt(dx * dx + dy * dy + dz * dz)
        if (worldDistance > maxDistance) return null

        val part = pickable.parts.partOf(hit.triangle)
        return PickHit(
            pickable = pickable,
            objectName = pickable.name,
            part = part,
            partName = pickable.parts.nameOfPart(part),
            externalId = pickable.parts.externalIdOfPart(part),
            triangle = hit.triangle,
            distance = worldDistance,
            point = worldPoint,
            normal = worldNormal,
            u = hit.u,
            v = hit.v,
            localPoint = local,
        )
    }

    /**
     * Every pickable's geometry, transformed into world space and concatenated — for framing a
     * camera on the whole scene, and for rendering it in one call.
     *
     * The triangle indices of the result do **not** line up with any pickable's [PickParts]; use
     * [mergedParts] for a part assignment over the merged mesh.
     *
     * **Computed once and held.** [PickScene] is immutable, so the merge cannot go stale, and the
     * alternative was measurably wrong in practice: every `renderPointer` call needs the merged mesh,
     * a pointer moving under a mouse calls it once per frame, and re-transforming and re-concatenating
     * every vertex to draw the same static scene again is work with no result. `plus` returns a new
     * scene, which gets its own lazy merge — which is the correct invalidation, for free.
     */
    fun mergedMesh(): TriMesh = merged.first

    /** [mergedMesh]'s part assignment, named `"object / part"` so a merged hit is still legible. */
    fun mergedParts(): PickParts = merged.second

    private val merged: Pair<TriMesh, PickParts> by lazy { computeMerged() }

    private fun computeMerged(): Pair<TriMesh, PickParts> {
        val builder = TriMeshBuilder()
        val names = ArrayList<String>()
        val externalIds = ArrayList<Int?>()
        val assignment = ArrayList<Int>()
        val keyToPart = HashMap<Pair<String, Int>, Int>()
        for (pickable in pickables) {
            val placed = if (pickable.toWorld == null) pickable.mesh else pickable.mesh.placedBy(pickable.toWorld)
            builder.add(placed)
            for (t in 0 until placed.triangleCount) {
                val local = pickable.parts.partOf(t)
                val key = pickable.name to local
                val part = keyToPart.getOrPut(key) {
                    names += "${pickable.name} / ${pickable.parts.nameOfPart(local)}"
                    externalIds += pickable.parts.externalIdOfPart(local)
                    names.size - 1
                }
                assignment += part
            }
        }
        return builder.build() to PickParts.of(assignment.toIntArray(), names, externalIds)
    }
}

/**
 * This mesh with every vertex pushed through a model-to-world matrix (column-major doubles), and its
 * normals through that matrix's inverse transpose.
 *
 * Private, and only here to serve [PickScene.mergedMesh]. A public `TriMesh.transformedBy` belongs in
 * `mesh_process.kt` beside the other geometry operations rather than smuggled in under picking, and
 * adding one is a separate decision from this file's.
 */
private fun TriMesh.placedBy(toWorld: DoubleArray): TriMesh {
    val out = FloatArray(positions.size)
    for (v in 0 until vertexCount) {
        val p = transform4(toWorld, x(v).toDouble(), y(v).toDouble(), z(v).toDouble(), 1.0)
        out[v * 3] = (p[0] / p[3]).toFloat()
        out[v * 3 + 1] = (p[1] / p[3]).toFloat()
        out[v * 3 + 2] = (p[2] / p[3]).toFloat()
    }
    val movedNormals = normals?.let { src ->
        val inv = invert4(toWorld) ?: return@let src.copyOf()
        // (M^-1)^T applied to a direction: inv is column-major, so the transpose's (r, c) is inv[r*4+c].
        FloatArray(src.size).also { dest ->
            for (v in 0 until vertexCount) {
                val nx = src[v * 3].toDouble(); val ny = src[v * 3 + 1].toDouble(); val nz = src[v * 3 + 2].toDouble()
                var tx = inv[0] * nx + inv[1] * ny + inv[2] * nz
                var ty = inv[4] * nx + inv[5] * ny + inv[6] * nz
                var tz = inv[8] * nx + inv[9] * ny + inv[10] * nz
                val len = sqrt(tx * tx + ty * ty + tz * tz)
                if (len > 1e-12) { tx /= len; ty /= len; tz /= len }
                dest[v * 3] = tx.toFloat(); dest[v * 3 + 1] = ty.toFloat(); dest[v * 3 + 2] = tz.toFloat()
            }
        }
    }
    return TriMesh(out, indices.copyOf(), movedNormals, uvs?.copyOf(), colors?.copyOf())
}

/** Build a [PickScene]. See [PickSceneBuilder.add]. */
fun pickScene(block: PickSceneBuilder.() -> Unit): PickScene {
    val b = PickSceneBuilder()
    b.block()
    return b.build()
}

/** Collects [Pickable]s for [pickScene]. */
class PickSceneBuilder {
    private val items = ArrayList<Pickable>()

    /**
     * Add something pointable.
     *
     * @param parts defaults to one part named after the object — see [PickParts] for the sources
     *   worth using instead.
     * @param at model-to-world, or `null` when the mesh is already placed.
     */
    fun add(
        name: String,
        mesh: TriMesh,
        parts: PickParts = PickParts.single(mesh, name),
        at: Matrix4f? = null,
        enabled: Boolean = true,
    ): Pickable = Pickable(name, mesh, parts, at, enabled).also { items += it }

    /** Add an already-built [Pickable]. */
    fun add(pickable: Pickable): Pickable = pickable.also { items += it }

    internal fun build(): PickScene = PickScene(items)
}
