package afield.graphics

import afield.Named
import afield.four.buffers.DynamicallyWriteable
import afield.stdlib.*

/**
 * Ordered map of named [FLine]s with a lightweight revision hash.
 *
 * Handy for GPU uploads where stable keys and change detection matter.
 */
class FLineMap : LinkedHashMap<String, FLine>() {

    /**
     * Combined revision hash of all contained lines.
     */
    val rev: Long
        get() {
            var hash: Long = 1L
            values.forEach { hash = 31 * hash + it.rev }
            return hash
        }

    operator fun plusAssign(f: FLine) {
        plusAssign("fline_${f.uid}" to f)
    }

    operator fun plusAssign(f: Pair<String, FLine>) {
        this[f.first] = f.second
    }

    operator fun plusAssign(f: Named<FLine>) {
        this[f.name] = f.value
    }

    override fun toString(): String = prettySummary(
        "FLineMap",
        "size" to size,
        "keys" to keys.take(4),
        "rev" to rev
    )
}


/**
 * Convenience overload of [pointsOut] for an [FLineMap].
 *
 */
fun pointsOut(
    m: FLineMap, vertexData: DynamicallyWriteable,
    pointData: DynamicallyWriteable,
    colorData: DynamicallyWriteable
) = pointsOut(m.values, vertexData, pointData, colorData)

/**
 * Write point positions and sizes for a collection of [FLine]s.
 *
 * Positions are written to [vertexData], and sizes (one float per point)
 * are written to [pointSizeData].
 *
 * @return number of points written.
 */
fun pointsOut(
    m: Collection<FLine>,
    vertexData: DynamicallyWriteable,
    colorData: DynamicallyWriteable,
    pointSizeData: DynamicallyWriteable
): Int {

    var num = 0
    colorData.write {
        val cd = this
        pointSizeData.write {
            val pointd = this
            vertexData.write {

                m.forEachIndexed { i, f ->
                    val fallback = f.pointSize
                    val fallbackColor = f.color
                    f.nodes.forEach { node ->
                        put(node.to)
                        val q = (node.properties["pointSize"] as? Number ?: fallback).f
                        pointd.put(q)
                        cd.put((node.properties["color"] as? Vec4 ?: fallbackColor))
                        num++
                    }
                }
            }
        }
    }
    return num
}

/**
 * Convenience overload of [trianglesOut] for an [FLineMap].
 */
fun trianglesOut(
    m: FLineMap, vertexData: DynamicallyWriteable,
    colorData: DynamicallyWriteable,
    samplesPerCubic: Int = 100
) = trianglesOut(m.values, vertexData, colorData, samplesPerCubic)


/**
 * Tessellate filled [FLine]s into triangles and write positions and colors.
 *
 * @return number of vertices written.
 */
fun trianglesOut(
    m: Collection<FLine>, vertexData: DynamicallyWriteable, colorData: DynamicallyWriteable, samplesPerCubic: Int = 100
): Int {

    var a = 0
    var splits = m.map { f ->
        val colors = f.colorsPer()
        var b = a
        a += colors.size / 4
        b to colors
    }

    var num = 0
    colorData.write {
        val cd = this
        vertexData.write {

            m.forEachIndexed { i, f ->
                f.tessellate(samplesPerCubic) { verts, index ->
                    index.forEach {
                        val at = vec(verts[3 * it + 0], verts[3 * it + 1], verts[3 * it + 2])
                        put(at)

                        // this can't possibly work with cubic interpolations, we need to map index back to drawing instruction
                        cd.put(splits[i].second[4 * it + 0])
                        cd.put(splits[i].second[4 * it + 1])
                        cd.put(splits[i].second[4 * it + 2])
                        cd.put(splits[i].second[4 * it + 3])
                        num++
                    }
                }
            }
        }
    }
    return num
}

/**
 * Convenience overload of [linesOut] for a single [FLine].
 */
fun linesOut(
    m: FLine,
    vertexData: DynamicallyWriteable? = null,
    colorData: DynamicallyWriteable? = null,
    samplesPerCubic: Int = 100
) = linesOut(listOf(m), vertexData, colorData, samplesPerCubic)

/**
 * Convenience overload of [linesOut] for an [FLineMap].
 */
fun linesOut(
    m: FLineMap, vertexData: DynamicallyWriteable? = null,
    colorData: DynamicallyWriteable? = null,
    samplesPerCubic: Int = 100
) = linesOut(m.values, vertexData, colorData, samplesPerCubic)

/**
 * Linearize stroke paths into a vertex stream and optional per-vertex colors.
 *
 * If provided, [vertexData] receives a stream of points with a segment id encoded
 * in the 4th component. A sentinel `(0,0,0,-1)` is written between lines.
 *
 * @return number of vertices written.
 */
fun linesOut(
    m: Collection<FLine>,
    vertexData: DynamicallyWriteable? = null,
    colorData: DynamicallyWriteable? = null,
    samplesPerCubic: Int = 100
): Int {

    var num = 0

    colorData?.write {
        m.map { f ->
            put(f.colorsPer())
        }
    }

    vertexData?.write {
        var base = 0
        m.forEach { f ->
            f.uniform_linearize(samplesPerCubic = samplesPerCubic) { at, inst ->
                put(at, inst + (if (inst < 0) -base else base))
                num += 1
            }
            put(0f, 0f, 0f, -1f) // this is actually important
            num += 1
            base += f.nodes.size
        }
    }

    return num
}
