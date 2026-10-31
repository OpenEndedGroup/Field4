@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.mesh

import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A recipe language for triangle meshes: emit triangles through a transform stack with a current
 * colour and a current material, and get back a scene of named, shaded parts.
 *
 * [GeometryBuilder] is the language and [GeometryScene] is what a recipe builds: [GeometryPart]s,
 * each an unindexed [TriMesh] with its own [GeometryMaterial] and a normal per triangle, plus the
 * [GeometryView] that says how the scene asks to be shown. The usual way to run a recipe is
 * [afield.graphics.three.geometry], which draws it in a studio-lit `three` view in a box output,
 * or [afield.graphics.three.animateGeometry], which runs it again every frame with the recipe's
 * `t` moving. [geometryScene] builds the same scene with no viewer, for code that wants the mesh
 * rather than the picture.
 *
 * From there, [GeometryScene.toTriMesh] joins the parts into one mesh for the rest of
 * `afield.geometry.mesh`, [writeGlb] writes a still as glTF, and [writeGlbAnimation] records a
 * recipe played over time, which needs every frame to emit the same parts with the same vertex
 * counts.
 */

/**
 * A stateful, immediate-mode triangle language: describe a model as a recipe of triangles, emitted
 * through a transform stack with a current colour and a current material, and get back a
 * [GeometryScene] of named, coloured, materialled [TriMesh] parts.
 *
 * This is the madp geometry toolbox's `build()` language, in Kotlin. Its usual host is
 * [afield.graphics.three.geometry], which runs the recipe and draws the result in a studio-lit
 * `three` view, and [afield.graphics.three.animateGeometry], which runs it again every frame with
 * [t] moving.
 *
 * ```kotlin
 * geometry {
 *     mesh("ripple terrain")
 *     mat(roughness = 0.86, metalness = 0.03)
 *     surface(78, 78) { u, v ->
 *         val x = (u - 0.5) * 7.2
 *         val z = (v - 0.5) * 7.2
 *         val y = sin(sqrt(x * x + z * z) * 3.6) * 0.22 + noise(x * 0.75, z * 0.75) * 0.38
 *         vertex(x, y, z, hsl(188 + y * 56, 0.74, 0.42 + y * 0.08))
 *     }
 * }
 * ```
 *
 * #### The state
 *
 * - [mat] sets the material for triangles that follow; [color] sets the colour for vertices that
 *   follow, unless a vertex carries its own.
 * - [push], [pop], [translate], [rotateX], [rotateY], [rotateZ], [scale] and [identity] set the
 *   transform for geometry that follows. [push] and [pop] save and restore the whole state — name,
 *   material, colour and transform.
 * - [mesh] names the part that follows. Changing the material after triangles have been emitted also
 *   starts a new part. Parts with the same name and material are drawn as one.
 *
 * #### The emitters
 *
 * [tri], [quad], [surface] (a grid of quads sampled over `u, v` in `0..1`), [revolve] (a lathe), [tube]
 * (a ring swept along a curve) and [box]. All of them are triangles in the end, and all of them go
 * through the transform.
 *
 * #### Colours
 *
 * Colours are Field's: sRGB [Vec4]s from [afield.graphics.color.hsl], [afield.graphics.color.rgb] and
 * friends, or CSS hex strings (`"#ffe4a0"`). Field's `hsl` reads the HSL cube as linear light, which is
 * also what three.js's `Color.setHSL` does, so a palette written for the madp toolbox comes out the
 * same here. The [TriMesh] parts store their vertex colours **linear**, as three.js and glTF expect.
 *
 * #### Randomness
 *
 * [rand] is the toolbox's linear congruential generator and [noise] its Perlin noise with the same
 * permutation table, so `seed(18)` grows the same crystal bloom here as in the browser.
 */
