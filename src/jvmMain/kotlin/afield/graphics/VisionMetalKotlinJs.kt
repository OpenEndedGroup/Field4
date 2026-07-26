package afield.graphics

/**
 * Kotlin/JS declarations for the tiny JavaScriptCore runtime owned by Field4Vision's Live Metal
 * renderer.
 *
 * Runtime Kotlin/JS compilation currently accepts one source file, so this surface is appended to
 * the user's source rather than compiled as a separate library. Appending is deliberate: compiler
 * diagnostics keep the user's original line numbers. [completionDeclarations] mirrors the same
 * public surface in JVM-analysis-safe Kotlin because the completion sidecar intentionally does not
 * load the Kotlin/JS compiler frontend.
 *
 * This only describes values that really exist in `LiveMetalJavaScript.swift`. Two things cross that
 * bridge: named `float4` uniform writes, and whole geometry uploads. Geometry is the head's own —
 * `geometry { … }` builds an indexed triangle mesh in JavaScriptCore and hands the packed arrays to
 * Metal in one call, so nothing per-vertex crosses the network or the bridge at frame rate.
 *
 * MSL source and the declared uniform slot order still travel in the `visionmetal/program` payload:
 * the shader lives in the box's `msl` property, and slot order comes from the head marker's
 * `uniforms=` option.
 */
object VisionMetalKotlinJs {
    const val surfaceId: String = "visionmetal"

    /**
     * Floats per packed vertex — four `float4`s. Kept in lockstep with [VISION_METAL_VERTEX_FLOATS]
     * and `LiveMetalVertex` on the Swift side; the builder writes this layout directly so the head
     * can hand the array to Metal without repacking.
     */
    private const val vertexFloats: Int = VISION_METAL_VERTEX_FLOATS

