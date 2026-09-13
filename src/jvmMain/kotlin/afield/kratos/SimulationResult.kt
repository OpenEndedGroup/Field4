@file:afield.CompletionCategory("Simulation")
package afield.kratos

import afield.graphics.FLine
import afield.graphics.polylineFrom
import afield.stdlib.TriMesh
import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * One solved revision: the raw fields exactly as Kratos produced them, and the views Field draws.
 *
 * The result is **described rather than declared**. The worker sends a `fields` list saying which
 * arrays it produced, whether each is nodal or per-cell, and how many components it has, and this
 * class holds whatever arrived. That is what lets one class carry a scalar temperature and a
 * velocity–pressure pair without knowing about either: [named] is the general accessor, and
 * [temperature], [pressure] and [velocity] are conveniences that fail with the list of fields that
 * *are* present rather than with a null.
 *
 * Two fields follow from how the formulation meets its geometry rather than from its physics, and
 * they are direct properties for that reason. An **embedded** result carries [distance], the signed
 * nodal level set, positive on the side that was solved, and [cellState], `0` inactive, `1` wholly
 * inside, `2` cut. A **body-fitted** result carries neither: its mesh is the body, so there is no
 * level set to report and no cell to classify, and both accessors say which fields did arrive
 * rather than inventing zeros.
 *
 * # Sampling is barycentric in the grid's own tetrahedra
 *
 * [sample] finds the containing cube, sorts the local coordinates to pick the same one of the six
 * Freudenthal tetrahedra the worker built, and takes its four barycentric weights. It is not
 * trilinear hexahedral interpolation and would disagree with one; it matches the discretisation
 * the answer was computed on. A point outside the domain, or one with no active support, samples
 * as `null` rather than as zero.
 *
 * That index calculation is closed-form only on a structured grid, so on a body-fitted mesh point
 * sampling is `null` everywhere and [sliceMesh] refuses outright. [boundaryMesh] and [deformedMesh]
 * are the views to draw there.
 */

/**
 * The arrays decoded from one result frame, and the sample and mesh views built over them.
 *
 * A result is decoded from a wire frame rather than built by a caller, which is why the constructor
 * is `internal`: the worker's own field list drives the decode, and every array it names is
 * shape-checked against the domain on the way in. [revision] says which solve produced this,
 * which is how a draw decides whether it is still the current one, and [diagnostics] carries the
 * worker's timings and the region identities that travelled with the request.
 */