class GeometryBuilder(
    /**
     * Seconds since an animation started; `0` for a still. See
     * [afield.graphics.three.animateGeometry].
     */
    val t: Double = 0.0,
    /** Frames since an animation started. */
    val frame: Int = 0,
    /** Seconds between frames. */
    val dt: Double = 1.0 / 60,
) {

    /** A full turn, `2π`. */
    val TAU: Double = 2 * PI

    private class Chunk(var name: String, var material: GeometryMaterial) {
        val positions = FloatArrayList()
        val colors = FloatArrayList()
    }

    private class State(
        var meshName: String,
        var material: GeometryMaterial,
        var color: FloatArray,
        var matrix: DoubleArray,
    ) {
        fun copy() = State(meshName, material, color.copyOf(), matrix.copyOf())
    }

    private val chunks = ArrayList<Chunk>()
    private var current: Chunk? = null
    private var meshIndex = 1
    private var state = State("mesh", GeometryMaterial(), floatArrayOf(1f, 1f, 1f), identityMatrix())
    private val stack = ArrayList<State>()
    private var view = GeometryView()
    private var randSeed: Long = 1337

    /** Triangles emitted so far. */
    var triangleCount: Int = 0
        private set

    // ---------------------------------------------------------------------------------------------
    // Scene and state
    // ---------------------------------------------------------------------------------------------

    /**
     * How the scene is shown: the background, and whether the studio ground and its grid are drawn.
     * Arguments left out keep their current value.
     *
     * @param groundY the ground's height. The toolbox's presets are all built to stand on `-1.25`.
     */
    fun view(background: String? = null, ground: Boolean? = null, grid: Boolean? = null, groundY: Double? = null) {
        view = view.copy(
            background = background ?: view.background,
            ground = ground ?: view.ground,
            grid = grid ?: view.grid,
            groundY = groundY ?: view.groundY,
        )
    }

    /** Name the part that follows. Triangles already emitted keep the name they had. */
    fun mesh(name: String) {
        if (current?.positions?.isEmpty() == false) current = null
        state.meshName = name
        current?.let { it.name = name }
    }

    /**
     * Change the material for triangles that follow. Arguments left out keep their current value.
     *
     * @param color the base colour as a CSS hex string; the vertex colours multiply it.
     * @param roughness 0 is a mirror, 1 is chalk.
     * @param metalness 0 is a dielectric (plastic, clay, wood), 1 is a metal.
     * @param emissive a colour the surface glows with, scaled by [emissiveIntensity].
     * @param opacity below 1 is see-through.
     * @param side which faces draw; double-sided by default, as in the toolbox.
     * @param wireframe draw the triangle edges over the surface.
     * @param flat shade each triangle flat rather than smoothing across them.
     */
    fun mat(
        color: String? = null,
        roughness: Double? = null,
        metalness: Double? = null,
        emissive: String? = null,
        emissiveIntensity: Double? = null,
        opacity: Double? = null,
        side: GeometrySide? = null,
        wireframe: Boolean? = null,
        flat: Boolean? = null,
    ) = setMaterial(
        color?.let { rgbOf(cssColor(it)) }, roughness, metalness,
        emissive?.let { rgbOf(cssColor(it)) }, emissiveIntensity, opacity, side, wireframe, flat,
    )

    /** [mat] with the base colour as a [Vec4] (an [afield.graphics.color.hsl], say). */
    fun mat(
        color: Vec4,
        roughness: Double? = null,
        metalness: Double? = null,
        emissive: Vec4? = null,
        emissiveIntensity: Double? = null,
        opacity: Double? = null,
        side: GeometrySide? = null,
        wireframe: Boolean? = null,
        flat: Boolean? = null,
    ) = setMaterial(rgbOf(color), roughness, metalness, emissive?.let(::rgbOf), emissiveIntensity, opacity, side, wireframe, flat)

    private fun setMaterial(
        color: FloatArray?, roughness: Double?, metalness: Double?, emissive: FloatArray?, emissiveIntensity: Double?,
        opacity: Double?, side: GeometrySide?, wireframe: Boolean?, flat: Boolean?,
    ) {
        val m = state.material
        state.material = GeometryMaterial(
            color = color ?: m.color,
            roughness = (roughness ?: m.roughness).coerceIn(0.0, 1.0),
            metalness = (metalness ?: m.metalness).coerceIn(0.0, 1.0),
            emissive = emissive ?: m.emissive,
            emissiveIntensity = (emissiveIntensity ?: m.emissiveIntensity).coerceAtLeast(0.0),
            opacity = (opacity ?: m.opacity).coerceIn(0.0, 1.0),
            side = side ?: m.side,
            wireframe = wireframe ?: m.wireframe,
            flat = flat ?: m.flat,
        )
        val c = current
        if (c != null && !c.positions.isEmpty()) current = null else c?.material = state.material
    }

    /** The colour for vertices that follow, unless a vertex carries its own. */
    fun color(c: Vec4) {
        state.color = rgbOf(c)
    }

    /** [color] from a CSS hex string, `"#rgb"` or `"#rrggbb"`. */
    fun color(css: String) = color(cssColor(css))

    /** Save the name, material, colour and transform; [pop] restores them. */
    fun push() {
        stack += state.copy()
    }

    /** Restore what the matching [push] saved. */
    fun pop() {
        check(stack.isNotEmpty()) { "pop() called with an empty transform stack" }
        state = stack.removeAt(stack.size - 1)
    }

    /** Reset the transform. */
    fun identity() {
        state.matrix = identityMatrix()
    }

    /** Move geometry that follows by `(x, y, z)`, in the current frame. */
    fun translate(x: Double, y: Double, z: Double) =
        multiply(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, x, y, z, 1.0))

    /** Move geometry that follows by [p]. */
    fun translate(p: Vec3) = translate(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())

    /** Turn geometry that follows about x, by [angle] radians. */
    fun rotateX(angle: Double) {
        val c = cos(angle); val s = sin(angle)
        multiply(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, c, s, 0.0, 0.0, -s, c, 0.0, 0.0, 0.0, 0.0, 1.0))
    }

    /** Turn geometry that follows about y, by [angle] radians. */
    fun rotateY(angle: Double) {
        val c = cos(angle); val s = sin(angle)
        multiply(doubleArrayOf(c, 0.0, -s, 0.0, 0.0, 1.0, 0.0, 0.0, s, 0.0, c, 0.0, 0.0, 0.0, 0.0, 1.0))
    }

    /** Turn geometry that follows about z, by [angle] radians. */
    fun rotateZ(angle: Double) {
        val c = cos(angle); val s = sin(angle)
        multiply(doubleArrayOf(c, s, 0.0, 0.0, -s, c, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
    }

    /** Scale geometry that follows, per axis. */
    fun scale(x: Double, y: Double = x, z: Double = x) =
        multiply(doubleArrayOf(x, 0.0, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 0.0, z, 0.0, 0.0, 0.0, 0.0, 1.0))

    // ---------------------------------------------------------------------------------------------
    // Points
    // ---------------------------------------------------------------------------------------------

    /** A point with an optional colour of its own, for [tri], [quad] and [surface]. Emits nothing. */
    fun vertex(x: Double, y: Double, z: Double, color: Vec4? = null) =
        GeometryVertex(x, y, z, color?.let(::rgbOf))

    /** [vertex] with a CSS hex colour. */
    fun vertex(x: Double, y: Double, z: Double, color: String) = vertex(x, y, z, cssColor(color))

    /** [vertex] from a [Vec3]. */
    fun vertex(p: Vec3, color: Vec4? = null) = vertex(p.x.toDouble(), p.y.toDouble(), p.z.toDouble(), color)

    /** The point a fraction [t] of the way from [a] to [b]. */
    fun mix(a: Vec3, b: Vec3, t: Double): Vec3 = Vec3(
        (a.x + (b.x - a.x) * t).toFloat(), (a.y + (b.y - a.y) * t).toFloat(), (a.z + (b.z - a.z) * t).toFloat(),
    )

    /** A point [radius] from the y axis at [angle] radians round it, at height [y]. */
    fun polar(radius: Double, angle: Double, y: Double = 0.0) =
        Vec3((cos(angle) * radius).toFloat(), y.toFloat(), (sin(angle) * radius).toFloat())

    /** A point on a sphere: [theta] round the y axis, [phi] down from the pole. */
    fun spherical(radius: Double, theta: Double, phi: Double) = Vec3(
        (radius * sin(phi) * cos(theta)).toFloat(), (radius * cos(phi)).toFloat(), (radius * sin(phi) * sin(theta)).toFloat(),
    )

    /** A point on a [revolve] profile: [r] from the axis at height [y]. */
    fun profile(r: Double, y: Double, color: Vec4? = null) = GeometryProfilePoint(r, y, color?.let(::rgbOf))

    /** [profile] with a CSS hex colour. */
    fun profile(r: Double, y: Double, color: String) = profile(r, y, cssColor(color))

    /** One ring of a [tube]: its centre, its radius and an optional colour. */
    fun section(pos: Vec3, radius: Double, color: Vec4? = null) =
        GeometryTubeSection(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), radius, color?.let(::rgbOf))

    // ---------------------------------------------------------------------------------------------
    // Emitters
    // ---------------------------------------------------------------------------------------------

    /** One triangle. Counter-clockwise seen from the front; [flip] reverses it. */
    fun tri(a: GeometryVertex, b: GeometryVertex, c: GeometryVertex, flip: Boolean = false) {
        val chunk = ensureChunk()
        emit(chunk, a)
        if (flip) { emit(chunk, c); emit(chunk, b) } else { emit(chunk, b); emit(chunk, c) }
        triangleCount++
    }

    /** One triangle in the current colour. */
    fun tri(a: Vec3, b: Vec3, c: Vec3, flip: Boolean = false) = tri(vertex(a), vertex(b), vertex(c), flip)

    /** Two triangles, `(a, b, c)` and `(a, c, d)`: corners in order round the quad. */
    fun quad(a: GeometryVertex, b: GeometryVertex, c: GeometryVertex, d: GeometryVertex) {
        tri(a, b, c)
        tri(a, c, d)
    }

    /** [quad] in the current colour. */
    fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3) = quad(vertex(a), vertex(b), vertex(c), vertex(d))

    /**
     * A grid of [cols] × [rows] points, sampled at `u` and `v` from 0 to 1, joined into quads. The
     * sampler returns a [vertex], usually with a colour.
     */
    fun surface(cols: Int, rows: Int, sampler: (u: Double, v: Double) -> GeometryVertex) {
        val nc = cols.coerceAtLeast(2)
        val nr = rows.coerceAtLeast(2)
        val points = List(nr) { j -> List(nc) { i -> sampler(i / (nc - 1.0), j / (nr - 1.0)) } }
        for (y in 0 until nr - 1) for (x in 0 until nc - 1) {
            quad(points[y][x], points[y][x + 1], points[y + 1][x + 1], points[y + 1][x])
        }
    }

    /** A lathe: the [profile] points (radius and height) turned round the y axis in [segments] steps. */
    fun revolve(profile: List<GeometryProfilePoint>, segments: Int = 32) {
        val n = segments.coerceAtLeast(3)
        val rings = profile.map { p ->
            List(n + 1) { i ->
                val angle = i.toDouble() / n * TAU
                GeometryVertex(cos(angle) * p.r, p.y, sin(angle) * p.r, p.color)
            }
        }
        for (py in 0 until rings.size - 1) for (sx in 0 until n) {
            quad(rings[py][sx], rings[py][sx + 1], rings[py + 1][sx + 1], rings[py + 1][sx])
        }
    }

    /**
     * A tube: rings of [sides] corners swept along a curve sampled at [segments] values of `t` in
     * `0..1`. The sampler returns a [section]. [closed] joins the last ring back to the first, for a
     * loop such as a knot; an open tube is a branch or a stem.
     */
    fun tube(segments: Int, sides: Int, closed: Boolean = true, sampler: (t: Double) -> GeometryTubeSection) {
        val ns = segments.coerceAtLeast(3)
        val nq = sides.coerceAtLeast(3)
        val samples = List(ns) { i -> sampler(i.toDouble() / ns) }
        val rings = List(ns) { s ->
            val prev = samples[(s - 1 + ns) % ns]
            val next = samples[(s + 1) % ns]
            val tangent = normalize(next.x - prev.x, next.y - prev.y, next.z - prev.z)
            val up = if (abs(tangent[1]) > 0.92) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
            val normal = normalize(cross(up, tangent))
            val binormal = normalize(cross(tangent, normal))
            val c = samples[s]
            List(nq) { side ->
                val angle = side.toDouble() / nq * TAU
                val ca = cos(angle) * c.radius; val sa = sin(angle) * c.radius
                GeometryVertex(
                    c.x + normal[0] * ca + binormal[0] * sa,
                    c.y + normal[1] * ca + binormal[1] * sa,
                    c.z + normal[2] * ca + binormal[2] * sa,
                    c.color,
                )
            }
        }
        val limit = if (closed) ns else ns - 1
        for (r in 0 until limit) {
            val rn = (r + 1) % ns
            for (q in 0 until nq) {
                val qn = (q + 1) % nq
                quad(rings[r][q], rings[r][qn], rings[rn][qn], rings[rn][q])
            }
        }
    }

    /** An axis-aligned box of [size] centred on [center], in the current colour and transform. */
    fun box(center: Vec3, size: Vec3) {
        val cx = center.x.toDouble(); val cy = center.y.toDouble(); val cz = center.z.toDouble()
        val sx = size.x * 0.5; val sy = size.y * 0.5; val sz = size.z * 0.5
        fun v(x: Double, y: Double, z: Double) = GeometryVertex(cx + x * sx, cy + y * sy, cz + z * sz, null)
        val p = listOf(
            v(-1.0, -1.0, -1.0), v(1.0, -1.0, -1.0), v(1.0, 1.0, -1.0), v(-1.0, 1.0, -1.0),
            v(-1.0, -1.0, 1.0), v(1.0, -1.0, 1.0), v(1.0, 1.0, 1.0), v(-1.0, 1.0, 1.0),
        )
        quad(p[4], p[5], p[6], p[7]); quad(p[1], p[0], p[3], p[2]); quad(p[0], p[4], p[7], p[3])
        quad(p[5], p[1], p[2], p[6]); quad(p[3], p[7], p[6], p[2]); quad(p[0], p[1], p[5], p[4])
    }

    /** A cube of side [size] centred on [center]. */
    fun box(center: Vec3, size: Double) = box(center, Vec3(size.toFloat(), size.toFloat(), size.toFloat()))

    // ---------------------------------------------------------------------------------------------
    // Randomness
    // ---------------------------------------------------------------------------------------------

    /** Restart [rand] from [value], so the same seed gives the same model. */
    fun seed(value: Int) {
        randSeed = (value.toLong() and 0xffffffffL).takeIf { it != 0L } ?: 1L
    }

    /** A random number in `0..1`. */
    fun rand(): Double {
        randSeed = (1664525L * randSeed + 1013904223L) and 0xffffffffL
        return randSeed / 4294967296.0
    }

    /** A random number in `0..max`. */
    fun rand(max: Double): Double = rand() * max

    /** A random number in `min..max`. */
    fun rand(min: Double, max: Double): Double = min + rand() * (max - min)

    /** A random whole number from [min] to [max], both included. */
    fun randInt(min: Int, max: Int): Int = floor(rand(min.toDouble(), max + 1.0)).toInt()

    /** Smooth 2D Perlin noise, roughly in `-1..1`; the same everywhere for the same `x, y`. */
    fun noise(x: Double, y: Double): Double = perlin2(x, y)

    // ---------------------------------------------------------------------------------------------
    // Result
    // ---------------------------------------------------------------------------------------------

    /** The scene so far. */
    fun build(): GeometryScene {
        val merged = LinkedHashMap<Pair<String, GeometryMaterial>, Pair<FloatArrayList, FloatArrayList>>()
        for (c in chunks) {
            if (c.positions.isEmpty()) continue
            val into = merged.getOrPut(c.name to c.material) { FloatArrayList() to FloatArrayList() }
            into.first.addAll(c.positions); into.second.addAll(c.colors)
        }
        val parts = merged.map { (key, arrays) -> GeometryPart(key.first, key.second, soup(arrays.first.toArray(), arrays.second.toArray(), key.second.side)) }
        return GeometryScene(parts, view)
    }

    private fun ensureChunk(): Chunk = current ?: Chunk(state.meshName.ifEmpty { "mesh-$meshIndex" }, state.material).also {
        meshIndex++
        current = it
        chunks += it
    }

    private fun emit(chunk: Chunk, v: GeometryVertex) {
        val m = state.matrix
        chunk.positions.add((m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12]).toFloat())
        chunk.positions.add((m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13]).toFloat())
        chunk.positions.add((m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14]).toFloat())
        val c = v.color ?: state.color
        chunk.colors.add(srgbToLinear(c[0])); chunk.colors.add(srgbToLinear(c[1])); chunk.colors.add(srgbToLinear(c[2]))
    }

    private fun multiply(b: DoubleArray) {
        val a = state.matrix
        val out = DoubleArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = s
        }
        state.matrix = out
    }

    private companion object {
        fun identityMatrix() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        fun cross(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun normalize(x: Double, y: Double, z: Double) = normalize(doubleArrayOf(x, y, z))
        fun normalize(v: DoubleArray): DoubleArray {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return if (l > 0) doubleArrayOf(v[0] / l, v[1] / l, v[2] / l) else v
        }
    }
}