    /**
     * The geometry builder, in plain Kotlin. Shared verbatim between the compiled surface and the
     * completion mirror — only the two-line host bridge above it differs, so the two cannot drift.
     */
    private val meshBuilderSource: String = """
        /** A point, for the polyline-driven builders. */
        data class P3(val x: Double, val y: Double, val z: Double)

        fun p(x: Double, y: Double, z: Double): P3 = P3(x, y, z)

        /**
         * An indexed triangle mesh under construction.
         *
         * [vertex] appends a position and returns its index, taking the current [normal], [color],
         * [uv] and [extra] state. Indices are supplied by [triangle] / [quad], so the same vertex can
         * be shared by many faces — or duplicated per face when you want facet normals.
         */
        class MeshBuilder {
            private var positions = FloatArray(1024)
            private var used = 0
            private var indexData = IntArray(1536)
            private var indexUsed = 0

            private var nx = 0.0; private var ny = 0.0; private var nz = 1.0
            private var cr = 1.0; private var cg = 1.0; private var cb = 1.0; private var ca = 1.0
            private var tu = 0.0; private var tv = 0.0
            private var e0 = 0.0; private var e1 = 0.0

            /** Vertices appended so far. */
            val vertexCount: Int get() = used / $vertexFloats

            /** Indices appended so far; three per triangle. */
            val indexCount: Int get() = indexUsed

            /** Surface normal applied to subsequent [vertex] calls. */
            fun normal(x: Double, y: Double, z: Double): MeshBuilder {
                nx = x; ny = y; nz = z
                return this
            }

            /** Colour applied to subsequent [vertex] calls. */
            fun color(r: Double, g: Double, b: Double, a: Double = 1.0): MeshBuilder {
                cr = r; cg = g; cb = b; ca = a
                return this
            }

            /** Texture coordinate applied to subsequent [vertex] calls. */
            fun uv(u: Double, v: Double): MeshBuilder {
                tu = u; tv = v
                return this
            }

            /** The two free per-vertex channels, readable in MSL as `v.uv.zw`. */
            fun extra(first: Double, second: Double = 0.0): MeshBuilder {
                e0 = first; e1 = second
                return this
            }

            /** Append a vertex at [x], [y], [z] and return its index. */
            fun vertex(x: Double, y: Double, z: Double): Int {
                if (used + $vertexFloats > positions.size) {
                    positions = positions.copyOf(maxOf(positions.size * 2, used + $vertexFloats))
                }
                val o = used
                positions[o] = x.toFloat(); positions[o + 1] = y.toFloat(); positions[o + 2] = z.toFloat()
                positions[o + 3] = 1f
                positions[o + 4] = nx.toFloat(); positions[o + 5] = ny.toFloat(); positions[o + 6] = nz.toFloat()
                positions[o + 7] = 0f
                positions[o + 8] = cr.toFloat(); positions[o + 9] = cg.toFloat(); positions[o + 10] = cb.toFloat()
                positions[o + 11] = ca.toFloat()
                positions[o + 12] = tu.toFloat(); positions[o + 13] = tv.toFloat()
                positions[o + 14] = e0.toFloat(); positions[o + 15] = e1.toFloat()
                used += $vertexFloats
                return o / $vertexFloats
            }

            /** Append a vertex, setting normal and colour in the same call. */
            fun vertex(
                x: Double, y: Double, z: Double,
                normalX: Double, normalY: Double, normalZ: Double,
                r: Double = 1.0, g: Double = 1.0, b: Double = 1.0, a: Double = 1.0,
            ): Int {
                normal(normalX, normalY, normalZ)
                color(r, g, b, a)
                return vertex(x, y, z)
            }

            private fun index(value: Int) {
                if (indexUsed + 1 > indexData.size) indexData = indexData.copyOf(indexData.size * 2)
                indexData[indexUsed] = value
                indexUsed += 1
            }

            /** Counter-clockwise winding faces the viewer; the head culls back faces. */
            fun triangle(a: Int, b: Int, c: Int): MeshBuilder {
                index(a); index(b); index(c)
                return this
            }

            /** Two triangles across the quad [a] [b] [c] [d], wound in that order. */
            fun quad(a: Int, b: Int, c: Int, d: Int): MeshBuilder {
                triangle(a, b, c)
                triangle(a, c, d)
                return this
            }

            /** Drop everything appended so far, keeping the current normal/colour/uv state. */
            fun clear(): MeshBuilder {
                used = 0
                indexUsed = 0
                return this
            }

            /**
             * An axis-aligned cube of edge `2 * half`, centred on [cx], [cy], [cz]. Each face gets its
             * own four vertices so facet normals survive. With [tintFaces] the six faces take
             * distinguishable colours derived from their normals; otherwise all six use the current
             * [color].
             */
            fun cube(
                half: Double,
                cx: Double = 0.0, cy: Double = 0.0, cz: Double = 0.0,
                tintFaces: Boolean = false,
            ): MeshBuilder {
                // Normal, then four corners in units of `half`, wound counter-clockwise seen from
                // outside — the same table the head's built-in cube uses.
                val faces = arrayOf(
                    doubleArrayOf(0.0, 0.0, 1.0, -1.0, -1.0, 1.0, 1.0, -1.0, 1.0, 1.0, 1.0, 1.0, -1.0, 1.0, 1.0),
                    doubleArrayOf(0.0, 0.0, -1.0, 1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, 1.0, -1.0, 1.0, 1.0, -1.0),
                    doubleArrayOf(1.0, 0.0, 0.0, 1.0, -1.0, 1.0, 1.0, -1.0, -1.0, 1.0, 1.0, -1.0, 1.0, 1.0, 1.0),
                    doubleArrayOf(-1.0, 0.0, 0.0, -1.0, -1.0, -1.0, -1.0, -1.0, 1.0, -1.0, 1.0, 1.0, -1.0, 1.0, -1.0),
                    doubleArrayOf(0.0, 1.0, 0.0, -1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, -1.0, -1.0, 1.0, -1.0),
                    doubleArrayOf(0.0, -1.0, 0.0, -1.0, -1.0, -1.0, 1.0, -1.0, -1.0, 1.0, -1.0, 1.0, -1.0, -1.0, 1.0),
                )
                val cornerU = doubleArrayOf(0.0, 1.0, 1.0, 0.0)
                val cornerV = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
                val keptR = cr; val keptG = cg; val keptB = cb; val keptA = ca
                for (face in faces) {
                    normal(face[0], face[1], face[2])
                    if (tintFaces) {
                        color(0.5 + 0.5 * face[0], 0.5 + 0.5 * face[1], 0.5 + 0.5 * face[2], keptA)
                    } else {
                        color(keptR, keptG, keptB, keptA)
                    }
                    val base = vertexCount
                    for (corner in 0..3) {
                        val o = 3 + corner * 3
                        uv(cornerU[corner], cornerV[corner])
                        vertex(cx + face[o] * half, cy + face[o + 1] * half, cz + face[o + 2] * half)
                    }
                    quad(base, base + 1, base + 2, base + 3)
                }
                color(keptR, keptG, keptB, keptA)
                return this
            }

            /**
             * A [cols] x [rows] quad grid on the XY plane, [width] by [height], centred on [cx], [cy],
             * [cz], facing +Z. UVs span the whole sheet.
             */
            fun plane(
                width: Double, height: Double,
                cols: Int = 1, rows: Int = 1,
                cx: Double = 0.0, cy: Double = 0.0, cz: Double = 0.0,
            ): MeshBuilder {
                val nCols = maxOf(1, cols)
                val nRows = maxOf(1, rows)
                val base = vertexCount
                normal(0.0, 0.0, 1.0)
                for (row in 0..nRows) {
                    for (col in 0..nCols) {
                        val fu = col.toDouble() / nCols
                        val fv = row.toDouble() / nRows
                        uv(fu, fv)
                        vertex(cx + (fu - 0.5) * width, cy + (fv - 0.5) * height, cz)
                    }
                }
                val stride = nCols + 1
                for (row in 0 until nRows) {
                    for (col in 0 until nCols) {
                        val a = base + row * stride + col
                        quad(a, a + 1, a + stride + 1, a + stride)
                    }
                }
                return this
            }

            /** A UV sphere of [radius] centred on [cx], [cy], [cz]. */
            fun sphere(
                radius: Double,
                segments: Int = 24, rings: Int = 16,
                cx: Double = 0.0, cy: Double = 0.0, cz: Double = 0.0,
            ): MeshBuilder {
                val nSeg = maxOf(3, segments)
                val nRing = maxOf(2, rings)
                val base = vertexCount
                for (ring in 0..nRing) {
                    val theta = kotlin.math.PI * ring / nRing
                    val sinTheta = kotlin.math.sin(theta)
                    val cosTheta = kotlin.math.cos(theta)
                    for (seg in 0..nSeg) {
                        val phi = 2.0 * kotlin.math.PI * seg / nSeg
                        val dx = sinTheta * kotlin.math.cos(phi)
                        val dy = cosTheta
                        val dz = sinTheta * kotlin.math.sin(phi)
                        normal(dx, dy, dz)
                        uv(seg.toDouble() / nSeg, 1.0 - ring.toDouble() / nRing)
                        vertex(cx + dx * radius, cy + dy * radius, cz + dz * radius)
                    }
                }
                val stride = nSeg + 1
                for (ring in 0 until nRing) {
                    for (seg in 0 until nSeg) {
                        val a = base + ring * stride + seg
                        quad(a, a + stride, a + stride + 1, a + 1)
                    }
                }
                return this
            }

            /**
             * Sweep a [sides]-gon of [radius] along the polyline [points] — the swept-tube treatment
             * Field's native FLine viewer gives a stroke, so a line drawn in Kotlin has real
             * thickness in the headset. Fewer than two points draws nothing.
             */
            fun tube(points: List<P3>, radius: Double, sides: Int = 8): MeshBuilder {
                if (points.size < 2) return this
                val nSides = maxOf(3, sides)
                val stride = nSides + 1
                val base = vertexCount
                for (i in points.indices) {
                    val here = points[i]
                    val ahead = points[if (i + 1 < points.size) i + 1 else i]
                    val behind = points[if (i > 0) i - 1 else i]
                    var dx = ahead.x - behind.x
                    var dy = ahead.y - behind.y
                    var dz = ahead.z - behind.z
                    val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                    if (length < 1e-9) { dx = 0.0; dy = 0.0; dz = 1.0 } else { dx /= length; dy /= length; dz /= length }
                    // side = up x tangent. Any `up` not parallel to the tangent gives a usable frame;
                    // swap axes near vertical so the cross product never collapses.
                    val upX = if (kotlin.math.abs(dy) > 0.9) 1.0 else 0.0
                    val upY = if (kotlin.math.abs(dy) > 0.9) 0.0 else 1.0
                    var sx = upY * dz
                    var sy = -upX * dz
                    var sz = upX * dy - upY * dx
                    val sLen = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
                    if (sLen < 1e-9) { sx = 1.0; sy = 0.0; sz = 0.0 } else { sx /= sLen; sy /= sLen; sz /= sLen }
                    val tx = dy * sz - dz * sy
                    val ty = dz * sx - dx * sz
                    val tz = dx * sy - dy * sx
                    for (side in 0..nSides) {
                        val angle = 2.0 * kotlin.math.PI * side / nSides
                        val ca2 = kotlin.math.cos(angle)
                        val sa = kotlin.math.sin(angle)
                        val ox = sx * ca2 + tx * sa
                        val oy = sy * ca2 + ty * sa
                        val oz = sz * ca2 + tz * sa
                        normal(ox, oy, oz)
                        uv(side.toDouble() / nSides, i.toDouble() / (points.size - 1))
                        vertex(here.x + ox * radius, here.y + oy * radius, here.z + oz * radius)
                    }
                }
                for (i in 0 until points.size - 1) {
                    for (side in 0 until nSides) {
                        val a = base + i * stride + side
                        quad(a, a + stride, a + stride + 1, a + 1)
                    }
                }
                return this
            }

            /** The packed vertex array, trimmed to [vertexCount]. */
            fun packedVertices(): FloatArray = positions.copyOf(used)

            /** The index array, trimmed to [indexCount]. */
            fun packedIndices(): IntArray = indexData.copyOf(indexUsed)
        }

        /**
         * Build an indexed triangle mesh and make it the head's resident geometry.
         *
         * Call it from `onStart` for a static mesh, or again at any time — including from `onFrame` —
         * to replace it. The whole mesh is uploaded in one bridge call and swapped into Metal at a
         * frame boundary, so a rebuild never tears.
         *
         * ```kotlin
         * geometry {
         *     color(1.0, 0.4, 0.1)
         *     cube(0.18, cz = -1.2, tintFaces = true)
         * }
         * ```
         */
        fun geometry(build: MeshBuilder.() -> Unit): MeshBuilder {
            val builder = MeshBuilder()
            builder.build()
            __visionMetalSetGeometry(builder.packedVertices(), builder.packedIndices())
            return builder
        }
    """.trimIndent()