class SimulationResult internal constructor(
    val domain: BackgroundDomain,
    val revision: SimulationRevision,
    /** Field name to values: `nodeCount` entries for a scalar, `3 * nodeCount` for a vector. */
    private val nodal: Map<String, DoubleArray>,
    /** Field name to `cellCount` values. Held as doubles because a cell field can be a state
     *  code (`cellState`) or a stress (`vonMises`), and only the wire knows which. */
    private val cells: Map<String, DoubleArray>,
    /** Components per entry, by field name: 1 for a scalar, 3 for a vector. */
    private val components: Map<String, Int>,
    val diagnostics: Map<String, Any?>,
) {
    /**
     * Signed nodal level set, positive on the side the formulation solved.
     *
     * Only an embedded formulation has one. A body-fitted result has no level set — every node of
     * the mesh is in the body by construction — and asking for it here says so rather than
     * inventing zeros. [isInside] is the question this was usually being asked to answer.
     */
    val distance: DoubleArray
        get() = nodal["distance"] ?: error(
            "this result is body-fitted and has no level set; its mesh is the body. Fields: $fieldNames")

    /** Whether node [index] is in the solved region — always true without a level set. */
    fun isInside(index: Int): Boolean = nodal["distance"]?.let { it[index] > 0 } ?: true

    /** `0` inactive, `1` wholly inside the solved region, `2` cut by the boundary. */
    val cellState: IntArray get() = cells.getValue("cellState").let { v -> IntArray(v.size) { v[it].toInt() } }

    /** A per-cell field by name — `vonMises` for a structural solve. */
    fun cellField(name: String): DoubleArray = cells[name]
        ?: error("No cell field '$name'; this result has ${cells.keys}")

    /** Per-element von Mises stress, for `structural.bodyFitted`. */
    val vonMises: DoubleArray get() = cellField("vonMises")

    /** The nodal fields this result actually carries, `distance` included. */
    val fieldNames: List<String> get() = nodal.keys.toList()

    /**
     * The field [sample] and the mesh views use when none is named.
     *
     * The first nodal scalar that is not the level set — `temperature` for conduction, `pressure`
     * for flow. Deliberately not "whatever is first": a caller that draws the default gets the
     * quantity the formulation is about, not its geometry.
     */
    val primaryField: String
        get() = nodal.keys.firstOrNull { it != "distance" && components[it] == 1 }
            ?: error("This result carries no scalar field to draw: $fieldNames")

    /** Raw values for [name], `components` per node. Throws naming what is present. */
    fun named(name: String): DoubleArray = nodal[name]
        ?: error("No field '$name' in this result; it carries $fieldNames")

    /** How many numbers each node has for [name]. */
    fun componentsOf(name: String): Int = components[name]
        ?: error("No field '$name' in this result; it carries $fieldNames")

    /** Nodal temperature, for `thermal.stationary`. */
    val temperature: DoubleArray get() = named("temperature")

    /** Nodal pressure, for `flow.embedded`. */
    val pressure: DoubleArray get() = named("pressure")

    /** Nodal velocity, xyz per node, for `flow.embedded`. */
    val velocity: DoubleArray get() = named("velocity")

    /** Velocity magnitude per node — the scalar worth colouring a flow picture by. */
    val speed: DoubleArray get() = velocity.let { v ->
        DoubleArray(domain.nodeCount) { sqrt(v[3*it]*v[3*it] + v[3*it+1]*v[3*it+1] + v[3*it+2]*v[3*it+2]) }
    }

    // ----------------------------------------------------------------------------------------
    // Sampling
    // ----------------------------------------------------------------------------------------

    fun sample(probe: SimulationProbe): SimulationProbeSample =
        SimulationProbeSample(probe.id, revision, sample(probe.x, probe.y, probe.z))

    /** [primaryField] at a point, or `null` outside the domain or the solved region. */
    fun sample(x: Double, y: Double, z: Double): Double? = sample(primaryField, x, y, z)

    /** A named scalar field at a point, or `null` outside the domain or the solved region. */
    fun sample(name: String, x: Double, y: Double, z: Double): Double? {
        require(componentsOf(name) == 1) { "$name has ${componentsOf(name)} components; use sampleVector" }
        val values = named(name)
        return weights(x, y, z)?.let { (ids, w) ->
            if (ids.none { isInside(it) }) null else ids.indices.sumOf { values[ids[it]] * w[it] }
        }
    }

    /** A named 3-component field at a point, or `null` outside the domain or the solved region. */
    fun sampleVector(name: String, x: Double, y: Double, z: Double): DoubleArray? {
        require(componentsOf(name) == 3) { "$name has ${componentsOf(name)} components; use sample" }
        val values = named(name)
        return weights(x, y, z)?.let { (ids, w) ->
            if (ids.none { isInside(it) }) null
            else DoubleArray(3) { c -> ids.indices.sumOf { values[3 * ids[it] + c] * w[it] } }
        }
    }

    private fun weights(x: Double, y: Double, z: Double): Pair<IntArray, DoubleArray>? {
        // Closed-form only on the structured grid. An unstructured body-fitted mesh would need a
        // point locator, and returning null is better than silently sampling the wrong cell.
        if (!domain.isStructured) return null
        val b = domain.bounds
        val coord = doubleArrayOf((x-b.minX)/(b.maxX-b.minX), (y-b.minY)/(b.maxY-b.minY), (z-b.minZ)/(b.maxZ-b.minZ))
        if (coord.any { !it.isFinite() || it < 0 || it > 1 }) return null
        val base = IntArray(3) { floor(coord[it]*domain.resolution).toInt().coerceAtMost(domain.resolution-1) }
        val frac = DoubleArray(3) { coord[it]*domain.resolution-base[it] }
        val order = (0..2).sortedByDescending { frac[it] }
        val ids = IntArray(4)
        ids[0] = domain.index(base[0],base[1],base[2])
        for (i in 0..2) { base[order[i]]++; ids[i+1] = domain.index(base[0],base[1],base[2]) }
        val weights = doubleArrayOf(1-frac[order[0]], frac[order[0]]-frac[order[1]], frac[order[1]]-frac[order[2]], frac[order[2]])
        return ids to weights
    }

    // ----------------------------------------------------------------------------------------
    // Streamlines
    // ----------------------------------------------------------------------------------------

    /**
     * Integrate the velocity field from a set of seed points and return the paths as [FLine]s.
     *
     * These are streamlines of the returned snapshot, not pathlines: the field is one time step,
     * so a particle would only follow these if the flow were steady. For a solve marched to a
     * quasi-steady wake the difference is small, and for one marched two steps it is not.
     *
     * Integration is midpoint RK2 at half a cell per step, which is accurate enough to show where
     * the flow goes and nowhere near accurate enough to measure with. A line stops when it leaves
     * the domain, reaches the obstacle, or stalls — [sampleVector] returns null off the active
     * region, so a line that runs into the body ends at the body rather than tunnelling through it.
     *
     * @param seeds where to start. [inletSeeds] builds a reasonable default set.
     * @param field the vector field to follow.
     * @param maxSteps cap per line, so a recirculating eddy terminates instead of spiralling.
     */
    fun streamlines(
        seeds: List<Vec3>,
        field: String = "velocity",
        maxSteps: Int = 400,
        stepFraction: Double = 0.5,
    ): List<FLine> {
        require(componentsOf(field) == 3) { "$field is not a vector field" }
        val b = domain.bounds
        val cell = minOf(b.maxX-b.minX, b.maxY-b.minY, b.maxZ-b.minZ) / domain.resolution
        val step = cell * stepFraction
        val (_, fastest) = range(field)
        val stall = fastest * 1e-3
        return seeds.mapNotNull { seed ->
            val points = ArrayList<Vec3>()
            var x = seed.x.toDouble(); var y = seed.y.toDouble(); var z = seed.z.toDouble()
            var remaining = maxSteps
            while (remaining-- > 0) {
                val v = sampleVector(field, x, y, z) ?: break
                val speed = sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
                if (speed <= stall) break
                points.add(Vec3(x.toFloat(), y.toFloat(), z.toFloat()))
                // Midpoint: sample again half a step along, and travel a full step on that.
                val hx = x + v[0]/speed*step*0.5; val hy = y + v[1]/speed*step*0.5; val hz = z + v[2]/speed*step*0.5
                val m = sampleVector(field, hx, hy, hz) ?: break
                val mid = sqrt(m[0]*m[0] + m[1]*m[1] + m[2]*m[2])
                if (mid <= stall) break
                x += m[0]/mid*step; y += m[1]/mid*step; z += m[2]/mid*step
            }
            if (points.size >= 2) polylineFrom(points) else null
        }
    }

    /**
     * A lattice of seed points just inside the low-x face, skipping any that start in the obstacle.
     *
     * `across` by `across` of them, inset from the walls so a seed does not begin on a Dirichlet
     * boundary node where the field is the boundary condition rather than the answer.
     */
    fun inletSeeds(across: Int = 5, inset: Double = 0.12): List<Vec3> {
        require(across >= 1)
        val b = domain.bounds
        val x = b.minX + (b.maxX-b.minX) * 0.02
        return buildList {
            for (i in 0 until across) for (j in 0 until across) {
                val fy = if (across == 1) 0.5 else inset + (1-2*inset) * i / (across-1.0)
                val fz = if (across == 1) 0.5 else inset + (1-2*inset) * j / (across-1.0)
                val y = b.minY + (b.maxY-b.minY) * fy
                val z = b.minZ + (b.maxZ-b.minZ) * fz
                if (sample("distance", x, y, z)?.let { it > 0 } == true) {
                    add(Vec3(x.toFloat(), y.toFloat(), z.toFloat()))
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Views
    // ----------------------------------------------------------------------------------------

    /**
     * The range a colour ramp for [name] should span: the extremes over the solved region only.
     *
     * Nodes on the inactive side hold extension values the formulation never constrained — a
     * temperature outside the part, a velocity inside the obstacle — and including them in the
     * range flattens the ramp over everything that is actually physical.
     */
    fun range(name: String): Pair<Double, Double> {
        val values = if (componentsOf(name) == 3) magnitude(named(name)) else named(name)
        var low = Double.POSITIVE_INFINITY
        var high = Double.NEGATIVE_INFINITY
        for (i in 0 until domain.nodeCount) if (isInside(i)) {
            low = minOf(low, values[i]); high = maxOf(high, values[i])
        }
        return if (low <= high) low to high else 0.0 to 1.0
    }

    private fun magnitude(v: DoubleArray) = DoubleArray(domain.nodeCount) {
        sqrt(v[3*it]*v[3*it] + v[3*it+1]*v[3*it+1] + v[3*it+2]*v[3*it+2])
    }

    private fun colorOf(value: Double?, low: Double, high: Double): FloatArray {
        if (value == null) return floatArrayOf(.35f, .35f, .35f)
        val t = ((value-low)/(high-low).coerceAtLeast(1e-15)).coerceIn(0.0, 1.0).toFloat()
        return floatArrayOf(t, .25f + .4f*(1-abs(2*t-1)), 1-t)
    }

    private fun scalarAt(name: String, x: Double, y: Double, z: Double): Double? =
        if (componentsOf(name) == 3) sampleVector(name, x, y, z)?.let { sqrt(it[0]*it[0]+it[1]*it[1]+it[2]*it[2]) }
        else sample(name, x, y, z)

    /**
     * The authored skin, coloured by a field sampled at each of its vertices.
     *
     * Grey vertices have no active background support. This is a *view*: the colour comes from
     * interpolating the background solution onto the surface, not from a surface quantity Kratos
     * computed, and it inherits the cut boundary's accuracy rather than improving on it.
     */
    fun surfaceMesh(skin: TriMesh, field: String = primaryField): TriMesh {
        val (low, high) = range(field)
        val colors = FloatArray(skin.positions.size)
        for (i in 0 until skin.vertexCount) {
            val p = skin.positions
            colorOf(scalarAt(field, p[i*3].toDouble(), p[i*3+1].toDouble(), p[i*3+2].toDouble()), low, high)
                .copyInto(colors, i*3)
        }
        return skin.withColors(colors)
    }

    /**
     * An axis-aligned interior slice, coloured by a field and cropped to the solved region.
     *
     * Triangles that straddle the boundary are dropped rather than clipped, so the edge of this
     * mesh is a display crop at slice resolution and not the cut quadrature the solve used.
     */
    fun sliceMesh(z: Double = (domain.bounds.minZ+domain.bounds.maxZ)/2, samples: Int = 64,
                  field: String = primaryField): TriMesh {
        require(samples in 2..256)
        require(domain.isStructured) {
            "sliceMesh samples by grid index and this domain is an unstructured body-fitted mesh; " +
                "use boundaryMesh or deformedMesh"
        }
        val (low, high) = range(field)
        val n = samples+1
        val positions = FloatArray(n*n*3)
        val colors = FloatArray(n*n*3)
        val inside = BooleanArray(n*n)
        val b = domain.bounds
        for (x in 0..samples) for (y in 0..samples) {
            val index = x*n+y
            val px = b.minX+(b.maxX-b.minX)*x/samples
            val py = b.minY+(b.maxY-b.minY)*y/samples
            positions[3*index]=px.toFloat(); positions[3*index+1]=py.toFloat(); positions[3*index+2]=z.toFloat()
            val w = weights(px,py,z)
            inside[index] = w?.let { (ids, ws) ->
                nodal["distance"]?.let { d -> ids.indices.sumOf { d[ids[it]] * ws[it] } > 0 } ?: true
            } == true
            colorOf(scalarAt(field, px, py, z), low, high).copyInto(colors, index*3)
        }
        val indices = ArrayList<Int>()
        fun triangle(a: Int,b2: Int,c: Int) { if (inside[a] && inside[b2] && inside[c]) { indices.add(a);indices.add(b2);indices.add(c) } }
        for (x in 0 until samples) for (y in 0 until samples) {
            val i = x*n+y
            triangle(i,i+n,i+n+1); triangle(i,i+n+1,i+1)
        }
        return TriMesh(positions,indices.toIntArray(),colors=colors)
    }

    /**
     * The mesh's own boundary, coloured by a field — the view a body-fitted result wants.
     *
     * No sampling and no interpolation: the tetrahedral mesh's boundary faces *are* the part's
     * surface, so this is the solved field displayed exactly where it was computed. A cell field
     * (von Mises, say) is averaged onto each vertex from the cells that touch it, which is a
     * display choice and not a stress-recovery scheme — do not read peak values off it.
     *
     * On a quadratic mesh each boundary triangle is split into four on the element's own midside
     * nodes, so a deflected part draws with the curvature the elements actually carry instead of a
     * fan of flat facets. That costs four times the triangles and adds no new points.
     *
     * @param displacementScale offsets every vertex by `displacementScale` times the nodal
     *   displacement, if the result has one. Real deflections are invisible at true scale; a few
     *   hundred is usually what makes a picture. `0.0` draws the undeformed part.
     */
    fun boundaryMesh(field: String = primaryFieldOrNull() ?: "", displacementScale: Double = 0.0): TriMesh {
        val corners = boundaryFaces()
        val faces = subdivided(corners) ?: corners
        val used = faces.distinct().sorted()
        val remap = HashMap<Int, Int>(used.size * 2)
        used.forEachIndexed { at, node -> remap[node] = at }

        val offset = if (displacementScale != 0.0) nodal["displacement"] else null
        val positions = FloatArray(used.size * 3)
        for ((at, node) in used.withIndex()) {
            for (c in 0 until 3) {
                val base = domain.positions[3 * node + c]
                val moved = offset?.let { base + displacementScale * it[3 * node + c] } ?: base
                positions[3 * at + c] = moved.toFloat()
            }
        }
        val indices = IntArray(faces.size) { remap.getValue(faces[it]) }
        val mesh = TriMesh(positions, indices)
        if (field.isBlank()) return mesh

        val values = nodalValuesOf(field)
        var low = Double.POSITIVE_INFINITY
        var high = Double.NEGATIVE_INFINITY
        for (node in used) { low = minOf(low, values[node]); high = maxOf(high, values[node]) }
        if (low > high) return mesh
        val colors = FloatArray(used.size * 3)
        used.forEachIndexed { at, node -> colorOf(values[node], low, high).copyInto(colors, at * 3) }
        return mesh.withColors(colors)
    }

    /** [boundaryMesh] with the deflection exaggerated — the picture a structural solve is for. */
    fun deformedMesh(displacementScale: Double = 200.0, field: String = "vonMises"): TriMesh =
        boundaryMesh(field, displacementScale)

    /** [primaryField], or null instead of throwing when there is no scalar to draw. */
    fun primaryFieldOrNull(): String? =
        nodal.keys.firstOrNull { it != "distance" && components[it] == 1 }
            ?: cells.keys.firstOrNull()

    /** A field as one value per *node*, averaging a per-cell field onto its corners. */
    private fun nodalValuesOf(name: String): DoubleArray {
        nodal[name]?.let { values ->
            if (componentsOf(name) == 1) return values
            return DoubleArray(domain.nodeCount) {
                sqrt(values[3*it]*values[3*it] + values[3*it+1]*values[3*it+1] + values[3*it+2]*values[3*it+2])
            }
        }
        val perCell = cells[name] ?: error("No field '$name'; nodal $fieldNames, cell ${cells.keys}")
        val total = DoubleArray(domain.nodeCount)
        val count = IntArray(domain.nodeCount)
        // Every node of the cell, midside ones included, so a quadratic mesh's extra nodes are
        // coloured rather than left at zero.
        for (cell in 0 until domain.cellCount) for (corner in 0 until domain.nodesPerCell) {
            val node = domain.cells[domain.nodesPerCell * cell + corner]
            total[node] += perCell[cell]; count[node]++
        }
        return DoubleArray(domain.nodeCount) { if (count[it] > 0) total[it] / count[it] else 0.0 }
    }

    /**
     * Triangles belonging to exactly one tetrahedron, wound outward.
     *
     * Corners only: a face is shared or not according to its three corners, and on a quadratic mesh
     * the midside nodes of a shared face are shared with it, so they add nothing to the test. They
     * come back in [subdivided], which needs to know which face it is looking at.
     */
    private fun boundaryFaces(): IntArray {
        val width = domain.nodesPerCell
        val seen = HashMap<Long, Int>(domain.cellCount * 2)
        val order = intArrayOf(0, 2, 1, /**/ 0, 1, 3, /**/ 1, 2, 3, /**/ 0, 3, 2)
        val faces = ArrayList<Int>()
        val keys = ArrayList<Long>()
        for (cell in 0 until domain.cellCount) {
            for (f in 0 until 4) {
                val a = domain.cells[width * cell + order[3 * f]]
                val b = domain.cells[width * cell + order[3 * f + 1]]
                val c = domain.cells[width * cell + order[3 * f + 2]]
                val sorted = intArrayOf(a, b, c).sortedArray()
                // Three node indices packed into one long; meshes here are far below 2^21 nodes.
                val key = (sorted[0].toLong() shl 42) or (sorted[1].toLong() shl 21) or sorted[2].toLong()
                seen[key] = (seen[key] ?: 0) + 1
                keys.add(key); faces.add(a); faces.add(b); faces.add(c)
            }
        }
        val kept = ArrayList<Int>()
        for (i in keys.indices) if (seen[keys[i]] == 1) {
            kept.add(faces[3 * i]); kept.add(faces[3 * i + 1]); kept.add(faces[3 * i + 2])
        }
        return kept.toIntArray()
    }

    /**
     * Each boundary triangle split into four on its midside nodes — the quadratic mesh's own extra
     * nodes, not new ones.
     *
     * Undeformed this changes nothing visible: the midside node lies exactly on the straight edge.
     * Deformed it is the whole point. A quadratic element's displacement varies quadratically along
     * an edge, so drawing corner-to-corner would straighten out precisely the curvature that makes
     * these elements worth using, and a bent beam would render as a fan of flat facets.
     *
     * Returns null when there are no midside nodes to split on.
     */
    private fun subdivided(faces: IntArray): IntArray? {
        if (domain.nodesPerCell != 10) return null
        // Corner pair -> the midside node bisecting it, harvested from the connectivity rather than
        // recomputed, using the one definition of which edge node 4+k belongs to.
        val edges = afield.tetgen.TetMesh.QUADRATIC_EDGES
        val midpoints = HashMap<Long, Int>(domain.cellCount * 4)
        // Two indices in a long, 32 bits each — unlike boundaryFaces above, which needs three in
        // one key and so is capped at 2^21 nodes.
        fun key(a: Int, b: Int): Long =
            (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
        for (cell in 0 until domain.cellCount) {
            val base = 10 * cell
            for ((k, edge) in edges.withIndex()) {
                midpoints[key(domain.cells[base + edge[0]], domain.cells[base + edge[1]])] =
                    domain.cells[base + 4 + k]
            }
        }
        val split = ArrayList<Int>(faces.size * 4)
        for (t in faces.indices step 3) {
            val a = faces[t]; val b = faces[t + 1]; val c = faces[t + 2]
            // The three sides of a boundary face are all edges of the tetrahedron it came from, so
            // all three lookups hit unless the mesh is not what it says it is.
            val ab = midpoints[key(a, b)] ?: return null
            val bc = midpoints[key(b, c)] ?: return null
            val ca = midpoints[key(c, a)] ?: return null
            split.add(a); split.add(ab); split.add(ca)
            split.add(ab); split.add(b); split.add(bc)
            split.add(ca); split.add(bc); split.add(c)
            split.add(ab); split.add(bc); split.add(ca)
        }
        return split.toIntArray()
    }

    internal companion object {
        fun decode(domain: BackgroundDomain, revision: SimulationRevision, payload: Map<String, Any?>,
                   geometryMs: Double, roundTripMs: Double, writeMs: Double = 0.0, readMs: Double = 0.0): SimulationResult {
            val diagnostics = payload["diagnostics"].fieldMap()
            require(diagnostics["backgroundId"] == domain.id && diagnostics.number("buildCount") == 1L) {
                "Worker rebuilt or replaced the background domain"
            }
            val arrays = KratosArrays(payload)
            // The worker's own field list drives the decode, so a formulation that adds an output
            // needs no change here — but every entry is still shape- and type-checked.
            val described = (diagnostics["fields"] as? List<*>)?.map { it.fieldMap() }
                ?: listOf(mapOf("name" to "temperature", "kind" to "nodal", "components" to 1),
                         mapOf("name" to "distance", "kind" to "nodal", "components" to 1),
                         mapOf("name" to "cellState", "kind" to "cell", "components" to 1))
            val nodal = LinkedHashMap<String, DoubleArray>()
            val cells = LinkedHashMap<String, DoubleArray>()
            val components = LinkedHashMap<String, Int>()
            for (field in described) {
                val name = field["name"] as? String ?: error("Field descriptor without a name")
                val width = (field["components"] as? Number)?.toInt() ?: 1
                components[name] = width
                when (field["kind"]) {
                    "nodal" -> nodal[name] =
                        if (width == 1) arrays.doubles(name, domain.nodeCount)
                        else arrays.doubles(name, domain.nodeCount, width)
                    "cell" -> cells[name] = when (arrays.dtypeOf(name)) {
                        "int32" -> arrays.ints(name, domain.cellCount).let { v -> DoubleArray(v.size) { v[it].toDouble() } }
                        else -> arrays.doubles(name, domain.cellCount)
                    }
                    else -> error("Unknown field kind ${field["kind"]} for $name")
                }
            }
            // No `distance` requirement: a body-fitted formulation has no level set to report.
            cells["cellState"]?.let { require(it.all { s -> s == 0.0 || s == 1.0 || s == 2.0 }) { "Invalid cell state" } }
            return SimulationResult(domain, revision, nodal, cells, components,
                diagnostics + mapOf("fieldGeometryEvaluationMs" to geometryMs, "roundTripMs" to roundTripMs,
                    "geometryTransferMs" to writeMs, "resultTransferMs" to readMs))
        }
    }
}

/**
 * What a `thermal.stationary` session returns.
 *
 * An alias rather than a subclass: the result payload is described by the worker, not by the
 * Kotlin type, so there is one class and several names for it. Code written against
 * `ThermalResult.temperature` keeps working, and `SimulationResult.fieldNames` is what to read
 * when the formulation is not known in advance.
 */
typealias ThermalResult = SimulationResult

/** What a `flow.embedded` session returns. See [SimulationResult]. */
typealias FlowResult = SimulationResult