/**
 * Build a [GeometryScene] from a recipe, with no viewer. [afield.graphics.three.geometry] is the same
 * recipe drawn in a box output.
 */
fun geometryScene(t: Double = 0.0, frame: Int = 0, dt: Double = 1.0 / 60, block: GeometryBuilder.() -> Unit): GeometryScene =
    GeometryBuilder(t, frame, dt).apply(block).build()

/** Which faces of a [GeometryBuilder] part draw. */
enum class GeometrySide { FRONT, BACK, DOUBLE }

/**
 * A part's material: what [GeometryBuilder.mat] set. [color] and [emissive] are sRGB, as written;
 * [linearColor] and [linearEmissive] are what a renderer shades with.
 */
data class GeometryMaterial(
    /** The base colour as sRGB `r, g, b` in `0..1`; the part's vertex colours multiply it. */
    val color: FloatArray = floatArrayOf(1f, 1f, 1f),
    /** 0 is a mirror, 1 is chalk. */
    val roughness: Double = 0.68,
    /** 0 is a dielectric (plastic, clay, wood), 1 is a metal. */
    val metalness: Double = 0.04,
    /** A colour the surface glows with, sRGB, scaled by [emissiveIntensity]. */
    val emissive: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** How strongly [emissive] glows. 0 leaves the surface unlit from within. */
    val emissiveIntensity: Double = 0.0,
    /** Below 1 is see-through. */
    val opacity: Double = 1.0,
    /** Which faces draw; double-sided by default, as in the toolbox. */
    val side: GeometrySide = GeometrySide.DOUBLE,
    /** Draw the triangle edges over the surface. */
    val wireframe: Boolean = false,
    /** Shade each triangle flat rather than smoothing across them. */
    val flat: Boolean = true,
) {
    /** The base colour, linear. */
    val linearColor: FloatArray get() = FloatArray(3) { srgbToLinear(color[it]) }

    /** The emissive colour, linear. */
    val linearEmissive: FloatArray get() = FloatArray(3) { srgbToLinear(emissive[it]) }

    override fun equals(other: Any?): Boolean = other is GeometryMaterial &&
        color.contentEquals(other.color) && emissive.contentEquals(other.emissive) &&
        roughness == other.roughness && metalness == other.metalness && emissiveIntensity == other.emissiveIntensity &&
        opacity == other.opacity && side == other.side && wireframe == other.wireframe && flat == other.flat

    override fun hashCode(): Int = listOf(
        color.contentHashCode(), emissive.contentHashCode(), roughness, metalness, emissiveIntensity, opacity, side, wireframe, flat,
    ).hashCode()
}