    /**
     * The user-facing layer intentionally wraps the raw JavaScript globals: the wrapper gives
     * `uniform` writes checked arities while retaining a zero-allocation scalar path, and gives
     * geometry a builder instead of a bare pair of arrays. DCE removes any unused overloads from
     * each snippet.
     */
    private val metalDeclarations: String = """
        @kotlin.js.JsName("uniform")
        private external fun __visionMetalUniform(
            name: String,
            x: Double,
            y: Double,
            z: Double,
            w: Double,
        )

        @kotlin.js.JsName("__fieldSetGeometry")
        private external fun __visionMetalSetGeometry(vertices: FloatArray, indices: IntArray)

        @kotlin.js.JsName("print")
        private external fun __visionMetalPrint(value: Any?)

        fun uniform(name: String, x: Double) =
            __visionMetalUniform(name, x, 0.0, 0.0, 0.0)

        fun uniform(name: String, x: Double, y: Double) =
            __visionMetalUniform(name, x, y, 0.0, 0.0)

        fun uniform(name: String, x: Double, y: Double, z: Double) =
            __visionMetalUniform(name, x, y, z, 0.0)

        fun uniform(name: String, x: Double, y: Double, z: Double, w: Double) =
            __visionMetalUniform(name, x, y, z, w)

        fun log(value: Any?) = __visionMetalPrint(value)
    """.trimIndent()