/** A point with an optional colour (sRGB), as [GeometryBuilder.vertex] makes it. */
class GeometryVertex(val x: Double, val y: Double, val z: Double, val color: FloatArray?)

/** A point on a [GeometryBuilder.revolve] profile. */
class GeometryProfilePoint(val r: Double, val y: Double, val color: FloatArray?)

/** One ring of a [GeometryBuilder.tube]. */
class GeometryTubeSection(val x: Double, val y: Double, val z: Double, val radius: Double, val color: FloatArray?)

/** How a [GeometryScene] asks to be shown. See [GeometryBuilder.view]. */
data class GeometryView(
    /** The background, as a CSS hex string. */
    val background: String = "#050607",
    /** Whether the studio ground is drawn. */
    val ground: Boolean = true,
    /** Whether the grid on the ground is drawn. */
    val grid: Boolean = true,
    /** The ground's height. The toolbox's presets are all built to stand on `-1.25`. */
    val groundY: Double = -1.25,
)

/** One named part of a [GeometryScene]: a triangle soup with face normals and linear vertex colours. */
class GeometryPart(val name: String, val material: GeometryMaterial, val mesh: TriMesh) {
    override fun toString() = "GeometryPart($name, ${mesh.triangleCount} triangles)"
}

/**
 * What a [GeometryBuilder] recipe built: its parts and how it asks to be shown.
 *
 * Each part's mesh is unindexed — three vertices per triangle — with each triangle's own normal, which
 * is the faceted look the toolbox has. [toTriMesh] joins the parts for code that wants one mesh.
 */
class GeometryScene(
    /** The parts, in the order they were emitted. */
    val parts: List<GeometryPart>,
    /** How the scene asks to be shown. */
    val view: GeometryView,
) {
    /** Triangles across every part. */
    val triangleCount: Int get() = parts.sumOf { it.mesh.triangleCount }

    /** Vertices across every part: three per triangle, since the parts are unindexed. */
    val vertexCount: Int get() = parts.sumOf { it.mesh.vertexCount }

    /** Every part in one mesh, materials aside. */
    fun toTriMesh(): TriMesh = triMesh { parts.forEach { add(it.mesh) } }

    /** True when [other] has the same parts with the same vertex counts — what an animation export needs. */
    fun sameTopology(other: GeometryScene): Boolean =
        parts.size == other.parts.size && parts.indices.all { parts[it].mesh.vertexCount == other.parts[it].mesh.vertexCount }

    override fun toString() = "GeometryScene($triangleCount triangles in ${parts.size} part${if (parts.size == 1) "" else "s"})"
}

// -------------------------------------------------------------------------------------------------
// Internals
// -------------------------------------------------------------------------------------------------

/** A triangle soup: positions and linear colours, three vertices a triangle, each with its face normal. */
private fun soup(positions: FloatArray, colors: FloatArray, side: GeometrySide): TriMesh {
    val n = positions.size / 3
    if (side == GeometrySide.BACK) {
        // Back faces only: the same triangles wound the other way, drawn front-only.
        for (t in 0 until n / 3) for (c in 0 until 3) {
            val b = (t * 3 + 1) * 3 + c; val d = (t * 3 + 2) * 3 + c
            positions[b] = positions[d].also { positions[d] = positions[b] }
            colors[b] = colors[d].also { colors[d] = colors[b] }
        }
    }
    val normals = FloatArray(n * 3)
    for (t in 0 until n / 3) {
        val o = t * 9
        val ux = positions[o + 3] - positions[o]; val uy = positions[o + 4] - positions[o + 1]; val uz = positions[o + 5] - positions[o + 2]
        val vx = positions[o + 6] - positions[o]; val vy = positions[o + 7] - positions[o + 1]; val vz = positions[o + 8] - positions[o + 2]
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l > 1e-20f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 1f; nz = 0f }
        for (k in 0 until 3) { normals[o + k * 3] = nx; normals[o + k * 3 + 1] = ny; normals[o + k * 3 + 2] = nz }
    }
    return TriMesh(positions, IntArray(n) { it }, normals = normals, colors = colors)
}