    val declarations: String = listOf(
        RemoteLifecycleKotlinJs.declarations,
        metalDeclarations,
        meshBuilderSource,
    ).joinToString("\n\n")

    /**
     * JVM-analysis-safe mirror of the user-facing surface. The completion sidecar deliberately runs
     * without the Kotlin/JS compiler frontend, so `external`, `dynamic`, `definedExternally` and
     * `@JsName` cannot be its source model — but everything below the bridge is ordinary Kotlin, so
     * the builder source itself is shared with [declarations] rather than mirrored by hand.
     */
    private val metalCompletionDeclarations: String = """
        private fun __visionMetalSetGeometry(vertices: FloatArray, indices: IntArray): Unit = TODO()
        fun uniform(name: String, x: Double, y: Double = 0.0, z: Double = 0.0, w: Double = 0.0): Unit = TODO()
        fun log(value: Any?): Unit = TODO()
    """.trimIndent()

    val completionDeclarations: String = listOf(
        RemoteLifecycleKotlinJs.completionDeclarations,
        metalCompletionDeclarations,
        meshBuilderSource,
    ).joinToString("\n\n")

    /** Add the external surface without changing any user-source line number. */
    fun appendTo(userSource: String): String = buildString(userSource.length + declarations.length + 2) {
        append(userSource.trimEnd())
        append("\n\n")
        append(declarations)
    }
}