/** A [Vec4] colour's rgb, kept sRGB until a vertex is emitted. */
private fun rgbOf(c: Vec4) = floatArrayOf(c.x.coerceIn(0f, 1f), c.y.coerceIn(0f, 1f), c.z.coerceIn(0f, 1f))

/** `#rgb` or `#rrggbb` (an optional `#rrggbbaa` alpha is ignored) as an sRGB [Vec4]. */
internal fun cssColor(css: String): Vec4 {
    val s = css.trim().removePrefix("#")
    val hex = when (s.length) {
        3 -> s.map { "$it$it" }.joinToString("")
        6, 8 -> s.substring(0, 6)
        else -> throw IllegalArgumentException("expected a CSS colour like \"#ffe4a0\", got \"$css\"")
    }
    val v = hex.toLongOrNull(16) ?: throw IllegalArgumentException("expected a CSS colour like \"#ffe4a0\", got \"$css\"")
    return Vec4(((v shr 16) and 255) / 255f, ((v shr 8) and 255) / 255f, (v and 255) / 255f, 1f)
}

internal fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

/** The toolbox's permutation table: the same shuffle, so the same noise. */
private val PERM: IntArray = run {
    val p = IntArray(256) { it }
    for (j in 255 downTo 1) {
        val k = (j * 16807 + 37) % (j + 1)
        val tmp = p[j]; p[j] = p[k]; p[k] = tmp
    }
    IntArray(512) { p[it and 255] }
}

private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)
private fun lerp(a: Double, b: Double, t: Double) = a + t * (b - a)
private fun grad(hash: Int, x: Double, y: Double): Double {
    val h = hash and 3
    val u = if (h < 2) x else y
    val v = if (h < 2) y else x
    return (if (h and 1 != 0) -u else u) + (if (h and 2 != 0) -v else v)
}

private fun perlin2(x: Double, y: Double): Double {
    val xi = floor(x).toInt() and 255
    val yi = floor(y).toInt() and 255
    val xf = x - floor(x)
    val yf = y - floor(y)
    val u = fade(xf)
    val v = fade(yf)
    val aa = PERM[PERM[xi] + yi]
    val ab = PERM[PERM[xi] + yi + 1]
    val ba = PERM[PERM[xi + 1] + yi]
    val bb = PERM[PERM[xi + 1] + yi + 1]
    return lerp(
        lerp(grad(aa, xf, yf), grad(ba, xf - 1, yf), u),
        lerp(grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1), u),
        v,
    )
}

/** A growable FloatArray, so a 12k-triangle surface does not box 100k Floats. */
private class FloatArrayList {
    private var data = FloatArray(256)
    var size = 0
        private set

    fun isEmpty() = size == 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun addAll(other: FloatArrayList) {
        if (size + other.size > data.size) data = data.copyOf(maxOf(size + other.size, size * 2))
        System.arraycopy(other.data, 0, data, size, other.size)
        size += other.size
    }
    fun toArray(): FloatArray = data.copyOf(size)
}
